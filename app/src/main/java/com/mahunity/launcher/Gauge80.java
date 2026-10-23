package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;

/**
 * The 80s digital dash, after a Corvette C4 or a Camaro Berlinetta, on a VHS screen: a bar graph of segments
 * that rise towards the right (green, then yellow, then red), the speed in large slanted seven-segment digits
 * glowing amber with the colour fringes of a worn tape, the time in cyan, the tape's on-screen text, scanlines.
 * Unlit segments show faintly, as on a real display.
 */
final class Gauge80 extends GaugeView {
    private static final int GREEN = 0xFF39FF88, YELLOW = 0xFFFFE03A, RED = 0xFFFF3B30;
    private static final int AMBER = 0xFFFFB000, CYAN = 0xFF38E8FF, MAGENTA = 0xFFFF3EA5;
    private static final int BARS = 36;
    /** Segments a to g of each digit, lit for 0 to 9, then a dash. */
    private static final int[] DIGITS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F, 0x40};
    private static final int DASH = 10;
    private static final float SLANT = -0.12f;

    private final Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scan = new Paint();
    private final TextPaint osd = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint scale = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unitPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path[] bars = new Path[BARS];
    private final Path[] big = new Path[7], small = new Path[7];
    private final String unit;
    private float bigW, bigH, smallW, smallH, gap, digitsX, digitsY, unitX, timeX, timeY, barTop, barBottom, scaleY, radius;

    Gauge80(Context context) {
        super(context);
        unit = Text.upper(context.getString(R.string.kmh));
        frame.setStyle(Paint.Style.STROKE);
        frame.setColor(0xFF23262B);
        scan.setColor(0x38000000);
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        osd.setTypeface(mono);
        osd.setColor(0xFFF2F4F7);
        scale.setTypeface(mono);
        scale.setColor(CYAN);
        scale.setTextAlign(Paint.Align.CENTER);
        unitPaint.setTypeface(mono);
        unitPaint.setColor(MAGENTA);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float inset = Math.min(w, h) * 0.02f;
        box.set(inset, inset, w - inset, h - inset);
        radius = Math.min(w, h) * 0.05f;
        screen.setShader(new LinearGradient(0, box.top, 0, box.bottom, 0xFF0A0C10, 0xFF030405, Shader.TileMode.CLAMP));
        frame.setStrokeWidth(Math.max(1f, h * 0.008f));
        float pad = box.height() * 0.08f;
        osd.setTextSize(box.height() * 0.085f);
        osd.setShadowLayer(osd.getTextSize() * 0.15f, 1, 1, 0xFF000000);

        // The bar graph: thin slanted bars, each a little taller than the one before.
        barTop = box.top + pad + osd.getTextSize() * 1.2f;
        barBottom = barTop + box.height() * 0.26f;
        float left = box.left + pad, right = box.right - pad;
        float step = (right - left) / BARS, bw = step * 0.62f, lean = (barBottom - barTop) * 0.2f;
        for (int i = 0; i < BARS; i++) {
            float x = left + i * step;
            float top = barBottom - (barBottom - barTop) * (0.3f + 0.7f * i / (BARS - 1));
            Path p = new Path();
            p.moveTo(x, barBottom);
            p.lineTo(x + lean * (barBottom - top) / (barBottom - barTop), top);
            p.lineTo(x + bw + lean * (barBottom - top) / (barBottom - barTop), top);
            p.lineTo(x + bw, barBottom);
            p.close();
            bars[i] = p;
        }
        scale.setTextSize(box.height() * 0.07f);
        scaleY = barBottom + scale.getTextSize() * 1.25f;

        // Digits along the bottom: the speed large on the left, its unit, the time smaller on the right. As tall
        // as the room under the scale allows, and narrow enough that the row fits: three digits with their gaps
        // and slant come to about 2.1 heights, the time to about 1.5, the spaces between to 0.15.
        unitPaint.setTextSize(box.height() * 0.075f);
        float unitW = unitPaint.measureText(unit);
        float roomH = box.bottom - pad - (scaleY + scale.getTextSize() * 0.6f);
        float roomW = right - left - unitW;
        bigH = Math.max(1f, Math.min(roomH, roomW / 3.75f));
        bigW = bigH * 0.52f;
        gap = bigW * 0.32f;
        buildDigit(big, bigW, bigH);
        smallH = bigH * 0.5f;
        smallW = smallH * 0.52f;
        buildDigit(small, smallW, smallH);
        digitsX = left;
        digitsY = box.bottom - pad;
        unitX = digitsX + 3 * bigW + 2 * gap + bigH * (0.12f + 0.08f);
        timeY = digitsY;
        timeX = right - (4 * smallW + 3 * smallW * 0.3f + smallW * 0.6f + smallH * 0.12f);
    }

    /** The seven segments of one digit, width w, height h, origin at its bottom left, before the slant. */
    private static void buildDigit(Path[] out, float w, float h) {
        float t = w * 0.2f, half = h / 2f;
        out[0] = horizontal(0, w, -h, t);
        out[1] = vertical(w, -h, -half, t);
        out[2] = vertical(w, -half, 0, t);
        out[3] = horizontal(0, w, 0, t);
        out[4] = vertical(0, -half, 0, t);
        out[5] = vertical(0, -h, -half, t);
        out[6] = horizontal(0, w, -half, t);
    }

    private static Path horizontal(float x0, float x1, float y, float t) {
        float g = t * 0.6f;
        Path p = new Path();
        p.moveTo(x0 + g, y);
        p.lineTo(x0 + g + t / 2, y - t / 2);
        p.lineTo(x1 - g - t / 2, y - t / 2);
        p.lineTo(x1 - g, y);
        p.lineTo(x1 - g - t / 2, y + t / 2);
        p.lineTo(x0 + g + t / 2, y + t / 2);
        p.close();
        return p;
    }

    private static Path vertical(float x, float y0, float y1, float t) {
        float g = t * 0.6f;
        Path p = new Path();
        p.moveTo(x, y0 + g);
        p.lineTo(x + t / 2, y0 + g + t / 2);
        p.lineTo(x + t / 2, y1 - g - t / 2);
        p.lineTo(x, y1 - g);
        p.lineTo(x - t / 2, y1 - g - t / 2);
        p.lineTo(x - t / 2, y0 + g + t / 2);
        p.close();
        return p;
    }

    @Override protected void onDraw(Canvas c) {
        if (box.isEmpty()) return;
        c.drawRoundRect(box, radius, radius, screen);
        c.save();
        c.clipRect(box);

        // On-screen text, as a VCR puts it over the picture.
        float pad = box.height() * 0.08f;
        c.drawText("▶ PLAY", box.left + pad, box.top + pad + osd.getTextSize() * 0.8f, osd);
        osd.setTextAlign(Paint.Align.RIGHT);
        c.drawText(dateText, box.right - pad, box.top + pad + osd.getTextSize() * 0.8f, osd);
        osd.setTextAlign(Paint.Align.LEFT);

        int lit = known ? Math.round(shown / MAX * BARS) : 0;
        for (int i = 0; i < BARS; i++) {
            int color = i < BARS * 0.6f ? GREEN : i < BARS * 0.82f ? YELLOW : RED;
            if (i < lit) {
                seg.setColor(color);
                seg.setShadowLayer(bigW * 0.12f, 0, 0, color);
            } else {
                seg.setColor(color & 0x00FFFFFF | 0x1A000000);
                seg.clearShadowLayer();
            }
            c.drawPath(bars[i], seg);
        }
        seg.clearShadowLayer();
        float left = box.left + pad, step = (box.width() - 2 * pad) / BARS;
        for (int v = 0; v <= MAX; v += 40) {
            float x = left + step * BARS * v / MAX;
            c.drawText(String.valueOf(v), Math.max(left + scale.getTextSize(), Math.min(box.right - pad - scale.getTextSize(), x)),
                    scaleY, scale);
        }

        // The speed: three digits, no leading zeros; with the tape's colour fringes either side.
        int[] d = new int[3];
        boolean[] blank = new boolean[3];
        if (known) {
            int s = Math.round(shown);
            d[0] = s / 100;
            d[1] = s / 10 % 10;
            d[2] = s % 10;
            blank[0] = s < 100;
            blank[1] = s < 10;
        } else {
            d[0] = d[1] = d[2] = DASH;
        }
        for (int k = 0; k < 3; k++) {
            float x = digitsX + k * (bigW + gap);
            int mask = blank[k] ? 0 : DIGITS[d[k]];
            digit(c, big, x - bigH * 0.012f, digitsY, mask, CYAN & 0x00FFFFFF | 0x60000000, false);
            digit(c, big, x + bigH * 0.012f, digitsY, mask, MAGENTA & 0x00FFFFFF | 0x60000000, false);
            digit(c, big, x, digitsY, mask, AMBER, true);
        }
        c.drawText(unit, unitX, digitsY, unitPaint);

        // The time: four small digits and a colon.
        float sgap = smallW * 0.3f;
        int[] t = {hour / 10, hour % 10, minute / 10, minute % 10};
        float x = timeX;
        for (int k = 0; k < 4; k++) {
            if (k == 2) {
                seg.setColor(CYAN);
                float dot = smallW * 0.16f, cx = x + smallW * 0.15f;
                c.drawRect(cx, timeY - smallH * 0.72f, cx + dot, timeY - smallH * 0.72f + dot, seg);
                c.drawRect(cx - dot * 0.4f, timeY - smallH * 0.32f, cx + dot * 0.6f, timeY - smallH * 0.32f + dot, seg);
                x += smallW * 0.6f;
            }
            digit(c, small, x, timeY, DIGITS[t[k]], CYAN, true);
            x += smallW + sgap;
        }

        // Scanlines over it all.
        float line = Math.max(2f, box.height() / 70f);
        for (float y = box.top; y < box.bottom; y += line * 1.6f) c.drawRect(box.left, y, box.right, y + line * 0.5f, scan);
        c.restore();
        c.drawRoundRect(box, radius, radius, frame);
    }

    /** One digit at x (its left) and y (its baseline): lit segments in colour, the rest faint (when ghosts). */
    private void digit(Canvas c, Path[] segments, float x, float y, int mask, int color, boolean ghosts) {
        c.save();
        c.translate(x, y);
        c.skew(SLANT, 0);
        for (int s = 0; s < 7; s++) {
            boolean on = (mask >> s & 1) == 1;
            if (on) {
                seg.setColor(color);
                if (ghosts) seg.setShadowLayer((segments == big ? bigW : smallW) * 0.14f, 0, 0, color);
            } else if (ghosts) {
                seg.setColor(color & 0x00FFFFFF | 0x16000000);
                seg.clearShadowLayer();
            } else {
                continue;
            }
            c.drawPath(segments[s], seg);
        }
        seg.clearShadowLayer();
        c.restore();
    }
}
