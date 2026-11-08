#version 330 core

// Embeddium chunk vertices in Oculus's extended layout (40 bytes per vertex).
in uvec4 a_PosId;      // xyz position, w = material bits | local section index << 8
in vec4 a_Color;       // tint and shade
in vec2 a_TexCoord;    // atlas coordinate, 1/32768 units
in ivec2 a_LightCoord; // block and sky light, 0 to 240
in vec3 a_Normal;      // face normal
in vec2 a_BlockId;     // x = block.properties id (-1 when not listed)
in vec4 a_MidBlock;    // w = the block's own light level, 0 to 15

uniform mat4 u_ViewProjection;
uniform vec3 u_RegionOffset; // region origin minus the view's origin

out vec2 v_TexCoord;
out vec4 v_Color;
out vec2 v_Light;
out vec3 v_Normal;
out vec3 v_Position;
flat out float v_BlockId;
flat out float v_Emission;
flat out uint v_Material;

void main() {
    uint drawId = (a_PosId.w >> 8u) & 0xFFu;
    vec3 sectionOffset = vec3((uvec3(drawId) >> uvec3(5u, 0u, 2u)) & uvec3(7u, 3u, 7u)) * 16.0;
    vec3 position = vec3(a_PosId.xyz) * (1.0 / 2048.0) - 8.0 + u_RegionOffset + sectionOffset;

    v_TexCoord = a_TexCoord * (1.0 / 32768.0);
    v_Color = a_Color;
    v_Light = clamp(vec2(a_LightCoord) / 240.0, 0.0, 1.0);
    v_Normal = a_Normal;
    v_Position = position;
    v_BlockId = a_BlockId.x;
    v_Emission = clamp(a_MidBlock.w / 15.0, 0.0, 1.0);
    v_Material = a_PosId.w & 0xFFu;

    gl_Position = u_ViewProjection * vec4(position, 1.0);
}
