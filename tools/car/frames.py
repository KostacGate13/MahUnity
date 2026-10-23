"""Renders the 72 turntable frames (every 5 degrees) as transparent WebP for the app's assets."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render as R

npz, out, w, h = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
quality = int(sys.argv[5]) if len(sys.argv) > 5 else 80
os.makedirs(out, exist_ok=True)
data, to_scan, origin = R.load(npz, 1)
total = 0
for k in range(72):
    im = R.frame(data, to_scan, origin, k * 5.0, w, h)
    path = os.path.join(out, '%02d.webp' % k)
    im.save(path, 'WEBP', quality=quality, method=6)
    total += os.path.getsize(path)
print('frames 72, total %.0f KB' % (total / 1024), file=sys.stderr)
