#version 430 core

// The far terrain's debug view (research/lod/M2-PLAN.md, "The debug view"): the far layer's albedo times a simple
// light (sky light with a fixed sun, block light), over the finished frame where the far layer was drawn and the
// scene's depth is the sky or farther than the far terrain; alone, the far layer over a plain sky colour.

layout(binding = 0) uniform sampler2D u_Albedo;
layout(binding = 1) uniform sampler2D u_Data;
layout(binding = 2) uniform sampler2D u_Distance;
layout(binding = 3) uniform sampler2D u_SceneDepth;

uniform mat4 u_InverseProjection; // the frame's projection, inverted: the scene's depth to view space
uniform vec2 u_ScreenSize;
uniform int u_Alone;
uniform int u_UseScene;

out vec4 fragColor;

const vec3 SKY = vec3(0.62, 0.76, 0.95);
const vec3 SUN = vec3(0.37, 0.84, 0.39);

vec3 octDecode(vec2 e) {
    e = e * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float distance = texelFetch(u_Distance, p, 0).r;
    if (distance <= 0.0) {
        if (u_Alone != 0) {
            fragColor = vec4(SKY, 1.0);
            return;
        }
        discard;
    }
    if (u_Alone == 0 && u_UseScene != 0) {
        float depth = texelFetch(u_SceneDepth, p, 0).r;
        if (depth < 1.0) {
            vec2 ndc = (vec2(p) + 0.5) / u_ScreenSize * 2.0 - 1.0;
            vec4 view = u_InverseProjection * vec4(ndc, depth * 2.0 - 1.0, 1.0);
            if (length(view.xyz / view.w) <= distance) {
                discard;
            }
        }
    }
    vec4 albedo = texelFetch(u_Albedo, p, 0);
    vec4 data = texelFetch(u_Data, p, 0);
    uint n = uint(round(data.r * 65535.0));
    uint l = uint(round(data.g * 65535.0));
    vec3 normal = octDecode(vec2(float(n >> 8u), float(n & 255u)) / 255.0);
    float block = float(l >> 8u) / 255.0;
    float sky = float(l & 255u) / 255.0;
    float sun = max(dot(normal, SUN), 0.0);
    float light = max(sky * (0.5 + 0.5 * sun), block * 0.85);
    fragColor = vec4(albedo.rgb * (0.08 + 0.92 * light), 1.0);
}
