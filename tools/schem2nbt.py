#!/usr/bin/env python3
"""Sponge .schem (v2/v3) -> vanilla structure .nbt tiles + place commands."""
import gzip, json, os, struct, sys, math

class NBT:
    def __init__(self, data):
        self.d = data; self.i = 0
    def u1(self):
        v = self.d[self.i]; self.i += 1; return v
    def i1(self):
        v = struct.unpack('>b', self.d[self.i:self.i+1])[0]; self.i += 1; return v
    def i2(self):
        v = struct.unpack('>h', self.d[self.i:self.i+2])[0]; self.i += 2; return v
    def i4(self):
        v = struct.unpack('>i', self.d[self.i:self.i+4])[0]; self.i += 4; return v
    def i8(self):
        v = struct.unpack('>q', self.d[self.i:self.i+8])[0]; self.i += 8; return v
    def f4(self):
        v = struct.unpack('>f', self.d[self.i:self.i+4])[0]; self.i += 4; return v
    def f8(self):
        v = struct.unpack('>d', self.d[self.i:self.i+8])[0]; self.i += 8; return v
    def s(self):
        n = struct.unpack('>H', self.d[self.i:self.i+2])[0]; self.i += 2
        v = self.d[self.i:self.i+n].decode('utf-8', 'replace'); self.i += n; return v
    def payload(self, t):
        if t == 1: return self.i1()
        if t == 2: return self.i2()
        if t == 3: return self.i4()
        if t == 4: return self.i8()
        if t == 5: return self.f4()
        if t == 6: return self.f8()
        if t == 7:
            n = self.i4(); v = self.d[self.i:self.i+n]; self.i += n; return v
        if t == 8: return self.s()
        if t == 9:
            et = self.u1(); n = self.i4()
            return [self.payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = self.u1()
                if tt == 0: break
                name = self.s()
                out[name] = self.payload(tt)
            return out
        if t == 11:
            n = self.i4(); v = struct.unpack('>%di' % n, self.d[self.i:self.i+4*n]); self.i += 4*n; return list(v)
        if t == 12:
            n = self.i4(); v = struct.unpack('>%dq' % n, self.d[self.i:self.i+8*n]); self.i += 8*n; return list(v)
        raise ValueError('tag %d' % t)

def read_nbt(path):
    raw = open(path, 'rb').read()
    try: raw = gzip.decompress(raw)
    except OSError: pass
    p = NBT(raw)
    t = p.u1()
    assert t == 10, t
    p.s()
    return p.payload(10)

def write_tag(out, t, name, value):
    out.append(struct.pack('>b', t))
    nb = name.encode()
    out.append(struct.pack('>H', len(nb))); out.append(nb)
    if t == 3: out.append(struct.pack('>i', value))
    elif t == 8: 
        vb = value.encode(); out.append(struct.pack('>H', len(vb))); out.append(vb)
    elif t == 9:
        et, items = value
        out.append(struct.pack('>b', et)); out.append(struct.pack('>i', len(items)))
        for it in items: write_payload(out, et, it, with_name=False)
    elif t == 10:
        for k, (ct, cv) in value.items(): write_tag(out, ct, k, cv)
        out.append(b'\x00')
    elif t == 11:
        out.append(struct.pack('>i', len(value)))
        out.extend(struct.pack('>%di' % len(value), *value))
    else:
        raise ValueError('write tag %d' % t)

def write_payload(out, t, value, with_name=True):
    if t == 3: out.append(struct.pack('>i', value))
    elif t == 10:
        for k, (ct, cv) in value.items(): write_tag(out, ct, k, cv)
        out.append(b'\x00')
    elif t == 9:
        et, items = value
        out.append(struct.pack('>b', et)); out.append(struct.pack('>i', len(items)))
        for it in items: write_payload(out, et, it)
    elif t == 11:
        out.append(struct.pack('>i', len(value)))
        out.extend(struct.pack('>%di' % len(value), *value))
    else: raise ValueError('write_payload %d' % t)

def encode_structure(size, palette_entries, blocks, data_version):
    out = [b'\x0a', struct.pack('>H', 0)]  # root compound, empty name
    write_tag(out, 3, 'DataVersion', data_version)
    write_tag(out, 9, 'size', (3, list(size)))
    pal = []
    for name, props in palette_entries:
        entry = {'Name': (8, name)}
        if props:
            entry['Properties'] = (10, {k: (8, v) for k, v in props.items()})
        pal.append(entry)
    write_tag(out, 9, 'palette', (10, pal))
    blk = [{'pos': (9, (3, p)), 'state': (3, s)} for p, s in blocks]
    write_tag(out, 9, 'blocks', (10, blk))
    write_tag(out, 9, 'entities', (10, []))
    out.append(b'\x00')
    return gzip.compress(b''.join(out), 6)

def main():
    src, outdir, tile = sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 64
    root = read_nbt(src)
    sch = root['Schematic'] if 'Schematic' in root else root
    W, H, L = sch['Width'], sch['Height'], sch['Length']
    ver = sch.get('Version', 2)
    if 'Blocks' in sch:
        blocks_tag = sch['Blocks']; pal_raw = blocks_tag['Palette']; data = blocks_tag['Data']
    else:
        pal_raw = sch['Palette']; data = sch['BlockData']
    # палитра: index -> (name, props)
    palette = {}
    for key, idx in pal_raw.items():
        if '[' in key:
            name = key[:key.index('[')]; props = key[key.index('[')+1:-1]
            pd = {}
            for kv in props.split(','):
                if '=' in kv:
                    k, v = kv.split('=', 1); pd[k.strip()] = v.strip()
        else:
            name, pd = key, {}
        if not name.startswith('minecraft:'): name = 'minecraft:' + name
        palette[idx] = (name, pd)
    skip = {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'}
    # распаковка varint
    vals = []; i = 0; n = len(data)
    while i < n:
        num = 0; shift = 0
        while True:
            b = data[i]; i += 1
            num |= (b & 0x7F) << shift; shift += 7
            if not (b & 0x80): break
        vals.append(num)
    tiles = {}
    for idx, pidx in enumerate(vals):
        y, rem = divmod(idx, W * L)
        z, x = divmod(rem, W)
        name, props = palette.get(pidx, ('minecraft:air', {}))
        if name in skip: continue
        tx, tz = x // tile, z // tile
        tiles.setdefault((tx, tz), []).append((x, y, z, name, props))
    os.makedirs(outdir, exist_ok=True)
    packdir = os.path.join(outdir, 'spawndata')
    sdir = os.path.join(packdir, 'data', 'spawn', 'structure')
    os.makedirs(sdir, exist_ok=True)
    with open(os.path.join(packdir, 'pack.mcmeta'), 'w') as f:
        json.dump({'pack': {'description': 'spawn structures',
                            'min_format': [121, 0], 'max_format': [121, 0]}}, f)
    x0 = -(W // 2); z0 = -(L // 2)
    cmds = []; total = 0
    for (tx, tz), items in sorted(tiles.items()):
        xs = [i[0] for i in items]; ys = [i[1] for i in items]; zs = [i[2] for i in items]
        minx, maxx = min(xs), max(xs); miny, maxy = min(ys), max(ys); minz, maxz = min(zs), max(zs)
        size = (maxx - minx + 1, maxy - miny + 1, maxz - minz + 1)
        pal_index = {}; pal_entries = []; blocks = []
        for x, y, z, name, props in items:
            key = (name, tuple(sorted(props.items())))
            if key not in pal_index:
                pal_index[key] = len(pal_entries); pal_entries.append((name, props))
            blocks.append(([x - minx, y - miny, z - minz], pal_index[key]))
        name = 't_%d_%d' % (tx, tz)
        data_bytes = encode_structure(size, pal_entries, blocks, sch.get('DataVersion', 0))
        open(os.path.join(sdir, name + '.nbt'), 'wb').write(data_bytes)
        total += len(blocks)
        cmds.append('spawn:%s %d %d %d' % (name, minx + x0, miny, minz + z0))
    with open(os.path.join(outdir, 'place.txt'), 'w') as f:
        f.write('\n'.join(cmds) + '\n')
    # рамки тайлов: нужны, чтобы вычистить рельеф внутри построек перед вставкой
    boxes = []
    for (tx, tz), items in sorted(tiles.items()):
        xs = [i[0] for i in items]; zs = [i[2] for i in items]
        boxes.append((min(xs) + x0, min(zs) + z0, max(xs) + x0, max(zs) + z0))
    with open(os.path.join(outdir, 'boxes.txt'), 'w') as f:
        for (a, b, c, d) in boxes:
            f.write('%d %d %d %d\n' % (a, b, c, d))
    fill_lines = []
    for (a, b, c, d) in boxes:
        for y in range(0, H, 7):
            fill_lines.append('%d %d %d %d %d %d' % (a, b, c, d, y, min(y + 6, H - 1)))
    with open(os.path.join(outdir, 'clear.txt'), 'w') as f:
        f.write('\n'.join(fill_lines) + '\n')
    print('clear-команд: %d' % len(fill_lines))
    print('размер %dx%dx%d, тайлов %d, блоков %d (без воздуха), файл пака: %s' % (W, H, L, len(tiles), total, packdir))

if __name__ == '__main__':
    main()
