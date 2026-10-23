# Car frames from a scan

How `app/src/main/assets/car/*.webp` are made: a Gaussian-splat scan of the car (Scaniverse, exported as
SPZ), rendered on the CPU from 72 angles with the same camera as `CarView`.

```sh
cd tools/car
gcc -O3 -ffast-math -shared -fPIC -o splat.so splat.c -lm
python3 decode.py My_car.spz car.npz          # numpy, the SPZ v2/v3 format
python3 frames.py car.npz out 760 520 80     # numpy + Pillow; 72 transparent WebP frames, ~2.2 MB
cp out/*.webp ../../app/src/main/assets/car/
```

`render.py` finds the ground and the car's heading on its own, trims the ground, the shadow under the car
and the haze around it, and blanks both number plates (the APK is public). Its crop box and the plate boxes
are in metres around the car and fit a Corsa E; another car may need them adjusted. The scan itself is not
in the repo.
