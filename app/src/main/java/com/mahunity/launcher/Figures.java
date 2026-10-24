package com.mahunity.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * A row of seven-segment figures, for the 80s dashes: their shapes for one height, where each stands, the colon
 * or decimal point after one of them, an optional lean. Callers paint the unlit segments once (ghost) and the lit
 * ones each frame (draw), with their own paint.
 */
final class Figures {
    /** Segments a to g lit for 0 to 9. */
    static final int[] DIGITS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
    static final int DASH = 0x40, BLANK = 0, ALL = 0x7F;
    private static final float ASPECT = 0.54f, SPACING = 0.22f, SEPARATOR = 0.42f;

    final float[] xs;
    private final Path[] segments = new Path[7];
    private final int after;
    private final float slant;
    float y, h;

    /** n figures; after: the index of the one a colon (four figures) or a point follows, or -1; slant: the lean. */
    Figures(int n, int after, float slant) {
        xs = new float[n];
        this.after = after;
        this.slant = slant;
    }

    /** The width of n figures, with a separator or not, for a height of one; a lean adds to it. */
    static float span(int n, boolean separator, float slant) {
        return ASPECT * (n + (n - 1) * SPACING + (separator ? SEPARATOR : 0)) + Math.abs(slant);
    }

    float width() {
        return h * span(xs.length, after >= 0, slant);
    }

    void layout(float left, float baseline, float height) {
        h = height;
        y = baseline;
        float w = h * ASPECT, gap = w * SPACING, x = left;
        for (int k = 0; k < xs.length; k++) {
            xs[k] = x;
            x += w + gap;
            if (k == after) x += w * SEPARATOR;
        }
        // Each segment runs between the middles of the ones it meets, so the figure fills h by w exactly.
        float t = w * 0.21f, top = -h + t / 2, mid = -h / 2, bottom = -t / 2, l = t / 2, r = w - t / 2;
        segments[0] = horizontal(l, r, top, t);
        segments[1] = vertical(r, top, mid, t);
        segments[2] = vertical(r, mid, bottom, t);
        segments[3] = horizontal(l, r, bottom, t);
        segments[4] = vertical(l, mid, bottom, t);
        segments[5] = vertical(l, top, mid, t);
        segments[6] = horizontal(l, r, mid, t);
    }

    /** Every segment and the separator: the unlit display. */
    void ghost(Canvas c, Paint p) {
        int[] all = new int[xs.length];
        java.util.Arrays.fill(all, ALL);
        paint(c, all, p, true);
    }

    /** The lit segments; the separator too, once the figure after it shows a digit. */
    void draw(Canvas c, int[] masks, Paint p) {
        paint(c, masks, p, false);
    }

    /** value in n figures, right aligned, without leading zeros; below zero, dashes. */
    static int[] number(int value, int n) {
        int[] out = new int[n];
        for (int k = n - 1; k >= 0; k--) {
            if (value < 0) {
                out[k] = DASH;
            } else {
                boolean lead = k < n - 1 && value == 0;
                out[k] = lead ? BLANK : DIGITS[value % 10];
                value /= 10;
            }
        }
        return out;
    }

    /** A clock's four figures from the text it shows (24 or 12 hours), right aligned. */
    static int[] clock(String time) {
        int[] out = {BLANK, BLANK, BLANK, BLANK};
        int k = 3;
        for (int i = time.length() - 1; i >= 0 && k >= 0; i--) {
            int d = Character.digit(time.charAt(i), 10);
            if (d >= 0) out[k--] = DIGITS[d];
        }
        return out;
    }

    private void paint(Canvas c, int[] masks, Paint p, boolean separator) {
        float w = h * ASPECT;
        c.save();
        c.translate(0, y);
        c.skew(slant, 0);
        for (int k = 0; k < xs.length; k++) {
            c.save();
            c.translate(xs[k], 0);
            for (int s = 0; s < 7; s++) if ((masks[k] >> s & 1) == 1) c.drawPath(segments[s], p);
            c.restore();
        }
        boolean shown = after >= 0 && (separator || masks[after + 1] != BLANK && masks[after + 1] != DASH);
        if (shown) {
            float x = xs[after] + w + w * (SPACING + SEPARATOR) / 2, dot = w * 0.21f;
            if (xs.length == 4) {
                c.drawRect(x - dot / 2, -h * 0.72f - dot / 2, x + dot / 2, -h * 0.72f + dot / 2, p);
                c.drawRect(x - dot / 2, -h * 0.28f - dot / 2, x + dot / 2, -h * 0.28f + dot / 2, p);
            } else {
                c.drawRect(x - dot / 2, -dot, x + dot / 2, 0, p);
            }
        }
        c.restore();
    }

    private static Path horizontal(float x0, float x1, float y, float t) {
        float g = t * 0.3f;
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
        float g = t * 0.3f;
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
}
