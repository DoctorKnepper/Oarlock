import struct, gzip, io, math, sys, urllib.request, os, ssl
URL = sys.argv[1]
BBOX = tuple(map(float, sys.argv[2].split(',')))  # minlon,minlat,maxlon,maxlat
MAXZ = int(sys.argv[3])
ctx = ssl.create_default_context(cafile='/root/.ccr/ca-bundle.crt')
fetches = [0, 0]

def rng(off, ln):
    req = urllib.request.Request(URL, headers={'User-Agent':'ArkOfflineLibrary/0.1 (Android)','Range': f'bytes={off}-{off+ln-1}'})
    d = urllib.request.urlopen(req, context=ctx).read()
    fetches[0] += 1; fetches[1] += len(d)
    return d

def uv(b, i):
    r = 0; s = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7f) << s; s += 7
        if c < 0x80: return r, i

def decdir(raw):
    b = gzip.decompress(raw); i = 0
    n, i = uv(b, i)
    ids = []; last = 0
    for _ in range(n):
        v, i = uv(b, i); last += v; ids.append(last)
    rl = []
    for _ in range(n):
        v, i = uv(b, i); rl.append(v)
    ln = []
    for _ in range(n):
        v, i = uv(b, i); ln.append(v)
    off = []
    for k in range(n):
        v, i = uv(b, i)
        off.append(off[k-1] + ln[k-1] if v == 0 and k > 0 else v - 1)
    return list(zip(ids, rl, off, ln))

def zxy_to_id(z, x, y):
    acc = (4**z - 1) // 3
    n = 1 << z; d = 0; s = n >> 1
    tx, ty = x, y
    while s > 0:
        rx = 1 if (tx & s) > 0 else 0
        ry = 1 if (ty & s) > 0 else 0
        d += s * s * ((3 * rx) ^ ry)
        # rotate
        if ry == 0:
            if rx == 1:
                tx = s - 1 - tx; ty = s - 1 - ty
            tx, ty = ty, tx
        s >>= 1
    return acc + d

def tile_range(z, lon0, lat0, lon1, lat1):
    n = 1 << z
    def x(lon): return min(n-1, max(0, int((lon + 180) / 360 * n)))
    def y(lat):
        lat = max(-85.0511, min(85.0511, lat)); r = math.radians(lat)
        return min(n-1, max(0, int((1 - math.log(math.tan(r) + 1/math.cos(r)) / math.pi) / 2 * n)))
    return x(lon0), y(lat1), x(lon1), y(lat0)

def wanted_ranges(bbox, maxz, coarse=8):
    rs = []
    for z in range(0, maxz+1):
        c = min(z, coarse)
        x0, y0, x1, y1 = tile_range(c, *bbox)
        base = (4**z - 1)//3; cbase = (4**c - 1)//3; mult = 4**(z - c)
        for x in range(x0, x1+1):
            for y in range(y0, y1+1):
                h = zxy_to_id(c, x, y) - cbase
                rs.append((base + h*mult, base + (h+1)*mult))
    rs.sort(); out = []
    for a, b in rs:
        if out and a <= out[-1][1]: out[-1] = (out[-1][0], max(out[-1][1], b))
        else: out.append((a, b))
    return out

def id_to_z(t):
    z = 0
    while (4**(z+1) - 1)//3 <= t: z += 1
    return z

hdr = rng(0, 16384)
f = struct.unpack('<QQQQQQQQQQQ', hdr[8:96])
root_off, root_len, _, _, leaf_off, leaf_len, data_off = f[:7]
root = decdir(hdr[root_off:root_off+root_len])
R = wanted_ranges(BBOX, MAXZ)
print('ranges', len(R))
import bisect
starts = [a for a, b in R]
def intersects(a, b):  # [a,b)
    i = bisect.bisect_right(starts, b - 1) - 1
    return i >= 0 and R[i][1] > a
def contains(t):
    i = bisect.bisect_right(starts, t) - 1
    return i >= 0 and R[i][0] <= t < R[i][1]

tiles = set(); byz=[0]*20
cnt = [0]*20
def handle(entries, nextid):
    leaves = []
    for k, (tid, rl, off, ln) in enumerate(entries):
        end = entries[k+1][0] if k+1 < len(entries) else nextid
        if rl == 0:
            if intersects(tid, end): leaves.append((off, ln, end))
        else:
            for t in range(tid, tid+rl):
                if t > (4**(MAXZ+1)-1)//3: break
                if contains(t):
                    z = id_to_z(t); cnt[z] += 1
                    if ln < 4096:
                        if off in tiles: break
                        tiles.add(off)
                    byz[z] += ln; break
    # batch contiguous leaves
    i = 0
    while i < len(leaves):
        j = i; start = leaves[i][0]; end = leaves[i][0] + leaves[i][1]
        while j+1 < len(leaves) and leaves[j+1][0] - end < 65536 and end - start < 8_000_000:
            j += 1; end = leaves[j][0] + leaves[j][1]
        blob = rng(leaf_off + start, end - start)
        for k in range(i, j+1):
            o, l, nid = leaves[k]
            handle(decdir(blob[o-start:o-start+l]), nid)
        i = j + 1
        print('.', end='', flush=True)
handle(root, 1 << 62)
print()
tot = 0
for z in range(MAXZ+1):
    tot += byz[z]; print(z, cnt[z], round(byz[z]/1e6, 1), 'MB cum', round(tot/1e9, 2), 'GB')
print('fetches', fetches[0], round(fetches[1]/1e6, 1), 'MB of directories')
