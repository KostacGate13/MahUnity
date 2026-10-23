package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.view.View;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** The left-hand panel: a greeting, the time in thin metal digits, the date, and the GPS speed when it is on. */
final class ClockView extends View {
    private final TextPaint greet = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint suffix = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint date = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint speed = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final String unitText;
    private String greetText = "", timeText = "", suffixText = "", dateText = "";
    /** Null while the speed row is off. */
    private String speedText;
    private String headingText = "";
    private float greetY, timeY, dateY, lineY, speedY, lineH;
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
        greet.setTypeface(Chrome.LIGHT);
        greet.setColor(Chrome.TEXT_DIM);
        greet.setLetterSpacing(0.3f);
        time.setTypeface(Chrome.THIN);
        suffix.setTypeface(Chrome.LIGHT);
        suffix.setColor(Chrome.TEXT_DIM);
        suffix.setLetterSpacing(0.1f);
        date.setTypeface(Chrome.REGULAR);
        date.setColor(Chrome.TEXT);
        date.setLetterSpacing(0.22f);
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
        dateText = Text.upper(new SimpleDateFormat("EEEE d MMMM", locale).format(now.getTime()));
        int greeting = hour >= 5 && hour < 12 ? R.string.greet_morning
                : hour >= 12 && hour < 18 ? R.string.greet_afternoon : R.string.greet_evening;
        greetText = Text.upper(getContext().getString(greeting));
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        place();
    }

    private void place() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        // Everything is sized from the digits, which take what the panel's width and height allow.
        float t = Math.min(h * 0.46f, w * 0.29f);
        time.setTextSize(t);
        float wide = time.measureText("00:00");
        if (wide > w * 0.82f) {
            t *= w * 0.82f / wide;
            time.setTextSize(t);
        }
        greet.setTextSize(t * 0.13f);
        suffix.setTextSize(t * 0.18f);
        date.setTextSize(t * 0.14f);
        speed.setTextSize(t * 0.36f);
        unit.setTextSize(t * 0.13f);

        // Greeting, time, date and hairline, then the speed row when it is on; cap heights are about 0.71em.
        float greetCap = greet.getTextSize() * 0.71f;
        float timeCap = t * 0.71f;
        float dateCap = date.getTextSize() * 0.71f;
        float speedCap = speed.getTextSize() * 0.71f;
        float block = greetCap + t * 0.15f + timeCap + t * 0.2f + dateCap + t * 0.17f;
        if (speedText != null) block += t * 0.24f + speedCap;
        float top = Math.max(0f, (h - block) * (speedText != null ? 0.5f : 0.4f));
        greetY = top + greetCap;
        timeY = greetY + t * 0.15f + timeCap;
        dateY = timeY + t * 0.2f + dateCap;
        lineY = dateY + t * 0.17f;
        speedY = lineY + t * 0.24f + speedCap;
        lineH = Math.max(1f, t * 0.008f);
        time.setShader(Chrome.metal(timeY - timeCap, timeY));
        speed.setShader(Chrome.metal(speedY - speedCap, speedY));
        line.setShader(Chrome.fadeRight(0, w, 0xB0));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawText(greetText, 0, greetY, greet);
        // Thin digits sit a little inside their advance; pull them back so they line up with the text below.
        float nudge = time.getTextSize() * 0.03f;
        c.drawText(timeText, -nudge, timeY, time);
        if (!suffixText.isEmpty()) {
            c.drawText(suffixText, time.measureText(timeText) + suffix.getTextSize() * 0.5f, timeY, suffix);
        }
        c.drawText(dateText, 0, dateY, date);
        c.drawRect(0, lineY, getWidth(), lineY + lineH, line);
        if (speedText != null) {
            c.drawText(speedText, -speed.getTextSize() * 0.02f, speedY, speed);
            float x = speed.measureText(speedText) + unit.getTextSize() * 0.6f;
            c.drawText(unitText, x, speedY, unit);
            if (!headingText.isEmpty()) {
                c.drawText(headingText, x + unit.measureText(unitText) + unit.getTextSize() * 0.9f, speedY, unit);
            }
        }
    }
}
