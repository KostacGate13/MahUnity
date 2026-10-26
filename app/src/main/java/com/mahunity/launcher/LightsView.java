package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

/**
 * The Lights tab: a card for each LED strip (the dashboard, the footwells) with its switch, a bar glowing as it
 * shows right now (the dashboard LED by LED where it moves) and the colours to choose from, two of them in the
 * modes that take two (a slot for each, then the colours for the slot chosen), and for the footwells in Normal
 * and Static a fuel button, to show the fuel instead; under them the four modes, and the way to the rest of the
 * settings.
 */
final class LightsView extends View {
    interface Listener {
        void onLightSwitch(int role);

        /** A colour chosen for a strip's first (slot 0) or second colour. */
        void onLightColor(int role, int slot, int color);

        /** The footwells' fuel button: the fuel instead of their colours, or back. */
        void onLightFuel();

        /** The dashboard's rev bar: one colour, or LED by LED left to right. */
        void onLightShape();

        void onLightMode(int mode);

        void onLightSettings();
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint swatch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pump = new Path();
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chip = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] cards = {new RectF(), new RectF()}, switches = {new RectF(), new RectF()},
            bars = {new RectF(), new RectF()};
    private final RectF[] modes = new RectF[4];
    private final RectF more = new RectF(), fuelButton = new RectF(), shapeChip = new RectF();
    private final float[][] swatchX = new float[2][Ambient.COLORS.length];
    private final float[] swatchY = new float[2];
    private final RectF[][] slots = {{new RectF(), new RectF()}, {new RectF(), new RectF()}};
    private final String[] names, modeNames;
    private final String moreText, raceWhole, raceBar;
    private final String[] status = {"", ""};
    private final boolean[] on = new boolean[2];
    private final int[] chosen = new int[2], chosen2 = new int[2], shown = new int[2], slot = new int[2];
    private int[] revBar;
    private int mode;
    /** Whether the mode takes two colours; whether the dashboard's are its own (not in Race). */
    private boolean pair, topColours = true;
    /** Whether the footwells' fuel button shows (Normal and Static), and whether it is on. */
    private boolean fuelMode, fuelOn;
    /** Race: whether the dashboard's rev bar is one colour (or LED by LED). */
    private boolean wholeBar = true;
    private float radius, pad, swatchR, cardH;
    private Listener listener;
    private Shader cardFill, cardFrame;

