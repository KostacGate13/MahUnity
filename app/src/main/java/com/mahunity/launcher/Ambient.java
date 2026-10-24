package com.mahunity.launcher;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * What the two LED strips show, worked out from the car as it reports itself. Four modes:
 * <ul>
 * <li>Normal: the dashboard is a rev bar, as on a racing car's wheel: from the left, green at idle, filling to
 * the right as the revs rise, through yellow to red half way and on to purple at the redline, where all of it
 * flashes as a shift light. A strip that cannot light its LEDs one by one takes the colour the bar has
 * reached. The footwells follow the fuel, red at or under the low mark, through orange, to their own colour
 * from the high mark up.</li>
 * <li>Race: both green while the car pulls (speed rising, or high revs), red while it brakes (speed falling
 * fast; the car sends no brake signal), the ambient colour while cruising; the dashboard keeps the shift light.</li>
 * <li>Ambient: both in Cockpit's ambient colour.</li>
 * <li>Static: each in its own colour.</li>
 * </ul>
 * Settings are kept in the preferences; the shown colours are kept for the Lights tab to draw.
 */
final class Ambient {
    interface Listener {
        void onAmbientChanged();
    }

    static final int NORMAL = 0, RACE = 1, AMBIENT = 2, STATIC = 3;
    static final int[] MODE_NAMES = {R.string.led_mode_normal, R.string.led_mode_race, R.string.led_mode_ambient,
        R.string.led_mode_static};
    static final int RED = 0xFF0000, ORANGE = 0xFF6A00, GREEN = 0x00FF2A, OFF = 0x000000;
    /** The colours offered on the Lights tab. */
    static final int[] COLORS = {0x8A2BFF, 0xC800FF, 0xFF2D95, 0xFF0000, 0xFF6A00, 0xFFC800, 0x00FF2A, 0x00E5FF,
        0x0050FF, 0xFFFFFF};
    private static final long TICK_MS = 110, BRAKE_HOLD_MS = 900, WINDOW_MS = 900;
    /** A trial of the rev bar: up to the redline over the first part, then the shift light. */
    private static final long SWEEP_UP_MS = 3_000, SWEEP_MS = 4_400, SWEEP_TICK_MS = 50;
    /** LEDs drawn on the tab while the strip has not said how many it has. */
    static final int PREVIEW = 30;
    /** Where along the bar it is red, and the hue it ends in. */
    private static final float RED_AT = 0.55f, PURPLE = 272f;

    private final SharedPreferences prefs;
    private final LedStrips leds;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    int mode;
    boolean topOn, bottomOn;
    int topColor, bottomColor;
    /** 10 to 100. */
    int brightness;
    /** Fuel marks in litres, the redline in rpm, braking in km/h lost per second. */
    float fuelLow, fuelHigh, brake;
    int redline;
    /** The rpm the rev bar starts from; the dashboard's LEDs set by hand (0: as its controller says). */
    int barFrom, topPixels;

