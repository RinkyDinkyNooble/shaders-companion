#version 430 core

// The far terrain's fragments in the mod's own views (FarViews: a cube face or a mirror plane, API.md "What is drawn in
// the cube", "Mirror view"): the views' fields as the chunk terrain writes them (cube_terrain.fsh): albedo, data (normal,
// light, the block.properties id, the light emission), the distance from the view's origin and the debug preview, and
// the views' logarithmic depth of that distance. Nothing within the chunk terrain's cylinder around the camera (less one
// chunk, as the main view's far terrain), where the views draw the chunk terrain.

in vec3 v_Position;
flat in vec4 v_Albedo;
flat in vec4 v_Data;

uniform float u_ChunkRadius; // the chunk terrain: within this many blocks of the camera horizontally and vertically
uniform vec3 u_Origin;       // the view's origin relative to the camera
uniform float u_LogDepth;    // 1 / log2(far / near): depth from the distance to the origin, even across the far range
uniform float u_Near;

layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outData;
layout(location = 2) out vec4 outDistance;
layout(location = 3) out vec4 outPreview;

void main() {
    if (dot(v_Position.xz, v_Position.xz) < u_ChunkRadius * u_ChunkRadius && abs(v_Position.y) < u_ChunkRadius) {
        discard;
    }
    float distance = length(v_Position - u_Origin);
    gl_FragDepth = clamp(log2(max(distance, u_Near) / u_Near) * u_LogDepth, 0.0, 1.0);
    outAlbedo = v_Albedo;
    outData = v_Data;
    outDistance = vec4(distance, 0.0, 0.0, 1.0);

    // The debug preview: the colour with a simple light from the face's direction and the light levels
    uint lightBits = uint(round(v_Data.y * 65535.0));
    vec2 light = vec2(float(lightBits >> 8u), float(lightBits & 255u)) / 255.0;
    uint n = uint(round(v_Data.x * 65535.0));
    vec2 e = vec2(float(n >> 8u), float(n & 255u)) / 255.0 * 2.0 - 1.0;
    vec3 normal = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * vec2(normal.x >= 0.0 ? 1.0 : -1.0, normal.y >= 0.0 ? 1.0 : -1.0);
    }
    normal = normalize(normal);
    float shade = 0.75 + 0.25 * abs(normal.y) + 0.1 * normal.x;
    outPreview = vec4(v_Albedo.rgb * shade * (0.08 + 0.92 * max(max(light.x, light.y), v_Data.w)), 1.0);
}
