package com.mahunity.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * The car on its turntable, drawn as polished lines in perspective. It turns to the angle each tab asks
 * for, and follows a finger dragged across it. Nothing is drawn between movements, so it costs nothing
 * while it stands still.
 */
final class CarView extends View {
    /** Angles in degrees: 0 shows the nose, 90 the right-hand side, 180 the tail. */
    static final float FRONT = 18f, RIGHT_SIDE = 90f, REAR = 205f;

    private static final float DISTANCE = 16f;
    private static final float ELEVATION = (float) Math.toRadians(4.5);
    private static final float COS_E = (float) Math.cos(ELEVATION), SIN_E = (float) Math.sin(ELEVATION);
    private static final float TARGET_Z = 0.62f;
    private static final float RING = 2.3f;
    private static final int TICKS = 72;
    private static final int[] ALPHAS = {0xFF, 0xB8, 0x78, 0x44};

    private final CarModel model = new CarModel();
    private final float[] ring = new float[TICKS * 6];
    private final float[] ticks = new float[TICKS * 6];
    private final float[][] buckets = new float[ALPHAS.length][];
    private final int[] counts = new int[ALPHAS.length];
    private final float[] point = new float[3];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lamp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix squash = new Matrix();
    private ValueAnimator turn;
    private float angle = FRONT;
    private float focal, cx, cy, stroke;
    private float lastX;
    private int floorColor;

