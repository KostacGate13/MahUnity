"""Renders turntable frames of the scanned car with the same camera as CarView (see CarView.java)."""
import ctypes, math, sys, os
import numpy as np
from PIL import Image

SP = os.path.dirname(os.path.abspath(__file__))
lib = ctypes.CDLL(os.path.join(SP, 'splat.so'))
fp = ctypes.POINTER(ctypes.c_float)
lib.render.argtypes = [ctypes.c_int, fp, fp, fp, ctypes.c_int, fp, fp, fp, fp, ctypes.c_int, ctypes.c_int, fp]

DISTANCE, ELEV, TARGET_Z = 16.0, math.radians(4.5), 0.62

def load(npz, forward_sign, blank_plates=True):
    z = np.load(npz)
    p = z['pos']
    near = (np.abs(p[:, 0] + 0.27) < 1.5) & (np.abs(p[:, 2] + 0.67) < 2.6)
    ground = np.median(p[near & (p[:, 1] < 0.0), 1])
    body = near & (p[:, 1] > ground + 0.3) & (p[:, 1] < ground + 1.3)
    # Heading: the long axis of the body seen from above; then the centre in that frame.
    xz = p[body][:, [0, 2]]
    mean = xz.mean(0)
    evals, evecs = np.linalg.eigh(np.cov((xz - mean).T))
    axis = evecs[:, np.argmax(evals)]
    if axis[1] * forward_sign < 0:
        axis = -axis
    side = np.array([axis[1], -axis[0]])  # car's left, for y up and a right-handed scan
    rel = xz - mean
    a, b = rel @ axis, rel @ side
    lo, hi = np.percentile(np.stack([a, b], 1), 0.5, 0), np.percentile(np.stack([a, b], 1), 99.5, 0)
    centre = mean + axis * (lo[0] + hi[0]) / 2 + side * (lo[1] + hi[1]) / 2
    rel = p[:, [0, 2]] - centre
    car = np.stack([rel @ axis, rel @ side, p[:, 1] - ground], 1).astype(np.float32)
    x, y, h = car[:, 0], car[:, 1], car[:, 2]
    keep = (np.abs(x) < 2.12) & (np.abs(y) < np.where(h > 0.85, 1.05, 0.93)) & (h > 0.03) & (h < 1.6)
    # Ground and shadow under the body: only the tyres reach below 0.12 m, and nothing lives under the floor.
    axle = np.minimum(np.abs(x - 1.27), np.abs(x + 1.27))
    wheel = (axle ** 2 + (h - 0.31) ** 2 < 0.36 ** 2) & (np.abs(y) > 0.48) & (np.abs(y) < 0.9)
    keep &= (h > 0.12) | wheel
    keep &= ~((np.abs(y) < 0.5) & (h < 0.22) & (np.abs(x) < 1.85))
    # The car's shadow on the ground: dark, low and outside the wheels (the black trim at either end stays).
    lum = 0.5 + 0.2820948 * z['dc'].mean(1)
    keep &= ~((lum < 0.3) & (h < 0.28) & ~wheel & (np.abs(x) < 1.7))
    # and its blurred edge: faint splats below the sills.
    keep &= ~((z['alpha'] < 0.6) & (h < 0.3) & ~wheel)
    # Haze: big, faint splats floating around the silhouette.
    keep &= ~((z['scale'].max(1) > 0.08) & (z['alpha'] < 0.5))
    keep &= z['alpha'] > 0.12
    # Then anything not near the car's actual surface: solid splats mark the surface on a 5 cm grid,
    # the grid grows by one cell, and splats centred outside it go.
    keep &= near_surface(car, z['alpha'], keep, grow=1)
    keep &= (z['alpha'] > 0.4) | near_surface(car, z['alpha'], keep, grow=0)
    data = {k: np.ascontiguousarray(z[k][keep].astype(np.float32)) for k in ('pos', 'dc', 'sh', 'alpha', 'scale', 'quat')}
    data['car'] = np.ascontiguousarray(car[keep])
    if blank_plates:
        c = data['car']
        # Number plates are painted over with the bumper around them: no plate, no frame, no lettering.
        covers = []
        # Measured from the scan: the front plate sits off-centre, its EU band at y = -0.4.
        for front, (ylo, yhi), (zlo, zhi) in ((True, (-0.44, 0.28), (0.17, 0.44)), (False, (-0.36, 0.36), (0.36, 0.62))):
            band = (c[:, 0] > 1.6) if front else (c[:, 0] < -1.6)
            height = (c[:, 2] > zlo) & (c[:, 2] < zhi)
            plate = band & height & (c[:, 1] > ylo) & (c[:, 1] < yhi)
            face = np.median(c[plate][:, 0])
            near = band & (np.abs(c[:, 0] - face) < 0.08) & (data['alpha'] > 0.4)
            paint = across(c, data, near, ylo, yhi)
            data['dc'][plate], data['sh'][plate] = paint(c[plate][:, 1], c[plate][:, 2])
            data['alpha'][plate] = np.maximum(data['alpha'][plate], 0.9)
            covers.append((front, ylo, yhi, zlo, zhi, face, paint))
            print('plate', 'front' if front else 'rear', plate.sum(), file=sys.stderr)
    # Camera vectors come in car axes; map them into the scan (whose frame the SH colours are in).
    ax3 = np.array([axis[0], 0, axis[1]], np.float32)
    sd3 = np.array([side[0], 0, side[1]], np.float32)
    up3 = np.array([0, 1, 0], np.float32)
    to_scan = lambda v: (v[0] * ax3 + v[1] * sd3 + v[2] * up3).astype(np.float32)
    origin = np.array([centre[0], ground, centre[1]], np.float32)
    yaw = math.degrees(math.atan2(axis[0], axis[1]))
    print('kept', keep.sum(), 'of', len(p), 'ground %.3f heading %.1f deg, length %.2f width %.2f' %
          (ground, yaw, hi[0] - lo[0], hi[1] - lo[1]), file=sys.stderr)
    data['basis'] = np.stack([ax3, sd3, up3], 1)  # columns: the car's x, y, z in scan axes
    if blank_plates:
        # The lettering also shows through gaps, since nothing was scanned behind a plate: back each plate with
        # a sheet of small opaque splats in the bumper's colour, a few millimetres behind its face.
        for front, ylo, yhi, zlo, zhi, x, paint in covers:
            ys, zs = np.meshgrid(np.arange(ylo, yhi, 0.012), np.arange(zlo + 0.01, zhi - 0.01, 0.012))
            n = ys.size
            dc, sh = paint(ys.ravel(), zs.ravel())
            cx = np.full(n, x - (0.006 if front else -0.006), np.float32)
            car = np.stack([cx, ys.ravel(), zs.ravel()], 1).astype(np.float32)
            basis = data['basis'] * np.array([1, 1, np.sign(np.linalg.det(data['basis']))], np.float32)
            add = {
                'car': car, 'pos': (origin + car @ data['basis'].T).astype(np.float32),
                'dc': dc.astype(np.float32), 'sh': sh.astype(np.float32),
                'alpha': np.full(n, 0.98, np.float32), 'scale': np.tile(np.float32([0.002, 0.012, 0.012]), (n, 1)),
                'quat': np.tile(quat_from_matrix(basis), (n, 1)),
            }
            for k, v in add.items():
                data[k] = np.ascontiguousarray(np.concatenate([data[k], v]))
    return data, to_scan, origin


