package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * Home with the car: glass cards on the left that change with the tab, the car on its turntable on the
 * right, the dock along the bottom. Portrait screens stack the car above the cards. Sizes are proportions
 * of the screen, like HomeLayout.
 */
final class CockpitLayout extends ViewGroup {
    static final int HOME = 0, MUSIC = 1, VEHICLE = 2, LIGHTS = 3;

    final TopBar top;
    final ChromeButton gear;
    /** Keys left of the gear, right to left (on NWD units: screen off, then DSP); may be empty. */
    final ChromeButton[] keys;
    final Header header;
    /** Home's widgets, picked in Settings: the clock or the speedometer-clock, and the media card or another. */
    final View homeTop, homeBottom;
    final MediaCard media;
    final TileView[] sources;
    final VehicleView vehicle;
    final CarView car;
    final DockBar dock;

    private final Rect[] keyRects;
    private final Rect topRect = new Rect(), gearRect = new Rect(), headerRect = new Rect(),
            dockRect = new Rect();
    private final Rect stageRect = new Rect();
    private final Rect clockRect = new Rect(), clockGlass = new Rect(), bottomRect = new Rect(), mediaRect = new Rect();
    private final Rect vehicleRect = new Rect();
    private final View lights;
    private final Rect[] sourceRects;
    private final RectF glass = new RectF();
    private final Paint background = new Paint();
    private final Paint backgroundGlow = new Paint();
    private final Paint glassFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.6f);
    private float glassRadius;
    private int tab = HOME;

    /** homeBottom may be the media card itself; otherwise the media card shows on the Music tab only. */
    CockpitLayout(Context context, TopBar top, ChromeButton gear, ChromeButton[] keys, Header header, View homeTop,
                  View homeBottom, MediaCard media, TileView[] sources, VehicleView vehicle, View lights, CarView car,
                  DockBar dock) {
        super(context);
        this.top = top;
        this.gear = gear;
        this.keys = keys;
        keyRects = new Rect[keys.length];
        for (int i = 0; i < keys.length; i++) keyRects[i] = new Rect();
        this.header = header;
        this.homeTop = homeTop;
        this.homeBottom = homeBottom;
        this.media = media;
        this.sources = sources;
        this.vehicle = vehicle;
        this.lights = lights;
        this.car = car;
        this.dock = dock;
        sourceRects = new Rect[sources.length];
        for (int i = 0; i < sources.length; i++) sourceRects[i] = new Rect();
        setWillNotDraw(false);
        glassEdge.setStyle(Paint.Style.STROKE);
        addView(car);
        addView(top);
        addView(gear);
        for (ChromeButton k : keys) addView(k);
        addView(header);
        addView(homeTop);
        if (homeBottom != media) addView(homeBottom);
        addView(media);
        for (TileView t : sources) addView(t);
        addView(vehicle);
        addView(lights);
        addView(dock);
        showTab(HOME, false);
    }

    int tab() {
        return tab;
    }

    /** Switches the cards on the left and turns the car to face the matching way. */
    void showTab(int next, boolean animate) {
        tab = next;
        homeTop.setVisibility(next == HOME ? VISIBLE : GONE);
        top.hideClock(next == HOME && homeTop instanceof ClockView);
        homeBottom.setVisibility(next == HOME ? VISIBLE : GONE);
        media.setVisibility(next == MUSIC || (next == HOME && homeBottom == media) ? VISIBLE : GONE);
        for (TileView t : sources) t.setVisibility(next == MUSIC ? VISIBLE : GONE);
        vehicle.setVisibility(next == VEHICLE ? VISIBLE : GONE);
        lights.setVisibility(next == LIGHTS ? VISIBLE : GONE);
        car.turnTo(next == MUSIC ? CarView.RIGHT_SIDE : next == VEHICLE ? CarView.REAR
                : next == LIGHTS ? CarView.FRONT_LEFT : CarView.FRONT);
        requestLayout();
        invalidate();
        if (!animate) return;
        View[] incoming = next == HOME ? new View[] {homeTop, homeBottom}
                : next == MUSIC ? concat(media, sources) : next == LIGHTS ? new View[] {lights} : new View[] {vehicle};
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
        for (int i = 0; i < keys.length; i++) measure(keys[i], keyRects[i]);
        measure(header, headerRect);
        measure(dock, dockRect);
        measure(car, stageRect);
        measure(homeTop, clockRect);
        if (homeBottom != media) measure(homeBottom, bottomRect);
        measure(media, mediaRect);
        measure(vehicle, vehicleRect);
        measure(lights, vehicleRect);
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
        int barH = Math.round(s * 0.1f);
        int headerH = Math.round(s * 0.1f);
        int dockH = Math.round(s * 0.13f);
        topRect.set(0, 0, w, barH);
        top.setInset(pad);
        int g = Math.round(barH * 0.62f);
        gearRect.set(w - pad - g, (barH - g) / 2, w - pad, (barH - g) / 2 + g);
        int keyRight = gearRect.left;
        for (Rect r : keyRects) {
            r.set(keyRight - pad / 2 - g, gearRect.top, keyRight - pad / 2, gearRect.bottom);
            keyRight = r.left;
        }
        top.showClock(w - keyRight + pad);
        headerRect.set(0, barH, w, barH + headerH);
        header.setInset(pad);
        dockRect.set(0, h - dockH, w, h);

        int contentTop = barH + headerH;
        int contentBottom = h - dockH - Math.round(pad * 0.8f);
        Rect pane = new Rect();
        if (!portrait) {
            int paneRight = Math.round(w * 0.42f);
            pane.set(pad, contentTop, paneRight, contentBottom);
            stageRect.set(paneRight, contentTop, w, h - dockH);
        } else {
            int stageBottom = contentTop + Math.round((contentBottom - contentTop) * 0.4f);
            stageRect.set(0, contentTop, w, stageBottom);
            pane.set(pad, stageBottom + pad, w - pad, contentBottom);
        }
        int ph = pane.height(), gap = Math.round(pad * 0.7f);
        // Home: the upper widget on glass over the lower one. Music: a taller media card over the three sources,
        // one short, wide row each, so their names fit.
        clockGlass.set(pane.left, pane.top, pane.right, pane.top + Math.round(ph * 0.54f));
        clockRect.set(clockGlass);
        clockRect.inset(Math.round(pad * 0.9f), Math.round(pad * 0.6f));
        bottomRect.set(pane.left, pane.bottom - Math.round(ph * 0.42f), pane.right, pane.bottom);
        if (tab == MUSIC) {
            mediaRect.set(pane.left, pane.top, pane.right, pane.top + Math.round(ph * 0.46f));
            int top = mediaRect.bottom + gap;
            float rh = (pane.bottom - top - gap * (sources.length - 1)) / (float) sources.length;
            for (int i = 0; i < sources.length; i++) {
                int t = Math.round(top + i * (rh + gap));
                sourceRects[i].set(pane.left, t, pane.right, Math.round(t + rh));
            }
        } else {
            mediaRect.set(bottomRect);
        }
        vehicleRect.set(pane);
        glassRadius = Chrome.radius(getContext());
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layout(car, stageRect);
        layout(top, topRect);
        layout(gear, gearRect);
        for (int i = 0; i < keys.length; i++) layout(keys[i], keyRects[i]);
        layout(header, headerRect);
        layout(dock, dockRect);
        layout(homeTop, clockRect);
        if (homeBottom != media) layout(homeBottom, bottomRect);
        layout(media, mediaRect);
        layout(vehicle, vehicleRect);
        layout(lights, vehicleRect);
        for (int i = 0; i < sources.length; i++) layout(sources[i], sourceRects[i]);
    }

    private static void layout(View v, Rect r) {
        v.layout(r.left, r.top, r.right, r.bottom);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        Chrome.resetBackground(background);
        glassFill.setShader(Chrome.panel(0, h));
        glassEdge.setStrokeWidth(Math.max(1f, Math.min(w, h) * 0.0015f));
        glassEdge.setShader(Chrome.edge(0, 0, w, h));
        glassEdge.setAlpha(0x90);
    }

    @Override protected void onDraw(Canvas c) {
        Chrome.background(c, getWidth(), getHeight(), background, backgroundGlow);
        // Glass under the cards that have none of their own (the media card, widgets and tiles draw theirs).
        if (tab == HOME) drawGlass(c, clockGlass);
    }

    private void drawGlass(Canvas c, Rect r) {
        glass.set(r);
        c.drawRoundRect(glass, glassRadius, glassRadius, glassFill);
        c.drawRoundRect(glass, glassRadius, glassRadius, glassEdge);
    }
}
