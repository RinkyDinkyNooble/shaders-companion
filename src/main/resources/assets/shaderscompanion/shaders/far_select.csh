#version 430 core

// The far terrain's selection (research/lod/M2-PLAN.md, "GPU"): one invocation per node record. A node is drawn at its
// level by the octree cut of the rings: a node at level L is split into its children while the camera is nearer to
// its box than K * 2^L, so it is drawn when it isn't split and its parent is (or it is at the top level), within the far
// range and not entirely inside the chunk terrain (Embeddium's cylinder of the render distance around the camera, less
// one chunk); then the frustum and the face directions the camera can see. A border range is drawn only where the
// neighbour node at the same level isn't drawn at that level. Each drawn range part becomes a
// DrawElementsIndirectCommand (6 indices of the shared quad pattern a quad, base vertex = the range's first quad times 4,
// base instance = the record) appended to one of 4 regions: opaque, opaque reaching into the chunk terrain or past the
// start of the far edge's fade (drawn with the discarding program), translucent, translucent reaching in or out.
// For the far shadow map (u_ShadowDirs set, FarShadow): the planes are a cascade's box, the face directions drawn are
// the ones facing away from the light, opaque quads only, all into region 0, with no chunk terrain cut (u_ChunkRadius 0)
// and no fade. For the mod's own views (u_ViewRegions set, FarViews: a cube face or a mirror plane): the main view's ring
// rule and chunk terrain cut from the camera, the view's frustum and cut plane, the face directions its eye sees, and
// two regions of at most u_Region commands, opaque and translucent, all for one program.

layout(local_size_x = 64) in;

layout(std430, binding = 1) readonly buffer Records { int records[]; };
layout(std430, binding = 2) writeonly buffer Commands { uint commands[]; };
// 0-3 the commands in each region (the views: 0 and 1), 4 nodes drawn, 5 quads drawn
layout(std430, binding = 3) buffer Counts { uint counts[]; };

uniform ivec3 u_CameraBlock;
uniform vec3 u_CameraFrac;
uniform float u_K;
uniform float u_Far;
uniform int u_TopLevel;
uniform float u_ChunkRadius;   // the chunk terrain: within this many blocks of the camera horizontally and vertically
uniform vec4 u_Planes[6];      // the view frustum's planes but the far one, and a mirror view's cut plane ((0, 0, 0, 1) for
                               // none), camera-relative: inside where dot(xyz, p) + w >= 0
uniform int u_RecordCount;
uniform int u_Region;          // commands a region holds
uniform int u_OneQuad;         // dev checks: 1 draws one quad per command (the commands' own cost)
uniform float u_FadeStart;     // the far edge's fade starts this far from the camera (0: no fade)
uniform int u_ShadowDirs;      // the far shadow map's pass: bit d set for each face direction drawn; 0 for the main view
uniform vec3 u_Eye;            // the view's eye relative to the camera, for the face directions it sees (0 but in the mod's views)
uniform int u_ViewRegions;     // 1 for the mod's views: opaque commands in region 0, translucent in region 1, no discarding split

const int RECORD_INTS = 48;
const ivec3 STEP[6] = ivec3[6](ivec3(0, -1, 0), ivec3(0, 1, 0), ivec3(0, 0, -1), ivec3(0, 0, 1), ivec3(-1, 0, 0), ivec3(1, 0, 0));

vec3 rel(ivec3 block) {
    return vec3(block - u_CameraBlock) - u_CameraFrac;
}

// The camera's distance to a node's box
float boxDistance(ivec3 lo, int size) {
    vec3 a = rel(lo);
    vec3 b = rel(lo + ivec3(size));
    return length(max(max(a, -b), vec3(0.0)));
}

bool split(int level, ivec3 lo) {
    return level > 0 && boxDistance(lo, 32 << level) < u_K * float(1 << level);
}

// Entirely inside the chunk terrain's cylinder
bool insideChunks(ivec3 lo, int size) {
    vec3 a = rel(lo);
    vec3 b = rel(lo + ivec3(size));
    vec2 far = max(abs(a.xz), abs(b.xz));
    return dot(far, far) < u_ChunkRadius * u_ChunkRadius && a.y > -u_ChunkRadius && b.y < u_ChunkRadius;
}

// Partly inside the chunk terrain's cylinder
bool reachesChunks(ivec3 lo, int size) {
    vec3 a = rel(lo);
    vec3 b = rel(lo + ivec3(size));
    vec2 near = max(max(a.xz, -b.xz), vec2(0.0));
    return dot(near, near) < u_ChunkRadius * u_ChunkRadius && a.y < u_ChunkRadius && b.y > -u_ChunkRadius;
}

