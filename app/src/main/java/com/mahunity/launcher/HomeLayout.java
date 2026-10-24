package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

/**
 * Home, laid out in proportions of the screen rather than fixed sizes, so the same build fills a 1024x600
 * and a 1280x720 head unit alike. Landscape: the two widgets (clock, media card) on the left, a 3x2 grid of tiles on
 * the right. Portrait: the same stacked, tiles 2x3. Also paints the background and the ambient light strip.
 */
final class HomeLayout extends ViewGroup {
    final TopBar top;
    final ChromeButton gear;
    /** The screen-off button left of the gear, on units that can turn their screen off; else null. */
    final ChromeButton power;
    final Header header;
    /** The widgets picked in Settings: the clock or the speedometer-clock, and the media card or another. */
    final View upper, lower;
    final TileView[] tiles;

    private final Rect topRect = new Rect(), gearRect = new Rect(), powerRect = new Rect(), headerRect = new Rect(),
            clockRect = new Rect();
    private final Rect mediaRect = new Rect();
    private final Rect[] tileRects;
    private final Paint background = new Paint();
    private final Paint backgroundGlow = new Paint();
    private final Paint divider = new Paint();
    private final Paint strip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripGlow = new Paint();
    private final Matrix squash = new Matrix();
    private float dividerX = -1, dividerTop, dividerBottom, stripY, stripH;
    private int stripColor;

    HomeLayout(Context context, TopBar top, ChromeButton gear, ChromeButton power, Header header, View upper, View lower,
               TileView[] tiles) {
        super(context);
        this.top = top;
        this.gear = gear;
        this.power = power;
        this.header = header;
        this.upper = upper;
        this.lower = lower;
        this.tiles = tiles;
        tileRects = new Rect[tiles.length];
        for (int i = 0; i < tiles.length; i++) tileRects[i] = new Rect();
        setWillNotDraw(false);
        addView(top);
        addView(gear);
        if (power != null) addView(power);
        addView(header);
        addView(upper);
        top.hideClock(upper instanceof ClockView);
        addView(lower);
        for (TileView t : tiles) addView(t);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        place(w, h);
        measure(top, topRect);
        measure(gear, gearRect);
        if (power != null) measure(power, powerRect);
        measure(header, headerRect);
        measure(upper, clockRect);
        measure(lower, mediaRect);
        for (int i = 0; i < tiles.length; i++) measure(tiles[i], tileRects[i]);
        setMeasuredDimension(w, h);
    }

    private static void measure(View v, Rect r) {
        v.measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY));
    }

    private void place(int w, int h) {
        boolean portrait = h > w;
        float s = Math.min(h, w * 0.6f);
        int pad = Math.round(s * 0.035f);
        int barH = Math.round(s * 0.1f);
        int headerH = Math.round(s * 0.1f);
        topRect.set(0, 0, w, barH);
        top.setInset(pad);
        int g = Math.round(barH * 0.62f);
        gearRect.set(w - pad - g, (barH - g) / 2, w - pad, (barH - g) / 2 + g);
        powerRect.set(gearRect.left - pad / 2 - g, gearRect.top, gearRect.left - pad / 2, gearRect.bottom);
        top.showClock(w - (power != null ? powerRect.left : gearRect.left) + pad);
        headerRect.set(0, barH, w, barH + headerH);
        header.setInset(pad);

        int contentTop = barH + headerH;
        int contentBottom = h - Math.round(pad * 1.4f);
        int ch = contentBottom - contentTop;
        int gap = Math.round(pad * 0.7f);
        if (!portrait) {
            int heroRight = Math.round(w * 0.38f);
            clockRect.set(pad, contentTop, heroRight, contentTop + Math.round(ch * 0.54f));
            mediaRect.set(pad, contentBottom - Math.round(ch * 0.42f), heroRight, contentBottom);
            dividerX = heroRight + pad;
            dividerTop = contentTop + ch * 0.06f;
            dividerBottom = contentBottom - ch * 0.06f;
            grid(heroRight + 2 * pad, contentTop, w - pad, contentBottom, 3, gap);
        } else {
            clockRect.set(pad, contentTop, w - pad, contentTop + Math.round(ch * 0.26f));
            mediaRect.set(pad, clockRect.bottom + pad, w - pad, clockRect.bottom + pad + Math.round(ch * 0.16f));
            dividerX = -1;
            grid(pad, mediaRect.bottom + 2 * pad, w - pad, contentBottom, 2, gap);
        }
        stripY = h - pad * 0.6f;
        stripH = Math.max(1.5f, s * 0.004f);
    }

    private void grid(int left, int top, int right, int bottom, int columns, int gap) {
        int rows = (tiles.length + columns - 1) / columns;
        float cw = (right - left - gap * (columns - 1)) / (float) columns;
        float rh = (bottom - top - gap * (rows - 1)) / (float) rows;
        for (int i = 0; i < tiles.length; i++) {
            int col = i % columns, row = i / columns;
            int l = Math.round(left + col * (cw + gap));
            int t = Math.round(top + row * (rh + gap));
            tileRects[i].set(l, t, Math.round(l + cw), Math.round(t + rh));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layout(top, topRect);
        layout(gear, gearRect);
        if (power != null) layout(power, powerRect);
        layout(header, headerRect);
        layout(upper, clockRect);
        layout(lower, mediaRect);
        for (int i = 0; i < tiles.length; i++) layout(tiles[i], tileRects[i]);
    }

    private static void layout(View v, Rect r) {
        v.layout(r.left, r.top, r.right, r.bottom);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        Chrome.resetBackground(background);
        divider.setShader(Chrome.hairline(0, dividerTop, 0, dividerBottom, 0x90));
        stripColor = 0;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        Chrome.background(c, w, h, background, backgroundGlow);
        if (dividerX > 0) c.drawRect(dividerX, dividerTop, dividerX + 1, dividerBottom, divider);

        // The ambient light strip along the bottom edge, in the colour picked in Settings.
        if (stripColor != Chrome.accent) {
            stripColor = Chrome.accent;
            int clear = stripColor & 0x00FFFFFF;
            strip.setShader(new LinearGradient(w * 0.1f, 0, w * 0.9f, 0,
                    new int[] {clear, Chrome.withAlpha(stripColor, 0xE0), clear}, null, Shader.TileMode.CLAMP));
            RadialGradient glow = new RadialGradient(w / 2f, stripY, w * 0.42f,
                    Chrome.withAlpha(stripColor, 0x30), clear, Shader.TileMode.CLAMP);
            squash.setScale(1f, 0.1f, w / 2f, stripY);
            glow.setLocalMatrix(squash);
            stripGlow.setShader(glow);
        }
        c.drawRect(0, stripY - w * 0.05f, w, h, stripGlow);
        c.drawRect(w * 0.1f, stripY, w * 0.9f, stripY + stripH, strip);
    }
}
