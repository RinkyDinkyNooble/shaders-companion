# Changelog

## 3.0.1 (2026-11-28)

- Fixed: a grey wall under bridges and highways in the far terrain (Lost Cities highways over water showed it). Where the world saved its light unfinished, at the edge of the land generated so far, the far terrain took the air under the deck for a cave and closed it with stone.
- Fixed: with shaders off and the far terrain shown (`/shaderscompanion far show`), a band of sky between your render distance and the far terrain. While it is shown, the fog starts far out, so your chunks no longer fade into the sky colour at your render distance.
- `/shaderscompanion far on` no longer shows the far terrain with shaders off. That view is `far show` and `far hide` (off by default), as before.
- The far terrain is stored again once: in singleplayer it fills from your world's saved chunks again by itself.

## 3.0.0 (2026-11-27)

For Minecraft 1.20.1, Forge 47, Oculus 1.8.0 and Embeddium 0.3.31 (exactly this version). Distant Horizons is optional. Client, and optionally the server too.

- Far terrain: the world past your render distance, out to 1,024 blocks (`farRange`), drawn by the mod from its own copy of the world and shaded by ABZA Shader with its light, shadows, fog, clouds and light shafts. On by default; `/shaderscompanion far off` turns it off. In singleplayer it fills from the world's saved chunks as soon as you join, chunks pre-generated with Chunky included, and from every chunk the world saves after that. On a server it is what you have loaded there, plus what the server shares.
- Far terrain in reflections: water, glass and mirrors show the far land, also while Distant Horizons draws the far terrain on screen. Distant Horizons' own terrain is no longer drawn into reflections (2.0.0 did that).
- With Distant Horizons drawing far terrain, or a Voxy mod installed, the mod's far terrain stays out of the way on screen. `farTerrain` (or `/shaderscompanion far mode`) chooses.
- Servers: with the mod on the server too, players who have it get the world's saved chunks around them for their far terrain, so they see what has been explored without going there, and new and changed chunks follow as the server saves them. Nothing goes from one player to another, and blocks deep inside the rock (ores, hidden rooms) are never sent. Settings in the server's `config/shaderscompanion-common.toml`; `/farshare` (operators) shows what is sent. A player turns it down for one server with `/shaderscompanion far server deny`. A server without the mod works as before.
- Disk: the far terrain is kept in `shaderscompanion/lod`, at most 4 GB for every world and server together (`farStoreDiskMb`). Past that, the worlds and servers you visited longest ago are let go, and the far terrain of deleted worlds is removed. One server can fill at most 2 GB (`farServerDiskMb`).
- `farHiddenBlocks` leaves blocks out of the far terrain: command blocks by default, so Lost Cities buildings don't show them from afar.
- Comes with ABZA Shader 3.0.0, which shades the far terrain (options under Shaders Companion > "Far terrain") and has depth of field off by default.
- For shader pack authors: far terrain protocol 1 and the far shadow map. See `API.md`.

## 2.0.0 (2026-11-22)

For Minecraft 1.20.1, Forge 47, Oculus 1.8.0 and Embeddium 0.3.31 (exactly this version now). Distant Horizons 3.3.2 is optional. Client only.

