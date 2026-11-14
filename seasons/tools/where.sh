#!/bin/bash
# where.sh X Y Z — печатает биомы из списка, которые стоят в точке (по живому миру).
# Список — все биомы, которые встречаются в этом мире плюс их сезонные варианты.
cd ~/repos/minecraft-server || exit 1
pass=$(grep -h '^RCON_PASSWORD=' env/default.env env/production.env | tail -1 | cut -d= -f2)
rcon() { docker exec -i minecraft_server ./mcrcon-x86_64 -p "$pass" "$1" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tr -d '\r'; }

CANDIDATES="plains sunflower_plains flower_forest forest birch_forest dark_forest taiga
old_growth_pine_taiga old_growth_spruce_taiga snowy_taiga snowy_plains snowy_beach snowy_slopes
meadow windswept_hills windswept_gravelly_hills windswept_forest windswept_savanna
swamp mangrove_swamp jungle bamboo_jungle sparse_jungle cherry_grove savanna savanna_plateau
desert badlands eroded_badlands wooded_badlands beach stony_shore stony_peaks jagged_peaks
frozen_peaks ice_spikes ocean deep_ocean warm_ocean lukewarm_ocean cold_ocean deep_cold_ocean
deep_lukewarm_ocean deep_frozen_ocean frozen_ocean river frozen_river mushroom_fields
lush_caves dripstone_caves deep_dark grove pale_garden"

rcon "forceload add $1 $3" >/dev/null
for b in $CANDIDATES; do
  out=$(rcon "execute if biome $1 $2 $3 minecraft:$b")
  case "$out" in
    *"Test passed"*) echo "$b" ;;
  esac
done
rcon "forceload remove $1 $3" >/dev/null
