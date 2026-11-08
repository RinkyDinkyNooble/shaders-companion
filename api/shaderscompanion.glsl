// Shaders Companion reference functions: cube protocol 2, mirror view protocol 1 (see API.md).
// 0BSD: copy, change and ship freely.
//
// The cube (default; define SC_NO_CUBE to leave it out). Before including, declare:
//   uniform sampler2D scAlbedo;
//   uniform sampler2D scData;
//   uniform sampler2D scDistance;
//   uniform sampler2D scInfo;
// The mirror view (define SC_MIRROR). Before including, declare:
//   uniform sampler2D scMirrorAlbedo;
//   uniform sampler2D scMirrorData;
//   uniform sampler2D scMirrorDistance;
//   uniform sampler2D scMirrorInfo;

#ifndef SHADERS_COMPANION_GLSL
#define SHADERS_COMPANION_GLSL

const int SC_PROTOCOL = 2;
const int SC_MIRROR_PROTOCOL = 1;

// What a texel shows (ScSurface.kind)
const int SC_BLOCK = 0;   // terrain from the chunk meshes; id = block.properties id, -1 when not listed
const int SC_ENTITY = 1;  // mobs, players, items and block entities; id = entity.properties id, -1 when not listed
const int SC_LOD = 2;     // Distant Horizons far terrain; id = Distant Horizons' block material (6 lava, 12 water, 15 light source)

struct ScSurface {
    bool hit;          // false: nothing drawn in that direction
    vec3 albedo;       // texture times tint, sRGB-encoded
    vec3 normal;       // world-space normal
    float blockLight;  // 0 to 1
    float skyLight;    // 0 to 1
    int kind;          // SC_BLOCK, SC_ENTITY or SC_LOD
    int id;            // see the kinds above
    float emission;    // blocks: their own light level / 15; entities: 1 on glowing layers (eyes)
    float distance;    // blocks from the view's origin (a cube face's capture origin, or the mirrored camera)
    vec3 position;     // surface position relative to this frame's camera
};

vec3 sc_octDecode(vec2 e) {
    e = e * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return normalize(n);
}

// Fills in a surface's fields from its albedo and data texels.
void sc_decode(vec4 albedo, vec4 data, inout ScSurface s) {
    uvec4 v = uvec4(round(data * 65535.0));
    s.albedo = albedo.rgb;
    s.normal = sc_octDecode(vec2(float(v.r >> 8u), float(v.r & 255u)) / 255.0);
    s.blockLight = float(v.g >> 8u) / 255.0;
    s.skyLight = float(v.g & 255u) / 255.0;
    if (v.b >= 0xC000u) {
        s.kind = SC_LOD;
        s.id = int(v.b & 0xFFu);
    } else if (v.b >= 0x8000u) {
        s.kind = SC_ENTITY;
        s.id = int(v.b & 0x3FFFu) - 1;
    } else {
        s.kind = SC_BLOCK;
        s.id = int(v.b) - 1;
    }
    s.emission = data.a;
}

#ifndef SC_NO_CUBE

// True when the mod is installed, speaks this protocol and has drawn the cube at least once.
bool sc_linked() {
    vec4 header = texelFetch(scInfo, ivec2(0, 0), 0);
    return abs(header.x - 4242.0) < 0.5 && abs(header.y - float(SC_PROTOCOL)) < 0.5 && header.z > 0.0;
}

// Range of the chunk terrain and of Distant Horizons' far terrain (0 without it), in blocks.
vec2 sc_ranges() {
    return texelFetch(scInfo, ivec2(7, 0), 0).zw;
}

// Capture origin of a face relative to this frame's camera, in blocks; w is the face's age in frames (-1: never drawn).
vec4 sc_faceOrigin(int face) {
    return texelFetch(scInfo, ivec2(1 + face, 0), 0);
}

int sc_face(vec3 d) {
    vec3 a = abs(d);
    if (a.x >= a.y && a.x >= a.z) return d.x >= 0.0 ? 0 : 1;
    if (a.y >= a.z) return d.y >= 0.0 ? 2 : 3;
    return d.z >= 0.0 ? 4 : 5;
}

