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
uniform vec3 u_SunDir;    // world space, towards the sun (below the horizon at night)
uniform float u_Daylight; // 0 at night to 1 by day, from the level's sky brightness
uniform float u_FarRange; // the far range in blocks

out vec4 fragColor;

const vec3 SKY = vec3(0.62, 0.76, 0.95);
const vec3 TORCH = vec3(1.0, 0.75, 0.45);

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
    // The sun by day (none once it is below the horizon), sky light that faces up see more of, torch light that shows
    // more at night: a stand-in for the pack's lighting until ABZA merges the far layer itself (M3)
    vec3 sunDir = normalize(u_SunDir);
    float direct = max(dot(normal, sunDir), 0.0) * smoothstep(-0.05, 0.15, sunDir.y);
    float ambient = sky * mix(0.16, 0.55, u_Daylight) * (0.75 + 0.25 * max(normal.y, 0.0));
    float sunLight = sky * u_Daylight * 0.7 * direct;
    vec3 lit = albedo.rgb * (0.03 + ambient + sunLight) + albedo.rgb * TORCH * block * 0.9 * (1.0 - 0.6 * u_Daylight * sky);
    // Haze by distance, and a fade over the last 15% of the range so its edge doesn't show: over the frame, blended
    // into the sky the pack drew behind it
    float x = distance / max(u_FarRange, 1.0);
    float fog = max(clamp(1.0 - exp(-2.2 * x * x), 0.0, 1.0) * 0.7, smoothstep(0.85, 1.0, x));
    // Water and glass (the translucent pass leaves alpha under 1): a dark body that mostly shows the sky behind it,
    // which is what far water reflects at these grazing angles
    float body = 1.0;
    if (albedo.a < 0.999) {
        lit *= 0.45;
        body = 0.4;
    }
    if (u_Alone != 0) {
        vec3 sky = SKY * mix(0.15, 1.0, u_Daylight);
        fragColor = vec4(mix(mix(sky, lit, body), sky, fog), 1.0);
    } else {
        fragColor = vec4(lit, (1.0 - fog) * body);
    }
}
