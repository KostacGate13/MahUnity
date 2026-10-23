"""Quick look: a few turntable angles and fully opened parts, side by side, on the app's background."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render as R
from PIL import Image
npz, out = sys.argv[1], sys.argv[2]
w, h = 760, 520
data, to_scan, origin = R.load(npz, 1)
shots = [R.frame(data, to_scan, origin, y, w, h) for y in (0, 180, 90)]
for name in sys.argv[3].split(',') if len(sys.argv) > 3 else []:
    p = R.PARTS[name]
    shots.append(R.frame(R.articulate(data, origin, name, 1.0), to_scan, origin, p['yaw'], w, h))
cols = 3
sheet = Image.new('RGBA', (w * cols, h * ((len(shots) + cols - 1) // cols)), (8, 9, 11, 255))
for i, im in enumerate(shots):
    sheet.alpha_composite(im, ((i % cols) * w, (i // cols) * h))
sheet.convert('RGB').save(out)
