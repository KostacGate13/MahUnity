"""Renders the app's car frames as transparent WebP: the turntable (72 frames, every 5 degrees), and for each
moving part (doors, boot, bonnet) the frames of it opening at that part's angle, listed in parts.txt."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render as R

npz, out, w, h = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
quality = int(sys.argv[5]) if len(sys.argv) > 5 else 80
steps = 10
os.makedirs(out, exist_ok=True)
data, to_scan, origin = R.load(npz, 1)
total = 0


def save(im, path):
    global total
    im.save(path, 'WEBP', quality=quality, method=6)
    total += os.path.getsize(path)


for k in range(72):
    save(R.frame(data, to_scan, origin, k * 5.0, w, h), os.path.join(out, '%02d.webp' % k))
lines = []
for name, p in R.PARTS.items():
    os.makedirs(os.path.join(out, name), exist_ok=True)
    for k in range(1, steps + 1):
        opened = R.articulate(data, origin, name, k / steps)
        save(R.frame(opened, to_scan, origin, p['yaw'], w, h), os.path.join(out, name, '%02d.webp' % k))
    # name, the turntable angle it opens at, and how many frames (frame 0 is the closed turntable frame).
    lines.append('%s %d %d' % (name, p['yaw'], steps))
with open(os.path.join(out, 'parts.txt'), 'w') as f:
    f.write('\n'.join(lines) + '\n')
print('total %.0f KB' % (total / 1024), file=sys.stderr)
