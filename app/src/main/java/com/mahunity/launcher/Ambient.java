package com.mahunity.launcher;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * What the two LED strips show, worked out from the car as it reports itself. Four modes:
 * <ul>
 * <li>Normal: the dashboard follows the revs, from its own colour through yellow to red, flashing at the redline
 * as a shift light; the footwells follow the fuel, red at or under the low mark, through orange, to their own
 * colour from the high mark up.</li>
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
    static final int RED = 0xFF0000, ORANGE = 0xFF6A00, YELLOW = 0xFFC800, GREEN = 0x00FF2A, OFF = 0x000000;
    /** The colours offered on the Lights tab. */
    static final int[] COLORS = {0x8A2BFF, 0xC800FF, 0xFF2D95, 0xFF0000, 0xFF6A00, 0xFFC800, 0x00FF2A, 0x00E5FF,
        0x0050FF, 0xFFFFFF};
    private static final long TICK_MS = 110, BRAKE_HOLD_MS = 900, WINDOW_MS = 900;

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

    private int rpm = -1, speed = -1;
    private float litres = -1;
    /** Recent speeds, as (time, km/h), oldest first. */
    private final long[] times = new long[16];
    private final int[] speeds = new int[16];
    private int samples;
    private long brakeUntil;
    private boolean flashOn;
    private boolean ticking;
    /** What the strips show now, before dimming: for the tab. */
    int shownTop, shownBottom;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ticking = false;
            flashOn = !flashOn;
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
        String top = prefs.getString("led_top_address", null), bottom = prefs.getString("led_bottom_address", null);
        leds.use(LedStrips.TOP, top, prefs.getString("led_top_name", null));
        leds.use(LedStrips.BOTTOM, bottom, prefs.getString("led_bottom_name", null));
    }

    void save() {
        prefs.edit().putInt("led_mode", mode).putBoolean("led_top_on", topOn).putBoolean("led_bottom_on", bottomOn)
                .putInt("led_top_color", topColor).putInt("led_bottom_color", bottomColor)
                .putInt("led_brightness", brightness).putFloat("led_fuel_low", fuelLow).putFloat("led_fuel_high", fuelHigh)
                .putInt("led_redline", redline).putFloat("led_brake", brake).apply();
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

    /** Works the colours out again and sends them; keeps ticking while something flashes or holds. */
    void apply() {
        long now = SystemClock.elapsedRealtime();
        boolean shifting = rpm >= redline && redline > 0;
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
                top = shifting ? (flashOn ? RED : OFF) : revs(topColor);
                bottom = fuel(bottomColor);
                break;
        }
        shownTop = top;
        shownBottom = bottom;
        leds.set(LedStrips.TOP, topOn, dim(top));
        leds.set(LedStrips.BOTTOM, bottomOn, dim(bottom));
        listener.onAmbientChanged();
        boolean dynamic = (mode == NORMAL || mode == RACE) && shifting || mode == RACE && braking;
        if (dynamic && (topOn || bottomOn) && !ticking) {
            ticking = true;
            handler.postDelayed(tick, TICK_MS);
        }
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

    /** The dashboard's colour at idle, through yellow to red towards the redline. */
    private int revs(int base) {
        if (rpm < 0) return base;
        float t = Math.max(0f, Math.min(1f, (rpm - 1500f) / Math.max(1f, redline - 1500f)));
        return t < 0.5f ? mix(base, YELLOW, t * 2) : mix(YELLOW, RED, (t - 0.5f) * 2);
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

    static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int g = Math.round((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return r << 16 | g << 8 | bl;
    }
}
