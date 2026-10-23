package com.mahunity.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * The speedometer-clock (Settings > Upper widget): a dial to 200 km/h with a needle in the accent colour,
 * the time in thin metal digits at its centre, the date above and the speed in figures below.
 */
final class SpeedGauge extends View {
    private static final float MAX = 200f, START = 135f, SWEEP = 270f;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint numbers = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint date = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint speedText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dial = new RectF();
    private final String unit;
    private float cx, cy, r, shown;
    private int faceColor;
    private String timeText = "", dateText = "", speedLabel = "";
    private ValueAnimator move;
    private boolean ticking;

    private final Runnable tickClock = new Runnable() {
        @Override public void run() {
            refreshTime();
            if (ticking) postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 20);
        }
    };

    SpeedGauge(Context context) {
        super(context);
        unit = context.getString(R.string.kmh);
        ring.setStyle(Paint.Style.STROKE);
        tick.setStrokeCap(Paint.Cap.ROUND);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        needle.setStrokeCap(Paint.Cap.ROUND);
        numbers.setTypeface(Chrome.LIGHT);
        numbers.setColor(Chrome.TEXT_DIM);
        numbers.setTextAlign(Paint.Align.CENTER);
        time.setTypeface(Chrome.THIN);
        time.setTextAlign(Paint.Align.CENTER);
        date.setTypeface(Chrome.MEDIUM);
        date.setColor(Chrome.TEXT_DIM);
        date.setLetterSpacing(0.2f);
        date.setTextAlign(Paint.Align.CENTER);
        speedText.setTypeface(Chrome.LIGHT);
        speedText.setColor(Chrome.TEXT);
        speedText.setLetterSpacing(0.1f);
        speedText.setTextAlign(Paint.Align.CENTER);
        setSpeed(-1);
    }

    void start() {
        ticking = true;
        removeCallbacks(tickClock);
        tickClock.run();
    }

    void stop() {
        ticking = false;
        removeCallbacks(tickClock);
    }

    /** km/h; below zero: no speed source, the needle rests and the figures show a dash. */
    void setSpeed(float kmh) {
        speedLabel = (kmh < 0 ? "–" : String.valueOf(Math.round(kmh))) + "  " + unit;
        float target = Math.max(0f, Math.min(MAX, kmh));
        if (move != null) move.cancel();
        if (!isLaidOut()) {
            shown = target;
            return;
        }
        move = ValueAnimator.ofFloat(shown, target);
        move.setDuration(350);
        move.setInterpolator(new DecelerateInterpolator());
        move.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        move.start();
    }

    private void refreshTime() {
        Locale locale = Locale.getDefault();
        Calendar now = Calendar.getInstance();
        boolean is24 = DateFormat.is24HourFormat(getContext());
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (!is24) hour = hour % 12 == 0 ? 12 : hour % 12;
        timeText = String.format(locale, is24 ? "%02d:%02d" : "%d:%02d", hour, now.get(Calendar.MINUTE));
        dateText = Text.upper(new SimpleDateFormat("EEE d MMM", locale).format(now.getTime()));
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        r = Math.min(w, h) * 0.47f;
        cx = w / 2f;
        cy = h / 2f;
        float stroke = Math.max(1f, r * 0.012f);
        dial.set(cx - r, cy - r, cx + r, cy + r);
        ring.setStrokeWidth(stroke);
        ring.setShader(Chrome.edge(dial.left, dial.top, dial.right, dial.bottom));
        arc.setStrokeWidth(r * 0.035f);
        needle.setStrokeWidth(Math.max(2f, r * 0.022f));
        numbers.setTextSize(r * 0.09f);
        time.setTextSize(r * 0.42f);
        time.setShader(Chrome.metal(cy - time.getTextSize() * 0.5f, cy + time.getTextSize() * 0.2f));
        date.setTextSize(r * 0.075f);
        speedText.setTextSize(r * 0.11f);
        faceColor = 0;
    }

    @Override protected void onDraw(Canvas c) {
        if (r == 0) return;
        if (faceColor != Chrome.accent) {
            faceColor = Chrome.accent;
            face.setShader(new RadialGradient(cx, cy, r, new int[] {0xFF15181B, 0xFF07080A, Chrome.withAlpha(faceColor, 0x18)},
                    new float[] {0f, 0.85f, 1f}, Shader.TileMode.CLAMP));
        }
        c.drawCircle(cx, cy, r, face);
        c.drawCircle(cx, cy, r, ring);

        // Ticks every 10 km/h, longer and numbered every 20.
        for (int v = 0; v <= MAX; v += 10) {
            double a = Math.toRadians(START + SWEEP * v / MAX);
            boolean major = v % 20 == 0;
            float outer = r * 0.93f, inner = r * (major ? 0.83f : 0.88f);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            tick.setStrokeWidth(Math.max(1f, r * (major ? 0.014f : 0.008f)));
            tick.setColor(major ? Chrome.SILVER : Chrome.TEXT_DIM);
            c.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * outer, cy + sin * outer, tick);
            if (major && v % 40 == 0) {
                float tr = r * 0.72f;
                c.drawText(String.valueOf(v), cx + cos * tr, cy + sin * tr + numbers.getTextSize() * 0.36f, numbers);
            }
        }

        // The swept arc and the needle, in accent light.
        float sweep = SWEEP * shown / MAX;
        arc.setColor(Chrome.withAlpha(Chrome.accent, 0xB0));
        float ar = r * 0.97f;
        c.drawArc(cx - ar, cy - ar, cx + ar, cy + ar, START, Math.max(0.5f, sweep), false, arc);
        double a = Math.toRadians(START + sweep);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        needle.setColor(Chrome.accent);
        c.drawLine(cx + cos * r * 0.55f, cy + sin * r * 0.55f, cx + cos * r * 0.9f, cy + sin * r * 0.9f, needle);

        c.drawText(dateText, cx, cy - time.getTextSize() * 0.62f, date);
        c.drawText(timeText, cx, cy + time.getTextSize() * 0.2f, time);
        c.drawText(speedLabel, cx, cy + r * 0.48f, speedText);
    }
}
