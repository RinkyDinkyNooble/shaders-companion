# Shaders Companion

A client-side Forge mod for Minecraft 1.20.1 that gives shader packs a second view. Screen-space reflections can only show what is on screen; with this mod a shader pack can also reflect what is behind or beside the player.

Status: prototype (0.4.0). It draws terrain, water, glass, mobs, players, block entities and Distant Horizons' far terrain into a cube around the camera, and a mirror view: the world mirrored in the largest water surface or glass wall on screen, at screen resolution, with your own player in it. ABZA Shader 1.0.18 reads both for reflections.

## How it works

- Every frame, just before the world renders and outside Oculus's world pass, the mod redraws one or more faces of a cube around the camera (two of six by default, so the whole cube every third frame).
- It doesn't rebuild any geometry. It draws the chunk meshes Embeddium has already built and uploaded, straight from Embeddium's region buffers, with its own small GL program: solid and cut-out blocks, then translucent ones (water, glass) over them. It reads Oculus's extended terrain vertex layout, so it gets each block's `block.properties` id too. Sections in range that Embeddium hasn't built yet (behind the player) are queued for building.
- Entities and block entities are drawn through Minecraft's own entity and block-entity renderers into a buffer source that keeps each render type's texture and stores the vertices for the mod's entity program, so modded mobs draw like vanilla ones. Oculus only wraps its own buffer sources, so it leaves this one alone. Nearest first, within a small time budget per frame.
- With Distant Horizons 3.3.2 installed, its LOD meshes are drawn too, straight from the vertex buffers Distant Horizons keeps for each LOD section (read-locked while drawing, as Distant Horizons' own renderer does), beyond the chunk terrain's range.
- The mirror view: a few rays from the camera each frame find the reflecting surfaces on screen (water, and blocks drawn in the translucent layer such as stained and tinted glass). The flat surface covering the most screen becomes the mirror plane, and the same terrain, entities and LODs are drawn once more from the camera mirrored in that plane, at the screen's resolution, with everything behind the plane cut away. Your own player is drawn into it too, so you can see yourself in water and glass. A pack reads it where a pixel lies on that plane: sharp reflections there, and the cube for everything else.
- It writes surface data, not a finished picture: colour, normal, block and sky light, what the texel is (block id, entity id or LOD material), the block's own light, and distance. The shader pack lights it with its own lighting, so reflections match the rest of the image.
- The textures are registered in Minecraft's texture manager as `shaderscompanion:cube_*`. A pack binds them with `customTexture` lines in `shaders.properties`; Oculus looks them up by name on every bind. Without the mod, Oculus binds Minecraft's missing texture, which the pack can detect and fall back from. The mod counts those lookups and draws only while the active pack makes them, so other packs cost nothing.
- Every GL state the mod touches is read before drawing and put back afterwards through `GlStateManager`, so vanilla's, Oculus's and Embeddium's state caches stay correct.

The format is in [API.md](API.md), with reference GLSL in [api/shaderscompanion.glsl](api/shaderscompanion.glsl) (0BSD, free to copy into any pack).

## Requirements

Minecraft 1.20.1, Forge 47, Oculus 1.8.0, Embeddium 0.3.31; Distant Horizons 3.3.2 optional. Built and tested against exactly those versions (the ABZA modpack's); it reads Embeddium's, Oculus's and Distant Horizons' internals, so other versions may need changes. It does nothing while no shader pack is active, or while the active pack doesn't read the cube.

## In game

- `/shaderscompanion debug` shows the six faces in the top-left corner, lit with a rough preview, and the mirror view next to them while there is one, with the cost per frame.
- `/shaderscompanion status` prints the same in chat.
- `/shaderscompanion dump` saves the faces to `screenshots/shaderscompanion-<time>-preview.png`, `-albedo.png` and `-distance.png`, and the mirror view to `-mirror.png`.
- `config/shaderscompanion-client.toml`: `enabled`, `faceSize` (pixels per face, 512), `facesPerFrame` (2), `maxDistance` (blocks; 0 = render distance), `entities`, `entityDistance` (48), `maxEntities` (48), `blockEntities`, `blockEntityDistance` (24), `maxBlockEntities` (32), `distantHorizons`, `lodDistance` (blocks; 0 = Distant Horizons' range), `mirror`, `mirrorScale` (percent of the screen's resolution, 100).

## Building

1. Put the jars it compiles against in `libs/` (gitignored), named `oculus-1.8.0.jar`, `embeddium-0.3.31.jar` and `distanthorizons-3.3.2.jar` (copied from the ABZA instance's `mods` folder).
2. `gradlew build` with Java 17. The jar is `build/libs/shaderscompanion-<version>.jar`.

### Dev check

`gradlew runClient -PdevDump=600` starts a dev client with Oculus and Embeddium, makes a fresh world, lifts the player 60 blocks (`-PdevLift=0` keeps them on the ground), waits until the cube has been drawn 600 times, saves it and a screenshot to `run/screenshots/` and quits. Scenes (`-PdevScene=`): `pool`, a water pool under a glowstone ceiling above the camera's view (`-PdevPitch` sets how far it looks down), saved with the mod drawing and turned off; `mobs`, still mobs, a chest and a sign around the camera on a platform; `mirror`, a black glass wall in front with the mobs behind the camera, saved with the mod on and off (the mirror view shows your player in the glass). `-PdevDh` loads Distant Horizons too. It needs a shader pack in `run/shaderpacks/` named in `run/config/oculus.properties`. The code lives in `src/verify/` and never ships.

## Not yet

Particles; textured Distant Horizons LODs; more than one mirror plane at a time; a second layer for water and glass, so tinted glass doesn't hide what is behind it in reflections.

## License

0BSD (see [LICENSE](LICENSE)). Oculus, Embeddium and Distant Horizons aren't included; this mod only calls them.
