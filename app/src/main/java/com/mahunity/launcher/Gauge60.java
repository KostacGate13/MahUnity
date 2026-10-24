package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Bitmap;
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

import java.util.Locale;

/**
 * The 60s cluster, after a '66 Charger at night: electroluminescent dials, turquoise numerals and ticks glowing on
 * black, thin red needles, the numerals on a sloped outer ring, each dial in a turned-metal bezel like a Mustang's
 * or a Bel Air's. With room for three, as the Charger's own: a split gauge (coolant above, fuel below, where the
 * Charger has its alternator and fuel), the speedometer, its odometer window reading the speed, and the
 * tachometer, red from 6000 rpm, a window in it reading the time. With room for one: the speedometer, its window
 * reading the time.
 *
 * Everything that does not move is drawn once into a bitmap; each frame adds the needles and the window's figures.
 */
final class Gauge60 extends GaugeView {
    private static final int GLOW = 0xFF62F7EA, GLOW_HALO = 0xC016D6C6, NEEDLE = 0xFFFF3B1F, NEEDLE_HALO = 0xB0FF2400;
    /** The speedometer's scale: 0 at the lower left, over the top, 200 at the lower right; the window in the gap. */
    private static final float START = 120f, SWEEP = 300f;
    /** The split gauge: coolant from C to H across the top, fuel from E to F across the bottom. */
    private static final float COOL_FROM = -140f, FUEL_FROM = 140f, ARC = 100f, COLD = 50f, HOT = 130f;

    private final TextPaint numbers = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint drumText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glare = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drumLine = new Paint();
    private final Path pointer = new Path();
    private final RectF drumBox = new RectF(), clockBox = new RectF();
    private final String unit, revsLabel, coolantLabel, fuelLabel;
    private boolean trio;
    /** The radii of the speedometer, the split gauge and the tachometer; their centres. */
    private float big, small, tach, speedX, splitX, tachX, cy;
    private final Shader[] hubs = new Shader[3], glares = new Shader[3];
    private Bitmap dial;

