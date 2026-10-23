package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.text.TextPaint;

/**
 * The 60s cluster, after a Mustang or a Charger: round pods in turned-chrome bezels, deep black faces, cream
 * numerals, a thin orange needle on a chrome hub, a curve of light on the glass. With room for two pods: the
 * speedometer, its odometer drum reading the speed, beside a clock with hands. With room for one: the
 * speedometer, its drum reading the time.
 */
final class Gauge60 extends GaugeView {
    private static final int CREAM = 0xFFEFE6CF, CREAM_DIM = 0xA0EFE6CF, NEEDLE = 0xFFFF6A1F;
    private static final float START = 150f, SWEEP = 240f;

    private final Paint bezel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glare = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drum = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drumLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint numbers = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint drumText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path pointer = new Path();
    private final RectF drumBox = new RectF();
    private final String unit;
    private boolean twin;
    private float r, speedX, clockX, cy;
    private final Shader[] bezels = new Shader[2], faces = new Shader[2], glares = new Shader[2], hubs = new Shader[2];

    Gauge60(Context context) {
        super(context);
        unit = context.getString(R.string.kmh);
        bezel.setStyle(Paint.Style.STROKE);
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor(0xFF000000);
        tick.setStrokeCap(Paint.Cap.BUTT);
        tick.setColor(CREAM);
        needle.setColor(NEEDLE);
        hand.setColor(CREAM);
        hand.setStrokeCap(Paint.Cap.ROUND);
        drumLine.setColor(0xFF2A2A2A);
        Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        numbers.setTypeface(condensed);
        numbers.setColor(CREAM);
        numbers.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(condensed);
        label.setColor(CREAM_DIM);
        label.setLetterSpacing(0.18f);
        label.setTextAlign(Paint.Align.CENTER);
        drumText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        drumText.setColor(CREAM);
        drumText.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        twin = w >= h * 1.85f;
        if (twin) {
            r = Math.min(h * 0.48f, w / 4.3f);
            speedX = w / 2f - r * 1.08f;
            clockX = w / 2f + r * 1.08f;
        } else {
            r = Math.min(w, h) * 0.48f;
            speedX = clockX = w / 2f;
        }
        cy = h / 2f;
        bezel.setStrokeWidth(r * 0.085f);
        rim.setStrokeWidth(Math.max(1f, r * 0.012f));
        float[] xs = {speedX, clockX};
        for (int i = 0; i < 2; i++) {
            float x = xs[i];
            // Turned chrome: bright and dark bands around the ring.
            bezels[i] = new SweepGradient(x, cy, new int[] {0xFFF4F6F8, 0xFF5E646C, 0xFFE3E7EB, 0xFF474C53, 0xFFC9CED4,
                    0xFF5E646C, 0xFFF4F6F8}, null);
            // A deep dish: lighter just above the middle, black at the rim.
            faces[i] = new RadialGradient(x, cy - r * 0.25f, r * 1.1f, new int[] {0xFF222224, 0xFF0B0B0C, 0xFF000000},
                    new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
            glares[i] = new LinearGradient(0, cy - r, 0, cy, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            hubs[i] = new RadialGradient(x - r * 0.03f, cy - r * 0.03f, r * 0.11f, new int[] {0xFFF4F6F8, 0xFF8E949B, 0xFF3A3F45},
                    new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        }
        numbers.setTextSize(r * (r < 120 ? 0.15f : 0.12f));
        label.setTextSize(r * 0.09f);
        float dw = r * 0.62f, dh = r * 0.2f;
        drumBox.set(speedX - dw / 2, cy + r * 0.36f, speedX + dw / 2, cy + r * 0.36f + dh);
        drum.setShader(new LinearGradient(0, drumBox.top, 0, drumBox.bottom, new int[] {0xFF050505, 0xFF1C1C1C, 0xFF050505},
                null, Shader.TileMode.CLAMP));
        drumText.setTextSize(dh * 0.78f);
        hand.setStrokeWidth(Math.max(2f, r * 0.045f));
    }

    @Override protected void onDraw(Canvas c) {
        if (r == 0) return;
        pod(c, 0, speedX);
        speedometer(c);
        if (twin) {
            pod(c, 1, clockX);
            clock(c);
        }
    }

    /** Bezel, face and the black line inside the bezel. */
    private void pod(Canvas c, int i, float x) {
        face.setShader(faces[i]);
        c.drawCircle(x, cy, r, face);
        bezel.setShader(bezels[i]);
        c.drawCircle(x, cy, r - bezel.getStrokeWidth() / 2, bezel);
        c.drawCircle(x, cy, r - bezel.getStrokeWidth(), rim);
    }

    private void glass(Canvas c, int i, float x) {
        glare.setShader(glares[i]);
        float in = r - bezel.getStrokeWidth();
        c.drawOval(x - in * 0.82f, cy - in * 0.95f, x + in * 0.82f, cy + in * 0.05f, glare);
    }

    private void speedometer(Canvas c) {
        float x = speedX;
        boolean few = r < 120;
        for (int v = 0; v <= MAX; v += 10) {
            double a = Math.toRadians(START + SWEEP * v / MAX);
            boolean major = v % 20 == 0;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float outer = r * 0.86f, inner = r * (major ? 0.72f : 0.79f);
            tick.setStrokeWidth(Math.max(1f, r * (major ? 0.028f : 0.014f)));
            c.drawLine(x + cos * inner, cy + sin * inner, x + cos * outer, cy + sin * outer, tick);
            if (major && (!few || v % 40 == 0)) {
                float tr = r * 0.57f;
                c.drawText(String.valueOf(v), x + cos * tr, cy + sin * tr + numbers.getTextSize() * 0.36f, numbers);
            }
        }
        c.drawText(Text.upper(unit), x, cy - r * 0.28f, label);

        // The drum: the speed beside the clock pod, the time on its own.
        c.drawRect(drumBox, drum);
        String s = twin ? (known ? String.format(java.util.Locale.ROOT, "%03d", Math.round(shown)) : "---") : timeText;
        int n = s.length();
        float cw = drumBox.width() / n;
        for (int k = 0; k < n; k++) {
            float cx = drumBox.left + cw * (k + 0.5f);
            if (k > 0) c.drawRect(drumBox.left + cw * k - 0.5f, drumBox.top, drumBox.left + cw * k + 0.5f, drumBox.bottom, drumLine);
            c.drawText(s, k, k + 1, cx, drumBox.centerY() + drumText.getTextSize() * 0.36f, drumText);
        }

        // The needle: a thin taper from a short tail to the scale, on its chrome hub.
        float sweep = SWEEP * shown / MAX;
        c.save();
        c.rotate(START + sweep, x, cy);
        pointer.reset();
        pointer.moveTo(x - r * 0.16f, cy - r * 0.022f);
        pointer.lineTo(x + r * 0.84f, cy - r * 0.006f);
        pointer.lineTo(x + r * 0.84f, cy + r * 0.006f);
        pointer.lineTo(x - r * 0.16f, cy + r * 0.022f);
        pointer.close();
        c.drawPath(pointer, needle);
        c.restore();
        hub.setShader(hubs[0]);
        c.drawCircle(x, cy, r * 0.1f, hub);
        glass(c, 0, x);
    }

    private void clock(Canvas c) {
        float x = clockX;
        for (int m = 0; m < 60; m++) {
            double a = Math.toRadians(m * 6 - 90);
            boolean major = m % 5 == 0;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float outer = r * 0.86f, inner = r * (major ? 0.74f : 0.81f);
            tick.setStrokeWidth(Math.max(1f, r * (major ? 0.028f : 0.01f)));
            c.drawLine(x + cos * inner, cy + sin * inner, x + cos * outer, cy + sin * outer, tick);
            if (m % 15 == 0) {
                float tr = r * 0.58f;
                String t = String.valueOf(m == 0 ? 12 : m / 5);
                c.drawText(t, x + cos * tr, cy + sin * tr + numbers.getTextSize() * 0.36f, numbers);
            }
        }
        c.drawText(dateText, x, cy + r * 0.42f, label);
        float hourAngle = (hour % 12 + minute / 60f) * 30f - 90f, minuteAngle = minute * 6f - 90f;
        handLine(c, x, hourAngle, r * 0.42f, hand.getStrokeWidth() * 1.5f);
        handLine(c, x, minuteAngle, r * 0.7f, hand.getStrokeWidth());
        hub.setShader(hubs[1]);
        c.drawCircle(x, cy, r * 0.08f, hub);
        glass(c, 1, x);
    }

    private void handLine(Canvas c, float x, float degrees, float length, float width) {
        double a = Math.toRadians(degrees);
        float stroke = hand.getStrokeWidth();
        hand.setStrokeWidth(width);
        c.drawLine(x - (float) Math.cos(a) * r * 0.1f, cy - (float) Math.sin(a) * r * 0.1f,
                x + (float) Math.cos(a) * length, cy + (float) Math.sin(a) * length, hand);
        hand.setStrokeWidth(stroke);
    }
}
