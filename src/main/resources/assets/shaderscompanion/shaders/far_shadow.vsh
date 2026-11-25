#version 430 core

// The far shadow map's quads (FarShadow): the far terrain's quads as far_terrain.vsh places them, by vertex pulling,
// into a cascade of the shadow map with an orthographic projection from the light. Depth only.

layout(std430, binding = 0) readonly buffer Geometry { uint geometry[]; };
layout(std430, binding = 1) readonly buffer Records { int records[]; };

layout(location = 0) in int a_Record;

uniform mat4 u_LightProjection; // camera-relative positions to the cascade's clip space
uniform ivec3 u_CameraBlock;
uniform vec3 u_CameraFrac;

const int RECORD_INTS = 48;
// Per direction (down, up, north, south, west, east): the normal's axis and the in-plane axes u and v (NodeMesh)
const int NORMAL_AXIS[6] = int[6](1, 1, 2, 2, 0, 0);
const int U_AXIS[6] = int[6](0, 0, 0, 0, 2, 2);
const int V_AXIS[6] = int[6](2, 2, 1, 1, 1, 1);

void main() {
    int quad = gl_VertexID >> 2;
    int corner = gl_VertexID & 3;
    uint w0 = geometry[quad * 2];
    int base = a_Record * RECORD_INTS;
    ivec3 origin = ivec3(records[base], records[base + 1], records[base + 2]);
    int level = records[base + 3];
    int dir = int((w0 >> 25) & 7u);
    ivec3 p = ivec3(w0 & 31u, (w0 >> 5) & 31u, (w0 >> 10) & 31u);
    p[NORMAL_AXIS[dir]] += dir & 1;
    p[U_AXIS[dir]] += corner == 1 || corner == 2 ? int((w0 >> 15) & 31u) + 1 : 0;
    p[V_AXIS[dir]] += corner >= 2 ? int((w0 >> 20) & 31u) + 1 : 0;
    vec3 position = vec3(origin - u_CameraBlock + p * (1 << level)) - u_CameraFrac;
    gl_Position = u_LightProjection * vec4(position, 1.0);
}