    Gauge60(Context context) {
        super(context);
        unit = Text.upper(context.getString(R.string.kmh));
        revsLabel = Text.upper(context.getString(R.string.rpm_thousands));
        coolantLabel = Text.upper(context.getString(R.string.veh_coolant));
        fuelLabel = Text.upper(context.getString(R.string.veh_fuel));
        Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        numbers.setTypeface(condensed);
        numbers.setColor(GLOW);
        numbers.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(condensed);
        label.setColor(GLOW);
        label.setLetterSpacing(0.12f);
        drumText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        drumText.setColor(GLOW);
        drumText.setTextAlign(Paint.Align.CENTER);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.BUTT);
        glow.setColor(GLOW);
        needle.setColor(NEEDLE);
        drumLine.setColor(0xFF000000);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        // Three in a row, the speedometer largest, the tachometer nearly as large, the split gauge smaller.
        trio = w >= h * 1.75f;
        cy = h / 2f;
        speedX = w / 2f;
        if (trio) {
            big = Math.min(h * 0.47f, w / 5.05f);
            small = big * 0.6f;
            tach = big * 0.85f;
            float gap = big * 0.05f, left = (w - (2 * small + 2 * big + 2 * tach + 2 * gap)) / 2;
            splitX = left + small;
            speedX = splitX + small + gap + big;
            tachX = speedX + big + gap + tach;
        } else {
            big = Math.min(w, h) * 0.47f;
        }
        float[] xs = {speedX, splitX, tachX}, rs = {big, small, tach};
        for (int i = 0; i < (trio ? 3 : 1); i++) {
            float x = xs[i], r = rs[i];
            // A dark metal cone, lit from the upper left.
            hubs[i] = new RadialGradient(x - r * 0.04f, cy - r * 0.04f, r * 0.13f, new int[] {0xFF77736C, 0xFF2A2927, 0xFF0B0B0B},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            glares[i] = new LinearGradient(0, cy - r, 0, cy, 0x1CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
        window(drumBox, speedX, big);
        window(clockBox, tachX, tach);
        float dh = drumBox.height();
        drumText.setTextSize(dh * 0.8f);
        drumText.setShadowLayer(dh * 0.25f, 0, 0, GLOW_HALO);
        drumLine.setStrokeWidth(Math.max(1f, big * 0.008f));
        needle.setShadowLayer(big * 0.035f, 0, 0, NEEDLE_HALO);

        dial = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas d = new Canvas(dial);
        pod(d, speedX, big);
        speedometer(d);
        if (trio) {
            pod(d, splitX, small);
            split(d);
            pod(d, tachX, tach);
            tachometer(d);
        }
    }

    /** The odometer window in the gap at the foot of a dial's scale. */
    private void window(RectF box, float x, float r) {
        float dw = r * 0.46f, dh = r * 0.17f;
        box.set(x - dw / 2, cy + r * 0.52f - dh / 2, x + dw / 2, cy + r * 0.52f + dh / 2);
    }

    @Override protected void onDraw(Canvas c) {
        if (dial == null) return;
        c.drawBitmap(dial, 0, 0, null);

        // The odometer window: the speed beside the tachometer, which keeps the time; the time on its own.
        drum(c, drumBox, trio ? (known ? String.format(Locale.ROOT, "%03d", Math.round(shown)) : "---") : timeText);
        if (trio) drum(c, clockBox, timeText);

        pointer(c, speedX, START + SWEEP * shown / MAX, big * 0.08f, big * 0.62f, big * 0.024f);
        cap(c, 0, speedX, big * 0.12f);
        if (trio) {
            float heat = Float.isNaN(coolant) ? 0f : Math.max(0f, Math.min(1f, (coolant - COLD) / (HOT - COLD)));
            pointer(c, splitX, COOL_FROM + ARC * heat, small * 0.1f, small * 0.58f, small * 0.032f);
            pointer(c, splitX, FUEL_FROM - ARC * Math.max(0f, fuelShare), small * 0.1f, small * 0.58f, small * 0.032f);
            cap(c, 1, splitX, small * 0.14f);
            pointer(c, tachX, START + SWEEP * revs / RPM_MAX, tach * 0.08f, tach * 0.62f, tach * 0.026f);
            cap(c, 2, tachX, tach * 0.12f);
        }
        // The curve of light on each glass, over the needles.
        glass(c, 0, speedX, big);
        if (trio) {
            glass(c, 1, splitX, small);
            glass(c, 2, tachX, tach);
        }
    }

    /** Figures on a drum: one wheel each, a black line between. */
    private void drum(Canvas c, RectF box, String s) {
        int n = s.length();
        float cw = box.width() / n;
        drumText.setTextSize(box.height() * 0.8f);
        for (int k = 0; k < n; k++) {
            if (k > 0) c.drawLine(box.left + cw * k, box.top, box.left + cw * k, box.bottom, drumLine);
            c.drawText(s, k, k + 1, box.left + cw * (k + 0.5f), box.centerY() + drumText.getTextSize() * 0.36f, drumText);
        }
    }

    /** Bezel, the sloped ring for the numerals, the dish inside it. */
    private void pod(Canvas c, float x, float r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float bezel = r * 0.085f;
        // Turned metal, a little warm, bright and dark bands around the ring.
        p.setShader(new SweepGradient(x, cy, new int[] {0xFFF1EEE8, 0xFF5A5751, 0xFFD3CFC7, 0xFF3D3B37, 0xFFE2DED7,
                0xFF4E4B47, 0xFFF1EEE8}, null));
        c.drawCircle(x, cy, r, p);
        p.setShader(null);
        p.setColor(0xFF000000);
        c.drawCircle(x, cy, r - bezel, p);
        // The outer ring slopes up to the bezel and catches a little light there.
        float ring = r - bezel * 1.18f, dish = r * 0.66f;
        p.setShader(new RadialGradient(x, cy, ring, new int[] {0xFF000000, 0xFF050607, 0xFF17191B},
                new float[] {0f, dish / ring, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, cy, ring, p);
        // The dish, black, a shade lighter above the middle.
        p.setShader(new RadialGradient(x, cy - dish * 0.3f, dish * 1.25f, new int[] {0xFF121416, 0xFF040505, 0xFF000000},
                new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, cy, dish, p);
        // The step between them: a dark line with a faint edge of light.
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, r * 0.012f));
        p.setColor(0x2EFFFFFF);
        c.drawCircle(x, cy, dish + p.getStrokeWidth(), p);
        p.setColor(0xFF000000);
        c.drawCircle(x, cy, dish, p);
    }

    private void speedometer(Canvas c) {
        float x = speedX, r = big;
        // Numerals round the outer ring, their tops to the bezel, as the Charger's.
        numbers.setTextSize(r * 0.15f);
        numbers.setShadowLayer(numbers.getTextSize() * 0.35f, 0, 0, GLOW_HALO);
        float ring = r * 0.79f;
        for (int v = 0; v <= MAX; v += 20) {
            float a = START + SWEEP * v / MAX;
            c.save();
            c.rotate(a + 90f, x, cy);
            c.drawText(String.valueOf(v), x, cy - ring + numbers.getTextSize() * 0.36f, numbers);
            c.restore();
        }
        // Ticks in the dish, every 5 km/h, long ones every 10, standing on a circle open at the bottom.
        glow.setShadowLayer(r * 0.03f, 0, 0, GLOW_HALO);
        for (int v = 0; v <= MAX; v += 5) {
            boolean major = v % 10 == 0;
            glow.setStrokeWidth(Math.max(1f, r * (major ? 0.02f : 0.012f)));
            radial(c, x, START + SWEEP * v / MAX, r * (major ? 0.4f : 0.49f), r * 0.62f);
        }
        glow.setStrokeWidth(Math.max(1f, r * 0.012f));
        c.drawArc(x - r * 0.4f, cy - r * 0.4f, x + r * 0.4f, cy + r * 0.4f, START, SWEEP, false, glow);
        label.setTextSize(r * 0.075f);
        label.setShadowLayer(label.getTextSize() * 0.35f, 0, 0, GLOW_HALO);
        label.setTextAlign(Paint.Align.CENTER);
        c.drawText(unit, x, cy + r * 0.3f, label);
        windowGlass(c, drumBox, r);
    }

    /** An odometer window, faintly lit from behind. */
    private void windowGlass(Canvas c, RectF box, float r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, box.top, 0, box.bottom, new int[] {0xFF020807, 0xFF0B2624, 0xFF020807},
                null, Shader.TileMode.CLAMP));
        c.drawRect(box, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, r * 0.01f));
        p.setColor(0xFF2B2A28);
        c.drawRect(box, p);
    }

