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
    tyre = (np.minimum(np.abs(x - 1.27), np.abs(x + 1.27)) < 0.36) & (np.abs(y) > 0.48)
    keep &= (h > 0.12) | tyre
    keep &= ~((np.abs(y) < 0.5) & (h < 0.22) & (np.abs(x) < 1.85))
    # Haze: big, faint splats floating around the silhouette.
    keep &= ~((z['scale'].max(1) > 0.08) & (z['alpha'] < 0.5))
    keep &= z['alpha'] > 0.12
    data = {k: np.ascontiguousarray(z[k][keep].astype(np.float32)) for k in ('pos', 'dc', 'sh', 'alpha', 'scale', 'quat')}
    if blank_plates:
        c = car[keep]
        # Number plates go blank white: the APK is public on GitHub. The lettering is as much gaps between
        # splats as dark splats, so every splat on the plate turns white, opaque and a little larger.
        plate = (np.abs(c[:, 1]) < 0.36) & (((c[:, 0] > 1.6) & (c[:, 2] > 0.19) & (c[:, 2] < 0.43)) |
                                             ((c[:, 0] < -1.6) & (c[:, 2] > 0.36) & (c[:, 2] < 0.62)))
        white = (0.84 - 0.5) / 0.2820948
        data['dc'][plate] = white
        data['sh'][plate] = 0
        data['alpha'][plate] = np.maximum(data['alpha'][plate], 0.97)
        data['scale'][plate] *= 1.4
        print('blanked', plate.sum(), file=sys.stderr)
    # Camera vectors come in car axes; map them into the scan (whose frame the SH colours are in).
    ax3 = np.array([axis[0], 0, axis[1]], np.float32)
    sd3 = np.array([side[0], 0, side[1]], np.float32)
    up3 = np.array([0, 1, 0], np.float32)
    to_scan = lambda v: (v[0] * ax3 + v[1] * sd3 + v[2] * up3).astype(np.float32)
    origin = np.array([centre[0], ground, centre[1]], np.float32)
    yaw = math.degrees(math.atan2(axis[0], axis[1]))
    print('kept', keep.sum(), 'of', len(p), 'ground %.3f heading %.1f deg, length %.2f width %.2f' %
          (ground, yaw, hi[0] - lo[0], hi[1] - lo[1]), file=sys.stderr)
    return data, to_scan, origin

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
