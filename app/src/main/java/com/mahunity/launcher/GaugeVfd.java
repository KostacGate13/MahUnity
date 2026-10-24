package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;

/**
 * The 80s digital dash of a Japanese coupé, after its vacuum-fluorescent display: everything glows green on black
 * glass. The speed in large slanted figures, km/h beside them, a speed-alarm bar under them that lights orange
 * from 130 km/h; a line of dots that climbs from the lower left with the speed, green, then yellow, orange and
 * red; the tachometer, a column of bars beside them marked 1 to 7 thousand rpm, red from 6000. At the left,
 * framed bar meters for the battery and the coolant, the time and the date under them; at the right, the fuel
 * meter with the litres, and a pump lamp that lights red when the tank runs low. Unlit, every segment shows
 * faintly. On a narrow widget: the speed, the dots, the tachometer and the time.
 *
 * Everything that does not change is drawn once into a bitmap, the unlit segments with it; each frame lights some.
 */
final class GaugeVfd extends GaugeView {
    private static final int GREEN = 0xFF4CFF72, YELLOW = 0xFFF2F046, ORANGE = 0xFFFF8A2A, RED = 0xFFFF3B2F,
            CYAN = 0xFF3AE2FF, BLUE = 0xFF3F7DFF;
    private static final int DOTS = 40, SEGMENTS = 10, REVS = 28;
    private static final float SLANT = -0.14f, ALARM = 130f;
    private static final float VOLTS_LOW = 8f, VOLTS_HIGH = 18f, COLD = 40f, HOT = 130f;
    /** Warnings: the battery below this, the coolant from this, the tank below this share. */
    private static final float WEAK = 11.8f, BOILING = 115f, LOW_FUEL = 0.12f;

    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF(), alarmLeft = new RectF(), alarmRight = new RectF(), pump = new RectF();
    private final float[] dotX = new float[DOTS], dotY = new float[DOTS];
    private final RectF[] voltsBar = bars(SEGMENTS), heatBar = bars(SEGMENTS), fuelBar = bars(SEGMENTS), revBars = bars(REVS);
    private final Figures speed = new Figures(3, -1, SLANT), time = new Figures(4, 1, SLANT);
    private final Path shape = new Path();
    private final String unit;
    private boolean wide;
    private float dotR, litresX, litresY, litresSize, dateX, dateY, dateSize;
    private Bitmap layer;

    GaugeVfd(Context context) {
        super(context);
        unit = context.getString(R.string.kmh);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setColor(GREEN);
    }

    private static RectF[] bars(int n) {
        RectF[] r = new RectF[n];
        for (int i = 0; i < n; i++) r[i] = new RectF();
        return r;
    }

    private float x(float f) {
        return panel.left + panel.width() * f;
    }

