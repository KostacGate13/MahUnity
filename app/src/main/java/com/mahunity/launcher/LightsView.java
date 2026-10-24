package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

/**
 * The Lights tab: a card for each LED strip (the dashboard, the footwells) with its switch, a bar glowing in the
 * colour it shows right now (the dashboard's rev bar LED by LED) and the colours to choose from; under them the
 * four modes, and the way to the rest of the settings.
 */
final class LightsView extends View {
    interface Listener {
        void onLightSwitch(int role);

        void onLightColor(int role, int color);

        void onLightMode(int mode);

        void onLightSettings();
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint swatch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chip = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] cards = {new RectF(), new RectF()}, switches = {new RectF(), new RectF()},
            bars = {new RectF(), new RectF()};
    private final RectF[] modes = new RectF[4];
    private final RectF more = new RectF();
    private final float[][] swatchX = new float[2][Ambient.COLORS.length];
    private final float[] swatchY = new float[2];
    private final String[] names, modeNames;
    private final String moreText;
    private final String[] status = {"", ""};
    private final boolean[] on = new boolean[2];
    private final int[] chosen = new int[2], shown = new int[2];
    private int[] revBar;
    private int mode;
    private float radius, pad, swatchR;
    private Listener listener;
    private Shader cardFill, cardFrame;

    LightsView(Context context) {
        super(context);
        names = new String[] {context.getString(R.string.led_top), context.getString(R.string.led_bottom)};
        modeNames = new String[Ambient.MODE_NAMES.length];
        for (int i = 0; i < modeNames.length; i++) modeNames[i] = context.getString(Ambient.MODE_NAMES[i]);
        moreText = context.getString(R.string.led_settings) + "  ›";
        for (int i = 0; i < modes.length; i++) modes[i] = new RectF();
        frame.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
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

    /** The state to draw: each strip's switch, own colour, shown colour and connection; the mode. */
    void set(Ambient a, String topStatus, String bottomStatus) {
        on[0] = a.topOn;
        on[1] = a.bottomOn;
        chosen[0] = a.topColor;
        chosen[1] = a.bottomColor;
        shown[0] = a.shownTop;
        shown[1] = a.shownBottom;
        revBar = a.topBar;
        mode = a.mode;
        status[0] = topStatus;
        status[1] = bottomStatus;
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
            int n = Ambient.COLORS.length;
            swatchR = Math.min((c.width() - 2 * pad) / n * 0.36f, cardH * 0.13f);
            swatchY[i] = c.bottom - pad - swatchR;
            for (int k = 0; k < n; k++) swatchX[i][k] = c.left + pad + (c.width() - 2 * pad) * (k + 0.5f) / n;
        }
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
            CharSequence st = TextUtils.ellipsize(status[i], note, switches[i].left - (r.left + pad + nameW + pad) - pad,
                    TextUtils.TruncateAt.END);
            c.drawText(st, 0, st.length(), r.left + pad + nameW + pad, r.top + pad + title.getTextSize() * 0.8f, note);
            drawSwitch(c, switches[i], on[i]);
            if (i == 0 && revBar != null && on[0]) drawLeds(c, bars[0], revBar);
            else drawBar(c, bars[i], on[i] ? shown[i] : 0);
            for (int k = 0; k < Ambient.COLORS.length; k++) {
                int col = Ambient.COLORS[k];
                swatch.setColor(0xFF000000 | col);
                c.drawCircle(swatchX[i][k], swatchY[i], swatchR, swatch);
                if (col == chosen[i]) {
                    ring.setColor(Chrome.TEXT);
                    c.drawCircle(swatchX[i][k], swatchY[i], swatchR + ring.getStrokeWidth() * 1.6f, ring);
                }
            }
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
            for (int k = 0; k < Ambient.COLORS.length; k++) {
                float dx = x - swatchX[i][k], dy = y - swatchY[i];
                if (dx * dx + dy * dy <= swatchR * swatchR * 2.6f) {
                    listener.onLightColor(i, Ambient.COLORS[k]);
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
