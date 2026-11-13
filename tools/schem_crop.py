#!/usr/bin/env python3
"""Вырезает из Sponge .schem кусок по мировым координатам и пишет тайлы структур.

Пример:
  schem_crop.py spawn.schem out 4 \
      -88 -69 36 55 70
  (мировые x1 z1 x2 z2 и мировая нижняя граница y)

Границы внутри схематики считаются от её origin (центр), поэтому координаты
мировые и совпадают с тем, что видно в игре при прошлой вставке.
"""
import importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('s2n', os.path.join(HERE, 'schem2nbt.py'))
s2n = importlib.util.module_from_spec(spec); spec.loader.exec_module(s2n)

SRC, OUT, TILE = sys.argv[1], sys.argv[2], int(sys.argv[3])
X1, Z1, X2, Z2, YMIN = (int(v) for v in sys.argv[4:9])
ROT = int(sys.argv[9]) if len(sys.argv) > 9 else 0   # 90 = против часовой стрелки

sch = s2n.read_nbt(SRC)['Schematic']
W, H, L = sch['Width'], sch['Height'], sch['Length']
SCH_X0, SCH_Z0 = -(W // 2), -(L // 2)       # origin схематики в мировых координатах
SCH_Y0 = 60                                  # как ставили изначально: низ схематики = y 60

blk = sch['Blocks']; pal = blk['Palette']; data = blk['Data']
vals = []; i = 0; n = len(data)
while i < n:
    num = 0; sh = 0
    while True:
        c = data[i]; i += 1; num |= (c & 0x7F) << sh; sh += 7
        if not (c & 0x80): break
    vals.append(num)
name_of = {idx: k for k, idx in pal.items()}
SKIP = {'minecraft:air', 'air', 'minecraft:cave_air', 'cave_air', 'minecraft:void_air', 'void_air'}

blocks = {}
for idx, pidx in enumerate(vals):
    key = name_of.get(pidx, 'minecraft:air')
    if key.split('[')[0] in SKIP: continue
    ry, rem = divmod(idx, W * L); rz, rx = divmod(rem, W)
    wx, wz, wy = rx + SCH_X0, rz + SCH_Z0, ry + SCH_Y0
    if not (X1 <= wx <= X2 and Z1 <= wz <= Z2): continue
    if ROT == 90:
        # поворот против часовой стрелки вокруг центра выреза
        cx, cz = (X1 + X2) / 2.0, (Z1 + Z2) / 2.0
        dx, dz = wx - cx, wz - cz
        wx, wz = int(round(cx + dz)), int(round(cz - dx))
    if wy < YMIN: continue
    if '[' in key:
        name, props = key[:key.index('[')], key[key.index('[') + 1:-1]
        pd = {}
        for kv in props.split(','):
            if '=' in kv:
                k, v = kv.split('=', 1); pd[k.strip()] = v.strip()
    else:
        name, pd = key, {}
    if not name.startswith('minecraft:'): name = 'minecraft:' + name
    blocks[(wx, wy, wz)] = (name, tuple(sorted(pd.items())))

print('блоков в куске:', len(blocks))

tiles = {}
for (x, y, z), st in blocks.items():
    tiles.setdefault((x // TILE, z // TILE), []).append((x, y, z, st))

sdir = os.path.join(OUT, 'fragdata', 'data', 'frag', 'structure')
os.makedirs(sdir, exist_ok=True)
with open(os.path.join(OUT, 'fragdata', 'pack.mcmeta'), 'w') as f:
    json.dump({'pack': {'description': 'spawn fragment', 'min_format': [121, 0], 'max_format': [121, 0]}}, f)

cmds = []
for (tx, tz), items in sorted(tiles.items()):
    xs = [i[0] for i in items]; ys = [i[1] for i in items]; zs = [i[2] for i in items]
    mnx, mny, mnz = min(xs), min(ys), min(zs)
    size = (max(xs) - mnx + 1, max(ys) - mny + 1, max(zs) - mnz + 1)
    idx = {}; pe = []; bl = []
    for x, y, z, st in items:
        if st not in idx:
            idx[st] = len(pe); pe.append((st[0], dict(st[1])))
        bl.append(([x - mnx, y - mny, z - mnz], idx[st]))
    name = 'f_%d_%d' % (tx, tz)
    open(os.path.join(sdir, name + '.nbt'), 'wb').write(
        s2n.encode_structure(size, pe, bl, sch.get('DataVersion', 0)))
    cmds.append('frag:%s %d %d %d' % (name, mnx, mny, mnz))
open(os.path.join(OUT, 'place.txt'), 'w').write('\n'.join(cmds) + '\n')
print('тайлов:%d  диапазон y: %d..%d' % (len(tiles), min(i[1] for it in tiles.values() for i in it), max(i[1] for it in tiles.values() for i in it)))