    private float y(float f) {
        return panel.top + panel.height() * f;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        float inset = h * 0.03f;
        panel.set(inset, inset, w - inset, h - inset);
        wide = w >= h * 1.6f;
        float ph = panel.height(), pw = panel.width();
        glow(GREEN);

        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(layer);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = h * 0.06f;
        p.setShader(new LinearGradient(0, panel.top, 0, panel.bottom, 0xFF040706, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(panel, r, r, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, h * 0.008f));
        p.setColor(0xFF1C2320);
        c.drawRoundRect(panel, r, r, p);

        float fh, left, baseline;
        if (wide) {
            meter(c, new RectF(x(0.03f), y(0.08f), x(0.12f), y(0.52f)), voltsBar, new String[] {"18", "12", "8"},
                    new float[] {1f, 0.4f, 0f}, "V", false);
            meter(c, new RectF(x(0.135f), y(0.08f), x(0.225f), y(0.52f)), heatBar, new String[] {"H", "C"},
                    new float[] {1f, 0f}, "°C", false);
            RectF fuel = new RectF(x(0.865f), y(0.08f), x(0.965f), y(0.52f));
            meter(c, fuel, fuelBar, new String[] {"F", "½", "E"}, new float[] {1f, 0.5f, 0f}, null, true);
            litresX = fuel.centerX();
            litresSize = ph * 0.08f;
            litresY = fuel.bottom + litresSize * 1.4f;

            float th = ph * 0.13f;
            time.layout(x(0.03f), y(0.76f), th);
            dateSize = ph * 0.07f;
            dateX = x(0.03f);
            dateY = y(0.76f) + dateSize * 1.7f;

            fh = Math.min(ph * 0.3f, pw * 0.28f / Figures.span(3, false, SLANT));
            left = x(0.47f) - fh * Figures.span(3, false, SLANT) / 2;
            baseline = y(0.54f);
            // Indicator squares over the speed, unlit, in the colours of the original's.
            int[] colors = {GREEN, CYAN, BLUE, GREEN};
            float s = ph * 0.075f, g = ph * 0.035f, sx = x(0.47f) - (4 * s + 3 * g) / 2;
            for (int k = 0; k < 4; k++) {
                dim.setColor(colors[k] & 0x00FFFFFF | 0x30000000);
                c.drawRect(sx + k * (s + g), y(0.04f), sx + k * (s + g) + s, y(0.04f) + s, dim);
            }
            // Two thin lines over the figures, lit, as on the original.
            float fw = fh * Figures.span(3, false, SLANT);
            Paint line = new Paint(lit);
            line.setStrokeWidth(Math.max(1f, ph * 0.008f));
            c.drawLine(left + fh * 0.1f, y(0.165f), left + fw, y(0.165f), line);
            c.drawLine(left + fh * 0.1f, y(0.2f), left + fw, y(0.2f), line);
            // Warning lamps beside them, unlit; the middle one, the pump, lights when the tank runs low.
            float lamp = ph * 0.075f;
            dim.setColor(RED & 0x00FFFFFF | 0x2C000000);
            for (int k = 0; k < 3; k++) {
                float ly = y(0.26f) + k * lamp * 1.45f;
                c.drawRect(x(0.735f), ly, x(0.735f) + lamp, ly + lamp, dim);
                c.drawRect(x(0.845f) - lamp, ly, x(0.845f), ly + lamp, dim);
            }
            float pc = (x(0.735f) + lamp + x(0.845f) - lamp) / 2, ps = lamp * 1.6f;
            pump.set(pc - ps / 2, y(0.26f) + lamp * 1.45f - ps * 0.3f, pc + ps / 2, y(0.26f) + lamp * 1.45f + ps * 0.7f);
            pumpIcon(c, pump, RED & 0x00FFFFFF | 0x30000000);
            text.setTextSize(ph * 0.09f);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(unit, left + fw + pw * 0.015f, baseline - fh * 0.42f, text);
            dots(c, 0.25f, 0.94f, 0.6f, 0.93f, 0.735f, 0.66f);
            revs(c, x(0.262f), x(0.29f), y(0.08f), y(0.62f), true);
        } else {
            float th = ph * 0.12f;
            time.layout(x(0.04f), y(0.04f) + th, th);
            revs(c, x(0.05f), x(0.085f), y(0.22f), y(0.84f), false);
            text.setTextSize(ph * 0.08f);
            float uw = text.measureText(unit);
            fh = Math.min(ph * 0.34f, (pw * 0.76f - uw) / Figures.span(3, false, SLANT));
            float fw = fh * Figures.span(3, false, SLANT);
            left = x(0.58f) - (fw + pw * 0.02f + uw) / 2;
            baseline = y(0.6f);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(unit, left + fw + pw * 0.02f, baseline - fh * 0.42f, text);
            dots(c, 0.15f, 0.94f, 0.62f, 0.93f, 0.92f, 0.7f);
        }
        speed.layout(left, baseline, fh);
        unlit(c, speed, GREEN);
        unlit(c, time, GREEN);
        // The speed alarm, two bars under the figures.
        float fw = fh * Figures.span(3, false, SLANT), bh = ph * 0.035f, top = baseline + ph * 0.035f, mid = left + fw / 2;
        alarmLeft.set(left, top, mid - fw * 0.03f, top + bh);
        alarmRight.set(mid + fw * 0.03f, top, left + fw - fh * Math.abs(SLANT), top + bh);
        dim.setColor(ghost(ORANGE));
        c.drawRect(alarmLeft, dim);
        c.drawRect(alarmRight, dim);
    }

    /**
     * The tachometer: a column of bars between left and right, top and bottom, 0 rpm at the foot, marked every
     * thousand on its left (marksLeft) or its right, red from 6000.
     */
    private void revs(Canvas c, float left, float right, float top, float bottom, boolean marksLeft) {
        float step = (bottom - top) / REVS;
        for (int i = 0; i < REVS; i++) {
            float b = bottom - i * step;
            revBars[i].set(left, b - step * 0.62f, right, b);
            dim.setColor(ghost(revColor(i)));
            c.drawRect(revBars[i], dim);
        }
        text.setTextSize(Math.min(step * 3.2f, (right - left) * 1.1f));
        text.setTextAlign(marksLeft ? Paint.Align.RIGHT : Paint.Align.LEFT);
        Paint tick = new Paint(lit);
        tick.setStrokeWidth(Math.max(1f, step * 0.3f));
        float dash = (right - left) * 0.3f;
        for (int k = 1; k <= RPM_MAX / 1000; k++) {
            float y = bottom - step * (k * 1000 / RPM_MAX * REVS) + step * 0.31f;
            int color = k * 1000 >= REDLINE ? RED : GREEN;
            text.setColor(color);
            tick.setColor(color);
            tick.setShadowLayer(step * 0.4f, 0, 0, color);
            float x0 = marksLeft ? left - dash * 0.4f : right + dash * 0.4f, x1 = marksLeft ? x0 - dash : x0 + dash;
            c.drawLine(x0, y, x1, y, tick);
            c.drawText(String.valueOf(k), marksLeft ? x1 - dash * 0.5f : x1 + dash * 0.5f, y + text.getTextSize() * 0.36f, text);
        }
        text.setColor(GREEN);
        text.setTextSize(Math.min(step * 2.2f, (right - left) * 0.8f));
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("rpm", (left + right) / 2, bottom + text.getTextSize() * 1.3f, text);
    }

    private int revColor(int i) {
        return (i + 0.5f) / REVS * RPM_MAX >= REDLINE ? RED : GREEN;
    }

    /** The speed's dots along a curve from (x0, y0) bending through (x1, y1) up to (x2, y2), fractions of the panel. */
    private void dots(Canvas c, float x0, float y0, float x1, float y1, float x2, float y2) {
        dotR = panel.height() * 0.014f;
        for (int i = 0; i < DOTS; i++) {
            float t = i / (DOTS - 1f), u = 1 - t;
            dotX[i] = x(u * u * x0 + 2 * u * t * x1 + t * t * x2);
            dotY[i] = y(u * u * y0 + 2 * u * t * y1 + t * t * y2);
            dim.setColor(ghost(dotColor(i)));
            c.drawCircle(dotX[i], dotY[i], dotR, dim);
        }
    }

    private int dotColor(int i) {
        float v = MAX * (i + 0.5f) / DOTS;
        return v < 120 ? GREEN : v < 150 ? YELLOW : v < 175 ? ORANGE : RED;
    }

    /** A framed bar meter: segments in a column, marks beside it at the given heights, the unit under it. */
    private void meter(Canvas c, RectF box, RectF[] bar, String[] marks, float[] at, String unitText, boolean marksRight) {
        Paint frame = new Paint(lit);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Math.max(1.5f, box.width() * 0.035f));
        float rr = box.width() * 0.16f;
        c.drawRoundRect(box, rr, rr, frame);
        float pad = box.width() * 0.14f, barW = box.width() * 0.24f;
        float barTop = box.top + pad, barBottom = box.bottom - pad - (unitText != null ? box.height() * 0.13f : 0);
        float barX = marksRight ? box.left + pad : box.right - pad - barW;
        float step = (barBottom - barTop) / SEGMENTS;
        dim.setColor(ghost(GREEN));
        for (int i = 0; i < SEGMENTS; i++) {
            float b = barBottom - i * step;
            bar[i].set(barX, b - step * 0.7f, barX + barW, b);
            c.drawRect(bar[i], dim);
        }
        text.setTextSize(Math.min(box.width() * 0.26f, box.height() * 0.11f));
        text.setTextAlign(marksRight ? Paint.Align.LEFT : Paint.Align.RIGHT);
        Paint tick = new Paint(lit);
        tick.setStrokeWidth(Math.max(1f, box.width() * 0.03f));
        float dash = box.width() * 0.08f;
        for (int k = 0; k < marks.length; k++) {
            float my = barBottom - step * 0.35f - (barBottom - barTop - step * 0.7f) * at[k];
            float tx = marksRight ? barX + barW + box.width() * 0.06f : barX - box.width() * 0.06f;
            c.drawLine(tx, my, marksRight ? tx + dash : tx - dash, my, tick);
            c.drawText(marks[k], marksRight ? tx + dash * 1.5f : tx - dash * 1.5f, my + text.getTextSize() * 0.36f, text);
        }
        if (unitText != null) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(box.height() * 0.1f);
            c.drawText(unitText, box.centerX(), box.bottom - pad * 0.7f, text);
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (layer == null) return;
        c.drawBitmap(layer, 0, 0, null);

        int on = known ? Math.round(shown / MAX * DOTS) : 0;
        for (int i = 0; i < on; i++) {
            glow(dotColor(i));
            c.drawCircle(dotX[i], dotY[i], dotR, lit);
        }
        glow(GREEN);
        lit.setShadowLayer(speed.h * 0.08f, 0, 0, GREEN);
        speed.draw(c, Figures.number(known ? Math.round(shown) : -1, 3), lit);
        lit.setShadowLayer(time.h * 0.1f, 0, 0, GREEN);
        time.draw(c, Figures.clock(timeText), lit);
        int revsOn = revsKnown ? Math.round(revs / RPM_MAX * REVS) : 0;
        for (int i = 0; i < revsOn; i++) {
            glow(revColor(i));
            c.drawRect(revBars[i], lit);
        }
        if (known && shown >= ALARM) {
            glow(ORANGE);
            c.drawRect(alarmLeft, lit);
            c.drawRect(alarmRight, lit);
        }

        if (wide) {
            level(c, voltsBar, battery <= 0 ? -1 : (battery - VOLTS_LOW) / (VOLTS_HIGH - VOLTS_LOW),
                    battery < WEAK ? ORANGE : GREEN);
            level(c, heatBar, Float.isNaN(coolant) ? -1 : (coolant - COLD) / (HOT - COLD), coolant >= BOILING ? RED : GREEN);
            level(c, fuelBar, fuelShare, fuelShare < LOW_FUEL ? ORANGE : GREEN);
            if (fuelShare >= 0 && fuelShare < LOW_FUEL) pumpIcon(c, pump, RED);
            glow(GREEN);
            lit.clearShadowLayer();
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(litresSize);
            c.drawText(litres < 0 ? getContext().getString(R.string.unknown) : Math.round(litres) + " L", litresX, litresY, text);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(dateSize);
            c.drawText(dateText, dateX, dateY, text);
        }
    }

