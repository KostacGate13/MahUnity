package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.view.View;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** The time in large digits (the header above carries the greeting and the date), and the GPS speed when on. */
final class ClockView extends View {
    private final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint suffix = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint speed = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final String unitText;
    private String timeText = "", suffixText = "";
    /** Null while the speed row is off. */
    private String speedText;
    private String headingText = "";
    private float timeY, lineY, speedY, lineH;
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            schedule();
        }
    };

    ClockView(Context context) {
        super(context);
        unitText = context.getString(R.string.kmh);
        time.setTypeface(Chrome.LIGHT);
        suffix.setTypeface(Chrome.LIGHT);
        suffix.setColor(Chrome.TEXT_DIM);
        suffix.setLetterSpacing(0.1f);
        speed.setTypeface(Chrome.LIGHT);
        unit.setTypeface(Chrome.LIGHT);
        unit.setColor(Chrome.TEXT_DIM);
        unit.setLetterSpacing(0.2f);
    }

    void start() {
        ticking = true;
        refresh();
        schedule();
    }

    void stop() {
        ticking = false;
        removeCallbacks(tick);
    }

    /** value: what to show in large digits ("72", or a dash without a fix); heading may be null. */
    void setSpeed(String value, String heading) {
        boolean placed = speedText != null;
        speedText = value;
        headingText = heading == null ? "" : "·  " + heading;
        if (!placed) place();
        invalidate();
    }

    void hideSpeed() {
        if (speedText == null) return;
        speedText = null;
        place();
        invalidate();
    }

    private void schedule() {
        removeCallbacks(tick);
        if (ticking) postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000 + 20);
    }

    private void refresh() {
        Locale locale = Locale.getDefault();
        Calendar now = Calendar.getInstance(TimeZone.getDefault(), locale);
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int minute = now.get(Calendar.MINUTE);
        if (DateFormat.is24HourFormat(getContext())) {
            timeText = String.format(locale, "%02d:%02d", hour, minute);
            suffixText = "";
        } else {
            timeText = String.format(locale, "%d:%02d", hour % 12 == 0 ? 12 : hour % 12, minute);
            suffixText = Text.upper(new DateFormatSymbols(locale).getAmPmStrings()[now.get(Calendar.AM_PM)]);
        }
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        place();
    }

    private void place() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        // Everything is sized from the digits, which take what the panel's width and height allow.
        float t = Math.min(h * (speedText != null ? 0.5f : 0.62f), w * 0.3f);
        time.setTextSize(t);
        float wide = time.measureText("00:00");
        if (wide > w * 0.82f) {
            t *= w * 0.82f / wide;
            time.setTextSize(t);
        }
        suffix.setTextSize(t * 0.18f);
        speed.setTextSize(t * 0.36f);
        unit.setTextSize(t * 0.13f);

        // The time, then a hairline and the speed row when it is on; cap heights are about 0.71em.
        float timeCap = t * 0.71f;
        float speedCap = speed.getTextSize() * 0.71f;
        float block = timeCap;
        if (speedText != null) block += t * 0.22f + t * 0.24f + speedCap;
        float top = Math.max(0f, (h - block) * 0.5f);
        timeY = top + timeCap;
        lineY = timeY + t * 0.22f;
        speedY = lineY + t * 0.24f + speedCap;
        lineH = Math.max(1f, t * 0.008f);
        time.setShader(Chrome.metal(timeY - timeCap, timeY));
        speed.setShader(Chrome.metal(speedY - speedCap, speedY));
        line.setShader(Chrome.fadeRight(0, w, 0xB0));
    }

    @Override protected void onDraw(Canvas c) {
        // Light digits sit a little inside their advance; pull them back so they line up with the text below.
        float nudge = time.getTextSize() * 0.03f;
        c.drawText(timeText, -nudge, timeY, time);
        if (!suffixText.isEmpty()) {
            c.drawText(suffixText, time.measureText(timeText) + suffix.getTextSize() * 0.5f, timeY, suffix);
        }
        if (speedText != null) {
            c.drawRect(0, lineY, getWidth(), lineY + lineH, line);
            c.drawText(speedText, -speed.getTextSize() * 0.02f, speedY, speed);
            float x = speed.measureText(speedText) + unit.getTextSize() * 0.6f;
            c.drawText(unitText, x, speedY, unit);
            if (!headingText.isEmpty()) {
                c.drawText(headingText, x + unit.measureText(unitText) + unit.getTextSize() * 0.9f, speedY, unit);
            }
        }
    }
}
