package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * The strip across the top: a name in spaced-out letters over a full-width hairline, and on the right the
 * outside temperature and the time, large enough to read at a glance.
 */
final class TopBar extends View {
    private final TextPaint mark = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint outside = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private String text = "", timeText = "", outsideText = "";
    private float baseline;
    private int inset, clockRight = -1;
    private boolean ticking, clockHidden;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar now = Calendar.getInstance();
            int hour = now.get(Calendar.HOUR_OF_DAY), minute = now.get(Calendar.MINUTE);
            boolean is24 = DateFormat.is24HourFormat(getContext());
            if (!is24) hour = hour % 12 == 0 ? 12 : hour % 12;
            timeText = String.format(Locale.getDefault(), is24 ? "%02d:%02d" : "%d:%02d", hour, minute);
            invalidate();
            if (ticking) postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 20);
        }
    };

    TopBar(Context context) {
        super(context);
        mark.setTypeface(Chrome.MEDIUM);
        mark.setLetterSpacing(0.24f);
        time.setTypeface(Chrome.MEDIUM);
        time.setColor(Chrome.TEXT);
        time.setLetterSpacing(0.02f);
        time.setTextAlign(Paint.Align.RIGHT);
        outside.setTypeface(Chrome.REGULAR);
        outside.setColor(Chrome.TEXT_DIM);
        outside.setTextAlign(Paint.Align.RIGHT);
    }

    /** The outside temperature from the car, shown left of the small clock; null hides it. */
    void setOutside(String s) {
        outsideText = s == null ? "" : s;
        invalidate();
    }

    /** Shows the small clock with its right edge this far from the bar's right edge. */
    void showClock(int fromRight) {
        clockRight = fromRight;
    }

    /** Hides the time while a larger clock is on screen (the Home tab's clock widget); the temperature stays. */
    void hideClock(boolean hidden) {
        if (hidden == clockHidden) return;
        clockHidden = hidden;
        invalidate();
    }

    /** Keeps the small clock current; it is drawn only once the layout has placed it (showClock). */
    void start() {
        ticking = true;
        removeCallbacks(tick);
        tick.run();
    }

    void stop() {
        ticking = false;
        removeCallbacks(tick);
    }

    void setText(String s) {
        text = Text.upper(s);
        invalidate();
    }

    /** Left margin of the name, set by the layout to line up with the panels below. */
    void setInset(int px) {
        if (px == inset) return;
        inset = px;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float size = h * 0.3f;
        mark.setTextSize(size);
        time.setTextSize(h * 0.42f);
        baseline = h / 2f + time.getTextSize() * 0.36f;
        mark.setShader(Chrome.metal(baseline - size * 0.72f, baseline));
        outside.setTextSize(h * 0.3f);
        line.setShader(Chrome.hairline(0, 0, w, 0, 0xC0));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawText(text, inset, baseline, mark);
        if (clockRight >= 0) {
            float x = getWidth() - clockRight;
            if (!clockHidden) {
                c.drawText(timeText, x, baseline, time);
                x -= time.measureText(timeText) + time.getTextSize() * 0.8f;
            }
            if (!outsideText.isEmpty()) c.drawText(outsideText, x, baseline, outside);
        }
        int h = getHeight();
        c.drawRect(0, h - 1, getWidth(), h, line);
    }
}
