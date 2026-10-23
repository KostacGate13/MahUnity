package com.mahunity.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * A line model of a 3-door Opel Corsa E (2015): 4.02 m long, 1.74 m wide, 1.48 m tall, 2.51 m wheelbase.
 * Metres; x forward, y to the car's left, z up, the origin on the ground under the middle of the car.
 * It stands in until a scan of the real car replaces it.
 */
final class CarModel {
    /** Body segments, six floats each (x0, y0, z0, x1, y1, z1). */
    final float[] body;
    /** Headlamps, drawn in the accent light. */
    final float[] lamps;
    /** Tail lamps, drawn red. */
    final float[] tails;

    static final float HALF_LENGTH = 2.03f;

    /**
     * Cross-sections front to back: x, then for one side the sill (z, half-width), the shoulder line
     * (z, half-width) and the top edge, hood or roof (z, half-width).
     */
    private static final float[][] SECTIONS = {
        {2.02f, 0.30f, 0.60f, 0.55f, 0.66f, 0.62f, 0.50f},
        {1.85f, 0.22f, 0.80f, 0.70f, 0.84f, 0.78f, 0.66f},
        {1.50f, 0.20f, 0.84f, 0.78f, 0.87f, 0.86f, 0.72f},
        {1.05f, 0.20f, 0.85f, 0.88f, 0.87f, 0.93f, 0.74f},
        {0.75f, 0.20f, 0.85f, 0.92f, 0.87f, 1.15f, 0.68f},
        {0.40f, 0.20f, 0.85f, 0.95f, 0.87f, 1.40f, 0.62f},
        {0.00f, 0.20f, 0.85f, 0.97f, 0.87f, 1.48f, 0.62f},
        {-0.60f, 0.20f, 0.85f, 0.99f, 0.87f, 1.47f, 0.62f},
        {-1.20f, 0.22f, 0.85f, 1.02f, 0.86f, 1.43f, 0.60f},
        {-1.60f, 0.25f, 0.82f, 1.03f, 0.84f, 1.32f, 0.58f},
        {-1.85f, 0.28f, 0.78f, 0.98f, 0.80f, 1.08f, 0.62f},
        {-2.00f, 0.32f, 0.70f, 0.80f, 0.74f, 0.92f, 0.64f},
        {-2.03f, 0.35f, 0.60f, 0.55f, 0.64f, 0.70f, 0.55f},
    };

    private static final float[] AXLES = {1.25f, -1.26f};
    private static final float WHEEL_R = 0.31f;

    CarModel() {
        List<float[]> b = new ArrayList<>();
        // Rings at each section, and the six lines that run the length of the car through them.
        for (int i = 0; i < SECTIONS.length; i++) {
            float[][] ring = ring(SECTIONS[i]);
            boolean end = i == 0 || i == SECTIONS.length - 1;
            for (int k = 0; k < ring.length; k++) {
                // Inner rings only over the top, so the sides stay clean; the ends are closed all round.
                if (end || (k >= 1 && k <= 3)) add(b, ring[k], ring[(k + 1) % ring.length]);
            }
            if (i > 0) {
                float[][] prev = ring(SECTIONS[i - 1]);
                for (int k = 0; k < ring.length; k++) add(b, prev[k], ring[k]);
            }
        }
        for (float axle : AXLES) {
            for (int side = -1; side <= 1; side += 2) wheel(b, axle, side);
        }
        for (int side = -1; side <= 1; side += 2) {
            float s = side;
            // Wing mirror.
            poly(b, true, 0.86f, 0.84f * s, 0.94f, 0.80f, 1.02f * s, 0.98f, 0.70f, 1.02f * s, 1.06f, 0.78f, 0.86f * s, 1.05f);
            // B-pillar and the rear edge of the side window.
            add(b, new float[] {-0.62f, 0.86f * s, 0.99f}, new float[] {-0.62f, 0.62f * s, 1.47f});
            add(b, new float[] {-1.55f, 0.83f * s, 1.03f}, new float[] {-1.30f, 0.60f * s, 1.42f});
        }
        // Grille with its chrome bar, and the rear spoiler.
        poly(b, true, 2.02f, 0.42f, 0.36f, 2.02f, -0.42f, 0.36f, 2.03f, -0.48f, 0.50f, 2.03f, 0.48f, 0.50f);
        add(b, new float[] {2.04f, 0.50f, 0.52f}, new float[] {2.04f, -0.50f, 0.52f});
        poly(b, true, -1.58f, 0.58f, 1.36f, -1.58f, -0.58f, 1.36f, -1.74f, -0.56f, 1.30f, -1.74f, 0.56f, 1.30f);
        body = flatten(b);

        List<float[]> l = new ArrayList<>();
        List<float[]> t = new ArrayList<>();
        for (int side = -1; side <= 1; side += 2) {
            float s = side;
            poly(l, true, 1.97f, 0.44f * s, 0.66f, 1.80f, 0.78f * s, 0.75f, 1.70f, 0.83f * s, 0.71f, 1.90f, 0.50f * s, 0.60f);
            poly(t, true, -1.97f, 0.54f * s, 0.96f, -1.82f, 0.79f * s, 1.00f, -1.82f, 0.81f * s, 0.86f, -1.99f, 0.60f * s, 0.84f);
        }
        lamps = flatten(l);
        tails = flatten(t);
    }