- Up to four mirrors at once: a lake and a window beside it, or two walls of a glass building, each get their own sharp reflection. ABZA Shader's "Mirror planes" sets how many (one in Ultra, four in Max).
- Mirrors no longer switch between surfaces while you stand still, in stores full of glass and metal too.
- Steady ambient occlusion (with ABZA Shader, every preset): the mod keeps a copy of the blocks around you, so shading in corners and under overhangs no longer changes as you turn or flickers as things move past. Reflections of what is off screen use the same copy, so shiny surfaces indoors show the room around them instead of the sky through the walls, lamps' glow included.
- Auto-PBR Material Engine (with ABZA Shader, Max preset or the Shaders Companion screen): blocks without a resource pack's PBR maps get a material, so glass, ice, polished stone, metals and gems reflect and shine as they should. Vanilla blocks and Mekanism's, Superb Warfare's and Ad Astra's machines come from the mod's tables, other modded blocks from their sound, tags and name. Rules of your own go in `config/shaderscompanion/*.rules` (an example file is written there).
- Held items can reflect the world (ABZA Shader's "Held items reflect the world", off by default), TaCZ guns included; "Entity materials" (off by default, experimental) does the same for armour, vehicles and dropped items.
- Light no longer leaks through the closed sides of stairs and slabs into dark rooms (`lightLeakFix`, on). With ABZA Shader's "Closed doors keep light out" on (off by default), closed doors and trapdoors keep the light out too.
- Glass beside a lamp no longer glows by itself.
- Comes with ABZA Shader 2.0.0: new presets (Low, Medium, High, Ultra, Max and ABZA), a new menu, reflections shaded like the screen, steadier rough reflections and TAA. Your shader settings carry over; pick a preset to get the new ones.
- For shader pack authors: the lattice, material classes and up to four mirror planes. See `API.md`.

## 1.0.0 (2026-11-19)

For Minecraft 1.20.1, Forge 47, Oculus 1.8.0 and Embeddium 0.3.31. Distant Horizons 3.3.2 is optional. Client only.

- Fixed: far water past your render distance (Distant Horizons water) losing the mirror, which left it blurry with a hard edge where the mirror began. With a window near you, the mirror no longer flips between the window and the water.
- Fixed: strips of water behind grass and flowers losing their mirror.
- Fixed: far terrain flickering through near terrain in reflections.
- Fixed: memory use growing without end with mods that keep making new render types while they draw.
- Comes with ABZA Shader 1.1.0, with the mirror options on in every preset, Potato included: far water reflects the far shore instead of nearby trees, glass no longer shifts the room behind it at shallow angles ("Thin glass"), Steady exposure in every preset, and reflections of what is behind you get a night vision mod's light too.

## 0.9.3 Hotfix (2026-11-15)

For Minecraft 1.20.1, Forge 47, Oculus 1.8.0 and Embeddium 0.3.31. Distant Horizons 3.3.2 is optional. Client only.

- Fixed: reflections on far water and large glass walls flickering. A small or distant mirror is no longer dropped for single frames, and a surface the mirror newly takes over fades in (with ABZA Shader).
- Comes with ABZA Shader 1.0.30: indoor glass no longer shows flickering patches of daylight in its reflections; the Standard preset uses Metered exposure and the ABZA preset keeps Steady.
- For shader pack authors: `mirror_info` has a ninth texel, the fade-in. See `API.md`.

## 0.9.2 (2026-11-14)

For Minecraft 1.20.1, Forge 47, Oculus 1.8.0 and Embeddium 0.3.31. Distant Horizons 3.3.2 is optional. Client only.

- Comes with ABZA Shader 1.0.29: lit buildings no longer glare at night with its Steady exposure ("Night lift", Exposure).

## 0.9.1 (2026-11-14)

For Minecraft 1.20.1, Forge 47, Oculus 1.8.0 and Embeddium 0.3.31. Distant Horizons 3.3.2 is optional. Client only.

- Comes with ABZA Shader 1.0.28.
- Water and glass in reflections look like the water and glass around you (with ABZA Shader): reflected water has its colour, waves and sky, and reflected stained glass tints what lies behind it.
- In rain, the ground under glass roofs stays dry (with ABZA Shader): the mod tells the shader pack where the rain stops.
- TaCZ guns show in reflections.
- Mirrors are ready as you turn to them, at any frame rate, and a lake far below a tall building gets mirrored too.
- Faster: only the part of the mirror that is on screen is drawn, small mirrors are drawn at a lower resolution, and far mobs and block entities are redrawn less often (`entityLod`).
- Fixed: mobs popping in at a mirror's edge while turning.
- For shader pack authors: two new textures, what lies behind water and glass in the mirror view, and where rain falls. See `API.md`.

## 0.6.5 (2026-11-11)

First public version, for Minecraft 1.20.1, Forge 47, Oculus 1.8.0 and Embeddium 0.3.31. Distant Horizons 3.3.2 is optional. Client only.

- Water and stained or tinted glass work as mirrors: the largest one on screen shows a sharp reflection of the world, your own player included, with the shader pack's light and shadows.
- Other reflecting surfaces show what is behind, beside and above you instead of nothing.
- Reflections include terrain, water, glass, mobs, other players, chests and other block entities, and Distant Horizons' far terrain.
- Comes with ABZA Shader 1.0.23, installed as `shaderpacks/ABZA Shader.zip`.
- `/shaderscompanion on`, `off`, `status`, `debug` and `dump`.
- Any shader pack can use the views; the format is in `API.md` on GitHub.
