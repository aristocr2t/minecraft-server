#!/usr/bin/env python3
"""Генератор парка вокруг готового спавна: дороги, деревья, фонари, поляны.

Читает исходную схематику (чтобы знать высоту земли по каждой колонке), не трогает
квартал города и пишет ванильные структурные .nbt тайлы + список команд /place template.
"""
import importlib.util, os, random, sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('s2n', os.path.join(HERE, 'schem2nbt.py'))
s2n = importlib.util.module_from_spec(spec); spec.loader.exec_module(s2n)

SRC = sys.argv[1]
OUT = sys.argv[2]
SEED = int(sys.argv[3]) if len(sys.argv) > 3 else 1337
TILE = 64

sch = s2n.read_nbt(SRC)['Schematic']
W, H, L = sch['Width'], sch['Height'], sch['Length']
blk = sch['Blocks']; pal = blk['Palette']; data = blk['Data']

vals = []; i = 0; n = len(data)
while i < n:
    num = 0; sh = 0
    while True:
        c = data[i]; i += 1; num |= (c & 0x7F) << sh; sh += 7
        if not (c & 0x80): break
    vals.append(num)
name_of = {idx: k.split('[')[0].replace('minecraft:', '') for k, idx in pal.items()}
X0 = -(W // 2); Z0 = -(L // 2)

top = {}
WATER = {'water', 'kelp', 'seagrass'}
for idx, pidx in enumerate(vals):
    nm = name_of.get(pidx, 'air')
    if nm == 'air': continue
    y, rem = divmod(idx, W * L); z, x = divmod(rem, W)
    k = (x, z)
    if nm in WATER: continue
    if y > top.get(k, (-1, ''))[0]: top[k] = (y, nm)

# квартал города ищем по плотности высоких колонок (крыши), а не по одиночным деревьям
from collections import Counter
cell_hits = Counter()
for (x, z), (y, nm) in top.items():
    if y > 40:
        cell_hits[(x // 16, z // 16)] += 1
dense = [c for c, v in cell_hits.items() if v >= 60]
if not dense:
    dense = [c for c, v in cell_hits.most_common(20)]
cx1 = min(c[0] for c in dense) * 16; cx2 = max(c[0] for c in dense) * 16 + 15
cz1 = min(c[1] for c in dense) * 16; cz2 = max(c[1] for c in dense) * 16 + 15
PAD = 12
city = (cx1 - PAD, cz1 - PAD, cx2 + PAD, cz2 + PAD)
print('город в координатах схематики: x %d..%d z %d..%d' % (city[0], city[2], city[1], city[3]))

def in_city(x, z):
    return city[0] <= x <= city[2] and city[1] <= z <= city[3]

# плоскость земли: только открытые колонки с травой на высоте 24..30
ground = {}
for (x, z), (y, nm) in top.items():
    if 24 <= y <= 30 and nm in ('grass_block', 'dirt', 'coarse_dirt', 'podzol'):
        ground[(x, z)] = y
print('клеток под парк:', len(ground))

rnd = random.Random(SEED)
blocks = {}   # (x, y_world, z) -> blockstate

def put(x, y, z, state):
    blocks[(x, y, z)] = state

def road_y(x, z):
    g = ground.get((x, z))
    return None if g is None else g + 1 + 60   # уровень, по которому ходим

ROAD = ['dirt_path', 'gravel', 'coarse_dirt', 'andesite']

# --- дороги: крест из ворот квартала до края площадки
gx1, gz1, gx2, gz2 = city[0], city[1], city[2], city[3]
midx = (gx1 + gx2) // 2
midz = (gz1 + gz2) // 2
roads = []
roads.append([(x, midz) for x in range(0, W)])          # запад-восток
roads.append([(midx, z) for z in range(0, L)])          # север-юг

for road in roads:
    for (x, z) in road:
        if (x, z) not in ground: continue
        gy = road_y(x, z)
        if gy is None: continue
        # трёхблочная ширина
        for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            xx, zz = x + dx, z + dz
            if (xx, zz) in ground and not in_city(xx, zz):
                put(xx + X0, road_y(xx, zz) - 1, zz + Z0, rnd.choice(ROAD))

# --- фонари вдоль дорог
for road in roads:
    for k, (x, z) in enumerate(road):
        if k % 11 or (x, z) not in ground or in_city(x, z): continue
        side = 2 if k % 22 else -2
        xx = x + (side if len(road) > 2 and road[1][1] == z else 0)
        zz = z + (side if len(road) > 2 and road[1][0] == x else 0)
        if (xx, zz) not in ground: continue
        base = road_y(xx, zz)
        if base is None: continue
        put(xx + X0, base, zz + Z0, 'spruce_fence')
        put(xx + X0, base + 1, zz + Z0, 'spruce_fence')
        put(xx + X0, base + 2, zz + Z0, 'lantern')

# --- деревья: плотнее к краям, реже у города
def tree(x, z, base, kind):
    h = rnd.randint(5, 8)
    for dy in range(h):
        put(x, base + dy, z, kind + '_log')
    top_y = base + h
    r = 2 if h < 7 else 3
    for dx in range(-r, r + 1):
        for dz in range(-r, r + 1):
            for dy in (-2, -1, 0):
                if dx * dx + dz * dz + (dy * 2) ** 2 > r * r + 2: continue
                if dx == 0 and dz == 0 and dy < 0: continue
                put(x + dx, top_y + dy, z + dz, kind + '_leaves')
    put(x, top_y + 1, z, kind + '_leaves')

trees = 0
for (x, z), gy in sorted(ground.items()):
    if in_city(x, z): continue
    dc = min(abs(x - city[0]), abs(x - city[2]), abs(z - city[1]), abs(z - city[3]))
    if dc < 8: continue
    near_road = any((x + dx, z + dz) in ground and abs((x + dx) - midx) < 3 for dx in (-1, 0, 1) for dz in (-1, 0, 1)) \
        or any((x + dx, z + dz) in ground and abs((x + dx) - (midx)) < 3 for dx in (-1, 0, 1) for dz in (-1, 0, 1))
    if (x % 6) or (z % 6): continue
    p = 0.30 if dc > 40 else 0.18
    if rnd.random() > p: continue
    base = gy + 61
    kind = rnd.choice(['oak', 'spruce', 'birch', 'oak'])
    tree(x + X0, z + Z0, base, kind)
    trees += 1
print('деревьев:', trees, 'блоков всего:', len(blocks))

# --- нарезка на тайлы и запись структур
tiles = {}
for (x, y, z), st in blocks.items():
    tiles.setdefault((x // TILE, z // TILE), []).append((x, y, z, st))
sdir = os.path.join(OUT, 'parkdata', 'data', 'park', 'structure')
os.makedirs(sdir, exist_ok=True)
import json
with open(os.path.join(OUT, 'parkdata', 'pack.mcmeta'), 'w') as f:
    json.dump({'pack': {'description': 'park', 'min_format': [121, 0], 'max_format': [121, 0]}}, f)
cmds = []
for (tx, tz), items in sorted(tiles.items()):
    xs = [i[0] for i in items]; ys = [i[1] for i in items]; zs = [i[2] for i in items]
    mnx, mny, mnz = min(xs), min(ys), min(zs)
    size = (max(xs) - mnx + 1, max(ys) - mny + 1, max(zs) - mnz + 1)
    pal_idx = {}; pal_entries = []; bl = []
    for x, y, z, st in items:
        if st not in pal_idx:
            pal_idx[st] = len(pal_entries)
            name = 'minecraft:' + st
            pal_entries.append((name, {}))
        bl.append(([x - mnx, y - mny, z - mnz], pal_idx[st]))
    name = 'p_%d_%d' % (tx, tz)
    open(os.path.join(sdir, name + '.nbt'), 'wb').write(
        s2n.encode_structure(size, pal_entries, bl, sch.get('DataVersion', 0)))
    cmds.append('park:%s %d %d %d' % (name, mnx, mny, mnz))
open(os.path.join(OUT, 'place.txt'), 'w').write('\n'.join(cmds) + '\n')
print('тайлов парка:', len(tiles))