    /** Coolant over the top, fuel along the bottom, a needle each from the middle; names on the ring. */
    private void split(Canvas c) {
        float x = splitX, r = small;
        glow.setShadowLayer(r * 0.04f, 0, 0, GLOW_HALO);
        for (int k = 0; k <= 4; k++) {
            boolean end = k % 2 == 0;
            glow.setStrokeWidth(Math.max(1f, r * (end ? 0.03f : 0.02f)));
            radial(c, x, COOL_FROM + ARC * k / 4, r * (end ? 0.38f : 0.44f), r * 0.6f);
            radial(c, x, FUEL_FROM - ARC * k / 4, r * (end ? 0.38f : 0.44f), r * 0.6f);
        }
        numbers.setTextSize(r * 0.17f);
        numbers.setShadowLayer(numbers.getTextSize() * 0.35f, 0, 0, GLOW_HALO);
        letter(c, x, "C", COOL_FROM - 18f);
        letter(c, x, "H", COOL_FROM + ARC + 18f);
        letter(c, x, "E", FUEL_FROM + 18f);
        letter(c, x, "F", FUEL_FROM - ARC - 18f);

        label.setTextSize(r * 0.13f);
        label.setShadowLayer(label.getTextSize() * 0.35f, 0, 0, GLOW_HALO);
        label.setTextAlign(Paint.Align.LEFT);
        // Over the top the letters stand outwards from their line; along the bottom, inwards.
        curved(c, x, coolantLabel, r * 0.72f, true);
        curved(c, x, fuelLabel, r * 0.84f, false);
    }