// Atlas coordinate (0 to 1) of direction d on a given face.
vec2 sc_atlasCoord(vec3 d, int face) {
    vec3 forward, up, right;
    if (face == 0)      { forward = vec3( 1, 0, 0); up = vec3(0, 1, 0); right = vec3( 0, 0,  1); }
    else if (face == 1) { forward = vec3(-1, 0, 0); up = vec3(0, 1, 0); right = vec3( 0, 0, -1); }
    else if (face == 2) { forward = vec3( 0, 1, 0); up = vec3(0, 0, 1); right = vec3( 1, 0,  0); }
    else if (face == 3) { forward = vec3( 0,-1, 0); up = vec3(0, 0,-1); right = vec3( 1, 0,  0); }
    else if (face == 4) { forward = vec3( 0, 0, 1); up = vec3(0, 1, 0); right = vec3(-1, 0,  0); }
    else                { forward = vec3( 0, 0,-1); up = vec3(0, 1, 0); right = vec3( 1, 0,  0); }
    vec2 uv = 0.5 + 0.5 * vec2(dot(d, right), dot(d, up)) / dot(d, forward);
    uv = clamp(uv, vec2(0.5 / 2048.0), vec2(1.0 - 0.5 / 2048.0));
    return (vec2(float(face % 3), float(face / 3)) + uv) / vec2(3.0, 2.0);
}

// The surface seen from the cube's capture origin in world direction d (relative to that face's origin).
ScSurface sc_fetch(vec3 d) {
    ScSurface s;
    int face = sc_face(d);
    vec2 coord = sc_atlasCoord(d, face);
    vec4 origin = sc_faceOrigin(face);
    s.distance = texture(scDistance, coord).r;
    s.hit = s.distance > 0.0 && origin.w >= 0.0;
    sc_decode(texture(scAlbedo, coord), texture(scData, coord), s);
    s.position = origin.xyz + normalize(d) * s.distance;
    return s;
}

#endif

#ifdef SC_MIRROR

// True when the mod is installed, speaks this protocol and drew a mirror view this frame.
bool sc_mirrorLinked() {
    vec4 header = texelFetch(scMirrorInfo, ivec2(0, 0), 0);
    return abs(header.x - 4242.0) < 0.5 && abs(header.y - float(SC_MIRROR_PROTOCOL)) < 0.5 && header.z > 0.0;
}

// The mirror plane: xyz its normal (towards the camera), w the camera's height above it. For a point p relative to the
// camera, dot(xyz, p) + w is its height above the plane.
vec4 sc_mirrorPlane() {
    return texelFetch(scMirrorInfo, ivec2(1, 0), 0);
}

// The mirrored camera (the view's origin), relative to the camera.
vec3 sc_mirrorOrigin() {
    return texelFetch(scMirrorInfo, ivec2(2, 0), 0).xyz;
}

// Projection of points relative to the camera into the mirror view (clip space).
mat4 sc_mirrorMatrix() {
    return mat4(texelFetch(scMirrorInfo, ivec2(3, 0), 0), texelFetch(scMirrorInfo, ivec2(4, 0), 0),
                texelFetch(scMirrorInfo, ivec2(5, 0), 0), texelFetch(scMirrorInfo, ivec2(6, 0), 0));
}

// True when a point relative to the camera, with geometry normal n, lies on the mirror plane.
bool sc_mirrorOnPlane(vec3 p, vec3 n) {
    vec4 plane = sc_mirrorPlane();
    return abs(dot(plane.xyz, p) + plane.w) < 0.03 + length(p) * 2e-3 && dot(n, plane.xyz) > 0.9;
}

// Texture coordinate of a point relative to the camera in the mirror view; false outside the view.
bool sc_mirrorCoord(vec3 p, out vec2 coord) {
    vec4 clip = sc_mirrorMatrix() * vec4(p, 1.0);
    coord = clip.xy / clip.w * 0.5 + 0.5;
    return clip.w > 0.0 && all(greaterThanEqual(coord, vec2(0.0))) && all(lessThanEqual(coord, vec2(1.0)));
}

// The surface the mirror view shows at a texture coordinate. For a point p on the plane, the mirror reflection is at
// sc_mirrorCoord(p); a bent reflection ray can be traced by comparing distance(point, sc_mirrorOrigin()) with
// s.distance at each point's coordinate.
ScSurface sc_mirrorFetch(vec2 coord) {
    ScSurface s;
    s.distance = texture(scMirrorDistance, coord).r;
    s.hit = s.distance > 0.0;
    sc_decode(texture(scMirrorAlbedo, coord), texture(scMirrorData, coord), s);
    vec4 view = inverse(sc_mirrorMatrix()) * vec4(coord * 2.0 - 1.0, 1.0, 1.0);
    vec3 origin = sc_mirrorOrigin();
    s.position = origin + normalize(view.xyz / view.w - origin) * s.distance;
    return s;
}

#endif

#endif