    private static float[][] ring(float[] s) {
        float x = s[0];
        return new float[][] {
            {x, -s[2], s[1]}, {x, -s[4], s[3]}, {x, -s[6], s[5]},
            {x, s[6], s[5]}, {x, s[4], s[3]}, {x, s[2], s[1]},
        };
    }

    /** A wheel: tyre faces, the connecting tread, a five-spoke rim, and the arch above it in the body side. */
    private static void wheel(List<float[]> out, float axle, int side) {
        int n = 20;
        float outer = 0.80f * side, inner = 0.62f * side;
        float[][] o = new float[n][], in = new float[n][], rim = new float[n][];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            float cx = (float) Math.cos(a), cz = (float) Math.sin(a);
            o[i] = new float[] {axle + WHEEL_R * cx, outer, WHEEL_R + WHEEL_R * cz};
            in[i] = new float[] {axle + WHEEL_R * cx, inner, WHEEL_R + WHEEL_R * cz};
            rim[i] = new float[] {axle + 0.21f * cx, outer, WHEEL_R + 0.21f * cz};
        }
        for (int i = 0; i < n; i++) {
            add(out, o[i], o[(i + 1) % n]);
            add(out, in[i], in[(i + 1) % n]);
            add(out, rim[i], rim[(i + 1) % n]);
            if (i % 5 == 0) add(out, o[i], in[i]);
        }
        for (int k = 0; k < 5; k++) {
            double a = 2 * Math.PI * k / 5 + Math.PI / 2;
            float cx = (float) Math.cos(a), cz = (float) Math.sin(a);
            add(out, new float[] {axle + 0.05f * cx, outer, WHEEL_R + 0.05f * cz},
                    new float[] {axle + 0.21f * cx, outer, WHEEL_R + 0.21f * cz});
        }
        float[] prev = null;
        for (int i = 0; i <= 12; i++) {
            double a = Math.PI * i / 12;
            float[] p = {axle + 0.39f * (float) Math.cos(a), 0.86f * side, WHEEL_R + 0.39f * (float) Math.sin(a)};
            if (prev != null) add(out, prev, p);
            prev = p;
        }
    }

    private static void poly(List<float[]> out, boolean closed, float... xyz) {
        int n = xyz.length / 3;
        for (int i = 0; i < n - 1 + (closed ? 1 : 0); i++) {
            int j = (i + 1) % n;
            add(out, new float[] {xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]},
                    new float[] {xyz[j * 3], xyz[j * 3 + 1], xyz[j * 3 + 2]});
        }
    }

    private static void add(List<float[]> out, float[] a, float[] b) {
        out.add(new float[] {a[0], a[1], a[2], b[0], b[1], b[2]});
    }

    private static float[] flatten(List<float[]> segments) {
        float[] f = new float[segments.size() * 6];
        for (int i = 0; i < segments.size(); i++) System.arraycopy(segments.get(i), 0, f, i * 6, 6);
        return f;
    }
}