def across(c, data, near, ylo, yhi, step=0.015, width=0.08):
    """Colours for points inside [ylo, yhi] (in y, any z): per 1.5 cm row, the median of what lies just left and
    just right of the span, blended linearly across it, so stripes of bumper and grille carry straight through."""
    c, dcs, shs = c[near].copy(), data['dc'][near].copy(), data['sh'][near].copy()
    z0 = c[:, 2].min()
    rows = {}
    def side(lo, hi, z):
        m = (c[:, 1] > lo) & (c[:, 1] < hi) & (np.abs(c[:, 2] - z) < step)
        if m.sum() < 3:
            return None
        return np.median(dcs[m], 0), np.median(shs[m], 0)
    def row(z):
        k = int(round((z - z0) / step))
        if k not in rows:
            zc = z0 + k * step
            l = side(ylo - 0.01 - width, ylo - 0.01, zc)
            r = side(yhi + 0.01, yhi + 0.01 + width, zc)
            rows[k] = (l or r, r or l)
        return rows[k]
    def paint(y, z):
        dc = np.zeros((len(y), 3), np.float32)
        sh = np.zeros((len(y),) + shs.shape[1:], np.float32)
        for i in range(len(y)):
            l, r = row(z[i])
            if l is None:
                l = r = (np.median(dcs, 0), np.median(shs, 0))
            t = (y[i] - ylo) / (yhi - ylo)
            dc[i] = l[0] * (1 - t) + r[0] * t
            sh[i] = l[1] * (1 - t) + r[1] * t
        return dc, sh
    return paint