// Whether the node of this level at this origin is drawn at its level this frame (the ring rule and the chunk terrain)
bool drawnAt(int level, ivec3 lo) {
    int size = 32 << level;
    if (level > u_TopLevel || split(level, lo) || boxDistance(lo, size) >= u_Far || insideChunks(lo, size)) {
        return false;
    }
    if (level < u_TopLevel) {
        int shift = 6 + level;
        return split(level + 1, (lo >> shift) << shift);
    }
    return true;
}

// A command at an index of a region (a view's regions hold at most u_Region commands: the rest are left out)
void writeCommand(uint region, uint index, int quads, int first, int record) {
    if (index >= uint(u_Region)) {
        return;
    }
    uint at = (region * uint(u_Region) + index) * 5u;
    commands[at] = uint(min(u_OneQuad != 0 ? 1 : quads, 65536) * 6);
    commands[at + 1u] = 1u;
    commands[at + 2u] = 0u;
    commands[at + 3u] = uint(first) * 4u;
    commands[at + 4u] = uint(record);
}

void main() {
    int id = int(gl_GlobalInvocationID.x);
    if (id >= u_RecordCount) {
        return;
    }
    int base = id * RECORD_INTS;
    int level = records[base + 3];
    if (level < 0) {
        return;
    }
    ivec3 lo = ivec3(records[base], records[base + 1], records[base + 2]);
    if (!drawnAt(level, lo)) {
        return;
    }
    int scale = 1 << level;
    int boxMin = records[base + 6];
    int boxMax = records[base + 7];
    vec3 gMin = rel(lo + ivec3(boxMin & 63, (boxMin >> 6) & 63, (boxMin >> 12) & 63) * scale);
    vec3 gMax = rel(lo + ivec3(boxMax & 63, (boxMax >> 6) & 63, (boxMax >> 12) & 63) * scale);
    for (int i = 0; i < 6; i++) {
        vec4 p = u_Planes[i];
        vec3 corner = vec3(p.x >= 0.0 ? gMax.x : gMin.x, p.y >= 0.0 ? gMax.y : gMin.y, p.z >= 0.0 ? gMax.z : gMin.z);
        if (dot(p.xyz, corner) + p.w < 0.0) {
            return;
        }
    }
    // The face directions the view's eye can see: down faces from below the geometry's top, and so on (the shadow map's
    // pass: those facing away from the light)
    bool shadowPass = u_ShadowDirs != 0;
    bool views = u_ViewRegions != 0;
    bool see[6] = bool[6](gMax.y > u_Eye.y, gMin.y < u_Eye.y, gMax.z > u_Eye.z, gMin.z < u_Eye.z, gMax.x > u_Eye.x, gMin.x < u_Eye.x);
    if (shadowPass) {
        for (int d = 0; d < 6; d++) {
            see[d] = ((u_ShadowDirs >> d) & 1) != 0;
        }
    }
    int size = 32 << level;
    // The discarding program for nodes that reach into the chunk terrain, or whose geometry reaches past the fade's start
    // (the views draw with one program)
    bool fades = u_FadeStart > 0.0 && length(max(abs(gMin), abs(gMax))) > u_FadeStart;
    int clipped = !shadowPass && !views && (reachesChunks(lo, size) || fades) ? 1 : 0;
    uint ranges = 0u;
    uint opaqueDraws = 0u;
    uint translucentDraws = 0u;
    uint quads = 0u;
    for (int d = 0; d < 6; d++) {
        if (!see[d]) {
            continue;
        }
        for (int border = 0; border < 2; border++) {
            int r = 2 * d + border;
            int count = records[base + 20 + r];
            if (count == 0 || border == 1 && drawnAt(level, lo + STEP[d] * size)) {
                continue;
            }
            int opaque = records[base + 32 + r];
            if (shadowPass && opaque == 0) {
                continue;
            }
            ranges |= 1u << r;
            opaqueDraws += opaque > 0 ? 1u : 0u;
            translucentDraws += count > opaque && !shadowPass ? 1u : 0u;
            quads += uint(shadowPass ? opaque : count);
        }
    }
    if (ranges == 0u) {
        return;
    }
    uint opaqueRegion = uint(clipped);
    uint translucentRegion = views ? 1u : uint(2 + clipped);
    uint o = opaqueDraws > 0u ? atomicAdd(counts[opaqueRegion], opaqueDraws) : 0u;
    uint t = translucentDraws > 0u ? atomicAdd(counts[translucentRegion], translucentDraws) : 0u;
    atomicAdd(counts[4], 1u);
    atomicAdd(counts[5], quads);
    for (int r = 0; r < 12; r++) {
        if ((ranges & (1u << r)) == 0u) {
            continue;
        }
        int first = records[base + 8 + r];
        int count = records[base + 20 + r];
        int opaque = records[base + 32 + r];
        if (opaque > 0) {
            writeCommand(opaqueRegion, o, opaque, first, id);
            o++;
        }
        if (count > opaque && !shadowPass) {
            writeCommand(translucentRegion, t, count - opaque, first + opaque, id);
            t++;
        }
    }
}
