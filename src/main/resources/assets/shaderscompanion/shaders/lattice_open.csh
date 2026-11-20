#version 430 core

// The lattice's openness field for one 16-block section: probes every half block, each with how open it is in six
// directions (an ambient cube), traced through the lattice's quarter-block shapes within u_Reach blocks.

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

layout(binding = 0) uniform usampler3D u_Cells;
layout(binding = 1) uniform usampler3D u_Shapes;
layout(rgba8, binding = 0) uniform writeonly image3D u_OpenA;
layout(rgba8, binding = 1) uniform writeonly image3D u_OpenB;

uniform ivec3 u_SectionMin;      // the section's lowest block
uniform ivec3 u_LatticeMin;      // the lattice window's lowest block
uniform ivec3 u_LatticeSize;     // the lattice window's size in blocks
uniform int u_Log2Width;
uniform int u_ProbeMask;         // probe texture size - 1
uniform float u_Tag;             // the section's field tile parity + 1, over 255
uniform float u_Reach;

const int RAYS = 48;
const float PLANT_PASS = 0.8;    // light kept per quarter block of plants (no collision)
const float SEE_THROUGH_PASS = 0.7; // per quarter block of glass and leaves

uint cellAt(ivec3 block) {
    ivec3 local = block - u_LatticeMin;
    if (any(greaterThanEqual(uvec3(local), uvec3(u_LatticeSize)))) {
        return 0u;
    }
    uint cell = texelFetch(u_Cells, block & (u_LatticeSize - 1), 0).r;
    ivec3 tile = ivec3(block.x >> u_Log2Width, block.y >> 7, block.z >> u_Log2Width) & 1;
    uint parity = uint(tile.x | tile.y << 1 | tile.z << 2);
    return (cell >> 31) == 1u && ((cell >> 28) & 7u) == parity ? cell : 0u;
}

bool shapeHas(uvec2 shape, ivec3 q) {
    int bit = q.x + 4 * q.z + 16 * q.y;
    return ((bit < 32 ? shape.x >> bit : shape.y >> (bit - 32)) & 1u) != 0u;
}

// Whether the quarter-block cell at q (in quarter blocks from the section's corner) is filled by a solid shape
bool solidQuarter(ivec3 q) {
    ivec3 block = u_SectionMin + (q >> 2);
    uint cell = cellAt(block);
    if (cell == 0u || (cell & (1u << 15)) != 0u) {
        return false;
    }
    return shapeHas(texelFetch(u_Shapes, block & (u_LatticeSize - 1), 0).rg, q & 3);
}

// Light that reaches the probe along a ray: 1 when nothing is in the way within the reach, less through plants and
// glass, and for a solid hit at distance t, t / reach of what got that far. Unknown blocks count as open.
float trace(vec3 start, vec3 dir) {
    // In quarter blocks from the section's lowest corner
    vec3 origin = start * 4.0 + dir * 0.004;
    ivec3 q = ivec3(floor(origin));
    ivec3 stepDir = ivec3(greaterThanEqual(dir, vec3(0.0))) * 2 - 1;
    vec3 tDelta = 1.0 / max(abs(dir), vec3(1e-6));
    vec3 tMax = (vec3(q) + vec3(greaterThan(stepDir, ivec3(0))) - origin) * vec3(stepDir) * tDelta;
    float reach = u_Reach * 4.0;
    float pass = 1.0;
    float t = 0.0;
    ivec3 lastBlock = ivec3(-2147483647);
    uint cell = 0u;
    uvec2 shape = uvec2(0u);
    for (int i = 0; i < 64; i++) {
        ivec3 block = u_SectionMin + (q >> 2);
        if (block != lastBlock) {
            lastBlock = block;
            cell = cellAt(block);
            shape = cell == 0u ? uvec2(0u) : texelFetch(u_Shapes, block & (u_LatticeSize - 1), 0).rg;
        }
        if (shape != uvec2(0u) && shapeHas(shape, q & 3)) {
            if ((cell & (1u << 15)) != 0u) {
                pass *= PLANT_PASS;
            } else if ((cell & (1u << 14)) != 0u) {
                pass *= SEE_THROUGH_PASS;
            } else {
                return pass * clamp(t / reach, 0.0, 1.0);
            }
        }
        int axis = tMax.x < tMax.y ? (tMax.x < tMax.z ? 0 : 2) : (tMax.y < tMax.z ? 1 : 2);
        t = tMax[axis];
        if (t >= reach) {
            break;
        }
        q[axis] += stepDir[axis];
        tMax[axis] += tDelta[axis];
    }
    return pass;
}

void main() {
    ivec3 probe = ivec3(gl_GlobalInvocationID);
    // Probe at a half-block point, in blocks from the section's lowest corner
    vec3 start = vec3(probe) * 0.5;
    ivec3 q = probe * 2;

    // A probe inside solid matter (all eight quarter blocks around it filled) is left out by the pack
    bool open = false;
    for (int c = 0; c < 8 && !open; c++) {
        open = !solidQuarter(q + ivec3(c & 1, (c >> 1) & 1, c >> 2) - 1);
    }

    vec3 sum = vec3(0.0);
    vec3 sumNeg = vec3(0.0);
    vec3 weight = vec3(0.0);
    vec3 weightNeg = vec3(0.0);
    if (open) {
        for (int i = 0; i < RAYS; i++) {
            // A fixed spiral of directions, the same for every probe, so the field holds still
            float y = 1.0 - (2.0 * float(i) + 1.0) / float(RAYS);
            float r = sqrt(max(1.0 - y * y, 0.0));
            float phi = float(i) * 2.39996323;
            vec3 dir = vec3(r * cos(phi), y, r * sin(phi));
            float light = trace(start, dir);
            vec3 pos = max(dir, vec3(0.0));
            vec3 neg = max(-dir, vec3(0.0));
            sum += pos * light;
            weight += pos;
            sumNeg += neg * light;
            weightNeg += neg;
        }
    }
    vec3 openPos = open ? sum / max(weight, vec3(1e-4)) : vec3(0.0);
    vec3 openNeg = open ? sumNeg / max(weightNeg, vec3(1e-4)) : vec3(0.0);
    ivec3 texel = (u_SectionMin * 2 + probe) & u_ProbeMask;
    imageStore(u_OpenA, texel, vec4(openPos.x, openNeg.x, openPos.y, openNeg.y));
    imageStore(u_OpenB, texel, vec4(openPos.z, openNeg.z, open ? 1.0 : 0.0, u_Tag));
}
