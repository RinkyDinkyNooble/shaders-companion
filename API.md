# Shaders Companion: API for shader packs (cube protocol 2, mirror view protocol 3, rain cover protocol 1, lattice protocol 1, material table protocol 1)

Shaders Companion draws two views for the active shader pack and hands them over as textures:

- the cube: a low-resolution view all around the camera, redrawn a few faces per frame;
- the mirror view: the world mirrored in the largest reflecting surfaces on screen (water levels and glass walls, as many as the pack asks for), at screen resolution, every frame. See [Mirror view](#mirror-view).

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
- Entities within 48 blocks (mobs, other players, items, minecarts; at most 48, nearest first) and block entities within 24 blocks (chests, signs, banners, modded machines; at most 32), through Minecraft's own renderers with their textures and light. Name tags, sign text, enchantment glint and shadows are left out. The local player is left out in first person (the camera is inside its head). Since 0.6.3 they are collected after the world is drawn and shown in the next frame's views, a frame late and moved to that frame's camera: entity renderers that keep animation state per frame (GeckoLib) then see the game's own draw first.
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

The name the info texture is bound under says how many planes the pack wants mirrored at once: `mirror_info` one, `mirror_info_2`, `mirror_info_3` or `mirror_info_4` up to that many (the same texture under each name; added in 2.0.0, mirror protocol 3). For example, from a pack option:

```properties
#if MIRROR_PLANES == 2
customTexture.scMirrorInfo = shaderscompanion:mirror_info_2
#else
customTexture.scMirrorInfo = shaderscompanion:mirror_info
#endif
```

`api/shaderscompanion.glsl` has the reference functions with `#define SC_MIRROR` (and `SC_NO_CUBE` for a pack that reads only the mirror view).

### What it is

Each frame the mod casts a few rays from the camera through a grid over the screen and a margin around it, and scores the reflecting surfaces they hit: water surfaces and the faces of blocks drawn in the translucent layer (stained and tinted glass, ice and similar). Minecraft's surfaces are axis-aligned, so each one lies on a plane: an axis, a side and a coordinate. The planes with the most area, weighted towards where the centre of the screen will be a moment from now (the surface you look at, or turn to), are mirrored, up to as many as the pack asks for, each in a slot of its own that it keeps while it is mirrored. An empty slot takes the best plane left at once. With every slot taken, another plane takes the weakest one's slot after it has covered more for half a second, or after 60 ms when it covers twice as much. A plane that leaves the view frees its slot. Plain glass is drawn in the cut-out layer and isn't picked.

Each plane's view is then drawn from the camera mirrored in that plane, with the main view's rotation and a field of view 10% wider, at the screen's resolution (`mirrorScale` in the config; with `mirrorLod`, 75% or 50% of it while the plane covers little of the screen). Only the part of the view where the plane is on screen, with a margin, is drawn (mirror protocol 2 and later): elsewhere the distance texture holds -1. Everything on the far side of the plane, and the mirror surface itself, is cut away. Terrain, water and glass, entities and block entities (as in the cube) and Distant Horizons LODs are drawn, and so is your own player in first person, which the cube can't show. A point on the plane lands on (nearly) the same texel as on screen: the reflection of a flat mirror at a screen pixel is the mirror view's texel there. For bent rays (water waves, rough glass), trace: points along the reflected ray project into the view with the matrix below, and a point is behind a surface when it is farther from the mirrored camera than the distance stored at its texel.

No mirror view is drawn while the camera is under water (or in lava or powder snow) or when no reflecting surface covers about 1% of the screen. Every plane costs about as much as the first while it is on screen: its own view of the world, and whatever the pack does with it.

### Shadows

Oculus draws into a pack's shadow map only what can cast a shadow into the main view, unless the pack sets `shadow.culling = false`. While mirror views are drawn, the mod (0.6.1) also keeps whatever can cast a shadow into any of them (`mirrorShadows` in the config), within the pack's shadow distance, so a pack can look the mirror view's surfaces up in its shadow map like the screen's. The cube has no such help: its surfaces behind the camera may be missing from the shadow map.

### Contents

`mirror_albedo` (RGBA8), `mirror_data` (RGBA16) and `mirror_distance` (R32F) hold the same fields as the cube's textures. They hold one tile per plane the pack asks for, side by side from the left, each the size of the screen at `mirrorScale`; a plane's view fills `width` x `height` texels from its tile's lower left corner (texel 0 and texel 8 of its info row), one texel per pixel of the view (1 x 1 before the first mirror view). A view coordinate `coord` (0 to 1 across the view) is the texture coordinate `(tile + coord * size) / textureSize(...)`; keep `coord * size` within half a texel of the view's edges so filtering doesn't reach the next tile. The distance is measured from the mirrored camera (the view's origin, texel 2); 0 is open sky, and -1 marks a texel the mod didn't draw (outside the plane's part of the screen, or a tile no plane uses this frame): treat a ray that reaches one like a ray that leaves the view.

