# Changelog

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
