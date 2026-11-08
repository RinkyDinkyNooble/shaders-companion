#version 330 core

uniform int u_Translucent;     // 1 for Distant Horizons' transparent buffers (water)
uniform float u_ClipDistance;  // LODs closer than this to the camera (horizontally) are left to the chunk terrain
uniform vec3 u_Camera;         // the camera relative to the view's origin
uniform vec4 u_ClipPlane; // drawn where dot(xyz, position) + w >= 0 (the mirror view cuts away what is behind its mirror)

uniform float u_LogDepth; // 1 / log2(far / near): depth from the distance to the origin, even across the LOD range
uniform float u_Near;

in vec4 v_Color;
in vec2 v_Light;
in vec3 v_Position;
flat in vec3 v_Normal;
flat in uint v_Material;

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
    if (length(v_Position.xz - u_Camera.xz) < u_ClipDistance || v_Color.a < 0.05 || dot(u_ClipPlane.xyz, v_Position) + u_ClipPlane.w < 0.0) {
        discard;
    }
    vec2 oct = octEncode(v_Normal);
    outAlbedo = vec4(v_Color.rgb, u_Translucent != 0 ? max(v_Color.a, 1.0 / 255.0) : 1.0);
    outData = vec4(pack88(oct.x, oct.y), pack88(v_Light.x, v_Light.y), float(0xC000u | (v_Material & 0xFFu)) / 65535.0, 0.0);
    float distance = length(v_Position);
    gl_FragDepth = clamp(log2(max(distance, u_Near) / u_Near) * u_LogDepth, 0.0, 1.0);
    outDistance = vec4(distance, 0.0, 0.0, 1.0);

    float shade = 0.75 + 0.25 * abs(v_Normal.y) + 0.1 * v_Normal.x;
    float light = max(v_Light.x, v_Light.y);
    outPreview = vec4(v_Color.rgb * shade * (0.08 + 0.92 * light), 1.0);
}
