#version 430 core

// The far terrain's fragments into the far layer. Compiled twice: plain, and with CLIP_CHUNKS for nodes that reach
// into the chunk terrain (Embeddium's cylinder around the camera, less one chunk), whose fragments there are discarded
// (only those draws lose early depth testing). With LOG_DEPTH (no glClipControl) the depth is logarithmic in the
// distance, as the views' is; otherwise reverse Z.

in vec3 v_Position;
flat in vec4 v_Albedo;
flat in vec4 v_Data;

uniform float u_ChunkRadius; // the chunk terrain: within this many blocks of the camera horizontally and vertically
uniform float u_LogDepth;   // 1 / log2(far / near)
uniform float u_Near;

layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outData;
layout(location = 2) out vec4 outDistance;

void main() {
#ifdef CLIP_CHUNKS
    if (dot(v_Position.xz, v_Position.xz) < u_ChunkRadius * u_ChunkRadius && abs(v_Position.y) < u_ChunkRadius) {
        discard;
    }
#endif
    float distance = length(v_Position);
#ifdef LOG_DEPTH
    gl_FragDepth = clamp(log2(max(distance, u_Near) / u_Near) * u_LogDepth, 0.0, 1.0);
#endif
    outAlbedo = v_Albedo;
    outData = v_Data;
    outDistance = vec4(distance, 0.0, 0.0, 1.0);
}
