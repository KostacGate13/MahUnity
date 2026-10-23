package com.mahunity.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The photos of the real car, rendered from its scan with the same camera as CarView: the turntable
 * (assets/car/NN.webp, one every STEP degrees) and, per moving part, the frames of it opening
 * (assets/car/PART/NN.webp, listed with the angle they are seen from in assets/car/parts.txt). Decoded off the
 * main thread a few at a time and kept in a small cache, so memory stays at a dozen frames.
 */
final class CarFrames {
    static final int STEP = 5;
    private static final String DIR = "car";

    /** A moving part: its name (a folder), the turntable angle it opens at, and its frame count. */
    static final class Part {
        final String name;
        final int yaw, frames;

        Part(String name, int yaw, int frames) {
            this.name = name;
            this.yaw = yaw;
            this.frames = frames;
        }
    }

    final int count, width, height;
    final List<Part> parts;
    private final AssetManager assets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("car", Process.THREAD_PRIORITY_DISPLAY);
    private final Handler worker;
    /** Keys: a turntable index, or 1000 * (part index + 1) + frame. */
    private final LruCache<Integer, Bitmap> cache = new LruCache<>(12);
    /** Frames queued for decoding. Main thread only. */
    private final Set<Integer> pending = new HashSet<>();
    private Runnable onReady;

    private CarFrames(AssetManager assets, int count, int width, int height, List<Part> parts) {
        this.assets = assets;
        this.count = count;
        this.width = width;
        this.height = height;
        this.parts = parts;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    /** The frames in the APK, or null when there are none (the line drawing is used then). */
    static CarFrames open(Context context) {
        AssetManager assets = context.getAssets();
        try {
            String[] names = assets.list(DIR);
            int count = 0;
            if (names != null) {
                // The turntable only, not the parts' folders (some asset managers list those recursively).
                for (String n : names) {
                    if (n.endsWith(".webp") && n.indexOf('/') < 0) count++;
                }
            }
            if (count == 0 || 360 % count != 0 || 360 / count != STEP) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = assets.open(path(0));
            try {
                BitmapFactory.decodeStream(in, null, bounds);
            } finally {
                in.close();
            }
            if (bounds.outWidth <= 0) return null;
            return new CarFrames(assets, count, bounds.outWidth, bounds.outHeight, readParts(assets));
        } catch (IOException e) {
            return null;
        }
    }

    private static List<Part> readParts(AssetManager assets) {
        List<Part> parts = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(assets.open(DIR + "/parts.txt"), "UTF-8"));
            try {
                for (String line; (line = r.readLine()) != null; ) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length == 3) parts.add(new Part(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2])));
                }
            } finally {
                r.close();
            }
        } catch (IOException | NumberFormatException e) {
            // No moving parts in this build; doors still turn the car and light up the label.
        }
        return parts;
    }

    Part part(String name) {
        for (Part p : parts) {
            if (p.name.equals(name)) return p;
        }
        return null;
    }

    void setOnReady(Runnable r) {
        onReady = r;
    }

    /** The turntable frame for an angle in degrees: the nearest one rendered. */
    int index(float angle) {
        float a = ((angle % 360) + 360) % 360;
        return Math.round(a / STEP) % count;
    }

    /** The turntable frame if decoded; otherwise the closest decoded neighbour, or null. Asks for it either way. */
    Bitmap get(int i) {
        i = wrap(i);
        Bitmap b = cache.get(i);
        if (b != null) return b;
        want(i);
        for (int d = 1; d <= count / 2; d++) {
            b = cache.get(wrap(i + d));
            if (b == null) b = cache.get(wrap(i - d));
            if (b != null) return b;
        }
        return null;
    }

    /** Frame k of a part opening (0 is the closed turntable frame); the nearest decoded one while it loads. */
    Bitmap get(Part p, int k) {
        k = Math.max(0, Math.min(p.frames, k));
        if (k == 0) return get(index(p.yaw));
        int base = 1000 * (parts.indexOf(p) + 1);
        Bitmap b = cache.get(base + k);
        if (b != null) return b;
        want(base + k);
        for (int d = k - 1; d >= 1; d--) {
            b = cache.get(base + d);
            if (b != null) return b;
        }
        return get(index(p.yaw));
    }

    /** Starts decoding a turntable frame that will be needed soon. */
    void want(int i) {
        decodeLater(i < 1000 ? wrap(i) : i);
    }

    /** Starts decoding a whole opening, ahead of playing it. */
    void want(Part p) {
        int base = 1000 * (parts.indexOf(p) + 1);
        for (int k = 1; k <= p.frames; k++) decodeLater(base + k);
    }

    private void decodeLater(final int key) {
        if (cache.get(key) != null || !pending.add(key)) return;
        worker.post(new Runnable() {
            @Override public void run() {
                final Bitmap b = decode(key);
                main.post(new Runnable() {
                    @Override public void run() {
                        pending.remove(key);
                        if (b == null) return;
                        cache.put(key, b);
                        if (onReady != null) onReady.run();
                    }
                });
            }
        });
    }

    void close() {
        thread.quitSafely();
        cache.evictAll();
    }

    private Bitmap decode(int key) {
        String path = key < 1000 ? path(key) : DIR + "/" + parts.get(key / 1000 - 1).name + "/" + two(key % 1000) + ".webp";
        try {
            InputStream in = assets.open(path);
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    private int wrap(int i) {
        return ((i % count) + count) % count;
    }

    private static String path(int i) {
        return DIR + "/" + two(i) + ".webp";
    }

    private static String two(int i) {
        return (i < 10 ? "0" : "") + i;
    }
}