    /** 0 to 7 thousand rpm on the speedometer's sweep, the last thousand in red, as a redline. */
    private void tachometer(Canvas c) {
        float x = tachX, r = tach;
        numbers.setTextSize(r * 0.16f);
        float ring = r * 0.79f;
        for (int k = 0; k <= RPM_MAX / 1000; k++) {
            boolean red = k * 1000 >= REDLINE;
            numbers.setColor(red ? NEEDLE : GLOW);
            numbers.setShadowLayer(numbers.getTextSize() * 0.35f, 0, 0, red ? NEEDLE_HALO : GLOW_HALO);
            c.save();
            c.rotate(START + SWEEP * k * 1000 / RPM_MAX + 90f, x, cy);
            c.drawText(String.valueOf(k), x, cy - ring + numbers.getTextSize() * 0.36f, numbers);
            c.restore();
        }
        numbers.setColor(GLOW);
        // Ticks every 250 rpm, long ones every thousand, on a circle open at the bottom like the speedometer's.
        for (int v = 0; v <= RPM_MAX; v += 250) {
            boolean major = v % 1000 == 0, half = v % 500 == 0, red = v >= REDLINE;
            glow.setColor(red ? NEEDLE : GLOW);
            glow.setShadowLayer(r * 0.03f, 0, 0, red ? NEEDLE_HALO : GLOW_HALO);
            glow.setStrokeWidth(Math.max(1f, r * (major ? 0.024f : 0.014f)));
            radial(c, x, START + SWEEP * v / RPM_MAX, r * (major ? 0.4f : half ? 0.47f : 0.53f), r * 0.62f);
        }
        glow.setColor(GLOW);
        glow.setShadowLayer(r * 0.03f, 0, 0, GLOW_HALO);
        glow.setStrokeWidth(Math.max(1f, r * 0.012f));
        float redFrom = SWEEP * REDLINE / RPM_MAX;
        c.drawArc(x - r * 0.4f, cy - r * 0.4f, x + r * 0.4f, cy + r * 0.4f, START, redFrom, false, glow);
        glow.setColor(NEEDLE);
        glow.setShadowLayer(r * 0.03f, 0, 0, NEEDLE_HALO);
        glow.setStrokeWidth(Math.max(2f, r * 0.05f));
        c.drawArc(x - r * 0.65f, cy - r * 0.65f, x + r * 0.65f, cy + r * 0.65f, START + redFrom, SWEEP - redFrom, false, glow);
        glow.setColor(GLOW);
        label.setTextSize(r * 0.08f);
        label.setShadowLayer(label.getTextSize() * 0.35f, 0, 0, GLOW_HALO);
        label.setTextAlign(Paint.Align.CENTER);
        c.drawText(revsLabel, x, cy + r * 0.3f, label);
        windowGlass(c, clockBox, r);
    }

    private void radial(Canvas c, float x, float degrees, float from, float to) {
        double a = Math.toRadians(degrees);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        c.drawLine(x + cos * from, cy + sin * from, x + cos * to, cy + sin * to, glow);
    }

    private void letter(Canvas c, float x, String s, float degrees) {
        double a = Math.toRadians(degrees);
        float at = small * 0.46f;
        c.drawText(s, x + (float) Math.cos(a) * at, cy + (float) Math.sin(a) * at + numbers.getTextSize() * 0.36f, numbers);
    }

    /** Text centred on an arc of the given radius: over the top (left to right, clockwise) or along the bottom. */
    private void curved(Canvas c, float x, String s, float radius, boolean top) {
        float sweep = 120f;
        Path arc = new Path();
        RectF o = new RectF(x - radius, cy - radius, x + radius, cy + radius);
        if (top) arc.addArc(o, -90f - sweep / 2, sweep);
        else arc.addArc(o, 90f + sweep / 2, -sweep);
        float length = (float) Math.toRadians(sweep) * radius;
        c.drawTextOnPath(s, arc, Math.max(0f, (length - label.measureText(s)) / 2), 0, label);
    }

    /** A needle or a hand: a thin taper from a short tail (under the hub) out to length, pointing at degrees. */
    private void pointer(Canvas c, float x, float degrees, float tail, float length, float width) {
        c.save();
        c.rotate(degrees, x, cy);
        pointer.reset();
        pointer.moveTo(x - tail, cy - width);
        pointer.lineTo(x + length, cy - width * 0.25f);
        pointer.lineTo(x + length, cy + width * 0.25f);
        pointer.lineTo(x - tail, cy + width);
        pointer.close();
        c.drawPath(pointer, needle);
        c.restore();
    }

    private void cap(Canvas c, int i, float x, float radius) {
        hub.setShader(hubs[i]);
        c.drawCircle(x, cy, radius, hub);
    }

    private void glass(Canvas c, int i, float x, float r) {
        glare.setShader(glares[i]);
        float in = r * 0.9f;
        c.drawOval(x - in * 0.8f, cy - in * 0.96f, x + in * 0.8f, cy + in * 0.02f, glare);
    }
}