    LightsView(Context context) {
        super(context);
        names = new String[] {context.getString(R.string.led_top), context.getString(R.string.led_bottom)};
        modeNames = new String[Ambient.MODE_NAMES.length];
        for (int i = 0; i < modeNames.length; i++) modeNames[i] = context.getString(Ambient.MODE_NAMES[i]);
        moreText = context.getString(R.string.led_settings) + "  ›";
        raceWhole = context.getString(R.string.led_race_whole);
        raceBar = context.getString(R.string.led_race_bar);
        for (int i = 0; i < modes.length; i++) modes[i] = new RectF();
        frame.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        title.setTypeface(Chrome.MEDIUM);
        title.setColor(Chrome.TEXT);
        note.setTypeface(Chrome.REGULAR);
        note.setColor(Chrome.TEXT_DIM);
        chip.setTypeface(Chrome.MEDIUM);
        chip.setTextAlign(Paint.Align.CENTER);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** The state to draw: each strip's switch, colours, what it shows and its connection; the mode. */
    void set(Ambient a, String topStatus, String bottomStatus) {
        on[0] = a.topOn;
        on[1] = a.bottomOn;
        chosen[0] = a.topColor;
        chosen[1] = a.bottomColor;
        chosen2[0] = a.topColor2;
        chosen2[1] = a.bottomColor2;
        shown[0] = a.shownTop;
        shown[1] = a.shownBottom;
        revBar = a.topBar;
        status[0] = topStatus;
        status[1] = bottomStatus;
        boolean two = a.mode == Ambient.NORMAL || a.mode == Ambient.MUSIC;
        topColours = a.mode != Ambient.RACE;
        fuelOn = a.bottomFuel;
        wholeBar = a.barShape == Ambient.WHOLE;
        if (two != pair || a.mode != mode || a.fuelMode() != fuelMode) {
            mode = a.mode;
            pair = two;
            fuelMode = a.fuelMode();
            if (!pair) slot[0] = slot[1] = 0;
            place();
        }
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float u = Chrome.unit(getContext());
        float gap = u * 0.024f;
        radius = Chrome.radius(getContext());
        pad = u * 0.03f;
        float rowH = u * 0.085f;
        float cardH = (h - 2 * rowH - 3 * gap) / 2;
        for (int i = 0; i < 2; i++) {
            float top = i * (cardH + gap);
            cards[i].set(0, top, w, top + cardH);
            RectF c = cards[i];
            float sh = u * 0.05f, sw = sh * 1.9f;
            switches[i].set(c.right - pad - sw, c.top + pad, c.right - pad, c.top + pad + sh);
            float barTop = c.top + pad + sh + gap * 0.9f;
            bars[i].set(c.left + pad, barTop, c.right - pad, barTop + Math.max(4f, cardH * 0.07f));
        }
        this.cardH = cardH;
        place();
        float modesTop = 2 * cardH + 2 * gap, mw = (w - 3 * gap * 0.5f) / 4;
        for (int k = 0; k < 4; k++) {
            float x = k * (mw + gap * 0.5f);
            modes[k].set(x, modesTop, x + mw, modesTop + rowH);
        }
        more.set(0, modesTop + rowH + gap, w, h);
        title.setTextSize(u * Chrome.TYPE_HEADLINE * 0.9f);
        note.setTextSize(u * Chrome.TYPE_CAPTION);
        chip.setTextSize(u * Chrome.TYPE_BODY * 0.9f);
        frame.setStrokeWidth(Math.max(1f, u * 0.0022f));
        ring.setStrokeWidth(Math.max(2f, u * 0.004f));
        cardFill = Chrome.panel(0, cardH);
        cardFrame = Chrome.edge(0, 0, w, cardH);
    }

    /**
     * The colours along each card's foot: with two slots first where the mode takes two colours, and room at the
     * end for the footwells' fuel button where it shows (kept on both cards, so their colours line up).
     */
    private void place() {
        if (cardH <= 0) return;
        int n = Ambient.COLORS.length;
        float lead = pair ? 2.8f : 0f, tail = fuelMode ? 1.6f : 0f;
        for (int i = 0; i < 2; i++) {
            RectF c = cards[i];
            float left = c.left + pad, cell = (c.width() - 2 * pad) / (n + lead + tail);
            swatchR = Math.min(cell * 0.36f, cardH * 0.13f);
            swatchY[i] = c.bottom - pad - swatchR;
            float half = swatchR * 1.15f;
            for (int k = 0; k < 2; k++) {
                float x = left + cell * (k * 1.2f + 0.5f);
                slots[i][k].set(x - half, swatchY[i] - half, x + half, swatchY[i] + half);
            }
            for (int k = 0; k < n; k++) swatchX[i][k] = left + cell * (lead + k + 0.5f);
            if (i == 1) {
                float x = c.right - pad - cell * 0.75f;
                fuelButton.set(x - half, swatchY[i] - half, x + half, swatchY[i] + half);
            }
        }
    }

    @Override protected void onDraw(Canvas c) {
        for (int i = 0; i < 2; i++) {
            RectF r = cards[i];
            c.save();
            c.translate(0, r.top);
            fill.setShader(cardFill);
            frame.setShader(cardFrame);
            RectF local = new RectF(0, 0, r.width(), r.height());
            c.drawRoundRect(local, radius, radius, fill);
            c.drawRoundRect(local, radius, radius, frame);
            c.restore();
            c.drawText(names[i], r.left + pad, r.top + pad + title.getTextSize() * 0.8f, title);
            float nameW = title.measureText(names[i]);
            String st = fit(status[i], note, switches[i].left - (r.left + pad + nameW + pad) - pad);
            c.drawText(st, r.left + pad + nameW + pad, r.top + pad + title.getTextSize() * 0.8f, note);
            drawSwitch(c, switches[i], on[i]);
            if (i == 0 && revBar != null && on[0]) drawLeds(c, bars[0], revBar);
            else drawBar(c, bars[i], on[i] ? shown[i] : 0);
            if (i == 0 && !topColours) {
                String t = fit(wholeBar ? raceWhole : raceBar, note, r.width() - 4 * pad);
                float h = note.getTextSize() * 2.1f, cw = note.measureText(t) + 2 * pad;
                shapeChip.set(r.left + pad, swatchY[i] - h / 2, r.left + pad + cw, swatchY[i] + h / 2);
                fill.setShader(null);
                fill.setColor(wholeBar ? 0x14FFFFFF : Chrome.withAlpha(Chrome.accent, 0x40));
                c.drawRoundRect(shapeChip, h / 2, h / 2, fill);
                frame.setShader(null);
                frame.setColor(wholeBar ? 0x40FFFFFF : Chrome.accent);
                c.drawRoundRect(shapeChip, h / 2, h / 2, frame);
                note.setColor(Chrome.TEXT);
                c.drawText(t, shapeChip.left + pad, shapeChip.centerY() + note.getTextSize() * 0.36f, note);
                note.setColor(Chrome.TEXT_DIM);
                continue;
            }
            int current = pair && slot[i] == 1 ? chosen2[i] : chosen[i];
            // The fuel in place of the footwells' colours: those dimmed, none marked.
            boolean byFuel = i == 1 && fuelMode && fuelOn;
            int opaque = byFuel ? 0x50000000 : 0xFF000000;
            if (pair) {
                for (int k = 0; k < 2; k++) {
                    RectF sr = slots[i][k];
                    float sr2 = sr.height() * 0.28f;
                    swatch.setColor(opaque | (k == 0 ? chosen[i] : chosen2[i]));
                    c.drawRoundRect(sr, sr2, sr2, swatch);
                    chip.setColor(opaque);
                    c.drawText(k == 0 ? "1" : "2", sr.centerX(), sr.centerY() + chip.getTextSize() * 0.36f, chip);
                    if (k == slot[i] && !byFuel) {
                        ring.setColor(Chrome.TEXT);
                        RectF o = new RectF(sr);
                        o.inset(-ring.getStrokeWidth() * 1.6f, -ring.getStrokeWidth() * 1.6f);
                        c.drawRoundRect(o, sr2 * 1.3f, sr2 * 1.3f, ring);
                    }
                }
                frame.setShader(null);
                frame.setColor(0x40FFFFFF);
                float x = (slots[i][1].right + swatchX[i][0] - swatchR) / 2;
                c.drawLine(x, swatchY[i] - swatchR, x, swatchY[i] + swatchR, frame);
            }
            for (int k = 0; k < Ambient.COLORS.length; k++) {
                int col = Ambient.COLORS[k];
                swatch.setColor(opaque | col);
                c.drawCircle(swatchX[i][k], swatchY[i], swatchR, swatch);
                if (col == current && !byFuel) {
                    ring.setColor(Chrome.TEXT);
                    c.drawCircle(swatchX[i][k], swatchY[i], swatchR + ring.getStrokeWidth() * 1.6f, ring);
                }
            }
            if (i == 1 && fuelMode) drawFuel(c);
        }
        for (int k = 0; k < 4; k++) {
            RectF r = modes[k];
            float rr = r.height() / 2;
            boolean sel = k == mode;
            fill.setShader(null);
            fill.setColor(sel ? Chrome.withAlpha(Chrome.accent, 0x40) : 0x14FFFFFF);
            c.drawRoundRect(r, rr, rr, fill);
            frame.setShader(null);
            frame.setColor(sel ? Chrome.accent : 0x40FFFFFF);
            c.drawRoundRect(r, rr, rr, frame);
            chip.setColor(sel ? Chrome.TEXT : Chrome.TEXT_DIM);
            CharSequence t = TextUtils.ellipsize(modeNames[k], chip, r.width() * 0.9f, TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), r.centerX(), r.centerY() + chip.getTextSize() * 0.36f, chip);
        }
        float mr = Math.min(more.height() / 2, radius * 1.4f);
        fill.setColor(0x10FFFFFF);
        c.drawRoundRect(more, mr, mr, fill);
        frame.setColor(0x50FFFFFF);
        c.drawRoundRect(more, mr, mr, frame);
        chip.setColor(Chrome.TEXT);
        c.drawText(moreText, more.centerX(), more.centerY() + chip.getTextSize() * 0.36f, chip);
    }

