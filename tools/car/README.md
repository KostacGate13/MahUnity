# Car frames from a scan

How `app/src/main/assets/car/` is made: a Gaussian-splat scan of the car (Scaniverse, exported as SPZ),
rendered on the CPU with the same camera as `CarView`: 72 turntable frames, and for each door, the boot and
the bonnet ten frames of it opening, seen from the angle listed in `parts.txt`.

```sh
cd tools/car
gcc -O3 -ffast-math -shared -fPIC -o splat.so splat.c -lm
python3 decode.py My_car.spz car.npz                 # numpy, the SPZ v2/v3 format
python3 preview.py car.npz look.png door_left,trunk  # a quick look: front, rear, side, parts opened
python3 frames.py car.npz out 760 520 80             # numpy + Pillow; ~3.7 MB of transparent WebP
rm -rf ../../app/src/main/assets/car && cp -r out ../../app/src/main/assets/car
```

`render.py` finds the ground and the car's heading on its own, then cleans the scan up:

- trims the ground, the shadow under the car and the faint haze floating around it (splats that are not on
  the car's surface);
- paints both number plates out with what lies either side of them, row by row, so bumper and grille carry
  straight through: no plate, no frame, no lettering (the APK is public);
- opens a part by turning its splats about a hinge, tints it red as it goes, and lines the opening with a
  dark copy of the panel, since nothing behind it was scanned.

The crop box, plate boxes, part outlines and hinges are in metres around the car and fit a Corsa E 3-door;
another car needs them adjusted (`PARTS` and the plate boxes in `render.py`). The scan itself is not in the
repo. An SPZ export is enough: it holds the same splats as PLY, only packed smaller.
