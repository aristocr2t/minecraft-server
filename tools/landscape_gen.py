#!/usr/bin/env python3
"""Замена плоского газона вокруг крепости на холмистый рельеф.

Площадка спавна была прямоугольным плато с газоном. Скрипт перестраивает всё
пространство вокруг квартала города: холмы с шумом, спуск к краю площадки (там
подхватывает склон из skirt_gen.py), дороги крестом, фонари, деревья.
Квартал города не трогается вообще.
"""
import importlib.util, json, os, random, sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('s2n', os.path.join(HERE, 'schem2nbt.py'))
s2n = importlib.util.module_from_spec(spec); spec.loader.exec_module(s2n)

SRC, OUT = sys.argv[1], sys.argv[2]
SEED = int(sys.argv[3]) if len(sys.argv) > 3 else 21
TILE = 64
TOP = 86          # уровень земли квартала
BASE = 60         # низ, ниже не трогаем
CITY = (-87, -68, 36, 55)   # квартал города в мировых координатах (с запасом)
EDGE_IN = 18      # сколько блоков у края площадки держим высокий уровень

sch = s2n.read_nbt(SRC)['Schematic']
W, H, L = sch['Width'], sch['Height'], sch['Length']
X0, Z0 = -(W // 2), -(L // 2)
PX1, PX2 = X0, X0 + W - 1
PZ1, PZ2 = Z0, Z0 + L - 1

rnd = random.Random(SEED)

def noise(x, z, s=1.0):
    x = int(x * 1000) & 0x7FFFFFFF; z = int(z * 1000) & 0x7FFFFFFF
    h = (x * 374761393 + z * 668265263 + SEED * 7919) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    return (((h ^ (h >> 16)) % 10000) / 10000.0 - 0.5) * 2.0 * s

def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)

def height(x, z):
    dc = max(CITY[0] - x, 0, x - CITY[2], CITY[1] - z, 0, z - CITY[3])
    de = min(x - PX1, PX2 - x, z - PZ1, PZ2 - z)
    h = TOP - 12.0 * smooth(dc / 40.0)
    h += 11.0 * (1.0 - smooth(de / EDGE_IN))
    h += noise(x / 23.0, z / 23.0, 3.2) + noise(x / 7.0, z / 7.0, 1.1)
    return int(max(BASE + 3, min(TOP, round(h))))

blocks = {}
def put(x, y, z, st):
    blocks[(x, y, z)] = st

def tree(x, z, base, kind):
    h = rnd.randint(5, 8)
    for dy in range(h):
        put(x, base + dy, z, kind + '_log')
    ty = base + h
    r = 2 if h < 7 else 3
    for dx in range(-r, r + 1):
        for dz in range(-r, r + 1):
            for dy in (-2, -1, 0):
                if dx * dx + dz * dz + (dy * 2) ** 2 > r * r + 2: continue
                if dx == 0 and dz == 0 and dy < 0: continue
                put(x + dx, ty + dy, z + dz, kind + '_leaves')
    put(x, ty + 1, z, kind + '_leaves')

inside_city = lambda x, z: CITY[0] <= x <= CITY[2] and CITY[1] <= z <= CITY[3]

# крест дорог: z=-7 и x=-26
ROAD_Z, ROAD_X, RW = -7, -26, 2
def is_road(x, z):
    return abs(z - ROAD_Z) <= RW or abs(x - ROAD_X) <= RW

cols = 0
for x in range(PX1, PX2 + 1):
    for z in range(PZ1, PZ2 + 1):
        if inside_city(x, z): continue
        h = height(x, z)
        road = is_road(x, z)
        for y in range(BASE, h + 1):
            depth = h - y
            if depth == 0:
                st = 'dirt_path' if road else 'grass_block'
            elif depth <= 3:
                st = 'dirt'
            else:
                st = 'stone'
            put(x, y, z, st)
        # воздух над поверхностью: убираем старый плоский газон и деревья
        for y in range(h + 1, 92):
            put(x, y, z, 'air')
        cols += 1
        if road:
            continue
        # фонари по обочинам дорог
        if (abs(z - ROAD_Z) == RW + 1 and x % 11 == 0) or (abs(x - ROAD_X) == RW + 1 and z % 11 == 0):
            put(x, h + 1, z, 'spruce_fence')
            put(x, h + 2, z, 'spruce_fence')
            put(x, h + 3, z, 'lantern')
            continue
        # деревья
        dc = max(CITY[0] - x, 0, x - CITY[2], CITY[1] - z, 0, z - CITY[3])
        if dc > 4 and noise(x, z) > -0.15 and rnd.random() < 0.035:
            tree(x, z, h + 1, rnd.choice(['oak', 'spruce', 'birch', 'oak']))

print('колонок перестроено:', cols, 'блоков:', len(blocks))

tiles = {}
for (x, y, z), st in blocks.items():
    tiles.setdefault((x // TILE, z // TILE), []).append((x, y, z, st))
sdir = os.path.join(OUT, 'landdata', 'data', 'land', 'structure')
os.makedirs(sdir, exist_ok=True)
with open(os.path.join(OUT, 'landdata', 'pack.mcmeta'), 'w') as f:
    json.dump({'pack': {'description': 'spawn landscape', 'min_format': [121, 0], 'max_format': [121, 0]}}, f)
cmds = []
for (tx, tz), items in sorted(tiles.items()):
    xs = [i[0] for i in items]; ys = [i[1] for i in items]; zs = [i[2] for i in items]
    mnx, mny, mnz = min(xs), min(ys), min(zs)
    size = (max(xs) - mnx + 1, max(ys) - mny + 1, max(zs) - mnz + 1)
    idx = {}; pe = []; bl = []
    for x, y, z, st in items:
        if st not in idx:
            idx[st] = len(pe); pe.append(('minecraft:' + st, {}))
        bl.append(([x - mnx, y - mny, z - mnz], idx[st]))
    name = 'l_%d_%d' % (tx, tz)
    open(os.path.join(sdir, name + '.nbt'), 'wb').write(
        s2n.encode_structure(size, pe, bl, sch.get('DataVersion', 0)))
    cmds.append('land:%s %d %d %d' % (name, mnx, mny, mnz))
open(os.path.join(OUT, 'place.txt'), 'w').write('\n'.join(cmds) + '\n')
print('тайлов:', len(tiles))
