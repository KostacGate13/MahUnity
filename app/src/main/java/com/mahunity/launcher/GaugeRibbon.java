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

/**
 * The 60s cluster after a '65 Mustang: a black panel in a chrome surround, the speedometer a horizontal ribbon
 * across it (tall, narrow numerals over a fan of slanted ticks, an orange needle running along them), and a
 * gold-ringed dial either side, fuel on the left and coolant on the right, their scales round the bottom and their
 * names in italic over the top. Under the ribbon, three windows: a lamp that lights when the battery is low, the
 * time on a drum, a lamp that lights when the tank runs low. On a narrow widget, the ribbon and windows alone.
 *
 * Everything that does not move is drawn once into a bitmap; each frame adds the needles, lamps and the time.
 */
final class GaugeRibbon extends GaugeView {
    private static final int CREAM = 0xFFEEE8D8, NEEDLE = 0xFFFF5A1F, LAMP = 0xFFFF3B2A;
    /** The dials' scales: from E (or C) at the lower left, round the bottom, to F (or H) at the lower right. */
    private static final float DIAL_FROM = 150f, DIAL_SWEEP = 120f, COLD = 50f, HOT = 130f;
    /** Lamps: the battery below this, the tank below this share. */
    private static final float LOW_VOLTS = 11.8f, LOW_FUEL = 0.12f;

    private final TextPaint numeral = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint script = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint drumText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glare = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lamp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drumLine = new Paint();
    private final Path shape = new Path();
    private final RectF panel = new RectF(), band = new RectF(), drum = new RectF(), lampLeft = new RectF(),
            lampRight = new RectF();
    private final String unit, fuelLabel, coolantLabel;
    private boolean dials;
    private float dialR, leftX, rightX, dialY, scaleLeft, scaleRight, lineY, fanBottom, pivotX, pivotY;
    private Shader hubLeft, hubRight, glareLeft, glareRight;
    private Bitmap layer;

