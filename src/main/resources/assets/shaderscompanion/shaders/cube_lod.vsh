#version 330 core

// Distant Horizons LOD vertices (16 bytes per vertex).
in uvec4 a_Position; // xyz blocks from the section corner, w = light (low byte: block light << 4 | sky light) and sub-block offset bits (high byte)
in vec4 a_Color;     // block colour
in uvec4 a_Data;     // x = Distant Horizons block material, y = face (0 down, 1 up, 2 north, 3 south, 4 west, 5 east)

uniform mat4 u_ViewProjection;
uniform vec3 u_ModelOffset; // section corner minus the view's origin

out vec4 v_Color;
out vec2 v_Light;
out vec3 v_Position;
flat out vec3 v_Normal;
flat out uint v_Material;

const vec3 FACE_NORMALS[6] = vec3[6](
    vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, -1.0),
    vec3(0.0, 0.0, 1.0), vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0)
);

void main() {
    vec3 position = vec3(a_Position.xyz) + u_ModelOffset;
    uint offsets = (a_Position.w >> 8u) & 0xFFu;
    position.x += (offsets & 1u) != 0u ? ((offsets & 2u) != 0u ? -0.01 : 0.01) : 0.0;
    position.z += (offsets & 16u) != 0u ? ((offsets & 32u) != 0u ? -0.01 : 0.01) : 0.0;
    uint light = a_Position.w & 0xFFu;

    v_Color = a_Color;
    // Block light in the high nibble, sky light in the low one (Distant Horizons samples its lightmap at (high, low),
    // and the lightmap's first coordinate is block light)
    v_Light = vec2(float(light >> 4u), float(light & 15u)) / 15.0;
    v_Position = position;
    v_Normal = FACE_NORMALS[min(a_Data.y, 5u)];
    v_Material = a_Data.x;
    gl_Position = u_ViewProjection * vec4(position, 1.0);
}
