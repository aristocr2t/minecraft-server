#!/usr/bin/env python3
"""Склон вокруг площадки спавна: превращает вертикальный обрыв в естественный холм.

Плато (площадка спавна) лежит на уровне PLATEAU. Снаружи, в полосе WIDTH блоков,
достраивается спуск к NATURAL с шумом, сверху трава и деревья. Клетки, где world
уже выше силуэта, не трогаем (структура их перезапишет только своей геометрией,
поэтому высота спуска берётся как max(естественная оценка, силуэт) — см. ниже).
"""
import importlib.util, json, os, random, sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('s2n', os.path.join(HERE, 'schem2nbt.py'))
s2n = importlib.util.module_from_spec(spec); spec.loader.exec_module(s2n)

SRC, OUT = sys.argv[1], sys.argv[2]
SEED = int(sys.argv[3]) if len(sys.argv) > 3 else 7
TILE = 64
PLATEAU = 86      # верх площадки спавна
NATURAL = 66      # средний уровень окружающего рельефа
WIDTH = 22        # ширина полосы спуска

sch = s2n.read_nbt(SRC)['Schematic']
W, H, L = sch['Width'], sch['Height'], sch['Length']
X0, Z0 = -(W // 2), -(L // 2)
bx1, bx2 = X0, X0 + W - 1
bz1, bz2 = Z0, Z0 + L - 1

rnd = random.Random(SEED)
blocks = {}

def put(x, y, z, st):
    blocks[(x, y, z)] = st

def hnoise(x, z):
    # детерминированный псевдошум без внешних библиотек
    h = (x * 374761393 + z * 668265263 + SEED * 2147483647) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((h ^ (h >> 16)) % 1000) / 1000.0

def tree(x, z, base, kind, hmin=5, hmax=8):
    h = rnd.randint(hmin, hmax)
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

# полоса снаружи площадки
cells = []
for d in range(1, WIDTH + 1):
    for x in range(bx1 - d, bx2 + d + 1):
        cells.append((x, bz1 - d)); cells.append((x, bz2 + d))
    for z in range(bz1 - d + 1, bz2 + d):
        cells.append((bx1 - d, z)); cells.append((bx2 + d, z))
cells = sorted(set(cells))
print('клеток в полосе:', len(cells))

MATS = ['grass_block', 'dirt', 'stone']
for (x, z) in cells:
    dx = max(bx1 - x, 0, x - bx2)
    dz = max(bz1 - z, 0, z - bz2)
    d = max(dx, dz)
    if d == 0: continue
    t = d / WIDTH
    noise = hnoise(x // 3, z // 3) * 3.0
    target = PLATEAU - (PLATEAU - NATURAL) * (t ** 1.25) + noise - 1.5
    target = int(max(NATURAL - 2, min(PLATEAU, target)))
    for y in range(target - 4, target + 1):
        if y < NATURAL - 6: continue
        depth = target - y
        if depth == 0: st = 'grass_block'
        elif depth <= 2: st = 'dirt'
        else: st = 'stone'
        put(x, y, z, st)
    # деревья на склоне и по кромке
    if d >= 3 and rnd.random() < (0.10 if d > 8 else 0.16):
        if hnoise(x, z) > 0.45:
            tree(x, z, target + 1, rnd.choice(['oak', 'spruce', 'birch', 'oak']))

# дополнительно плотная кромка внутри площадки, чтобы спрятать шов
for d in range(0, 7):
    for x in range(bx1 + d, bx2 - d + 1):
        for z in (bz1 + d, bz2 - d):
            if rnd.random() < 0.3 and hnoise(x, z) > 0.35:
                tree(x, z, PLATEAU + 1, rnd.choice(['oak', 'spruce', 'birch']))
    for z in range(bz1 + d + 1, bz2 - d):
        for x in (bx1 + d, bx2 - d):
            if rnd.random() < 0.3 and hnoise(x, z) > 0.35:
                tree(x, z, PLATEAU + 1, rnd.choice(['oak', 'spruce', 'birch']))

print('блоков склона:', len(blocks))
tiles = {}
for (x, y, z), st in blocks.items():
    tiles.setdefault((x // TILE, z // TILE), []).append((x, y, z, st))
sdir = os.path.join(OUT, 'skirtdata', 'data', 'skirt', 'structure')
os.makedirs(sdir, exist_ok=True)
with open(os.path.join(OUT, 'skirtdata', 'pack.mcmeta'), 'w') as f:
    json.dump({'pack': {'description': 'spawn skirt', 'min_format': [121, 0], 'max_format': [121, 0]}}, f)
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
    name = 's_%d_%d' % (tx, tz)
    open(os.path.join(sdir, name + '.nbt'), 'wb').write(
        s2n.encode_structure(size, pe, bl, sch.get('DataVersion', 0)))
    cmds.append('skirt:%s %d %d %d' % (name, mnx, mny, mnz))
open(os.path.join(OUT, 'place.txt'), 'w').write('\n'.join(cmds) + '\n')
print('тайлов склона:', len(tiles))
