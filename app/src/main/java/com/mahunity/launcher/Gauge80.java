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
 * The 80s digital dash, after a Corvette C4: liquid-crystal screens under smoked glass, set deep in a black
 * surround. On the left the speedometer: a band of green segments that climbs from the lower left and bends over
 * to the upper right, amber numerals beside it, the speed in large amber seven-segment figures. On the right, as
 * the C4's centre screen: amber-framed boxes for coolant and battery either side of a green fuel column marked F,
 * ½ and E, and the time below. Unlit segments show faintly, as they do on the real thing. With room for one
 * screen: the speedometer alone, the time in its corner.
 *
 * Everything that does not change is drawn once into a bitmap, the unlit segments with it; each frame lights some.
 */
final class Gauge80 extends GaugeView {
    private static final int GREEN = 0xFF5CE860, AMBER = 0xFFFFB420, RED = 0xFFFF3D2E;
    private static final int BAND = 40, STRIPES = 10;
    /** Segments a to g lit for 0 to 9; a dash; all of them. */
    private static final int[] DIGITS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
    private static final int DASH = 0x40, BLANK = 0;
    /** The band's outer edge: a part of an ellipse, from straight up at the bottom to this many degrees round. */
    private static final float BEND = 60f;

    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint numeral = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF speedBox = new RectF(), infoBox = new RectF();
    private final Path[] band = new Path[BAND];
    private final RectF[] stripes = new RectF[STRIPES];
    private final Figures speed = new Figures(3, -1), time = new Figures(4, 1), heat = new Figures(3, -1),
            volts = new Figures(3, 1);
    private final String unit, coolantLabel, batteryLabel;
    private final float[] fuelText = new float[3];
    private boolean wide;
    private float radius, dateX, dateY;
    private Bitmap layer;

