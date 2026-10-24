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
 * surround, three as in the car. On the left the speedometer: a band of green segments that climbs from the lower
 * left and bends over to the upper right, amber numerals beside it, the speed in large amber seven-segment
 * figures. On the right the tachometer, the same band turning amber and then red towards 7000 rpm, the revs in
 * hundreds. Between them, as the C4's centre screen: amber-framed boxes for coolant and battery, a green fuel bar
 * marked E, ½ and F with the litres, and the time. Unlit segments show faintly, as they do on the real thing.
 * Narrower, the centre screen goes (the time is in the top bar); narrower still, the tachometer too, and the time
 * comes back in the speedometer's corner.
 *
 * Everything that does not change is drawn once into a bitmap, the unlit segments with it; each frame lights some.
 */
final class Gauge80 extends GaugeView {
    private static final int GREEN = 0xFF5CE860, AMBER = 0xFFFFB420, RED = 0xFFFF3D2E;
    private static final int STRIPES = 10;
    /** The band's outer edge: a part of an ellipse, from straight up at the bottom to this many degrees round. */
    private static final float BEND = 60f;

    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint numeral = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF infoBox = new RectF();
    private final Band speedBand = new Band(40, 3, false), tachBand = new Band(28, 2, true);
    private final RectF[] stripes = new RectF[STRIPES];
    private final Figures time = new Figures(4, 1, 0), heat = new Figures(3, -1, 0), volts = new Figures(3, 1, 0);
    private final String unit, revsUnit, coolantLabel, batteryLabel;
    private final float[] fuelText = new float[3];
    /** Which screens there are room for: the centre one, the tachometer. */
    private boolean info, tach;
    private float radius;
    private Bitmap layer;