    private void drawSwitch(Canvas c, RectF r, boolean isOn) {
        float rr = r.height() / 2;
        fill.setShader(null);
        fill.setColor(isOn ? Chrome.withAlpha(Chrome.accent, 0xD0) : 0x30FFFFFF);
        c.drawRoundRect(r, rr, rr, fill);
        knob.setColor(0xFFF4F6F8);
        float kx = isOn ? r.right - rr : r.left + rr;
        c.drawCircle(kx, r.centerY(), rr * 0.78f, knob);
    }

    /** The strip as it glows now: a bright core fading out at both ends, or a dark line when off. */
    private void drawBar(Canvas c, RectF r, int rgb) {
        float rr = r.height() / 2;
        if ((rgb & 0xFFFFFF) == 0) {
            fill.setShader(null);
            fill.setColor(0x20FFFFFF);
            c.drawRoundRect(r, rr, rr, fill);
            return;
        }
        int col = 0xFF000000 | rgb, clear = rgb & 0xFFFFFF;
        glow.setShader(new LinearGradient(r.left, 0, r.right, 0, new int[] {clear, col, col, clear},
                new float[] {0f, 0.15f, 0.85f, 1f}, Shader.TileMode.CLAMP));
        glow.setShadowLayer(r.height() * 1.6f, 0, 0, col);
        c.drawRoundRect(r, rr, rr, glow);
        glow.clearShadowLayer();
    }

