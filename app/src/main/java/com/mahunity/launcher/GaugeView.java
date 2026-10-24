package com.mahunity.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.text.format.DateFormat;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Home's upper widget as a speedometer with the time (Settings > Upper widget): the 60s dial or the 80s digital
 * dash. This holds what they share: the clock, ticking once a minute while Home shows; the speed, eased towards
 * each new reading so a needle or a bar sweeps rather than jumps; and what the car reports through Car Info, for
 * the smaller gauges beside it.
 */
abstract class GaugeView extends View {
    static final float MAX = 200f;

    /** The speed drawn now, in km/h, moving towards the last reading; below zero none is known. */
    protected float shown;
    protected boolean known;
    protected int hour, minute;
    protected String timeText = "", dateText = "";
    /** Fuel in litres and as a share of the tank (below zero: unknown), coolant in °C (NaN: unknown), battery in volts (0: unknown). */
    protected float litres = -1, fuelShare = -1, coolant = Float.NaN, battery;
    private ValueAnimator move;
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshTime();
            if (ticking) postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 20);
        }
    };

    GaugeView(Context context) {
        super(context);
        refreshTime();
    }

    void start() {
        ticking = true;
        removeCallbacks(tick);
        tick.run();
    }

    void stop() {
        ticking = false;
        removeCallbacks(tick);
    }

    /** km/h; below zero: no speed source, the needle rests and the figures show a dash. */
    void setSpeed(float kmh) {
        known = kmh >= 0;
        float target = Math.max(0f, Math.min(MAX, kmh));
        if (move != null) move.cancel();
        if (!isLaidOut()) {
            shown = target;
            invalidate();
            return;
        }
        move = ValueAnimator.ofFloat(shown, target);
        move.setDuration(400);
        move.setInterpolator(new DecelerateInterpolator());
        move.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        move.start();
    }

    /** What Car Info decoded; capacity is the tank size from Settings. */
    void setCar(float litres, float capacity, float coolant, float battery) {
        this.litres = litres;
        fuelShare = litres < 0 ? -1 : Math.max(0f, Math.min(1f, litres / Math.max(1f, capacity)));
        this.coolant = coolant;
        this.battery = Math.max(0f, battery);
        invalidate();
    }

    private void refreshTime() {
        Locale locale = Locale.getDefault();
        Calendar now = Calendar.getInstance();
        hour = now.get(Calendar.HOUR_OF_DAY);
        minute = now.get(Calendar.MINUTE);
        boolean is24 = DateFormat.is24HourFormat(getContext());
        int h = is24 ? hour : hour % 12 == 0 ? 12 : hour % 12;
        timeText = String.format(locale, is24 ? "%02d:%02d" : "%d:%02d", h, minute);
        dateText = Text.upper(new SimpleDateFormat("EEE d MMM", locale).format(now.getTime())).replace(".", "");
        invalidate();
    }
}