    GaugeRibbon(Context context) {
        super(context);
        unit = Text.upper(context.getString(R.string.kmh));
        fuelLabel = context.getString(R.string.veh_fuel);
        coolantLabel = context.getString(R.string.veh_coolant);
        numeral.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        numeral.setColor(CREAM);
        numeral.setTextAlign(Paint.Align.CENTER);
        script.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
        script.setColor(CREAM);
        script.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        small.setColor(CREAM);
        small.setTextAlign(Paint.Align.CENTER);
        drumText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        drumText.setColor(CREAM);
        drumText.setTextAlign(Paint.Align.CENTER);
        needle.setColor(NEEDLE);
        pin.setColor(0xFF3A3E44);
        drumLine.setColor(0xFF000000);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        float inset = h * 0.03f, chrome = h * 0.035f, inner = chrome + h * 0.02f;
        panel.set(inset, inset, w - inset, h - inset);
        dials = w >= h * 1.6f;
        dialY = h * 0.5f;
        if (dials) {
            dialR = Math.min(h * 0.34f, w * 0.125f);
            leftX = panel.left + inner + dialR;
            rightX = panel.right - inner - dialR;
            band.set(leftX + dialR + h * 0.04f, h * 0.2f, rightX - dialR - h * 0.04f, h * 0.6f);
        } else {
            band.set(panel.left + inner + h * 0.04f, h * 0.2f, panel.right - inner - h * 0.04f, h * 0.6f);
        }
        if (dials) {
            hubLeft = hubShader(leftX);
            hubRight = hubShader(rightX);
            glareLeft = new LinearGradient(0, dialY - dialR, 0, dialY, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            glareRight = glareLeft;
        }
        needle.setShadowLayer(h * 0.01f, 0, h * 0.004f, 0x90000000);

        // Under the ribbon: lamp, drum, lamp.
        float top = band.bottom + h * 0.07f, wh = h * 0.15f, bw = band.width();
        lampLeft.set(band.left + bw * 0.05f, top, band.left + bw * 0.27f, top + wh);
        drum.set(band.centerX() - bw * 0.19f, top, band.centerX() + bw * 0.19f, top + wh);
        lampRight.set(band.right - bw * 0.27f, top, band.right - bw * 0.05f, top + wh);
        drumText.setTextSize(wh * 0.7f);
        drumLine.setStrokeWidth(Math.max(1f, h * 0.004f));

        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(layer);
        surround(c, chrome);
        ribbon(c);
        windows(c);
        if (dials) {
            dial(c, leftX, fuelLabel, "E", "F", false);
            dial(c, rightX, coolantLabel, "C", "H", true);
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (layer == null) return;
        c.drawBitmap(layer, 0, 0, null);

        // The ribbon's needle: one of the fan's lines, thick and orange, from above the line to the fan's foot.
        float x = scaleLeft + (scaleRight - scaleLeft) * shown / MAX;
        float dx = pivotX - x, dy = pivotY - lineY, len = (float) Math.hypot(dx, dy);
        float ux = dx / len, uy = dy / len, half = getHeight() * 0.011f;
        float lift = getHeight() * 0.035f, down = (fanBottom - lineY) / uy;
        float x0 = x - ux * lift / uy, y0 = lineY - lift, x1 = x + ux * down, y1 = lineY + uy * down;
        shape.reset();
        shape.moveTo(x0 - uy * half, y0 + ux * half);
        shape.lineTo(x1 - uy * half, y1 + ux * half);
        shape.lineTo(x1 + uy * half, y1 - ux * half);
        shape.lineTo(x0 + uy * half, y0 - ux * half);
        shape.close();
        c.drawPath(shape, needle);

        // The time on the drum.
        String s = timeText;
        int n = s.length();
        float cw = drum.width() / n;
        for (int k = 0; k < n; k++) {
            if (k > 0) c.drawLine(drum.left + cw * k, drum.top, drum.left + cw * k, drum.bottom, drumLine);
            c.drawText(s, k, k + 1, drum.left + cw * (k + 0.5f), drum.centerY() + drumText.getTextSize() * 0.36f, drumText);
        }
        lamp(c, lampLeft, battery > 0 && battery < LOW_VOLTS, false);
        lamp(c, lampRight, fuelShare >= 0 && fuelShare < LOW_FUEL, true);

        if (dials) {
            pointer(c, leftX, DIAL_FROM - DIAL_SWEEP * Math.max(0f, fuelShare));
            float heat = Float.isNaN(coolant) ? 0f : Math.max(0f, Math.min(1f, (coolant - COLD) / (HOT - COLD)));
            pointer(c, rightX, DIAL_FROM - DIAL_SWEEP * heat);
            cap(c, leftX, hubLeft);
            cap(c, rightX, hubRight);
            glass(c, leftX, glareLeft);
            glass(c, rightX, glareRight);
        }
    }

    /** The chrome surround and the black panel inside it. */
    private void surround(Canvas c, float chrome) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = panel.height() * 0.17f;
        p.setShader(new LinearGradient(0, panel.top, 0, panel.bottom, new int[] {0xFFFBFBFA, 0xFF9CA1A7, 0xFFE6E8EA,
                0xFF70757B, 0xFFD8DBDE}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(panel, r, r, p);
        RectF in = new RectF(panel);
        in.inset(chrome, chrome);
        p.setShader(new LinearGradient(0, in.top, 0, in.bottom, 0xFF16181B, 0xFF070809, Shader.TileMode.CLAMP));
        c.drawRoundRect(in, r - chrome, r - chrome, p);
    }

    private void ribbon(Canvas c) {
        float h = getHeight();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, band.top, 0, band.bottom, 0xFF111720, 0xFF06080B, Shader.TileMode.CLAMP));
        c.drawRect(band, p);
        // A chrome strip along the foot of the ribbon, a thin bright edge along its top.
        float strip = h * 0.025f;
        p.setShader(new LinearGradient(0, band.bottom - strip, 0, band.bottom, 0xFFF2F3F4, 0xFF7D8288, Shader.TileMode.CLAMP));
        c.drawRect(band.left, band.bottom - strip, band.right, band.bottom, p);
        p.setShader(null);
        p.setColor(0x40FFFFFF);
        c.drawRect(band.left, band.top, band.right, band.top + Math.max(1f, h * 0.004f), p);

        // Tall, narrow numerals, every 20 km/h if they fit side by side, else every 40.
        numeral.setTextScaleX(0.78f);
        numeral.setTextSize(100f);
        float wide = numeral.measureText("200") / 100f, gap = h * 0.012f, room = band.width() - h * 0.08f;
        int step = 20;
        float size = Math.min(h * 0.12f, (room / (MAX / 20f + 1) - gap) / wide);
        if (size < h * 0.065f) {
            step = 40;
            size = Math.min(h * 0.12f, (room / (MAX / 40f + 1) - gap) / wide);
        }
        numeral.setTextSize(size);
        float pad = numeral.measureText("200") / 2 + h * 0.03f;
        scaleLeft = band.left + pad;
        scaleRight = band.right - pad;
        float numY = band.top + band.height() * 0.12f + size * 0.8f;
        for (int v = 0; v <= MAX; v += step) {
            c.drawText(String.valueOf(v), scaleLeft + (scaleRight - scaleLeft) * v / MAX, numY, numeral);
        }

        // The line under the numerals, and the fan of ticks hanging from it towards a point far below.
        lineY = numY + size * 0.45f;
        fanBottom = band.bottom - strip - h * 0.025f;
        pivotX = band.centerX();
        pivotY = lineY + (scaleRight - scaleLeft) * 0.6f;
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(CREAM);
        line.setStrokeWidth(Math.max(1f, h * 0.006f));
        c.drawLine(scaleLeft - h * 0.02f, lineY, scaleRight + h * 0.02f, lineY, line);
        for (int v = 0; v <= MAX; v += 10) {
            boolean major = v % step == 0;
            float x = scaleLeft + (scaleRight - scaleLeft) * v / MAX;
            float dx = pivotX - x, dy = pivotY - lineY, len = (float) Math.hypot(dx, dy);
            float depth = (fanBottom - lineY) * (major ? 1f : 0.55f) / (dy / len);
            line.setStrokeWidth(Math.max(1f, h * (major ? 0.008f : 0.005f)));
            c.drawLine(x, lineY, x + dx / len * depth, lineY + dy / len * depth, line);
        }
        small.setTextSize(h * 0.045f);
        small.setTextAlign(Paint.Align.LEFT);
        c.drawText(unit, band.left + h * 0.025f, fanBottom, small);
        small.setTextAlign(Paint.Align.CENTER);
    }

    /** Chrome frames for the three windows, and the drum's black wheels; the lamps' glass is drawn each frame. */
    private void windows(Canvas c) {
        float h = getHeight();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF[] all = {lampLeft, drum, lampRight};
        for (RectF r : all) {
            RectF o = new RectF(r);
            o.inset(-h * 0.012f, -h * 0.012f);
            p.setShader(new LinearGradient(0, o.top, 0, o.bottom, 0xFFF2F3F4, 0xFF6F747A, Shader.TileMode.CLAMP));
            c.drawRect(o, p);
        }
        p.setShader(new LinearGradient(0, drum.top, 0, drum.bottom, new int[] {0xFF050505, 0xFF1E1E1E, 0xFF050505}, null,
                Shader.TileMode.CLAMP));
        c.drawRect(drum, p);
    }

    /** A warning lamp, lit or not, with its sign: a battery, or a fuel pump. */
    private void lamp(Canvas c, RectF r, boolean on, boolean fuel) {
        if (on) {
            lamp.setShader(new RadialGradient(r.centerX(), r.centerY(), r.width() * 0.7f, 0xFFFF6A50, LAMP, Shader.TileMode.CLAMP));
            lamp.setShadowLayer(r.height() * 0.5f, 0, 0, LAMP);
        } else {
            lamp.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF4A1210, 0xFF250807, Shader.TileMode.CLAMP));
            lamp.clearShadowLayer();
        }
        c.drawRect(r, lamp);
        icon.setColor(on ? 0xFFFFF1EC : 0xFF5E1A16);
        icon.setStyle(Paint.Style.FILL);
        float s = r.height() * 0.62f, cx = r.centerX(), cy = r.centerY();
        if (fuel) {
            // A pump: its body with a window, the hose from its side.
            c.drawRect(cx - s * 0.42f, cy - s * 0.5f, cx + s * 0.08f, cy + s * 0.5f, icon);
            icon.setColor(on ? LAMP : 0xFF3A0E0C);
            c.drawRect(cx - s * 0.32f, cy - s * 0.38f, cx - s * 0.02f, cy - s * 0.1f, icon);
            icon.setColor(on ? 0xFFFFF1EC : 0xFF5E1A16);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(s * 0.1f);
            shape.reset();
            shape.moveTo(cx + s * 0.08f, cy - s * 0.25f);
            shape.lineTo(cx + s * 0.32f, cy - s * 0.1f);
            shape.lineTo(cx + s * 0.32f, cy + s * 0.32f);
            c.drawPath(shape, icon);
        } else {
            // A battery: the case, its two terminals, minus and plus.
            c.drawRect(cx - s * 0.6f, cy - s * 0.32f, cx + s * 0.6f, cy + s * 0.45f, icon);
            c.drawRect(cx - s * 0.45f, cy - s * 0.5f, cx - s * 0.2f, cy - s * 0.32f, icon);
            c.drawRect(cx + s * 0.2f, cy - s * 0.5f, cx + s * 0.45f, cy - s * 0.32f, icon);
            icon.setColor(on ? LAMP : 0xFF3A0E0C);
            float t = s * 0.08f;
            c.drawRect(cx - s * 0.45f, cy - t, cx - s * 0.18f, cy + t, icon);
            c.drawRect(cx + s * 0.18f, cy - t, cx + s * 0.45f, cy + t, icon);
            c.drawRect(cx + s * 0.315f - t, cy - s * 0.135f, cx + s * 0.315f + t, cy + s * 0.135f, icon);
        }
    }

    /** A gold-ringed dial: its name in italic over the top, its scale round the bottom. */
    private void dial(Canvas c, float x, String name, String from, String to, boolean hot) {
        float r = dialR, y = dialY;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new SweepGradient(x, y, new int[] {0xFFF4E3B5, 0xFF7A5E2A, 0xFFE6CC8E, 0xFF5A4520, 0xFFF0DCA4,
                0xFF6E5527, 0xFFF4E3B5}, null));
        c.drawCircle(x, y, r, p);
        p.setShader(null);
        p.setColor(0xFF000000);
        c.drawCircle(x, y, r * 0.85f, p);
        p.setShader(new RadialGradient(x, y - r * 0.3f, r * 1.05f, new int[] {0xFF333C4A, 0xFF181D25, 0xFF0A0D11},
                new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 0.83f, p);

        script.setTextSize(r * 0.24f);
        float max = r * 1.2f;
        if (script.measureText(name) > max) script.setTextSize(script.getTextSize() * max / script.measureText(name));
        c.drawText(name, x, y - r * 0.36f, script);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(CREAM);
        line.setStrokeWidth(Math.max(1f, r * 0.035f));
        for (int k = 0; k <= 4; k++) {
            double a = Math.toRadians(DIAL_FROM - DIAL_SWEEP * k / 4);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float from0 = r * (k % 2 == 0 ? 0.58f : 0.63f);
            c.drawLine(x + cos * from0, y + sin * from0, x + cos * r * 0.72f, y + sin * r * 0.72f, line);
        }
        if (hot) {
            // The bracket the coolant reads against, red at its hot end.
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1f, r * 0.03f));
            float br = r * 0.72f;
            RectF o = new RectF(x - br, y - br, x + br, y + br);
            c.drawArc(o, DIAL_FROM - DIAL_SWEEP, DIAL_SWEEP, false, line);
            line.setColor(0xFFE0331F);
            c.drawArc(o, DIAL_FROM - DIAL_SWEEP, DIAL_SWEEP * 0.15f, false, line);
        }
        small.setTextSize(r * 0.2f);
        letter(c, x, from, DIAL_FROM + 14f);
        letter(c, x, to, DIAL_FROM - DIAL_SWEEP - 14f);
    }

    private void letter(Canvas c, float x, String s, float degrees) {
        double a = Math.toRadians(degrees);
        float at = dialR * 0.6f;
        c.drawText(s, x + (float) Math.cos(a) * at, dialY + (float) Math.sin(a) * at + small.getTextSize() * 0.36f, small);
    }

    private Shader hubShader(float x) {
        return new RadialGradient(x - dialR * 0.08f, dialY - dialR * 0.08f, dialR * 0.3f,
                new int[] {0xFFFFFFFF, 0xFFBFC4CA, 0xFF5D636A}, new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    }

    /** A dial's needle, from the hub out over its scale. */
    private void pointer(Canvas c, float x, float degrees) {
        float w = dialR * 0.035f, length = dialR * 0.68f;
        c.save();
        c.rotate(degrees, x, dialY);
        shape.reset();
        shape.moveTo(x, dialY - w);
        shape.lineTo(x + length, dialY - w * 0.3f);
        shape.lineTo(x + length, dialY + w * 0.3f);
        shape.lineTo(x, dialY + w);
        shape.close();
        c.drawPath(shape, needle);
        c.restore();
    }

    /** The big chrome hub, and the pin at its middle. */
    private void cap(Canvas c, float x, Shader shader) {
        hub.setShader(shader);
        c.drawCircle(x, dialY, dialR * 0.26f, hub);
        c.drawCircle(x, dialY, dialR * 0.07f, pin);
    }

    private void glass(Canvas c, float x, Shader shader) {
        glare.setShader(shader);
        float in = dialR * 0.82f;
        c.drawOval(x - in * 0.8f, dialY - in * 0.96f, x + in * 0.8f, dialY + in * 0.02f, glare);
    }
}
