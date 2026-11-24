#version 430 core

// Far terrain quads by vertex pulling (research/lod/M2-PLAN.md, "GPU"): no vertex attributes but the record, from
// the draw's base instance through a per-instance attribute. Drawn through a shared index pattern (4 vertices a quad,
// the command's base vertex its range's first quad times 4), so gl_VertexID / 4 is the quad in the geometry buffer and
// gl_VertexID % 4 its corner, and the vertex cache runs this once per corner. Quad layout: NodeMesh.

layout(std430, binding = 0) readonly buffer Geometry { uint geometry[]; };
layout(std430, binding = 1) readonly buffer Records { int records[]; };
layout(std430, binding = 4) readonly buffer Colours { uint colours[]; };
layout(std430, binding = 5) readonly buffer Info { uint info[]; };
layout(std430, binding = 6) readonly buffer Biomes { uint biomeColours[]; };

layout(location = 0) in int a_Record;

uniform mat4 u_ViewProjection; // camera-relative positions to clip space
uniform ivec3 u_CameraBlock;
uniform vec3 u_CameraFrac;
uniform int u_StateCount;      // states with colours (others draw grey)
uniform int u_BiomeCount;      // biomes with colours
uniform int u_Translucent;     // 1 in the water and glass pass

out vec3 v_Position;
flat out vec4 v_Albedo;
flat out vec4 v_Data;

const int RECORD_INTS = 48;
// Per direction (Minecraft's Direction order: down, up, north, south, west, east): the normal's axis and the in-plane
// axes u and v (NodeMesh)
const int NORMAL_AXIS[6] = int[6](1, 1, 2, 2, 0, 0);
const int U_AXIS[6] = int[6](0, 0, 0, 0, 2, 2);
const int V_AXIS[6] = int[6](2, 2, 1, 1, 1, 1);
const vec3 NORMAL[6] = vec3[6](vec3(0, -1, 0), vec3(0, 1, 0), vec3(0, 0, -1), vec3(0, 0, 1), vec3(-1, 0, 0), vec3(1, 0, 0));
// Corners u0 v0, u1 v0, u1 v1, u0 v1 (the index pattern's triangles 0 1 2 and 0 2 3), counter-clockwise seen from the
// face's side when u x v points along the normal; up, north and east faces swap corners 1 and 3

vec3 unpackColour(uint c) {
    return vec3(c & 255u, (c >> 8) & 255u, (c >> 16) & 255u) / 255.0;
}

vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return e * 0.5 + 0.5;
}

float pack88(float hi, float lo) {
    return (round(clamp(hi, 0.0, 1.0) * 255.0) * 256.0 + round(clamp(lo, 0.0, 1.0) * 255.0)) / 65535.0;
}

void main() {
    int quad = gl_VertexID >> 2;
    int corner = gl_VertexID & 3;
    uint w0 = geometry[quad * 2];
    uint w1 = geometry[quad * 2 + 1];
    int base = a_Record * RECORD_INTS;
    ivec3 origin = ivec3(records[base], records[base + 1], records[base + 2]);
    int level = records[base + 3];
    int paletteBase = records[base + 5];

    int dir = int((w0 >> 25) & 7u);
    if (dir == 1 || dir == 2 || dir == 5) {
        corner = (corner == 1 || corner == 3) ? 4 - corner : corner;
    }
    ivec3 p = ivec3(w0 & 31u, (w0 >> 5) & 31u, (w0 >> 10) & 31u);
    p[NORMAL_AXIS[dir]] += dir & 1;
    p[U_AXIS[dir]] += corner == 1 || corner == 2 ? int((w0 >> 15) & 31u) + 1 : 0;
    p[V_AXIS[dir]] += corner >= 2 ? int((w0 >> 20) & 31u) + 1 : 0;
    vec3 position = vec3(origin - u_CameraBlock + p * (1 << level)) - u_CameraFrac;
    v_Position = position;
    gl_Position = u_ViewProjection * vec4(position, 1.0);

    uint entry = geometry[paletteBase + int(w1 & 0x3FFFu)];
    uint state = entry & 0xFFFFFu;
    uint biome = entry >> 20;
    vec4 albedo = vec4(0.5, 0.5, 0.5, 1.0);
    uint stateInfo = 0u;
    if (int(state) < u_StateCount) {
        uint c = colours[state * 6u + uint(dir)];
        stateInfo = info[state];
        if (c != 0u) {
            albedo = vec4(unpackColour(c), float(c >> 24) / 255.0);
            uint kind = (stateInfo >> uint(2 * dir)) & 3u;
            if (kind != 0u && int(biome) < u_BiomeCount) {
                albedo.rgb *= unpackColour(biomeColours[biome * 4u + kind - 1u]);
            }
        }
    }
    albedo.a = u_Translucent != 0 ? max(albedo.a, 1.0 / 255.0) : 1.0;
    v_Albedo = albedo;
    uint light = (w1 >> 14) & 255u;
    vec2 oct = octEncode(NORMAL[dir]);
    // As the views' data texture (API.md): normal, light (block high, sky low), block.properties id + 1, 0 for LODs
    v_Data = vec4(pack88(oct.x, oct.y), pack88(float(light & 15u) / 15.0, float(light >> 4) / 15.0), float(stateInfo >> 16) / 65535.0, 0.0);
}
