#version 330 core

// Entity vertices collected from Minecraft's entity renderers (36 bytes per vertex), relative to the camera.
in vec3 a_Position;
in vec4 a_Color;
in vec2 a_TexCoord;
in uvec2 a_Light;   // block and sky light, 0 to 240
in vec3 a_Normal;   // world-space normal
in uvec2 a_Meta;    // x = data texture id field, y = 1 on glowing layers (eyes)

uniform mat4 u_ViewProjection;
uniform vec3 u_Camera; // the camera relative to the view's origin

out vec2 v_TexCoord;
out vec4 v_Color;
out vec2 v_Light;
out vec3 v_Normal;
out vec3 v_Position;
flat out uint v_Id;
flat out uint v_Glow;

void main() {
    v_TexCoord = a_TexCoord;
    v_Color = a_Color;
    v_Light = clamp(vec2(a_Light) / 240.0, 0.0, 1.0);
    v_Normal = a_Normal;
    vec3 position = a_Position + u_Camera;
    v_Position = position;
    v_Id = a_Meta.x;
    v_Glow = a_Meta.y;
    gl_Position = u_ViewProjection * vec4(position, 1.0);
}
