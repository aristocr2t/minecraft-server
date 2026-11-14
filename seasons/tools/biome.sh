#!/bin/bash
# biome.sh X Y Z biome — PASS, если в точке стоит этот биом; чанк временно форс-лоадим.
# Проверка идёт ванильным /execute if biome, то есть по живому миру, а не по карте плагина.
cd ~/repos/minecraft-server || exit 1
pass=$(grep -h '^RCON_PASSWORD=' env/default.env env/production.env | tail -1 | cut -d= -f2)
rcon() { docker exec -i minecraft_server ./mcrcon-x86_64 -p "$pass" "$1" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tr -d '\r'; }

rcon "forceload add $1 $3" >/dev/null
out=$(rcon "execute if biome $1 $2 $3 minecraft:$4")
rcon "forceload remove $1 $3" >/dev/null
case "$out" in
  *"Test passed"*) echo PASS ;;
  *) echo FAIL ;;
esac
