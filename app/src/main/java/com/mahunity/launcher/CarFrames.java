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

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * The turntable photos of the real car, rendered from its scan: assets/car/NN.webp, one every STEP degrees,
 * with the same camera as CarView. Decoded off the main thread a few at a time and kept in a small cache,
 * so memory stays at a dozen frames however far the car turns.
 */
final class CarFrames {
    static final int STEP = 5;
    private static final String DIR = "car";

    final int count, width, height;
    private final AssetManager assets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("car", Process.THREAD_PRIORITY_DISPLAY);
    private final Handler worker;
    private final LruCache<Integer, Bitmap> cache = new LruCache<>(12);
    /** Frames queued for decoding. Main thread only. */
    private final Set<Integer> pending = new HashSet<>();
    private Runnable onReady;

    private CarFrames(AssetManager assets, int count, int width, int height) {
        this.assets = assets;
        this.count = count;
        this.width = width;
        this.height = height;
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
                for (String n : names) {
                    if (n.endsWith(".webp")) count++;
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
            return new CarFrames(assets, count, bounds.outWidth, bounds.outHeight);
        } catch (IOException e) {
            return null;
        }
    }

    void setOnReady(Runnable r) {
        onReady = r;
    }

    /** The frame for an angle in degrees: the nearest one rendered. */
    int index(float angle) {
        float a = ((angle % 360) + 360) % 360;
        return Math.round(a / STEP) % count;
    }

    /** The frame if decoded; otherwise the closest decoded neighbour, or null. Asks for the frame either way. */
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

    /** Starts decoding a frame that will be needed soon. */
    void want(int i) {
        final int k = wrap(i);
        if (cache.get(k) != null || !pending.add(k)) return;
        worker.post(new Runnable() {
            @Override public void run() {
                final Bitmap b = decode(k);
                main.post(new Runnable() {
                    @Override public void run() {
                        pending.remove(k);
                        if (b == null) return;
                        cache.put(k, b);
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

    private Bitmap decode(int i) {
        try {
            InputStream in = assets.open(path(i));
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
        return DIR + "/" + (i < 10 ? "0" : "") + i + ".webp";
    }
}
