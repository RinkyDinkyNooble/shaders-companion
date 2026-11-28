#version 430 core

// The far terrain's debug view (research/lod/M2-PLAN.md, "The debug view"), shown while the shader pack doesn't draw
// the far terrain itself: the nearest far surface (the translucent layer's where it has one, else the opaque one's)
// times a simple light (sky light with a fixed sun, block light), over the finished frame where the far layers hold a
// surface and the screen shows sky; alone, the far layers over a plain sky colour. The distance comes from the
// protocol's depth through the protocol's inverse projection (API.md, "Far terrain"), as a pack reads it.

layout(binding = 0) uniform sampler2D u_Albedo;
layout(binding = 1) uniform sampler2D u_Data;
layout(binding = 2) uniform sampler2D u_TranslucentAlbedo;
layout(binding = 3) uniform sampler2D u_TranslucentData;
layout(binding = 4) uniform sampler2D u_FarDepth;
layout(binding = 5) uniform sampler2D u_SceneDepth;

uniform mat4 u_InverseProjection; // the frame's projection inverted, with [2].w and [3].w for the far depth's N and F
uniform vec2 u_ScreenSize;
uniform int u_Alone;
uniform int u_UseScene;
uniform vec3 u_SunDir;    // world space, towards the sun (below the horizon at night)
uniform float u_Daylight; // 0 at night to 1 by day, from the level's sky brightness
uniform float u_FarRange; // the far range in blocks
uniform vec3 u_FogColor;  // the game's fog colour this frame (time of day, weather, biome)
uniform float u_FogStart; // the fog's start in blocks; it ends at u_FarRange

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
    vec2 depths = texelFetch(u_FarDepth, p, 0).rg;
    bool translucent = depths.g < 1.0;
    float depth = translucent ? depths.g : depths.r;
    if (depth >= 1.0) {
        if (u_Alone != 0) {
            fragColor = vec4(SKY, 1.0);
            return;
        }
        discard;
    }
    // Only where the screen shows sky: wherever the game drew anything, it wins. Comparing distances let the far terrain
    // and the chunk terrain fight over the band where both exist (the same blocks at the same distance), and the pack's
    // TAA moves the screen's depth a little every frame, so the winner flickered (the user's dev1 test)
    if (u_Alone == 0 && u_UseScene != 0 && texelFetch(u_SceneDepth, p, 0).r < 1.0) {
        discard;
    }
    vec4 albedo = translucent ? texelFetch(u_TranslucentAlbedo, p, 0) : texelFetch(u_Albedo, p, 0);
    vec4 data = translucent ? texelFetch(u_TranslucentData, p, 0) : texelFetch(u_Data, p, 0);
    vec4 view = u_InverseProjection * vec4(gl_FragCoord.xy / u_ScreenSize * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float distance = length(view.xyz / view.w);
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
    // Fog by distance towards the game's fog colour, with vanilla's curve (smoothstep from start to end, a sphere): with
    // shaders off the chunk terrain gets the same fog while this view draws (FarTerrain.onRenderFog), so the two meet
    // without a seam, and the far edge is fully fogged. Opaque: blending with the sky behind let the stars and the sun
    // below the horizon show through (dev1)
    vec3 haze = u_Alone != 0 ? SKY * mix(0.15, 1.0, u_Daylight) : u_FogColor;
    float fog = smoothstep(u_FogStart, max(u_FarRange, u_FogStart + 1.0), distance);
    // Water and glass (the translucent pass leaves alpha under 1): a dark body with the haze colour standing in for
    // the sky it reflects at these grazing angles
    if (albedo.a < 0.999) {
        lit = mix(lit * 0.45, haze * 0.85, 0.55);
    }
    fragColor = vec4(mix(lit, haze, fog), 1.0);
}
