---
name: minecraft-bluemap-rerender
description: Force a full BlueMap re-render on the production Minecraft server (cyborgpc.local) and set weather over RCON. Use when the user asks to перерендерить/пересинхронизировать BlueMap, update the web map after world edits, or change weather (snow/rain) on the server.
---

# BlueMap re-render and weather on the production server

Live experience from real sessions (Paper 26.3, BlueMap 5.29). Verified working.

## Server access

- Host: `cyborgpc.local` (SSH alias, user `master`). The server runs in Docker container `minecraft_server`; all files live on the host under `~/repos/minecraft-server`.
- RCON helper: `./rcon "<команда>"` from `~/repos/minecraft-server` on the host (password comes from `env/production.env`).
- **Files under `server/` are root-owned** (container runs as root) — any file operation needs `sudo -n`. RCON commands do not.
- Map URLs: internal `http://<host>:8100` (e.g. players at `/maps/world/live/players.json`), external `https://mc.geekideas.ru/map/`.
- rcon may drop the connection under load (`Error 111`) — retry with pauses, don't chain blindly.

## Full re-render (the only reliable way)

BlueMap reacts **only to region-file mtime**, not to content changes. To re-render everything:

```bash
ssh cyborgpc.local 'cd ~/repos/minecraft-server && \
  ./rcon "save-all flush" && sleep 5 && \
  ./rcon "bluemap stop" && sleep 2 && \
  sudo -n rm -rf server/bluemap/web/maps/world/tiles server/bluemap/web/maps/world/rstate && \
  ./rcon "bluemap reload" && sleep 8 && \
  sudo -n find server/world/dimensions/minecraft/overworld/region -name "*.mca" -exec touch {} + && \
  sleep 4 && ./rcon "bluemap start"'
```

Gotchas that cost a retry in practice:

- **`save-all flush` first** — otherwise BlueMap reads regions not yet flushed to disk.
- **`bluemap reload` is mandatory** after deleting tiles/rstate, with a pause (~8s).
- Regions in 26.x live in `server/world/dimensions/minecraft/overworld/region` — not `world/region`.
- Without `sudo` the `rm`/`touch` fail with "Отказано в доступе"; if any step of the chain fails, BlueMap is left **stopped** — always finish the procedure or run `./rcon "bluemap start"`.

## Verification

- Judge by **map folder size**, not command output: `du -sm server/bluemap/web/maps/world` — <15 MB means nothing rendered; a healthy re-render grows steadily (e.g. 13 → 125 MB per minute). Re-check after a minute; if stuck small, run the whole procedure a second time.
- Tile files are `.prbm.gz`; rstate is `.dat` files.
- The web map lags minutes behind; don't verify by screenshots.

## Weather over RCON

- `./rcon "weather rain 999999"` — "rain" falls as **snow** in cold biomes (biome temperature < 0.15); there is no separate "snow" command.
- `./rcon "weather clear"` to stop. `weather query` does not exist — don't use it.
- Snow/ice simulation plugins (e.g. Seasons `far-weather`) react to `world.hasStorm()` — rain is enough.