    /** The footwells' fuel button: a pump, lit in the accent when the fuel shows; a line before it. */
    private void drawFuel(Canvas c) {
        RectF b = fuelButton;
        float r = b.width() / 2, cx = b.centerX(), cy = b.centerY();
        frame.setShader(null);
        frame.setColor(0x40FFFFFF);
        float x = (swatchX[1][Ambient.COLORS.length - 1] + swatchR + b.left) / 2;
        c.drawLine(x, cy - swatchR, x, cy + swatchR, frame);
        fill.setShader(null);
        fill.setColor(fuelOn ? Chrome.withAlpha(Chrome.accent, 0xD0) : 0x18FFFFFF);
        c.drawCircle(cx, cy, r, fill);
        if (fuelOn) {
            ring.setColor(Chrome.TEXT);
            c.drawCircle(cx, cy, r + ring.getStrokeWidth() * 1.6f, ring);
        } else {
            c.drawCircle(cx, cy, r, frame);
        }
        // The pump: its body with a window, a base, and the hose from its side to the nozzle.
        float s = r * 1.65f, px = cx - s * 0.04f;
        line.setColor(fuelOn ? Chrome.TEXT : Chrome.TEXT_DIM);
        line.setStrokeWidth(Math.max(1.5f, s * 0.08f));
        c.drawRoundRect(px - s * 0.27f, cy - s * 0.36f, px + s * 0.09f, cy + s * 0.34f, s * 0.05f, s * 0.05f, line);
        c.drawLine(px - s * 0.36f, cy + s * 0.34f, px + s * 0.18f, cy + s * 0.34f, line);
        c.drawLine(px - s * 0.19f, cy - s * 0.16f, px + s * 0.01f, cy - s * 0.16f, line);
        pump.reset();
        pump.moveTo(px + s * 0.09f, cy - s * 0.06f);
        pump.lineTo(px + s * 0.2f, cy - s * 0.06f);
        pump.lineTo(px + s * 0.2f, cy + s * 0.16f);
        pump.quadTo(px + s * 0.2f, cy + s * 0.24f, px + s * 0.27f, cy + s * 0.24f);
        pump.quadTo(px + s * 0.34f, cy + s * 0.24f, px + s * 0.34f, cy + s * 0.16f);
        pump.lineTo(px + s * 0.34f, cy - s * 0.2f);
        pump.lineTo(px + s * 0.25f, cy - s * 0.32f);
        c.drawPath(pump, line);
    }