The view's rays don't always start at that origin: with view bobbing on, Oculus puts the bobbing into the view matrix, so while the player walks the view's eye sits up to about 0.1 block off the origin (as the main view's eye sits off the camera position). Projecting a point into the view with the matrix is exact either way. To turn a texel back into a position, use the texel's own ray: with `m` the matrix for points relative to the origin (the matrix below with its last column replaced by `matrix * vec4(origin, 1.0)`), the eye is `e = inverse(m) * vec4(0.0, 0.0, 1.0, 0.0)`, `e.xyz / e.w`; the ray runs from it through the texel's point on the near plane (`inverse(m) * vec4(ndc.xy, -1.0, 1.0)`), and the surface is the point on it at the stored distance from the origin. A direction taken from the origin through the near-plane point is far off while walking (the near plane is 0.05 blocks from the eye).

`mirror_info` (RGBA32F, 9 x 4: one row per slot, rows past the planes the pack asks for empty), relative to this frame's camera, written every frame before the world renders. A row:

| Texel | Contents |
|---|---|
| 0 | `(4242, protocol, width, height)`: the view's size. Width and height are 0 when the slot has no plane this frame; then ignore the rest of the row. |
| 1 | The plane: xyz its normal (pointing to the camera's side), w the camera's height above it. For a point `p` relative to the camera, `dot(xyz, p) + w` is its height above the plane. |
| 2 | xyz: the mirrored camera (the view's origin) relative to the camera; w: frame counter mod 4096. |
| 3 to 6 | Columns 0 to 3 of the matrix that takes a point relative to the camera to the view's clip space: `clip = mat4(t3, t4, t5, t6) * vec4(p, 1.0)`, texture coordinate `clip.xy / clip.w * 0.5 + 0.5`. |
| 7 | `(near plane, far plane, chunk terrain range, Distant Horizons range)` |
| 8 | x: how far the plane has faded in, 0 to 1 over 0.15 seconds after the mod starts mirroring it. For a pack that blends the mirror view's reflection with its own by it, so a surface doesn't jump between them in one frame. yz: the corner of the view's tile in the textures, in texels (y is 0 for now). w: 0. |

A pixel uses a plane's view when its position lies on the plane (`abs(dot(plane.xyz, p) + plane.w)` within a few hundredths of a block, more with distance for depth precision) and its geometry normal is the plane's. Try the rows in order; at most one plane fits a pixel. Other reflecting surfaces fall back to whatever the pack did before.

### What lies behind water and glass (0.9.0)

```properties
customTexture.scMirrorBehindAlbedo = shaderscompanion:mirror_behind_albedo
customTexture.scMirrorBehindData = shaderscompanion:mirror_behind_data
customTexture.scMirrorBehindDistance = shaderscompanion:mirror_behind_distance
```

The mirror views as they were after their opaque surfaces (terrain, entities, LODs) and before water, glass and other translucent surfaces were drawn over them: the same three fields, in the same layout, tiles and size as `mirror_albedo`, `mirror_data` and `mirror_distance`, valid in the drawn part of each view. Where the mirror view shows a translucent surface (`mirror_albedo` alpha below 1), these show what lies behind it, so a pack can shade water over the depth to the ground under it and let glass tint what is behind it. Copied only while the pack binds them (they cost a copy of the drawn part each frame and about 16 bytes a pixel of memory); 1 x 1 before the first copy.

## Rain cover (0.9.1)

```properties
customTexture.scRainCover = shaderscompanion:rain_cover
```

Where rain falls to: Minecraft's `MOTION_BLOCKING` heightmap, the one the game stops rain and its splashes at, for the 512 x 512 block columns around the camera. Glass and every other block that stops movement count, so a pack can keep the ground under a glass roof dry, which sky light can't tell (glass lets full sky light through).

`R32F`, 512 x 513. The column at block coordinates `(x, z)` is at texel `(x & 511, z & 511)` (`texelFetch`; the window follows the camera without moving data). Read `int v = int(texel.r)`:

- `texel.r < 0`: unknown (the column's chunk isn't loaded): fall back to whatever the pack did before.
- `v >> 12` is a tag, `((x >> 9) & 15) | (((z >> 9) & 15) << 4)` of the column the texel holds; when it isn't the tag of the column you asked for, the texel holds a column 512 blocks away: unknown.
- `(v & 4095) - 2048` is the lowest free height above the column's highest block that stops rain (Minecraft's `getFirstAvailable`). A surface point is in the rain when it is at or above that height minus half a block (the top of a bottom slab is half a block below it); test a point pushed a little off the surface along its normal, so side faces read the column in front of them.

Row 512 is the header: texel 0 is 4242 (0 while the mod isn't writing it), texel 1 the protocol (1), texel 2 the window size (512). Check the texture's size first: without the mod, Oculus binds Minecraft's 16 x 16 missing texture. Chunks entering the window are read at once and every chunk again within 32 frames, for blocks placed or broken; written only while the pack binds it.

## Lattice (2.0.0)

```properties
customTexture.abzaLatticeCells = shaderscompanion:lattice_cells
customTexture.abzaLatticeShape = shaderscompanion:lattice_shape
customTexture.abzaLatticeInfo = shaderscompanion:lattice_info
```

```glsl
uniform usampler3D abzaLatticeCells;
uniform usampler3D abzaLatticeShape;
uniform isampler2D abzaLatticeInfo;
```

A copy of the blocks around the camera, built from the client's world rather than from what is drawn: every block within the window, whatever is on screen, the sun's direction or the shadow settings, with modded blocks in their own shapes and the light engine's light. It changes only where the world changes (a block placed or broken, light changing, a chunk loading), so it holds still while you look around. The two 3D textures are bound as 3D textures (the mod hands them to Oculus itself).

**Window.** `W x 128 x W` blocks, `W` the `latticeSize` config (64, 128 by default, or 256), centred on the camera's 16-block section. The block at `(x, y, z)` is at texel `(x & (W - 1), y & 127, z & (W - 1))` (`texelFetch`; two's complement makes this right for negative coordinates).

**`lattice_info`** (`RGBA32I`, 8 x 1): texel 0 is `(4242, protocol 1, W, 128)` (magic 0 while the mod isn't keeping the lattice; without the mod, Oculus binds Minecraft's missing texture, which is no integer texture: check the magic), texel 1 `xyz` the window's lowest block. Blocks outside `[min, min + (W, 128, W))` are unknown. Texels 2 and 3 describe the openness field, texel 4 the detail (below).

**`lattice_cells`** (`R32UI`), per block:

- bits 0-3: block light, 4-7: sky light (the light engine's, 0 to 15; solid blocks hold 0, so light a face by the block in front of it)
- bits 8-11: the block's own light emission
- bit 12: water (waterlogged blocks too), 13: lava, 14: see-through (glass, leaves, plants: the block doesn't hide its neighbours), 15: no collision (plants, flowers, cobwebs, torches)
- bits 16-27: material class ("Material classes" below: the class the block's id carries in the pipeline's block state to id map, 0 for none or while the pack doesn't read the material table); it follows the pack's options, as the ids do
- bits 28-30: the window tile's parity per axis, `((x >> log2 W) & 1) | ((y >> 7) & 1) << 1 | ((z >> log2 W) & 1) << 2`; when it isn't the parity of the block you asked for, the texel holds a block a window away: unknown
- bit 31: known. 0 where the chunk isn't loaded, below the world, or not read yet

**`lattice_shape`** (`RG32UI`), per block: its outline shape in 4 x 4 x 4 quarter-block cells, bit `x + 4 z + 16 y` for the cell at `(x, y, z)`; `r` holds the bits 0 to 31 (y 0 and 1), `g` the bits 32 to 63. A cell is filled when the shape covers more than 1/64 of a block of it along every axis, so panes, carpets and thin plates keep a layer of cells. The outline shape is the one you see around a block you look at: plants are their boxes (use bit 15 to treat them as sparse), fluids have none (bits 12 and 13).

**Openness field.**

```properties
customTexture.abzaLatticeOpenA = shaderscompanion:lattice_open_a
customTexture.abzaLatticeOpenB = shaderscompanion:lattice_open_b
```

```glsl
uniform sampler3D abzaLatticeOpenA;
uniform sampler3D abzaLatticeOpenB;
```

Ambient occlusion baked from the lattice by the mod (a compute shader of its own, OpenGL 4.4), so a pack samples a field that holds still instead of tracing per pixel per frame. A probe every half block in a 64-block window centred on the section boundary nearest the camera (so it reaches at least 24 blocks every way), each with how open it is in six directions: 48 fixed rays (the same for every probe) through the quarter-block shapes, each counting `t / reach` of its light for a solid hit at distance `t` (reach 1 block), 0.8 per quarter block of plants (no collision) and 0.7 per quarter block of see-through blocks passed, all of it where nothing is in the way; a direction's openness is the rays' average weighted by their cosine to it. A section's probes are baked when it enters the window and again when the shapes or plant and see-through flags of it or its 26 neighbours change, nearest first, at most 4 sections a frame (about 0.22 ms of GPU each on an RTX 5050); never because the camera moved.

- `lattice_info` texel 2: `(4243, protocol 1, 64, reach in quarter blocks)`, magic 0 while the field isn't baked (the pack doesn't bind the textures, or no OpenGL 4.4); texel 3 `xyz` the window's lowest block.
- Both `RGBA8`, 128 x 128 x 128. The probe at world position `p` (a multiple of half a block) is at texel `ivec3(p * 2) & 127`. `A` = openness towards +x, -x, +y, -y; `B` = +z, -z, 1 for a probe in open space (0 inside solid matter: leave it out), and the window tile's parity + 1 over 255, the parity being `((q >> 7) & 1) | ((q.y >> 7) & 1) << 1 | ((q.z >> 7) & 1) << 2` for `q = ivec3(p * 2)`: when it doesn't match, the probe isn't baked for this place: leave it out.
- Sample a surface point by the eight probes around it pushed a little along its normal, trilinear, leaving out probes inside solid matter, behind the surface or not baked; evaluate each with the normal `n` as `n.x^2 * (n.x > 0 ? +x : -x) + n.y^2 * ... + n.z^2 * ...`.

**Detail (2.0.0-dev12).**

```properties
customTexture.abzaLatticeDetail = shaderscompanion:lattice_detail
customTexture.abzaLatticeDetailDistance = shaderscompanion:lattice_detail_distance
```

```glsl
uniform usampler3D abzaLatticeDetail;
uniform sampler3D abzaLatticeDetailDistance;
```

Each block's shape as it is drawn, at full resolution, for contact shading finer than the quarter blocks: per block state, the distance from points in the block to the nearest surface of its model (the quads Minecraft draws for it, translucent parts left out; the outline boxes for blocks drawn otherwise, such as chests), worked out once per state on the processor and kept in an atlas slot shared by every state with the same shape. Blocks without collision (plants, torches), translucent blocks and blocks drawn offset (bamboo) shade nothing; full cubes take no slot.

- `lattice_info` texel 4: `(4244, protocol 1, 16, 0)`; magic 0 while the lattice isn't kept.
- `lattice_detail` (`R32UI`), per block, the window and addressing of `lattice_cells`: bits 0-25 the detail (0 shades nothing or unknown, 1 a full cube, from 2 an atlas slot), bit 26 a block in or next to this one has a detail the openness field doesn't hold as it is (from 2 or 1 without bit 27: a cheap test for whether a surface on this block needs a contact shade at all), bit 27 the openness field holds this block as it is (a solid block whose model lies on quarter blocks: full cubes, slabs, stairs; furniture and other blocks that don't hide their neighbours count as see-through in the field and don't have it), bits 28-30 the window tile's parity, bit 31 known (as `lattice_cells`).
- `lattice_detail_distance` (`R8`, linear filtering, 256 x 256 x 16 per layer of 256 slots; it grows by layers): slot `s` (from 2) at texel `((s - 2) & 15, (s - 2) >> 4 & 15, (s - 2) >> 8) * 16`, 16 x 16 x 16 texels, texel `(x, y, z)` the distance in half blocks (1 = half a block or more) from the point `((x, y, z) + 0.5) / 16` of the block to its drawn surfaces. Sample with `clamp(p * 16, 0.5, 15.5)` inside the slot so the filter stays in it. Outside the block, `sqrt(|p - q|^2 + d(q)^2)` with `q` the nearest point of the block is a lower bound. A slot reads as 1 everywhere until it is baked (about 0.3 ms of processor time a frame while shapes wait; 1264 shapes of Modern Furniture Forked took about 20 seconds).
- Block models baked again (a resource reload) work every state out again.

**Updates.** Sections are read nearest the camera first, within `latticeBudget` microseconds a frame (1000 by default): on joining a world the whole window (512 sections at 128) took 80 frames in the dev client, about 1.2 ms a frame, 94 ms in all. After that, about 40 to 60 microseconds per changed section (a section also reads the blocks around it when a neighbour may have a fine detail), plus one section read again each frame in turn and uploaded only if it changed. Written only while the pack binds the textures. Video memory: 32 MB at 128, 128 MB at 256, 16 MB for the openness field, and 1 MB per 256 detail shapes.

## Material classes (2.0.0)

```properties
customTexture.scMaterials = shaderscompanion:material_table
```

A pack that binds this texture asks the mod to give blocks material classes. When Oculus builds the pipeline's block state to id map from the pack's `block.properties`, the mod puts a class (1 to 511) into bits 0-8 of the ids, in Sundial's convention for feature-flag ids:

- a block the pack doesn't list gets `16384 | class`;
- a block the pack lists with an id of 16384 or more whose bits 0-8 are 0 gets `id | class`;
- any other listed block keeps its id (no class). Unlisted blocks without collision (plants) get none.
- every other block state the pack doesn't list gets `16384` (class 0, no flags) when it gives no light, has collision and isn't a plant by its sound or name. Packs that make unlisted blocks glow at full block light (Sundial's modded light detection) then glow only real light sources: a face pointing at a light-15 block samples that block's cell and has full light too (glass beside a lamp, its far face seen through it). Unlisted light sources and plants stay unlisted (-1).

So `mc_Entity.x` (and `blockEntityId`, for block entities), the cube and mirror views' ids (`cube_data.b - 1`) carry the class: `id >= 16384 ? id & 0x1FF : 0`; the lattice holds the class itself (`lattice_cells` bits 16-27). Entities and block entities in the mod's views carry no class (their `cube_data.b` is in the entity range). Distant Horizons' LODs carry its 16 block materials only (`cube_data.b & 0xFF` from 49152, `dhMaterialId` on screen); its METAL (4) is sound-based, not this system's.

**`material_table`** (`RGBA8`, 512 x 1, read with `texelFetch(...) * 255`): texel 0 is `(42, 42, protocol 1, 0)` (all 0 without the mod: check it); texel `c` is class `c`'s LabPBR 1.3 bytes: r smoothness, g F0 (0-229) or metal (230-237 the named metals, 255 metal by albedo), b porosity / subsurface scattering, a 255 for a class in use. Use the class where the block's own specular map is absent (Oculus's default specular is 0 in every channel).

Classes come from `config/shaderscompanion-materials.json` (class name -> block ids and `#tags`; `none` takes blocks out), then the mod's vanilla table, then inference from a modded block's sound type, tags (`forge:glass`, `forge:storage_blocks/<metal>`, ...) and name. The classes and their values: `research/MATERIALS.md` in the mod's repository; the first 17 are glass, ice, gel, polished, glazed, concrete, obsidian, gem, iron, gold, aluminum, chrome, copper, exposed_copper, silver, metal, dark_metal (numbers 1 to 17 in that order). The config's `materials` switch turns it off.

## Versions

Minecraft 1.20.1, Forge 47, Oculus 1.8.0 and Embeddium 0.3.31; Distant Horizons 3.3.2 optional. The protocol number goes up whenever a texture's layout or meaning changes; a pack should check it.

- 2.0.0 (development): `lattice_*` (protocol 1): the lattice; from 2.0.0-dev12 its detail (`lattice_detail*`, `lattice_info` texel 4). From 2.0.0-dev16, mirror view protocol 3: up to four planes mirrored at once, as many as the name the pack binds `mirror_info` under asks for (`mirror_info_N`); the mirror textures hold one tile per plane, `mirror_info` one row per plane (9 x 4) with the tile's corner in texel 8.
- 1.0.0: the same protocols and textures. The plane search traces collision shapes (plants no longer stop its rays) and counts water past the chunk terrain's range; chunk terrain is drawn over Distant Horizons LODs in the views by a stencil, so far terrain no longer shows through near terrain; the entity capture frees its kept memory when mods create render types without end. Comes with ABZA Shader 1.1.0.
- 0.9.3: the same protocols. `mirror_info` texel 8: how far a newly mirrored plane has faded in. A small or far plane is held through frames its rays miss. Comes with ABZA Shader 1.0.30.
- 0.9.2: the same protocols and textures; comes with ABZA Shader 1.0.29.
- 0.9.1: the same protocols. `rain_cover` (protocol 1): where rain falls to.
- 0.9.0: the same protocols. `mirror_behind_*`: the mirror view's surfaces behind water and glass. The plane search follows rays going down past its 64 blocks to the water levels it has seen and the sea level, so a lake far below counts.
- 0.8.0: the same protocols. The mirror's plane search runs in real time (as quick at 60 frames a second as at 260); mobs and block entities are collected for the whole mirror view, not only its drawn part; far ones are redrawn less often (`entityLod`).
- 0.7.0: mirror view protocol 2: only the mirror's part of the view is drawn, -1 in the distance texture elsewhere; the view's size can change with `mirrorLod`. The plane is chosen ahead of the camera's turn.
- 0.6.3: the same protocols. Mobs and block entities are a frame late in the views (collected after the world is drawn); the local player in the mirror view is not.
- 0.6.1: the same protocols. The shadow pass keeps what can cast a shadow into the mirror view.
- 0.4.1: the same protocols. Distant Horizons LODs carry their block and sky light the right way round (0.3.0 and 0.4.0 wrote them swapped).
- Mirror view protocol 1 (0.4.0): first version.
- Protocol 2 (0.3.0): `cube_data.b` tells blocks, entities and LODs apart; `cube_data.a` marks glowing entity layers; `cube_info` texel 7 w is the Distant Horizons range.
- Protocol 1 (0.1.0, 0.2.0): terrain only.
