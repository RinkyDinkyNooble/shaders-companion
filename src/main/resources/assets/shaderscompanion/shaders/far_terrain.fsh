#version 430 core

// The far terrain's fragments into its layers (API.md, "Far terrain"): albedo and data, and the depth in the protocol's
// convention (an OpenGL window depth of the view-space z with near N and far F) to location 2, whose colour mask picks
// the opaque (r) or the translucent (g) layer's channel. The hardware depth stays the mod's own reverse Z (or, with
// LOG_DEPTH where there is no glClipControl, a logarithmic depth in the distance) for the depth test. Compiled twice:
// plain, and with CLIP for nodes that reach into the chunk terrain (Embeddium's cylinder around the camera, less one
// chunk), whose fragments there are discarded, or past the start of the far edge's fade, whose fragments are thinned
// out towards the far range by a pattern the pack's TAA blends into a fade (only those draws lose early depth testing).

in vec3 v_Position;
in float v_ViewZ;
flat in vec4 v_Albedo;
flat in vec4 v_Data;

uniform float u_ChunkRadius; // the chunk terrain: within this many blocks of the camera horizontally and vertically
uniform float u_LogDepth;   // 1 / log2(far / near)
uniform float u_Near;
uniform vec2 u_DepthMapping; // (F + N) / (F - N), 2 F N / (F - N): the protocol's depth from the view-space z
uniform float u_FarNear;     // N: nearer than this the protocol's depth is 0
uniform float u_FadeStart;   // the far edge's fade from this distance to u_FarRange (0: no fade)
uniform float u_FarRange;
uniform int u_FrameIndex;    // the pack's TAA frame index (frameCounter % 16), for the fade's pattern

layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outData;
layout(location = 2) out vec4 outDepth;

void main() {
#ifdef CLIP
    if (dot(v_Position.xz, v_Position.xz) < u_ChunkRadius * u_ChunkRadius && abs(v_Position.y) < u_ChunkRadius) {
        discard;
    }
    if (u_FadeStart > 0.0) {
        float fade = (length(v_Position) - u_FadeStart) / (u_FarRange - u_FadeStart);
        // Interleaved gradient noise, moved each of the 16 frames
        vec2 p = gl_FragCoord.xy + 5.588238 * float(u_FrameIndex);
        if (fade > 0.0 && fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))) < fade) {
            discard;
        }
    }
#endif
#ifdef LOG_DEPTH
    gl_FragDepth = clamp(log2(max(length(v_Position), u_Near) / u_Near) * u_LogDepth, 0.0, 1.0);
#endif
    float w = -v_ViewZ;
    float depth = w < u_FarNear ? 0.0 : min(0.5 + 0.5 * (u_DepthMapping.x - u_DepthMapping.y / w), 0.99999994);
    outAlbedo = v_Albedo;
    outData = v_Data;
    outDepth = vec4(depth, depth, 0.0, 1.0);
}