    /** The text, cut short with an ellipsis where it is wider than w. */
    private static String fit(String text, TextPaint p, float w) {
        if (p.measureText(text) <= w) return text;
        float dots = p.measureText("…");
        int n = text.length();
        while (n > 0 && p.measureText(text, 0, n) + dots > w) n--;
        return n == 0 ? "" : text.substring(0, n).trim() + "…";
    }

    /** The rev bar: its LEDs side by side, a glow under the lit ones in the colour at their tip. */
    private void drawLeds(Canvas c, RectF r, int[] leds) {
        int n = leds.length, lit = 0;
        for (int k = 0; k < n; k++) if ((leds[k] & 0xFFFFFF) != 0) lit = k + 1;
        float w = r.width() / n, gap = w >= 5 ? Math.max(1f, w * 0.18f) : 0, rr = r.height() / 2;
        if (lit > 0) {
            int tip = 0xFF000000 | leds[lit - 1];
            fill.setShader(null);
            fill.setColor(tip);
            fill.setShadowLayer(r.height() * 1.6f, 0, 0, tip);
            c.drawRoundRect(r.left, r.top, r.left + lit * w, r.bottom, rr, rr, fill);
            fill.clearShadowLayer();
        }
        fill.setShader(null);
        for (int k = 0; k < n; k++) {
            boolean isLit = (leds[k] & 0xFFFFFF) != 0;
            fill.setColor(isLit ? 0xFF000000 | leds[k] : 0x24FFFFFF);
            c.drawRect(r.left + k * w + gap / 2, r.top, r.left + (k + 1) * w - gap / 2, r.bottom, fill);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP || listener == null) return e.getAction() != MotionEvent.ACTION_CANCEL;
        float x = e.getX(), y = e.getY();
        for (int i = 0; i < 2; i++) {
            RectF s = new RectF(switches[i]);
            s.inset(-pad, -pad);
            if (s.contains(x, y)) {
                listener.onLightSwitch(i);
                return true;
            }
            if (i == 0 && !topColours) {
                if (shapeChip.contains(x, y)) {
                    listener.onLightShape();
                    return true;
                }
                continue;
            }
            if (i == 1 && fuelMode) {
                RectF fb = new RectF(fuelButton);
                fb.inset(-pad * 0.4f, -pad * 0.4f);
                if (fb.contains(x, y)) {
                    listener.onLightFuel();
                    return true;
                }
            }
            if (pair) {
                for (int k = 0; k < 2; k++) {
                    RectF sr = new RectF(slots[i][k]);
                    sr.inset(-pad * 0.3f, -pad * 0.3f);
                    if (sr.contains(x, y)) {
                        slot[i] = k;
                        invalidate();
                        return true;
                    }
                }
            }
            for (int k = 0; k < Ambient.COLORS.length; k++) {
                float dx = x - swatchX[i][k], dy = y - swatchY[i];
                if (dx * dx + dy * dy <= swatchR * swatchR * 2.6f) {
                    listener.onLightColor(i, pair ? slot[i] : 0, Ambient.COLORS[k]);
                    return true;
                }
            }
        }
        for (int k = 0; k < 4; k++) {
            if (modes[k].contains(x, y)) {
                listener.onLightMode(k);
                return true;
            }
        }
        if (more.contains(x, y)) listener.onLightSettings();
        return true;
    }
}
