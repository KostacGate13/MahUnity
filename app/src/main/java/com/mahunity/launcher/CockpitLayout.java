package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * Home with the car: glass cards on the left that change with the tab, the car on its turntable on the
 * right, the dock along the bottom. Portrait screens stack the car above the cards. Sizes are proportions
 * of the screen, like HomeLayout.
 */
final class CockpitLayout extends ViewGroup {
    static final int HOME = 0, MUSIC = 1, VEHICLE = 2;

    final TopBar top;
    final ChromeButton gear;
    final ClockView clock;
    final MediaCard media;
    final TileView[] sources;
    final VehicleView vehicle;
    final CarView car;
    final DockBar dock;

    private final Rect topRect = new Rect(), gearRect = new Rect(), dockRect = new Rect(), stageRect = new Rect();
    private final Rect clockRect = new Rect(), clockGlass = new Rect(), mediaRect = new Rect(), vehicleRect = new Rect();
    private final Rect[] sourceRects;
    private final RectF glass = new RectF();
    private final Paint background = new Paint();
    private final Paint glassFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.6f);
    private float glassRadius;
    private int tab = HOME;

    CockpitLayout(Context context, TopBar top, ChromeButton gear, ClockView clock, MediaCard media,
                  TileView[] sources, VehicleView vehicle, CarView car, DockBar dock) {
        super(context);
        this.top = top;
        this.gear = gear;
        this.clock = clock;
        this.media = media;
        this.sources = sources;
        this.vehicle = vehicle;
        this.car = car;
        this.dock = dock;
        sourceRects = new Rect[sources.length];
        for (int i = 0; i < sources.length; i++) sourceRects[i] = new Rect();
        setWillNotDraw(false);
        glassEdge.setStyle(Paint.Style.STROKE);
        addView(car);
        addView(top);
        addView(gear);
        addView(clock);
        addView(media);
        for (TileView t : sources) addView(t);
        addView(vehicle);
        addView(dock);
        showTab(HOME, false);
    }

    int tab() {
        return tab;
    }

    /** Switches the cards on the left and turns the car to face the matching way. */
    void showTab(int next, boolean animate) {
        tab = next;
        clock.setVisibility(next == HOME ? VISIBLE : GONE);
        media.setVisibility(next == VEHICLE ? GONE : VISIBLE);
        for (TileView t : sources) t.setVisibility(next == MUSIC ? VISIBLE : GONE);
        vehicle.setVisibility(next == VEHICLE ? VISIBLE : GONE);
        car.turnTo(next == MUSIC ? CarView.RIGHT_SIDE : next == VEHICLE ? CarView.REAR : CarView.FRONT);
        requestLayout();
        invalidate();
        if (!animate) return;
        View[] incoming = next == HOME ? new View[] {clock, media}
                : next == MUSIC ? concat(media, sources) : new View[] {vehicle};
        float shift = -getWidth() * 0.02f;
        for (View v : incoming) {
            v.setAlpha(0f);
            v.setTranslationX(shift);
            v.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(ease).start();
        }
    }

    private static View[] concat(View first, View[] rest) {
        View[] all = new View[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        place(w, h);
        measure(top, topRect);
        measure(gear, gearRect);
        measure(dock, dockRect);
        measure(car, stageRect);
        measure(clock, clockRect);
        measure(media, mediaRect);
        measure(vehicle, vehicleRect);
        for (int i = 0; i < sources.length; i++) measure(sources[i], sourceRects[i]);
        setMeasuredDimension(w, h);
    }

    private static void measure(View v, Rect r) {
        v.measure(MeasureSpec.makeMeasureSpec(Math.max(0, r.width()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, r.height()), MeasureSpec.EXACTLY));
    }

    private void place(int w, int h) {
        boolean portrait = h > w;
        float s = Math.min(h, w * 0.6f);
        int pad = Math.round(s * 0.035f);
        int barH = Math.round(s * 0.11f);
        int dockH = Math.round(s * 0.15f);
        topRect.set(0, 0, w, barH);
        top.setInset(pad);
        int g = Math.round(barH * 0.62f);
        gearRect.set(w - pad - g, (barH - g) / 2, w - pad, (barH - g) / 2 + g);
        top.showClock(w - gearRect.left + pad);
        dockRect.set(0, h - dockH, w, h);

        int contentTop = barH + Math.round(pad * 0.8f);
        int contentBottom = h - dockH - Math.round(pad * 0.8f);
        Rect pane = new Rect();
        if (!portrait) {
            int paneRight = Math.round(w * 0.38f);
            pane.set(pad, contentTop, paneRight, contentBottom);
            stageRect.set(paneRight, barH, w, h - dockH);
        } else {
            int stageBottom = contentTop + Math.round((contentBottom - contentTop) * 0.4f);
            stageRect.set(0, barH, w, stageBottom);
            pane.set(pad, stageBottom + pad, w - pad, contentBottom);
        }
        int ph = pane.height(), gap = Math.round(pad * 0.7f);
        // Home: the clock on glass over the media card. Music: a taller media card over three sources.
        clockGlass.set(pane.left, pane.top, pane.right, pane.top + Math.round(ph * 0.54f));
        clockRect.set(clockGlass);
        clockRect.inset(Math.round(pad * 0.9f), Math.round(pad * 0.6f));
        if (tab == MUSIC) {
            mediaRect.set(pane.left, pane.top, pane.right, pane.top + Math.round(ph * 0.52f));
            int top = mediaRect.bottom + gap;
            float cw = (pane.width() - gap * (sources.length - 1)) / (float) sources.length;
            for (int i = 0; i < sources.length; i++) {
                int l = Math.round(pane.left + i * (cw + gap));
                sourceRects[i].set(l, top, Math.round(l + cw), pane.bottom);
            }
        } else {
            mediaRect.set(pane.left, pane.bottom - Math.round(ph * 0.42f), pane.right, pane.bottom);
        }
        vehicleRect.set(pane);
        glassRadius = s * 0.025f;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layout(car, stageRect);
        layout(top, topRect);
        layout(gear, gearRect);
        layout(dock, dockRect);
        layout(clock, clockRect);
        layout(media, mediaRect);
        layout(vehicle, vehicleRect);
        for (int i = 0; i < sources.length; i++) layout(sources[i], sourceRects[i]);
    }

    private static void layout(View v, Rect r) {
        v.layout(r.left, r.top, r.right, r.bottom);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        background.setShader(new RadialGradient(w * 0.68f, h * 0.45f, Math.max(w, h) * 0.7f,
                new int[] {0xFF15191D, 0xFF08090B, 0xFF000000}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        glassFill.setShader(Chrome.panel(0, h));
        glassFill.setAlpha(0xC8);
        glassEdge.setStrokeWidth(Math.max(1f, Math.min(w, h) * 0.0015f));
        glassEdge.setShader(Chrome.edge(0, 0, w, h));
        glassEdge.setAlpha(0x90);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), background);
        // Glass under the cards that have none of their own (the media card and tiles draw theirs).
        if (tab == HOME) drawGlass(c, clockGlass);
        if (tab == VEHICLE) drawGlass(c, vehicleRect);
    }

    private void drawGlass(Canvas c, Rect r) {
        glass.set(r);
        c.drawRoundRect(glass, glassRadius, glassRadius, glassFill);
        c.drawRoundRect(glass, glassRadius, glassRadius, glassEdge);
    }
}