    Gauge80(Context context) {
        super(context);
        unit = Text.upper(context.getString(R.string.kmh));
        coolantLabel = Text.upper(context.getString(R.string.veh_coolant));
        batteryLabel = Text.upper(context.getString(R.string.veh_battery));
        Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        numeral.setTypeface(bold);
        numeral.setColor(AMBER);
        numeral.setTextAlign(Paint.Align.CENTER);
        caption.setTypeface(bold);
        caption.setColor(AMBER);
        caption.setLetterSpacing(0.06f);
        for (int i = 0; i < STRIPES; i++) stripes[i] = new RectF();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        wide = w >= h * 1.6f;
        float inset = h * 0.035f, gap = h * 0.05f;
        radius = h * 0.05f;
        if (wide) {
            float sw = Math.min((w - 2 * inset - gap) * 0.54f, h * 1.35f);
            speedBox.set(inset, inset, inset + sw, h - inset);
            infoBox.set(speedBox.right + gap, inset, w - inset, h - inset);
        } else {
            speedBox.set(inset, inset, w - inset, h - inset);
            infoBox.setEmpty();
        }
        lit.setShadowLayer(h * 0.018f, 0, 0, GREEN);

        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(layer);
        screen(c, speedBox);
        speedometer(c);
        if (wide) {
            screen(c, infoBox);
            info(c);
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (layer == null) return;
        c.drawBitmap(layer, 0, 0, null);

        int on = known ? Math.round(shown / MAX * BAND) : 0;
        for (int i = 0; i < on; i++) glow(c, band[i], bandColor(i));
        int s = Math.round(shown);
        speed.draw(c, this, new int[] {
            !known ? DASH : s < 100 ? BLANK : DIGITS[s / 100 % 10],
            !known ? DASH : s < 10 ? BLANK : DIGITS[s / 10 % 10],
            !known ? DASH : DIGITS[s % 10],
        }, AMBER);
        time.draw(c, this, clockDigits(), AMBER);

        if (wide) {
            if (Float.isNaN(coolant)) {
                heat.draw(c, this, new int[] {BLANK, DASH, DASH}, AMBER);
            } else {
                int t = Math.max(0, Math.min(199, Math.round(coolant)));
                heat.draw(c, this, new int[] {t < 100 ? BLANK : DIGITS[1], t < 10 ? BLANK : DIGITS[t / 10 % 10], DIGITS[t % 10]},
                        coolant >= 115 ? RED : AMBER);
            }
            if (battery <= 0) {
                volts.draw(c, this, new int[] {DASH, DASH, DASH}, AMBER);
            } else {
                int v = Math.max(0, Math.min(199, Math.round(battery * 10)));
                volts.draw(c, this, new int[] {v < 100 ? BLANK : DIGITS[v / 100], DIGITS[v / 10 % 10], DIGITS[v % 10]},
                        battery < 11.5f ? RED : AMBER);
            }
            if (fuelShare >= 0) {
                int n = Math.round(fuelShare * STRIPES);
                int color = fuelShare < 0.1f ? RED : fuelShare < 0.2f ? AMBER : GREEN;
                for (int i = 0; i < n; i++) {
                    lit.setColor(color);
                    lit.setShadowLayer(radius * 0.35f, 0, 0, color);
                    c.drawRect(stripes[i], lit);
                }
            }
            caption.setTextSize(fuelText[2]);
            caption.setTextAlign(Paint.Align.CENTER);
            c.drawText(litres < 0 ? getContext().getString(R.string.unknown) : Math.round(litres) + " L", fuelText[0], fuelText[1], caption);
            caption.setTextAlign(Paint.Align.LEFT);
            c.drawText(dateText, dateX, dateY, caption);
        }
        // Smoked glass over each screen.
        sheen(c, speedBox);
        if (wide) sheen(c, infoBox);
    }

    /** The time's figures from the text the clock shows (24 or 12 hours), right aligned. */
    private int[] clockDigits() {
        int[] out = {BLANK, BLANK, BLANK, BLANK};
        int k = 3;
        for (int i = timeText.length() - 1; i >= 0 && k >= 0; i--) {
            int d = Character.digit(timeText.charAt(i), 10);
            if (d >= 0) out[k--] = DIGITS[d];
        }
        return out;
    }

    private int bandColor(int i) {
        float v = MAX * (i + 0.5f) / BAND;
        return v < 130 ? GREEN : v < 170 ? AMBER : RED;
    }

    private void glow(Canvas c, Path p, int color) {
        lit.setColor(color);
        lit.setShadowLayer(radius * 0.35f, 0, 0, color);
        c.drawPath(p, lit);
    }

    /** A screen: the bevelled surround it sits in, the dark liquid crystal. */
    private void screen(Canvas c, RectF r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float bezel = radius * 0.5f;
        RectF o = new RectF(r);
        o.inset(-bezel, -bezel);
        p.setShader(new LinearGradient(0, o.top, 0, o.bottom, new int[] {0xFF34383D, 0xFF15171A, 0xFF060708}, null,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(o, radius + bezel, radius + bezel, p);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF0A0C0B, 0xFF020302, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, radius * 0.12f));
        p.setColor(0xFF000000);
        c.drawRoundRect(r, radius, radius, p);
    }

    private void sheen(Canvas c, RectF r) {
        glass.setShader(new LinearGradient(r.left, r.top, r.left + r.width() * 0.55f, r.top + r.height() * 0.8f,
                0x12FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, glass);
    }

    private void speedometer(Canvas c) {
        RectF in = new RectF(speedBox);
        in.inset(speedBox.width() * 0.06f, speedBox.height() * 0.07f);
        float iw = in.width(), ih = in.height();
        // The outer edge runs from the bottom, a fifth of the way in, to the upper right; the band inside it.
        float ax = iw * 1.16f, ay = ih * 0.98f, bx = in.left + iw * 0.22f + ax, by = in.bottom, thick = iw * 0.13f;
        for (int i = 0; i < BAND; i++) {
            double a0 = Math.toRadians(BEND * (i + 0.14f) / BAND), a1 = Math.toRadians(BEND * (i + 0.86f) / BAND);
            Path p = new Path();
            p.moveTo(bx - ax * (float) Math.cos(a0), by - ay * (float) Math.sin(a0));
            p.lineTo(bx - ax * (float) Math.cos(a1), by - ay * (float) Math.sin(a1));
            p.lineTo(bx - (ax - thick) * (float) Math.cos(a1), by - (ay - thick) * (float) Math.sin(a1));
            p.lineTo(bx - (ax - thick) * (float) Math.cos(a0), by - (ay - thick) * (float) Math.sin(a0));
            p.close();
            band[i] = p;
            ghost(c, p, bandColor(i));
        }
        // Beside the band, outside it: a green mark every 10 km/h, longer with a numeral every 20.
        boolean few = ih < 150;
        numeral.setTextSize(ih * 0.085f);
        Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(GREEN);
        mark.setStrokeWidth(Math.max(1.5f, ih * 0.02f));
        float off = iw * 0.025f;
        for (int v = 10; v <= MAX; v += 10) {
            double a = Math.toRadians(BEND * v / MAX);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float px = bx - ax * cos, py = by - ay * sin;
            float nx = -cos / ax, ny = -sin / ay, len = (float) Math.hypot(nx, ny);
            nx /= len;
            ny /= len;
            boolean major = v % 20 == 0;
            float mark1 = off + iw * (major ? 0.045f : 0.022f);
            c.drawLine(px + nx * off, py + ny * off, px + nx * mark1, py + ny * mark1, mark);
            if (!major || few && v % 40 != 0) continue;
            String s = String.valueOf(v);
            float tw = numeral.measureText(s), th = numeral.getTextSize() * 0.72f;
            float d = mark1 + iw * 0.02f + Math.abs(nx) * tw / 2 + Math.abs(ny) * th / 2;
            c.drawText(s, px + nx * d, py + ny * d + th / 2, numeral);
        }

        // The speed in the lower right, KM/H under it; as tall as fits clear of the band.
        caption.setTextSize(ih * 0.075f);
        caption.setTextAlign(Paint.Align.RIGHT);
        c.drawText(unit, in.right, in.bottom, caption);
        float baseline = in.bottom - caption.getTextSize() * 1.4f;
        float fh = Math.min(ih * 0.34f, iw * 0.46f / Figures.span(3, false));
        while (fh > ih * 0.12f) {
            float left = in.right - fh * Figures.span(3, false), top = baseline - fh;
            // Where the band's inner edge is at the figures' top line.
            float e = Math.min(1f, (by - top) / (ay - thick));
            float edge = bx - (ax - thick) * (float) Math.sqrt(1 - e * e);
            if (left > edge + iw * 0.05f) break;
            fh *= 0.95f;
        }
        speed.layout(in.right - fh * Figures.span(3, false), baseline, fh);
        speed.ghost(c, this, AMBER);

        // On its own, the screen keeps the time in its upper left, where the band leaves room.
        if (!wide) {
            float th = ih * 0.13f;
            time.layout(in.left, in.top + th, th);
            time.ghost(c, this, AMBER);
        }
    }

    private void info(Canvas c) {
        RectF in = new RectF(infoBox);
        in.inset(infoBox.width() * 0.06f, infoBox.height() * 0.07f);
        float iw = in.width(), ih = in.height();
        // Two boxes, one over the other, the fuel column beside them; the time under it all.
        float colW = iw * 0.09f, markW = iw * 0.1f, g = iw * 0.05f;
        float rowsH = ih * 0.56f, rowGap = ih * 0.05f, rowH = (rowsH - rowGap) / 2, boxRight = in.right - colW - markW - g;
        box(c, new RectF(in.left, in.top, boxRight, in.top + rowH), coolantLabel, "°C", heat, false);
        box(c, new RectF(in.left, in.top + rowH + rowGap, boxRight, in.top + rowsH), batteryLabel, "V", volts, true);

        // The fuel column, its marks beside it, the litres under it.
        float colX = boxRight + g, litresSize = ih * 0.075f;
        float colTop = in.top, colBottom = in.top + rowsH - litresSize * 1.5f;
        float step = (colBottom - colTop) / STRIPES;
        dim.setColor(ghostColor(GREEN));
        for (int i = 0; i < STRIPES; i++) {
            float b = colBottom - i * step;
            stripes[i].set(colX, b - step * 0.72f, colX + colW, b);
            c.drawRect(stripes[i], dim);
        }
        Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(GREEN);
        mark.setStrokeWidth(Math.max(1.5f, ih * 0.015f));
        caption.setTextSize(ih * 0.07f);
        caption.setTextAlign(Paint.Align.LEFT);
        String[] marks = {"E", "½", "F"};
        for (int k = 0; k < 3; k++) {
            float y = colBottom - step * 0.36f - (colBottom - colTop - step * 0.72f) * k / 2f;
            float x = colX + colW + markW * 0.1f;
            c.drawLine(x, y, x + markW * 0.3f, y, mark);
            c.drawText(marks[k], x + markW * 0.42f, y + caption.getTextSize() * 0.36f, caption);
        }
        fuelText[0] = colX + (colW + markW) / 2;
        fuelText[1] = in.top + rowsH;
        fuelText[2] = litresSize;

        // The date, and the time large under it.
        float dateSize = ih * 0.075f;
        dateX = in.left;
        dateY = in.top + rowsH + ih * 0.08f + dateSize * 0.8f;
        float fh = Math.min(in.bottom - dateY - ih * 0.06f, iw / Figures.span(4, true));
        time.layout(in.left, in.bottom, fh);
        time.ghost(c, this, AMBER);
    }

    /** An amber-framed box: its name at the left, the figures at the right, the unit small beside them. */
    private void box(Canvas c, RectF r, String name, String unitText, Figures f, boolean point) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, r.height() * 0.03f));
        p.setColor(AMBER);
        float rr = r.height() * 0.14f;
        c.drawRoundRect(r, rr, rr, p);
        float pad = r.height() * 0.16f, sep = pad * 0.3f;
        caption.setTextAlign(Paint.Align.LEFT);
        caption.setTextSize(r.height() * 0.2f);
        float uw = caption.measureText(unitText);
        c.drawText(unitText, r.right - pad - uw, r.top + pad + caption.getTextSize() * 0.75f, caption);
        // The figures as tall as the box allows, unless that leaves the name too little room.
        float span = Figures.span(3, point), nameSize = r.height() * 0.24f;
        caption.setTextSize(nameSize);
        float nameW = caption.measureText(name), minName = nameW * 0.6f;
        float fh = Math.min(r.height() - 2 * pad, (r.width() - 3 * pad - sep - uw - minName) / span);
        float room = r.width() - 3 * pad - sep - uw - fh * span;
        if (nameW > room) caption.setTextSize(nameSize * room / nameW);
        c.drawText(name, r.left + pad, r.bottom - pad, caption);
        f.layout(r.right - pad - uw - sep - fh * span, r.bottom - pad, fh);
        f.ghost(c, this, AMBER);
    }

    private void ghost(Canvas c, Path p, int color) {
        dim.setColor(ghostColor(color));
        c.drawPath(p, dim);
    }

    private static int ghostColor(int color) {
        return color & 0x00FFFFFF | 0x24000000;
    }

    /**
     * A row of seven-segment figures: their shapes for one height, where each stands, and the colon or decimal
     * point after one of them.
     */
    private static final class Figures {
        private static final float ASPECT = 0.54f, SPACING = 0.22f, SEPARATOR = 0.42f;
        final float[] xs;
        final Path[] segments = new Path[7];
        final int after;
        float y, h;

        /** n figures; after: the index of the one the separator follows, or -1. */
        Figures(int n, int after) {
            xs = new float[n];
            this.after = after;
        }

        /** The width of n figures, with a separator or not, for a height of one. */
        static float span(int n, boolean separator) {
            return ASPECT * (n + (n - 1) * SPACING + (separator ? SEPARATOR : 0));
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

        void ghost(Canvas c, Gauge80 g, int color) {
            int[] all = new int[xs.length];
            java.util.Arrays.fill(all, 0x7F);
            g.dim.setColor(ghostColor(color));
            paint(c, all, g.dim, true);
        }

        void draw(Canvas c, Gauge80 g, int[] masks, int color) {
            g.lit.setColor(color);
            g.lit.setShadowLayer(h * 0.08f, 0, 0, color);
            paint(c, masks, g.lit, false);
        }

        private void paint(Canvas c, int[] masks, Paint p, boolean separator) {
            float w = h * ASPECT;
            for (int k = 0; k < xs.length; k++) {
                c.save();
                c.translate(xs[k], y);
                for (int s = 0; s < 7; s++) if ((masks[k] >> s & 1) == 1) c.drawPath(segments[s], p);
                c.restore();
            }
            // The separator is always lit once the figures are: the colon of a clock, the point of a voltmeter.
            if (after < 0 || !separator && (masks[after + 1] == BLANK || masks[after + 1] == DASH)) return;
            float x = xs[after] + w + w * (SPACING + SEPARATOR) / 2, dot = w * 0.21f;
            if (xs.length == 4) {
                c.drawRect(x - dot / 2, y - h * 0.72f - dot / 2, x + dot / 2, y - h * 0.72f + dot / 2, p);
                c.drawRect(x - dot / 2, y - h * 0.28f - dot / 2, x + dot / 2, y - h * 0.28f + dot / 2, p);
            } else {
                c.drawRect(x - dot / 2, y - dot, x + dot / 2, y, p);
            }
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
}