    /** Lights a meter's segments up to share (0 to 1; below zero, none). */
    private void level(Canvas c, RectF[] bar, float share, int color) {
        if (share < 0) return;
        int n = Math.max(1, Math.round(Math.min(1f, share) * SEGMENTS));
        glow(color);
        for (int i = 0; i < n; i++) c.drawRect(bar[i], lit);
    }

    /** A fuel pump: its body with a window, the hose from its side. */
    private void pumpIcon(Canvas c, RectF r, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        if (color == RED) p.setShadowLayer(r.height() * 0.2f, 0, 0, RED);
        float s = r.height(), cx = r.centerX() - s * 0.08f, cy = r.centerY();
        Path body = new Path();
        body.addRect(cx - s * 0.25f, cy - s * 0.42f, cx + s * 0.1f, cy + s * 0.42f, Path.Direction.CW);
        body.addRect(cx - s * 0.18f, cy - s * 0.32f, cx + s * 0.03f, cy - s * 0.1f, Path.Direction.CCW);
        body.setFillType(Path.FillType.EVEN_ODD);
        c.drawPath(body, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.07f);
        shape.reset();
        shape.moveTo(cx + s * 0.1f, cy - s * 0.2f);
        shape.lineTo(cx + s * 0.27f, cy - s * 0.08f);
        shape.lineTo(cx + s * 0.27f, cy + s * 0.25f);
        c.drawPath(shape, p);
    }

    private void glow(int color) {
        lit.setColor(color);
        lit.setShadowLayer(getHeight() * 0.014f, 0, 0, color);
    }

    private void unlit(Canvas c, Figures f, int color) {
        dim.setColor(ghost(color));
        f.ghost(c, dim);
    }

    private static int ghost(int color) {
        return color & 0x00FFFFFF | 0x1E000000;
    }
}