    Gauge80(Context context) {
        super(context);
        unit = Text.upper(context.getString(R.string.kmh));
        revsUnit = Text.upper(context.getString(R.string.rpm_hundreds));
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
        info = w >= h * 1.6f;
        tach = w >= h * 1.15f;
        float inset = h * 0.035f, gap = h * 0.05f, top = inset, bottom = h - inset;
        radius = h * 0.05f;
        if (info) {
            float avail = w - 2 * inset - 2 * gap, sw = Math.min(avail * 0.35f, h * 1.35f);
            speedBand.box.set(inset, top, inset + sw, bottom);
            tachBand.box.set(w - inset - sw, top, w - inset, bottom);
            infoBox.set(speedBand.box.right + gap, top, tachBand.box.left - gap, bottom);
        } else if (tach) {
            float sw = (w - 2 * inset - gap) / 2;
            speedBand.box.set(inset, top, inset + sw, bottom);
            tachBand.box.set(w - inset - sw, top, w - inset, bottom);
        } else {
            speedBand.box.set(inset, top, w - inset, bottom);
        }

        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(layer);
        screen(c, speedBand.box);
        speedBand.build(c, unit);
        if (tach) {
            screen(c, tachBand.box);
            tachBand.build(c, revsUnit);
        }
        if (info) {
            screen(c, infoBox);
            info(c);
        } else if (!tach) {
            // The time in the speedometer's upper left, where the band leaves room on a screen this wide.
            RectF in = speedBand.inner();
            float th = in.height() * 0.13f;
            time.layout(in.left, in.top + th, th);
            unlit(c, time, AMBER);
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (layer == null) return;
        c.drawBitmap(layer, 0, 0, null);

        speedBand.light(c, known ? shown / MAX : 0f, known ? Math.round(shown) : -1);
        if (tach) tachBand.light(c, revsKnown ? revs / RPM_MAX : 0f, revsKnown ? Math.round(revs / 100f) : -1);
        if (info || !tach) light(c, time, Figures.clock(timeText), AMBER);

        if (info) {
            int t = Float.isNaN(coolant) ? -1 : Math.max(0, Math.min(199, Math.round(coolant)));
            light(c, heat, Figures.number(t, 3), coolant >= 115 ? RED : AMBER);
            int v = battery <= 0 ? -1 : Math.min(199, Math.round(battery * 10));
            light(c, volts, Figures.number(v, 3), battery > 0 && battery < 11.5f ? RED : AMBER);
            if (fuelShare >= 0) {
                int n = Math.round(fuelShare * STRIPES);
                int color = fuelShare < 0.1f ? RED : fuelShare < 0.2f ? AMBER : GREEN;
                lit.setColor(color);
                lit.setShadowLayer(radius * 0.35f, 0, 0, color);
                for (int i = 0; i < n; i++) c.drawRect(stripes[i], lit);
            }
            lit.clearShadowLayer();
            caption.setTextSize(fuelText[2]);
            caption.setTextAlign(Paint.Align.RIGHT);
            c.drawText(litres < 0 ? getContext().getString(R.string.unknown) : Math.round(litres) + " L", fuelText[0], fuelText[1], caption);
        }
        // Smoked glass over each screen.
        sheen(c, speedBand.box);
        if (tach) sheen(c, tachBand.box);
        if (info) sheen(c, infoBox);
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

    /**
     * A screen with a band of segments, the speedometer's or the tachometer's: the band, the marks and numerals
     * outside it, the figures in the lower right and the unit under them.
     */
    private final class Band {
        final RectF box = new RectF();
        final Path[] segments;
        final Figures figures;
        final boolean revs;

        Band(int count, int digits, boolean revs) {
            segments = new Path[count];
            figures = new Figures(digits, -1, 0);
            this.revs = revs;
        }

        RectF inner() {
            RectF in = new RectF(box);
            in.inset(box.width() * 0.06f, box.height() * 0.07f);
            return in;
        }

        /** Green, then amber, then red: from 130 and 170 km/h, or from 4500 and 6000 rpm. */
        int color(int i) {
            float f = (i + 0.5f) / segments.length;
            if (revs) return f * RPM_MAX < 4500 ? GREEN : f * RPM_MAX < REDLINE ? AMBER : RED;
            return f * MAX < 130 ? GREEN : f * MAX < 170 ? AMBER : RED;
        }

        void build(Canvas c, String unitText) {
            RectF in = inner();
            float iw = in.width(), ih = in.height();
            // The outer edge runs from the bottom, a fifth of the way in, to the upper right; the band inside it.
            float ax = iw * 1.16f, ay = ih * 0.98f, bx = in.left + iw * 0.22f + ax, by = in.bottom, thick = iw * 0.13f;
            int n = segments.length;
            for (int i = 0; i < n; i++) {
                double a0 = Math.toRadians(BEND * (i + 0.14f) / n), a1 = Math.toRadians(BEND * (i + 0.86f) / n);
                Path p = new Path();
                p.moveTo(bx - ax * (float) Math.cos(a0), by - ay * (float) Math.sin(a0));
                p.lineTo(bx - ax * (float) Math.cos(a1), by - ay * (float) Math.sin(a1));
                p.lineTo(bx - (ax - thick) * (float) Math.cos(a1), by - (ay - thick) * (float) Math.sin(a1));
                p.lineTo(bx - (ax - thick) * (float) Math.cos(a0), by - (ay - thick) * (float) Math.sin(a0));
                p.close();
                segments[i] = p;
                dim.setColor(ghostColor(color(i)));
                c.drawPath(p, dim);
            }
            // Beside the band, outside it: a green mark every tenth of the scale (10 km/h, 500 rpm), longer with a
            // numeral every other one; every fourth on a small screen. The tachometer counts in hundreds, as the C4's.
            boolean few = ih < 150;
            numeral.setTextSize(ih * 0.085f);
            Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
            mark.setColor(GREEN);
            mark.setStrokeWidth(Math.max(1.5f, ih * 0.02f));
            float off = iw * 0.025f, top = revs ? RPM_MAX : MAX, step = revs ? 500 : 10;
            for (int k = 1; k * step <= top; k++) {
                double a = Math.toRadians(BEND * k * step / top);
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                float px = bx - ax * cos, py = by - ay * sin;
                float nx = -cos / ax, ny = -sin / ay, len = (float) Math.hypot(nx, ny);
                nx /= len;
                ny /= len;
                boolean major = k % 2 == 0;
                float mark1 = off + iw * (major ? 0.045f : 0.022f);
                c.drawLine(px + nx * off, py + ny * off, px + nx * mark1, py + ny * mark1, mark);
                if (!major || few && k % 4 != 0) continue;
                String s = String.valueOf(Math.round(revs ? k * step / 100 : k * step));
                float tw = numeral.measureText(s), th = numeral.getTextSize() * 0.72f;
                float d = mark1 + iw * 0.02f + Math.abs(nx) * tw / 2 + Math.abs(ny) * th / 2;
                c.drawText(s, px + nx * d, py + ny * d + th / 2, numeral);
            }

            // The figures in the lower right, the unit under them; as tall as fits clear of the band.
            caption.setTextSize(ih * 0.075f);
            caption.setTextAlign(Paint.Align.RIGHT);
            float room = iw * 0.6f;
            if (caption.measureText(unitText) > room) caption.setTextSize(caption.getTextSize() * room / caption.measureText(unitText));
            c.drawText(unitText, in.right, in.bottom, caption);
            float baseline = in.bottom - ih * 0.075f * 1.4f;
            int digits = figures.xs.length;
            float span = Figures.span(digits, false, 0);
            float fh = Math.min(ih * 0.34f, iw * 0.46f / Figures.span(3, false, 0));
            while (fh > ih * 0.12f) {
                float left = in.right - fh * span, top0 = baseline - fh;
                // Where the band's inner edge is at the figures' top line.
                float e = Math.min(1f, (by - top0) / (ay - thick));
                float edge = bx - (ax - thick) * (float) Math.sqrt(1 - e * e);
                if (left > edge + iw * 0.05f) break;
                fh *= 0.95f;
            }
            figures.layout(in.right - fh * span, baseline, fh);
            unlit(c, figures, AMBER);
        }

        /** Lights the band up to share of the scale, and the figures with value (below zero: dashes). */
        void light(Canvas c, float share, int value) {
            int on = Math.round(Math.max(0f, Math.min(1f, share)) * segments.length);
            for (int i = 0; i < on; i++) {
                int color = color(i);
                lit.setColor(color);
                lit.setShadowLayer(radius * 0.35f, 0, 0, color);
                c.drawPath(segments[i], lit);
            }
            Gauge80.this.light(c, figures, Figures.number(value, figures.xs.length), AMBER);
        }
    }

    /** The centre screen: two boxes, the fuel bar, the time; one over the other, as the screen is narrow. */
    private void info(Canvas c) {
        RectF in = new RectF(infoBox);
        in.inset(infoBox.width() * 0.07f, infoBox.height() * 0.07f);
        float iw = in.width(), ih = in.height();
        float boxH = ih * 0.25f, gap = ih * 0.04f;
        box(c, new RectF(in.left, in.top, in.right, in.top + boxH), coolantLabel, "°C", heat, false);
        box(c, new RectF(in.left, in.top + boxH + gap, in.right, in.top + 2 * boxH + gap), batteryLabel, "V", volts, true);

        // The fuel bar across, E, ½ and F under it, the litres at its right.
        float barTop = in.top + 2 * boxH + 2.2f * gap, barH = ih * 0.075f, litresSize = ih * 0.075f;
        caption.setTextSize(litresSize);
        float litresW = caption.measureText("45 L") * 1.1f;
        float barLeft = in.left, barRight = in.right - litresW, step = (barRight - barLeft) / STRIPES;
        dim.setColor(ghostColor(GREEN));
        for (int i = 0; i < STRIPES; i++) {
            float l = barLeft + i * step;
            stripes[i].set(l, barTop, l + step * 0.72f, barTop + barH);
            c.drawRect(stripes[i], dim);
        }
        Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(GREEN);
        mark.setStrokeWidth(Math.max(1.5f, ih * 0.012f));
        caption.setTextSize(ih * 0.065f);
        caption.setTextAlign(Paint.Align.CENTER);
        String[] marks = {"E", "½", "F"};
        float span = barRight - barLeft - step * 0.28f;
        for (int k = 0; k < 3; k++) {
            float x = barLeft + span * k / 2f, y = barTop + barH + ih * 0.015f;
            c.drawLine(x, y, x, y + ih * 0.025f, mark);
            c.drawText(marks[k], x, y + ih * 0.025f + caption.getTextSize() * 0.95f, caption);
        }
        fuelText[0] = in.right;
        fuelText[1] = barTop + barH;
        fuelText[2] = litresSize;

        // The time, as large as fits under it all.
        float timeTop = barTop + barH + ih * 0.15f;
        float fh = Math.min(in.bottom - timeTop, iw / Figures.span(4, true, 0));
        time.layout(in.left + (iw - fh * Figures.span(4, true, 0)) / 2, in.bottom, fh);
        unlit(c, time, AMBER);
    }

    /** An amber-framed box: its name over the top at the left, the unit at the right, the figures under them. */
    private void box(Canvas c, RectF r, String name, String unitText, Figures f, boolean point) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, r.height() * 0.03f));
        p.setColor(AMBER);
        float rr = r.height() * 0.14f;
        c.drawRoundRect(r, rr, rr, p);
        float pad = r.height() * 0.12f;
        caption.setTextAlign(Paint.Align.LEFT);
        caption.setTextSize(r.height() * 0.2f);
        float uw = caption.measureText(unitText);
        float nameY = r.top + pad + caption.getTextSize() * 0.75f;
        c.drawText(unitText, r.right - pad - uw, nameY, caption);
        float room = r.width() - 3 * pad - uw;
        if (caption.measureText(name) > room) caption.setTextSize(caption.getTextSize() * room / caption.measureText(name));
        c.drawText(name, r.left + pad, nameY, caption);
        float span = Figures.span(3, point, 0);
        float fh = Math.min(r.bottom - pad - (nameY + pad * 0.6f), (r.width() - 2 * pad) / span);
        f.layout(r.right - pad - fh * span, r.bottom - pad, fh);
        unlit(c, f, AMBER);
    }

    private static int ghostColor(int color) {
        return color & 0x00FFFFFF | 0x24000000;
    }
}
