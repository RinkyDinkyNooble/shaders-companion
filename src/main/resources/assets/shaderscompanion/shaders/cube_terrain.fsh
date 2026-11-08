#version 330 core

uniform sampler2D u_BlockAtlas;
uniform int u_Translucent; // 1 while drawing the translucent pass (water, glass)

uniform vec4 u_ClipPlane; // drawn where dot(xyz, position) + w >= 0 (the mirror view cuts away what is behind its mirror)
uniform float u_LogDepth; // 1 / log2(far / near): depth from the distance to the origin, even across the LOD range
uniform float u_Near;

in vec2 v_TexCoord;
in vec4 v_Color;
in vec2 v_Light;
in vec3 v_Normal;
in vec3 v_Position;
flat in float v_BlockId;
flat in float v_Emission;
flat in uint v_Material;

out vec4 outAlbedo;   // rgb = texture times tint, a = 1 on opaque surfaces, the texture's alpha on translucent ones, 0 where nothing was drawn
out vec4 outData;     // 16-bit fields: normal, light, block id, emission (see API.md)
out vec4 outDistance; // r = distance from the capture origin in blocks
out vec4 outPreview;  // debug only: albedo with simple lighting

const float ALPHA_CUTOFF[4] = float[4](0.0, 0.1, 0.5, 1.0);

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
    float mipBias = (v_Material & 1u) != 0u ? 0.0 : -4.0;
    vec4 texel = texture(u_BlockAtlas, v_TexCoord, mipBias);
    if (texel.a < ALPHA_CUTOFF[(v_Material >> 1u) & 3u] || (u_Translucent != 0 && texel.a < 0.1)) {
        discard;
    }

    vec3 albedo = texel.rgb * v_Color.rgb;
    vec3 normal = normalize(v_Normal);
    vec2 oct = octEncode(normal);

    outAlbedo = vec4(albedo, u_Translucent != 0 ? max(texel.a, 1.0 / 255.0) : 1.0);
    outData = vec4(
        pack88(oct.x, oct.y),
        pack88(v_Light.x, v_Light.y),
        clamp(v_BlockId + 1.0, 0.0, 65535.0) / 65535.0,
        v_Emission
    );
    float distance = length(v_Position);
    gl_FragDepth = clamp(log2(max(distance, u_Near) / u_Near) * u_LogDepth, 0.0, 1.0);
    outDistance = vec4(distance, 0.0, 0.0, 1.0);

    float shade = 0.75 + 0.25 * abs(normal.y) + 0.1 * normal.x;
    float light = max(max(v_Light.x, v_Light.y), v_Emission);
    outPreview = vec4(albedo * shade * (0.08 + 0.92 * light), 1.0);
}
