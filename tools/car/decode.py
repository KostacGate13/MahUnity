"""Decodes a Niantic SPZ v2/v3 Gaussian splat file into numpy arrays (npz)."""
import gzip, struct, sys
import numpy as np

src, dst = sys.argv[1], sys.argv[2]
d = gzip.open(src).read()
magic, version, n, sh_deg, frac, flags, _ = struct.unpack('<IIIBBBB', d[:16])
assert magic == 0x5053474e and version in (2, 3), (hex(magic), version)
o = 16
def take(nbytes):
    global o
    b = np.frombuffer(d, np.uint8, nbytes, o); o += nbytes; return b

p = take(n * 9).reshape(n, 3, 3).astype(np.int32)
pos = (p[..., 0] | (p[..., 1] << 8) | (p[..., 2] << 16))
pos = np.where(pos & 0x800000, pos - (1 << 24), pos).astype(np.float32) / (1 << frac)
alpha = take(n).astype(np.float32) / 255.0
dc = (take(n * 3).reshape(n, 3).astype(np.float32) / 255.0 - 0.5) / 0.15
scale = np.exp(take(n * 3).reshape(n, 3).astype(np.float32) / 16.0 - 10.0)
if version == 2:
    xyz = take(n * 3).reshape(n, 3).astype(np.float32) / 127.5 - 1.0
    w = np.sqrt(np.clip(1.0 - (xyz ** 2).sum(1), 0, 1))
    quat = np.concatenate([xyz, w[:, None]], 1)
else:
    r = take(n * 4).reshape(n, 4).astype(np.uint64)
    comp = r[:, 0] | (r[:, 1] << 8) | (r[:, 2] << 16) | (r[:, 3] << 24)
    largest = (comp >> 30).astype(np.int64)
    quat = np.zeros((n, 4), np.float32)
    mask = (1 << 9) - 1
    ss = np.zeros(n, np.float32)
    for i in range(3, -1, -1):
        sel = largest != i
        mag = (comp & mask).astype(np.float32)
        neg = ((comp >> 9) & 1) == 1
        val = np.sqrt(0.5) * mag / mask
        val = np.where(neg, -val, val)
        quat[sel, i] = val[sel]
        ss[sel] += val[sel] ** 2
        comp = np.where(sel, comp >> 10, comp)
    quat[np.arange(n), largest] = np.sqrt(np.clip(1 - ss, 0, 1))
dims = {0: 0, 1: 3, 2: 8, 3: 15}[sh_deg]
sh = ((take(n * dims * 3).reshape(n, dims, 3).astype(np.float32) - 128.0) / 128.0) if dims else np.zeros((n, 0, 3), np.float32)
assert o == len(d), (o, len(d))
np.savez(dst, pos=pos, alpha=alpha, dc=dc, scale=scale, quat=quat, sh=sh)
print('points', n, 'sh', sh_deg, 'flags', flags)
for k, a in (('x', pos[:, 0]), ('y', pos[:, 1]), ('z', pos[:, 2])):
    print(k, 'min %.2f p1 %.2f p50 %.2f p99 %.2f max %.2f' % (a.min(), *np.percentile(a, [1, 50, 99]), a.max()))
print('alpha mean %.2f, scale median %.4f' % (alpha.mean(), np.median(scale)))