    CarView(Context context) {
        super(context);
        int most = Math.max(model.body.length, ticks.length) / 6 * 4;
        for (int i = 0; i < buckets.length; i++) buckets[i] = new float[most];
        for (int i = 0; i < TICKS; i++) {
            double a0 = 2 * Math.PI * i / TICKS, a1 = 2 * Math.PI * (i + 1) / TICKS;
            set(ring, i, RING * (float) Math.cos(a0), RING * (float) Math.sin(a0), 0,
                    RING * (float) Math.cos(a1), RING * (float) Math.sin(a1), 0);
            float inner = i % 6 == 0 ? RING - 0.16f : RING - 0.07f;
            set(ticks, i, RING * (float) Math.cos(a0), RING * (float) Math.sin(a0), 0,
                    inner * (float) Math.cos(a0), inner * (float) Math.sin(a0), 0);
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        lamp.setStyle(Paint.Style.STROKE);
        lamp.setStrokeCap(Paint.Cap.ROUND);
    }

    private static void set(float[] f, int i, float x0, float y0, float z0, float x1, float y1, float z1) {
        f[i * 6] = x0;
        f[i * 6 + 1] = y0;
        f[i * 6 + 2] = z0;
        f[i * 6 + 3] = x1;
        f[i * 6 + 4] = y1;
        f[i * 6 + 5] = z1;
    }

    /** Turns smoothly to the angle, the short way round. */
    void turnTo(float target) {
        if (turn != null) turn.cancel();
        float from = angle;
        float delta = ((target - from) % 360 + 540) % 360 - 180;
        if (Math.abs(delta) < 0.5f) return;
        turn = ValueAnimator.ofFloat(from, from + delta);
        turn.setDuration(Math.round(500 + Math.abs(delta) * 4));
        turn.setInterpolator(new AccelerateDecelerateInterpolator());
        turn.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                angle = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        turn.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (turn != null) turn.cancel();
                lastX = e.getX();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                angle -= (e.getX() - lastX) * 0.35f;
                lastX = e.getX();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Big enough that the side view fills most of the width, small enough that the turntable fits.
        focal = Math.min(w * 0.86f * DISTANCE / (2 * CarModel.HALF_LENGTH), h * 0.8f * DISTANCE / 2.4f);
        cx = w / 2f;
        cy = h * 0.5f;
        stroke = Math.max(1f, Math.min(w, h) * 0.0032f);
        line.setStrokeWidth(stroke);
        glow.setStrokeWidth(stroke * 5);
        lamp.setStrokeWidth(stroke * 1.8f);
        line.setShader(Chrome.metal(project(0, 0, 1.5f, 0)[1], project(0, 0, 0, 0)[1]));
        floorColor = 0;
    }

    @Override protected void onDraw(Canvas c) {
        if (focal == 0) return;
        float rad = (float) Math.toRadians(angle);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);

        // A pool of light under the car, in the ambient colour.
        if (floorColor != Chrome.accent) {
            floorColor = Chrome.accent;
            float[] centre = project(0, 0, 0, 0);
            float rx = focal * RING * 1.05f / DISTANCE;
            RadialGradient pool = new RadialGradient(centre[0], centre[1], rx,
                    new int[] {Chrome.withAlpha(floorColor, 0x2C), Chrome.withAlpha(floorColor, 0x10), 0},
                    new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
            squash.setScale(1f, SIN_E * 1.15f, centre[0], centre[1]);
            pool.setLocalMatrix(squash);
            floor.setShader(pool);
        }
        c.drawRect(0, 0, getWidth(), getHeight(), floor);

        // The turntable: its ring stays put, its ticks turn with the car.
        drawSegments(c, ring, 1, 0, 0.6f, line, null);
        drawSegments(c, ticks, cos, sin, 0.6f, line, null);
        glow.setColor(Chrome.withAlpha(Chrome.accent, 0x16));
        drawSegments(c, model.body, cos, sin, 1f, line, glow);
        lamp.setColor(Chrome.accent);
        drawSegments(c, model.lamps, cos, sin, 1f, lamp, null);
        lamp.setColor(0xFFE0353B);
        drawSegments(c, model.tails, cos, sin, 1f, lamp, null);
    }

    /**
     * Projects every segment and draws it at one of four strengths by depth, so far lines fade back. The
     * glow, if any, goes under the nearest lines only.
     */
    private void drawSegments(Canvas c, float[] seg, float cos, float sin, float strength, Paint paint, Paint under) {
        for (int i = 0; i < counts.length; i++) counts[i] = 0;
        float near = DISTANCE - RING, far = DISTANCE + RING;
        for (int i = 0; i < seg.length; i += 6) {
            float[] a = project(seg[i], seg[i + 1], seg[i + 2], cos, sin);
            float ax = a[0], ay = a[1], ad = a[2];
            float[] b = project(seg[i + 3], seg[i + 4], seg[i + 5], cos, sin);
            float depth = (ad + b[2]) / 2;
            int k = (int) ((depth - near) / (far - near) * ALPHAS.length);
            k = Math.max(0, Math.min(ALPHAS.length - 1, k));
            float[] out = buckets[k];
            int n = counts[k];
            out[n] = ax;
            out[n + 1] = ay;
            out[n + 2] = b[0];
            out[n + 3] = b[1];
            counts[k] = n + 4;
        }
        int base = paint.getAlpha();
        for (int k = 0; k < ALPHAS.length; k++) {
            if (counts[k] == 0) continue;
            if (under != null && k == 0) c.drawLines(buckets[k], 0, counts[k], under);
            paint.setAlpha(Math.round(ALPHAS[k] * strength * base / 255f));
            c.drawLines(buckets[k], 0, counts[k], paint);
            paint.setAlpha(base);
        }
    }

    private float[] project(float x, float y, float z, float yawDegrees) {
        double r = Math.toRadians(yawDegrees);
        return project(x, y, z, (float) Math.cos(r), (float) Math.sin(r));
    }

    /** Turns the point by the car's angle and projects it: screen x, screen y, depth. Reuses one array. */
    private float[] project(float x, float y, float z, float cos, float sin) {
        float rx = x * cos - y * sin;
        float ry = x * sin + y * cos;
        float qz = z - TARGET_Z;
        float depth = DISTANCE - (rx * COS_E + qz * SIN_E);
        float up = -rx * SIN_E + qz * COS_E;
        point[0] = cx + focal * ry / depth;
        point[1] = cy - focal * up / depth;
        point[2] = depth;
        return point;
    }
}
