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
 * The strip across the top: a name in spaced-out metal letters over a full-width hairline, and in the
 * cockpit a small clock on the right, since the big one shows only on the Home tab.
 */
final class TopBar extends View {
    private final TextPaint mark = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private String text = "", timeText = "";
    private float baseline;
    private int inset, clockRight = -1;
    private boolean ticking;

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
        mark.setTypeface(Chrome.LIGHT);
        mark.setLetterSpacing(0.42f);
        time.setTypeface(Chrome.LIGHT);
        time.setColor(Chrome.TEXT);
        time.setLetterSpacing(0.08f);
        time.setTextAlign(Paint.Align.RIGHT);
    }

    /** Shows the small clock with its right edge this far from the bar's right edge. */
    void showClock(int fromRight) {
        clockRight = fromRight;
    }

    void start() {
        if (clockRight < 0) return;
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
        float size = h * 0.28f;
        mark.setTextSize(size);
        baseline = h / 2f + size * 0.36f;
        mark.setShader(Chrome.metal(baseline - size * 0.72f, baseline));
        time.setTextSize(h * 0.3f);
        line.setShader(Chrome.hairline(0, 0, w, 0, 0xC0));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawText(text, inset, baseline, mark);
        if (clockRight >= 0) c.drawText(timeText, getWidth() - clockRight, baseline, time);
        int h = getHeight();
        c.drawRect(0, h - 1, getWidth(), h, line);
    }
}
