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
    private final Figures speed = new Figures(3, -1, 0), time = new Figures(4, 1, 0), heat = new Figures(3, -1, 0),
            volts = new Figures(3, 1, 0);
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
        light(c, speed, Figures.number(known ? Math.round(shown) : -1, 3), AMBER);
        light(c, time, Figures.clock(timeText), AMBER);

        if (wide) {
            int t = Float.isNaN(coolant) ? -1 : Math.max(0, Math.min(199, Math.round(coolant)));
            light(c, heat, Figures.number(t, 3), coolant >= 115 ? RED : AMBER);
            int v = battery <= 0 ? -1 : Math.min(199, Math.round(battery * 10));
            light(c, volts, Figures.number(v, 3), battery > 0 && battery < 11.5f ? RED : AMBER);
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

    private void light(Canvas c, Figures f, int[] masks, int color) {
        lit.setColor(color);
        lit.setShadowLayer(f.h * 0.08f, 0, 0, color);
        f.draw(c, masks, lit);
    }

    private void unlit(Canvas c, Figures f, int color) {
        dim.setColor(ghostColor(color));
        f.ghost(c, dim);
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
        float fh = Math.min(ih * 0.34f, iw * 0.46f / Figures.span(3, false, 0));
        while (fh > ih * 0.12f) {
            float left = in.right - fh * Figures.span(3, false, 0), top = baseline - fh;
            // Where the band's inner edge is at the figures' top line.
            float e = Math.min(1f, (by - top) / (ay - thick));
            float edge = bx - (ax - thick) * (float) Math.sqrt(1 - e * e);
            if (left > edge + iw * 0.05f) break;
            fh *= 0.95f;
        }
        speed.layout(in.right - fh * Figures.span(3, false, 0), baseline, fh);
        unlit(c, speed, AMBER);

        // On its own, the screen keeps the time in its upper left, where the band leaves room.
        if (!wide) {
            float th = ih * 0.13f;
            time.layout(in.left, in.top + th, th);
            unlit(c, time, AMBER);
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
        float fh = Math.min(in.bottom - dateY - ih * 0.06f, iw / Figures.span(4, true, 0));
        time.layout(in.left, in.bottom, fh);
        unlit(c, time, AMBER);
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
        float span = Figures.span(3, point, 0), nameSize = r.height() * 0.24f;
        caption.setTextSize(nameSize);
        float nameW = caption.measureText(name), minName = nameW * 0.6f;
        float fh = Math.min(r.height() - 2 * pad, (r.width() - 3 * pad - sep - uw - minName) / span);
        float room = r.width() - 3 * pad - sep - uw - fh * span;
        if (nameW > room) caption.setTextSize(nameSize * room / nameW);
        c.drawText(name, r.left + pad, r.bottom - pad, caption);
        f.layout(r.right - pad - uw - sep - fh * span, r.bottom - pad, fh);
        unlit(c, f, AMBER);
    }

    private void ghost(Canvas c, Path p, int color) {
        dim.setColor(ghostColor(color));
        c.drawPath(p, dim);
    }

    private static int ghostColor(int color) {
        return color & 0x00FFFFFF | 0x24000000;
    }
}
