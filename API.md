# Shaders Companion: API for shader packs (cube protocol 2, mirror view protocol 1)

Shaders Companion draws two views for the active shader pack and hands them over as textures:

- the cube: a low-resolution view all around the camera, redrawn a few faces per frame;
- the mirror view: the world mirrored in the largest reflecting surface on screen (a water level or a glass wall), at screen resolution, every frame. See [Mirror view](#mirror-view).

A pack reads them by resource location; nothing else is needed on the pack's side, and a pack that uses them still works without the mod. The cube and the mirror view are independent: a pack can bind either or both.

## Binding the textures

In `shaders.properties`:

```properties
customTexture.scAlbedo = shaderscompanion:cube_albedo
customTexture.scData = shaderscompanion:cube_data
customTexture.scDistance = shaderscompanion:cube_distance
customTexture.scInfo = shaderscompanion:cube_info
```

and in the programs that use them:

```glsl
uniform sampler2D scAlbedo;
uniform sampler2D scData;
uniform sampler2D scDistance;
uniform sampler2D scInfo;
```

Oculus looks each name up in Minecraft's texture manager every time it binds it. Without the mod it binds Minecraft's missing texture (16 x 16, magenta and black), so the check below fails and the pack falls back to whatever it did before.

The mod draws the cube only while the active pack binds these textures, and the mirror view only while it binds the mirror view's: a pack without the `customTexture` lines (or with them switched off by an option) costs nothing.

## Checking the link

`texelFetch(scInfo, ivec2(0, 0), 0)` is `(4242, protocol, face size, frame counter)`. The cube is usable when the first value is 4242, the protocol is the one the pack was written for (2), and the face size is above 0. `api/shaderscompanion.glsl` has `sc_linked()`.

## Layout

All four cube textures are one atlas of 3 x 2 faces, `3N x 2N` pixels for faces of `N` pixels (`faceSize` in the mod's config, 512 by default). Face `i` is at column `i % 3`, row `i / 3`, with row 0 at texture coordinate v = 0 (OpenGL's bottom row).

| Face | Looks along | Up | Right |
|---|---|---|---|
| 0 | +X | +Y | +Z |
| 1 | -X | +Y | -Z |
| 2 | +Y | +Z | +X |
| 3 | -Y | -Z | +X |
| 4 | +Z | +Y | -X |
| 5 | -Z | +Y | +X |

Directions are world axes (the same as Oculus's player space). For a direction `d` from the face's capture origin: the face is the one whose "looks along" axis is `d`'s largest component, and within it `uv = 0.5 + 0.5 * vec2(dot(d, right), dot(d, up)) / dot(d, forward)`. The atlas coordinate is `((column + uv.x) / 3, (row + uv.y) / 2)`. Each face is a 90 degree perspective view.

## Contents

| Texture | Format | Contents |
|---|---|---|
| `cube_albedo` | RGBA8 | rgb: block texture times vertex tint, encoded like Minecraft's textures (sRGB). a: 1 on opaque surfaces, the texture's alpha on translucent ones (water, glass, at least 1/255), 0 where nothing was drawn (sky, or beyond the range). |
| `cube_data` | RGBA16 | Read each channel as `uint v = uint(round(x * 65535.0))`. r: surface normal, octahedral, high byte x and low byte y (each / 255). g: light, high byte block light and low byte sky light (each / 255, 1 = full light). b: what the texel shows, see below. a: for blocks, the block's own light level / 15; for entities, 1 on glowing layers (spider eyes and other "eyes" or "emissive" render types), else 0; for LODs 0. |
| `cube_distance` | R32F | Distance in blocks from the face's capture origin to the surface. 0 where nothing was drawn. |
| `cube_info` | RGBA32F, 8 x 1 | Texel 0: `(4242, protocol, face size, frame counter mod 4096)`. Texels 1 to 6, for faces 0 to 5: xyz is the face's capture origin minus this frame's camera position, in blocks; w is how many frames ago the face was drawn (-1: never). Texel 7: `(near plane, far plane, chunk terrain range, Distant Horizons range)`; the ranges are horizontal distances in blocks, the last one 0 without Distant Horizons LODs. |

`cube_data.b`, as `uint v`:

| Range | Texel shows | Id |
|---|---|---|
| 0 | a block not listed in `block.properties` | |
| 1 to 32767 | a block from the chunk meshes | `v - 1` is its `block.properties` id |
| 32768 to 49151 (`0x8000` bit) | an entity or block entity | `(v & 0x3FFF) - 1` is its `entity.properties` id, -1 when not listed (block entities are always -1) |
| 49152 and up (`0xC000` bits) | Distant Horizons far terrain | `v & 0xFF` is Distant Horizons' block material: 0 unknown, 1 leaves, 2 stone, 3 wood, 4 metal, 5 dirt, 6 lava, 7 deepslate, 8 snow, 9 sand, 10 terracotta, 11 nether stone, 12 water, 13 grass, 14 air, 15 light source |

Faces are redrawn in turn (`facesPerFrame` in the config, 2 by default), so each face has its own capture origin. The mod writes `cube_info` every frame before the world renders, relative to that frame's camera, so a pack can use the origins as they are.

`shaderscompanion:cube_preview` (RGBA8) is a debug picture for the mod's own overlay; packs shouldn't rely on it.

## What is drawn in the cube (0.4.0)

- Terrain from Embeddium's built chunk meshes within the render distance: solid and cut-out blocks, then translucent blocks (water, glass) over them, nearest surface first (texels with alpha below 0.1, such as clear glass, are left out). Chunks Embeddium hasn't built yet are queued for building.
- Entities within 48 blocks (mobs, other players, items, minecarts; at most 48, nearest first) and block entities within 24 blocks (chests, signs, banners, modded machines; at most 32), through Minecraft's own renderers with their textures and light. Name tags, sign text, enchantment glint and shadows are left out. The local player is left out in first person (the camera is inside its head).
- Distant Horizons LODs (3.3.2, when installed) beyond the chunk terrain, up to Distant Horizons' own range: its block colour, light and face direction, no textures.
- Not yet: particles. The local player in first person is drawn only in the mirror view (the cube's camera sits inside its head).

Each face's depth comes from the distance to its origin (logarithmic), so surfaces thousands of blocks away still sort correctly; `cube_distance` holds the exact distance.

## Mirror view

### Binding

```properties
customTexture.scMirrorAlbedo = shaderscompanion:mirror_albedo
customTexture.scMirrorData = shaderscompanion:mirror_data
customTexture.scMirrorDistance = shaderscompanion:mirror_distance
customTexture.scMirrorInfo = shaderscompanion:mirror_info
```

`api/shaderscompanion.glsl` has the reference functions with `#define SC_MIRROR` (and `SC_NO_CUBE` for a pack that reads only the mirror view).

### What it is

Each frame the mod casts a few rays from the camera through a grid over the screen and scores the reflecting surfaces they hit: water surfaces and the faces of blocks drawn in the translucent layer (stained and tinted glass, ice and similar). Minecraft's surfaces are axis-aligned, so each one lies on a plane: an axis, a side and a coordinate. The plane with the most screen area, weighted towards the centre of the screen (the surface you look at), is the mirror plane; it changes only after another one has clearly covered more for half a second, or at once when it leaves the view. Plain glass is drawn in the cut-out layer and isn't picked.

The view is then drawn from the camera mirrored in that plane, with the main view's rotation and a field of view 10% wider, at the screen's resolution (`mirrorScale` in the config). Everything on the far side of the plane, and the mirror surface itself, is cut away. Terrain, water and glass, entities and block entities (as in the cube) and Distant Horizons LODs are drawn, and so is your own player in first person, which the cube can't show. A point on the plane lands on (nearly) the same texel as on screen: the reflection of a flat mirror at a screen pixel is the mirror view's texel there. For bent rays (water waves, rough glass), trace: points along the reflected ray project into the view with the matrix below, and a point is behind a surface when it is farther from the mirrored camera than the distance stored at its texel.

No mirror view is drawn while the camera is under water or when no reflecting surface covers about 1% of the screen.

### Contents

`mirror_albedo` (RGBA8), `mirror_data` (RGBA16) and `mirror_distance` (R32F) hold the same fields as the cube's textures, one texel per pixel of the view (`width` x `height` from the info texture; 1 x 1 before the first mirror view). The distance is measured from the mirrored camera.

`mirror_info` (RGBA32F, 8 x 1), relative to this frame's camera, written every frame before the world renders:

| Texel | Contents |
|---|---|
| 0 | `(4242, protocol, width, height)`. Width and height are 0 when there is no mirror view this frame; then ignore the rest. |
| 1 | The plane: xyz its normal (pointing to the camera's side), w the camera's height above it. For a point `p` relative to the camera, `dot(xyz, p) + w` is its height above the plane. |
| 2 | xyz: the mirrored camera (the view's origin) relative to the camera; w: frame counter mod 4096. |
| 3 to 6 | Columns 0 to 3 of the matrix that takes a point relative to the camera to the view's clip space: `clip = mat4(t3, t4, t5, t6) * vec4(p, 1.0)`, texture coordinate `clip.xy / clip.w * 0.5 + 0.5`. |
| 7 | `(near plane, far plane, chunk terrain range, Distant Horizons range)` |

A pixel uses the mirror view when its position lies on the plane (`abs(dot(plane.xyz, p) + plane.w)` within a few hundredths of a block, more with distance for depth precision) and its geometry normal is the plane's. Other reflecting surfaces fall back to whatever the pack did before.

## Versions

Minecraft 1.20.1, Forge 47, Oculus 1.8.0 and Embeddium 0.3.31; Distant Horizons 3.3.2 optional. The protocol number goes up whenever a texture's layout or meaning changes; a pack should check it.

- 0.4.1: the same protocols. Distant Horizons LODs carry their block and sky light the right way round (0.3.0 and 0.4.0 wrote them swapped).
- Mirror view protocol 1 (0.4.0): first version.
- Protocol 2 (0.3.0): `cube_data.b` tells blocks, entities and LODs apart; `cube_data.a` marks glowing entity layers; `cube_info` texel 7 w is the Distant Horizons range.
- Protocol 1 (0.1.0, 0.2.0): terrain only.