def near_surface(car, alpha, keep, grow, cell=0.05):
    solid = keep & (alpha > 0.5)
    idx = np.floor(car / cell).astype(np.int64)
    key = lambda i: (i[:, 0] + 512) * 1048576 + (i[:, 1] + 512) * 1024 + (i[:, 2] + 512)
    cells = np.unique(key(idx[solid]))
    r = range(-grow, grow + 1)
    grown = [cells + (dx * 1048576 + dy * 1024 + dz) for dx in r for dy in r for dz in r]
    return np.isin(key(idx), np.unique(np.concatenate(grown)))


# Moving parts, in car coordinates: the region (an x-z outline, a side), the hinge and how it turns.
PARTS = {
    # Left = driver (left-hand drive). Outline from the panel gaps in the scan.
    'door_left': dict(outline=[(0.79, 0.24), (0.79, 0.98), (0.56, 1.08), (0.12, 1.39), (-0.62, 1.39), (-0.62, 0.24)],
                      side=1, ymin=0.45, hinge=(0.78, 0.84, 0.0), axis='z', sign=-1, angle=38, yaw=235, inset=(0, -0.22, 0)),
    'door_right': dict(outline=[(0.79, 0.24), (0.79, 0.98), (0.56, 1.08), (0.12, 1.39), (-0.62, 1.39), (-0.62, 0.24)],
                       side=-1, ymin=0.45, hinge=(0.78, -0.84, 0.0), axis='z', sign=1, angle=38, yaw=125, inset=(0, 0.22, 0)),
    'trunk': dict(xmax=-1.57, zmin=0.6, ymax=0.62, hinge=(-1.6, 0.0, 1.4), axis='y', sign=1, angle=50, yaw=205, inset=(0.2, 0, -0.05)),
    'hood': dict(xmin=1.02, zmin=0.72, ymax=0.74, hinge=(1.0, 0.0, 0.94), axis='y', sign=-1, angle=42, yaw=25, inset=(0, 0, -0.14)),
}


def part_mask(c, part):
    p = PARTS[part]
    if 'outline' in p:
        return inside_polygon(c[:, 0], c[:, 2], p['outline']) & (c[:, 1] * p['side'] > p['ymin'])
    m = np.abs(c[:, 1]) < p['ymax']
    m &= c[:, 2] > p['zmin']
    m &= (c[:, 0] < p['xmax']) if 'xmax' in p else (c[:, 0] > p['xmin'])
    return m


def inside_polygon(x, y, poly):
    """Even-odd ray casting, vectorised over the points."""
    inside = np.zeros(len(x), bool)
    n = len(poly)
    for i in range(n):
        (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % n]
        crosses = (y0 > y) != (y1 > y)
        at = x0 + (y - y0) * (x1 - x0) / ((y1 - y0) if y1 != y0 else 1e-9)
        inside ^= crosses & (x < at)
    return inside


def rot(axis, deg):
    t = math.radians(deg)
    co, si = math.cos(t), math.sin(t)
    if axis == 'z':
        return np.array([[co, -si, 0], [si, co, 0], [0, 0, 1]], np.float32)
    return np.array([[co, 0, si], [0, 1, 0], [-si, 0, co]], np.float32)


def quat_from_matrix(m):
    w = math.sqrt(max(0.0, 1 + m[0, 0] + m[1, 1] + m[2, 2])) / 2
    x = math.copysign(math.sqrt(max(0.0, 1 + m[0, 0] - m[1, 1] - m[2, 2])) / 2, m[2, 1] - m[1, 2])
    y = math.copysign(math.sqrt(max(0.0, 1 - m[0, 0] + m[1, 1] - m[2, 2])) / 2, m[0, 2] - m[2, 0])
    z = math.copysign(math.sqrt(max(0.0, 1 - m[0, 0] - m[1, 1] + m[2, 2])) / 2, m[1, 0] - m[0, 1])
    return np.array([x, y, z, w], np.float32)