    private int rpm = -1, speed = -1;
    private float litres = -1;
    /** Recent speeds, as (time, km/h), oldest first. */
    private final long[] times = new long[16];
    private final int[] speeds = new int[16];
    private int samples;
    private long brakeUntil;
    private boolean flashOn;
    private boolean ticking, sweeping;
    private long sweepFrom;
    /** What the strips show now, before dimming: for the tab. */
    int shownTop, shownBottom;
    /** The rev bar LED by LED as it shows now (PREVIEW of them if the count is not known); null in other modes. */
    int[] topBar;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ticking = false;
            flashOn = !flashOn;
            apply();
        }
    };

    private final Runnable sweeper = new Runnable() {
        @Override public void run() {
            sweeping = false;
            apply();
        }
    };

    Ambient(SharedPreferences prefs, LedStrips leds, Listener listener) {
        this.prefs = prefs;
        this.leds = leds;
        this.listener = listener;
        mode = prefs.getInt("led_mode", NORMAL);
        topOn = prefs.getBoolean("led_top_on", true);
        bottomOn = prefs.getBoolean("led_bottom_on", true);
        topColor = prefs.getInt("led_top_color", COLORS[0]);
        bottomColor = prefs.getInt("led_bottom_color", COLORS[0]);
        brightness = prefs.getInt("led_brightness", 80);
        fuelLow = prefs.getFloat("led_fuel_low", 5f);
        fuelHigh = prefs.getFloat("led_fuel_high", 10f);
        redline = prefs.getInt("led_redline", 5500);
        brake = prefs.getFloat("led_brake", 8f);
        barFrom = prefs.getInt("led_bar_from", 800);
        topPixels = prefs.getInt("led_top_pixels", 0);
        String top = prefs.getString("led_top_address", null), bottom = prefs.getString("led_bottom_address", null);
        leds.use(LedStrips.TOP, top, prefs.getString("led_top_name", null));
        leds.use(LedStrips.BOTTOM, bottom, prefs.getString("led_bottom_name", null));
    }

    void save() {
        prefs.edit().putInt("led_mode", mode).putBoolean("led_top_on", topOn).putBoolean("led_bottom_on", bottomOn)
                .putInt("led_top_color", topColor).putInt("led_bottom_color", bottomColor)
                .putInt("led_brightness", brightness).putFloat("led_fuel_low", fuelLow).putFloat("led_fuel_high", fuelHigh)
                .putInt("led_redline", redline).putFloat("led_brake", brake).putInt("led_bar_from", barFrom)
                .putInt("led_top_pixels", topPixels).apply();
        apply();
    }

    /** A device chosen for a strip, kept for the next start. */
    void use(int role, String address, String name) {
        String key = role == LedStrips.TOP ? "led_top" : "led_bottom";
        prefs.edit().putString(key + "_address", address).putString(key + "_name", name).apply();
        leds.use(role, address, name);
        apply();
    }

    /** The car's latest: rpm and km/h (below zero unknown), litres in the tank (below zero unknown). */
    void update(int rpm, int kmh, float litres) {
        this.rpm = rpm;
        this.litres = litres;
        if (kmh != speed) {
            speed = kmh;
            sample(kmh);
        }
        apply();
    }

    private void sample(int kmh) {
        long now = SystemClock.elapsedRealtime();
        if (kmh < 0) {
            samples = 0;
            return;
        }
        if (samples == times.length) {
            System.arraycopy(times, 1, times, 0, samples - 1);
            System.arraycopy(speeds, 1, speeds, 0, samples - 1);
            samples--;
        }
        times[samples] = now;
        speeds[samples] = kmh;
        samples++;
        // Braking: speed lost faster than the mark, over the last moment.
        float change = change(now);
        if (change <= -brake) brakeUntil = now + BRAKE_HOLD_MS;
    }

    /** km/h per second over the last WINDOW_MS, from the oldest sample in it to the newest. */
    private float change(long now) {
        if (samples < 2) return 0;
        int last = samples - 1, first = last;
        while (first > 0 && now - times[first - 1] <= WINDOW_MS) first--;
        if (first == last) first = last - 1;
        long dt = times[last] - times[first];
        if (dt <= 0 || dt > 2 * WINDOW_MS) return 0;
        return (speeds[last] - speeds[first]) * 1000f / dt;
    }

    /** Runs the rev bar up to the redline and its shift light once, whatever the mode, then back to the car. */
    void sweep() {
        sweepFrom = SystemClock.elapsedRealtime();
        apply();
    }

    /** The dashboard's LEDs: as set by hand, or as its controller says; 0 if neither. */
    int pixels() {
        return topPixels > 0 ? topPixels : leds.pixels(LedStrips.TOP);
    }

    /** Works the colours out again and sends them; keeps ticking while something flashes, holds or sweeps. */
    void apply() {
        long now = SystemClock.elapsedRealtime();
        boolean trial = sweepFrom > 0 && now - sweepFrom < SWEEP_MS;
        if (!trial) sweepFrom = 0;
        int revs = trial ? sweepRevs(now) : rpm;
        boolean shifting = revs >= redline && redline > 0;
        boolean braking = now < brakeUntil;
        int top, bottom;
        switch (mode) {
            case RACE:
                int pull = braking ? RED : racing(now) ? green() : Chrome.accent & 0xFFFFFF;
                top = shifting && !braking ? (flashOn ? RED : OFF) : pull;
                bottom = pull;
                break;
            case AMBIENT:
                top = bottom = Chrome.accent & 0xFFFFFF;
                break;
            case STATIC:
                top = topColor;
                bottom = bottomColor;
                break;
            default:
                top = topColor;
                bottom = fuel(bottomColor);
                break;
        }
        // The rev bar: its colours by place, lit up to the revs; all of it flashing at the redline.
        topBar = null;
        int n = pixels();
        if ((mode == NORMAL || trial) && revs >= 0) {
            float f = fill(revs);
            int count = n > 0 ? n : PREVIEW;
            topBar = bar(count, shifting ? (flashOn ? count : 0) : Math.max(Math.max(1, Math.round(count / 20f)),
                    Math.round(f * count)));
            top = shifting ? (flashOn ? barColor(1f) : OFF) : barColor(f);
        }
        shownTop = top;
        shownBottom = bottom;
        leds.set(LedStrips.TOP, topOn || trial, dim(top), topBar != null && n > 0 ? dim(topBar) : null);
        leds.set(LedStrips.BOTTOM, bottomOn, dim(bottom));
        listener.onAmbientChanged();
        boolean dynamic = (mode == NORMAL || mode == RACE || trial) && shifting || mode == RACE && braking;
        if (dynamic && (topOn || bottomOn || trial) && !ticking) {
            ticking = true;
            handler.postDelayed(tick, TICK_MS);
        }
        if (trial && !sweeping) {
            sweeping = true;
            handler.postDelayed(sweeper, SWEEP_TICK_MS);
        }
    }

    /** The revs in a trial: from the bar's start to just under the redline, then on it. */
    private int sweepRevs(long now) {
        long t = now - sweepFrom;
        if (t >= SWEEP_UP_MS) return redline;
        return Math.min(redline - 1, Math.round(barFrom + (redline - barFrom) * t / (float) SWEEP_UP_MS));
    }

    /** How far along the bar the revs are, 0 at its start to 1 at the redline. */
    private float fill(int revs) {
        return Math.max(0f, Math.min(1f, (revs - barFrom) / (float) Math.max(100, redline - barFrom)));
    }

    /** A bar of count LEDs, the first lit of them in their colours, the rest off. */
    static int[] bar(int count, int lit) {
        int[] out = new int[count];
        for (int i = 0; i < Math.min(lit, count); i++) out[i] = barColor(count == 1 ? 1f : i / (float) (count - 1));
        return out;
    }

    /** The bar's colour at t along it: green at the left, yellow, red at RED_AT, then magenta to purple. */
    static int barColor(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float hue = t <= RED_AT ? 120f * (1 - t / RED_AT) : 360f - (360f - PURPLE) * (t - RED_AT) / (1 - RED_AT);
        return Color.HSVToColor(new float[] {hue % 360f, 1f, 1f}) & 0xFFFFFF;
    }

    /** Pulling: speed rising, or revs up while moving. */
    private boolean racing(long now) {
        return speed > 3 && (change(now) >= 1.5f || rpm >= 3000);
    }

    /** Green, brighter with the revs. */
    private int green() {
        float t = rpm < 0 ? 0.6f : Math.max(0.35f, Math.min(1f, rpm / (float) Math.max(1, redline)));
        return mix(OFF, GREEN, t);
    }

    /** Red at or under the low mark, orange half way, the footwells' own colour from the high mark up. */
    private int fuel(int base) {
        if (litres < 0) return base;
        if (litres <= fuelLow) return RED;
        if (litres >= fuelHigh) return base;
        float t = (litres - fuelLow) / Math.max(0.1f, fuelHigh - fuelLow);
        return t < 0.5f ? mix(RED, ORANGE, t * 2) : mix(ORANGE, base, (t - 0.5f) * 2);
    }

    private int dim(int rgb) {
        float f = Math.max(10, Math.min(100, brightness)) / 100f;
        return Math.round((rgb >> 16 & 0xFF) * f) << 16 | Math.round((rgb >> 8 & 0xFF) * f) << 8 | Math.round((rgb & 0xFF) * f);
    }

    private int[] dim(int[] bar) {
        int[] out = new int[bar.length];
        for (int i = 0; i < bar.length; i++) out[i] = dim(bar[i]);
        return out;
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int g = Math.round((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return r << 16 | g << 8 | bl;
    }
}
