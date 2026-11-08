#version 330 core

uniform sampler2D u_Texture;
uniform int u_Translucent; // 1 for translucent render types

uniform vec4 u_ClipPlane; // drawn where dot(xyz, position) + w >= 0 (the mirror view cuts away what is behind its mirror)
uniform float u_LogDepth; // 1 / log2(far / near): depth from the distance to the origin, even across the LOD range
uniform float u_Near;

in vec2 v_TexCoord;
in vec4 v_Color;
in vec2 v_Light;
in vec3 v_Normal;
in vec3 v_Position;
flat in uint v_Id;
flat in uint v_Glow;

out vec4 outAlbedo;
out vec4 outData;
out vec4 outDistance;
out vec4 outPreview;

vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return e * 0.5 + 0.5;
}

float pack88(float hi, float lo) {
    return (round(clamp(hi, 0.0, 1.0) * 255.0) * 256.0 + round(clamp(lo, 0.0, 1.0) * 255.0)) / 65535.0;
}

void main() {
    if (dot(u_ClipPlane.xyz, v_Position) + u_ClipPlane.w < 0.0) {
        discard;
    }
    vec4 texel = textureLod(u_Texture, v_TexCoord, 0.0) * v_Color;
    if (texel.a < 0.1) {
        discard;
    }
    vec3 normal = length(v_Normal) > 0.01 ? normalize(v_Normal) : vec3(0.0, 1.0, 0.0);
    // Faces drawn from behind (entity models aren't culled) take the side facing the origin
    if (dot(normal, v_Position) > 0.0) {
        normal = -normal;
    }
    vec2 oct = octEncode(normal);
    float glow = v_Glow != 0u ? 1.0 : 0.0;

    outAlbedo = vec4(texel.rgb, u_Translucent != 0 ? max(texel.a, 1.0 / 255.0) : 1.0);
    outData = vec4(pack88(oct.x, oct.y), pack88(v_Light.x, v_Light.y), float(v_Id) / 65535.0, glow);
    float distance = length(v_Position);
    gl_FragDepth = clamp(log2(max(distance, u_Near) / u_Near) * u_LogDepth, 0.0, 1.0);
    outDistance = vec4(distance, 0.0, 0.0, 1.0);

    float shade = 0.75 + 0.25 * abs(normal.y) + 0.1 * normal.x;
    float light = max(max(v_Light.x, v_Light.y), glow);
    outPreview = vec4(texel.rgb * shade * (0.08 + 0.92 * light), 1.0);
}