def articulate(data, origin, part, fraction):
    """A copy of the car with one part opened by fraction (0..1) of its full angle and tinted red as it goes."""
    p = PARTS[part]
    out = {k: v.copy() for k, v in data.items()}
    m = part_mask(data['car'], part)
    if fraction <= 0 or not m.any():
        return out
    rc = rot(p['axis'], p['sign'] * p['angle'] * fraction)
    hinge = np.array(p['hinge'], np.float32)
    c = (data['car'][m] - hinge) @ rc.T + hinge
    out['car'][m] = c
    M = data['basis']
    out['pos'][m] = origin + c @ M.T
    rs = M @ rc @ M.T
    qr = quat_from_matrix(rs)
    q = data['quat'][m]
    x1, y1, z1, w1 = qr
    x2, y2, z2, w2 = q[:, 0], q[:, 1], q[:, 2], q[:, 3]
    out['quat'][m] = np.stack([w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
                               w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
                               w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
                               w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2], 1)
    # Lit red as it opens: the panel's own shading kept, its colour turned to red.
    t = 0.8 * fraction
    lum = np.clip(0.5 + 0.2820948 * data['dc'][m].mean(1, keepdims=True), 0, 1)
    red = (np.array([0.92, 0.10, 0.08], np.float32) * (0.35 + 0.65 * lum) - 0.5) / 0.2820948
    out['dc'][m] = data['dc'][m] * (1 - t) + red * t
    out['sh'][m] = data['sh'][m] * (1 - t)
    # Nothing was scanned behind the panel, so the opening would show the far side of the car: line it with a dark
    # copy of the panel, set into the body.
    inset = np.array(p['inset'], np.float32)
    lc = data['car'][m] + inset
    n = len(lc)
    dark = np.full((n, 3), (0.05 - 0.5) / 0.2820948, np.float32)
    add = {'car': lc, 'pos': origin + lc @ M.T, 'dc': dark, 'sh': np.zeros_like(data['sh'][m]),
           'alpha': np.maximum(data['alpha'][m], 0.95), 'scale': data['scale'][m], 'quat': data['quat'][m]}
    for k, v in add.items():
        out[k] = np.ascontiguousarray(np.concatenate([out[k], v.astype(np.float32)]))
    return out

def camera(yaw_deg, w, h, to_scan, origin):
    t = math.radians(yaw_deg)
    ce, se = math.cos(ELEV), math.sin(ELEV)
    eye = np.array([DISTANCE * ce * math.cos(t), -DISTANCE * ce * math.sin(t), TARGET_Z + DISTANCE * se])
    right = np.array([math.sin(t), math.cos(t), 0.0])
    up = np.array([-se * math.cos(t), se * math.sin(t), ce])
    fwd = np.array([-ce * math.cos(t), ce * math.sin(t), -se])
    focal = min(w * 0.86 * DISTANCE / (2 * 2.03), h * 0.8 * DISTANCE / 2.4)
    cam = np.concatenate([to_scan(eye) + origin, to_scan(right), to_scan(up), to_scan(fwd), [focal, focal, w / 2, h * 0.5]])
    return cam.astype(np.float32)

def render(data, cam, w, h):
    data = {k: v for k, v in data.items() if k in ('pos', 'dc', 'sh', 'alpha', 'scale', 'quat')}
    out = np.zeros((h, w, 4), np.float32)
    P = lambda a: a.ctypes.data_as(fp)
    n = len(data['pos'])
    ncoef = data['sh'].shape[1]
    lib.render(n, P(data['pos']), P(data['dc']), P(data['sh']), ncoef, P(data['alpha']), P(data['scale']),
               P(data['quat']), P(cam), w, h, P(out))
    return out

def frame(data, to_scan, origin, yaw, w, h, ss=2):
    big = render(data, camera(yaw, w * ss, h * ss, to_scan, origin), w * ss, h * ss)
    small = big.reshape(h, ss, w, ss, 4).mean((1, 3))
    rgb, a = small[..., :3], small[..., 3:]
    # Un-premultiply for a straight-alpha PNG/WebP.
    straight = np.where(a > 1e-4, rgb / np.maximum(a, 1e-4), 0)
    img = np.concatenate([np.clip(straight, 0, 1), a], 2)
    return Image.fromarray((img * 255 + 0.5).astype(np.uint8), 'RGBA')

if __name__ == '__main__':
    npz, sign, outdir = sys.argv[1], float(sys.argv[2]), sys.argv[3]
    yaws = [float(v) for v in sys.argv[4].split(',')]
    w, h = int(sys.argv[5]), int(sys.argv[6])
    os.makedirs(outdir, exist_ok=True)
    data, to_scan, origin = load(npz, sign)
    for yaw in yaws:
        im = frame(data, to_scan, origin, yaw, w, h)
        bg = Image.new('RGBA', im.size, (8, 9, 11, 255))
        bg.alpha_composite(im)
        bg.convert('RGB').save(os.path.join(outdir, 'yaw_%03d.png' % int(yaw)))
        print('yaw', yaw, file=sys.stderr)
