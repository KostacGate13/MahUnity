package com.mahunity.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * What the two LED strips show, worked out from the car, the music or the clock. Each strip has two colours of
 * its own. Four modes:
 * <ul>
 * <li>Normal: the two colours chase each other slowly from left to right along a strip that lights its LEDs one
 * by one; one that cannot (the footwells) fades from one to the other and back.</li>
 * <li>Race: the dashboard is a rev bar, as on a racing car's wheel: from the left, green at idle, filling to the
 * right as the revs rise, through yellow to red half way and on to purple at the redline, where all of it
 * flashes as a shift light (a strip that cannot light its LEDs one by one takes the colour the bar has reached).
 * The footwells keep their first colour and flash red while the car brakes (speed falling fast: the car sends
 * no brake signal).</li>
 * <li>Music: the dashboard is a level meter filling from the left with the music, from its first colour to its
 * second; the footwells pulse with the bass, which takes a harder hit to show.</li>
 * <li>Static: each strip in its first colour.</li>
 * </ul>
 * In Normal and Static the footwells can show the fuel instead: red up to 5 litres, blue to 15, purple to 29,
 * green above.
 * Settings are kept in the preferences; what the strips show is kept for the Lights tab to draw.
 */
final class Ambient implements Sound.Listener {
    interface Listener {
        void onAmbientChanged();
    }

    static final int NORMAL = 0, RACE = 1, MUSIC = 2, STATIC = 3;
    static final int[] MODE_NAMES = {R.string.led_mode_normal, R.string.led_mode_race, R.string.led_mode_music,
        R.string.led_mode_static};
    static final int RED = 0xFF0000, OFF = 0x000000;
    /** The colours offered on the Lights tab. */
    static final int[] COLORS = {0x8A2BFF, 0xC800FF, 0xFF2D95, 0xFF0000, 0xFF6A00, 0xFFC800, 0x00FF2A, 0x00E5FF,
        0x0050FF, 0xFFFFFF};
    /** Normal: how long the colours take to pass along a strip, slow, medium and fast. */
    static final long[] CHASE_MS = {9_000, 5_000, 2_500};
    /** Music, at low, medium and high sensitivity: the gain on the loudness, and how hard the bass must hit. */
    private static final float[] MUSIC_GAIN = {0.8f, 1f, 1.3f}, BASS_GATE = {0.6f, 0.45f, 0.3f};
    /** The footwells by the fuel: the colour up to each mark in whole litres, then the last one above them. */
    private static final int[] FUEL_MARKS = {5, 15, 29};
    static final int[] FUEL_COLORS = {RED, 0x0050FF, 0x8A2BFF, 0x00FF2A};
    private static final long FRAME_MS = 60, FLASH_MS = 120, BRAKE_HOLD_MS = 900, WINDOW_MS = 900;
    /** A trial of the rev bar: up to the redline over the first part, then the shift light. */
    private static final long SWEEP_UP_MS = 3_000, SWEEP_MS = 4_400;
    /** LEDs drawn on the tab while the strip has not said how many it has. */
    static final int PREVIEW = 30;
    /** Where along the rev bar it is red, and the hue it ends in. */
    private static final float RED_AT = 0.55f, PURPLE = 272f;

    private final SharedPreferences prefs;
    private final LedStrips leds;
    private final Listener listener;
    private final Sound sound;
    private final Handler handler = new Handler(Looper.getMainLooper());

    int mode;
    boolean topOn, bottomOn;
    /** Each strip's two colours: Static and Race take the first, Normal and Music both. */
    int topColor, topColor2, bottomColor, bottomColor2;
    /** 10 to 100. */
    int brightness;
    /** The redline and the rev bar's start in rpm; braking in km/h lost per second. */
    int redline, barFrom;
    float brake;
    /** The dashboard's LEDs set by hand (0: as its controller says); how it lights them one by one. */
    int topPixels, topMethod;
    /** Normal's speed and Music's sensitivity, 0 to 2 each; where Music listens, Sound.OUTPUT or MIC. */
    int chase, musicSense, musicSource;
    /** In Normal and Static, the footwells show the fuel instead of their colours. */
    boolean bottomFuel;

    private int rpm = -1, speed = -1;
    private float litres = -1;
    /** Recent speeds, as (time, km/h), oldest first. */
    private final long[] times = new long[16];
    private final int[] speeds = new int[16];
    private int samples;
    private long brakeUntil, sweepFrom;
    private float level, bass;
    private boolean framing;
    /** What the strips show now, before dimming: for the tab. */
    int shownTop, shownBottom;
    /** The dashboard LED by LED as it shows now (PREVIEW of them if the count is not known); null if in one colour. */
    int[] topBar;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            framing = false;
            apply();
        }
    };

    Ambient(Context context, SharedPreferences prefs, LedStrips leds, Listener listener) {
        this.prefs = prefs;
        this.leds = leds;
        this.listener = listener;
        sound = new Sound(context, this);
        // The modes were Normal, Race, Ambient, Static: Ambient is gone, its place Music's.
        int old = prefs.getInt("led_mode", NORMAL);
        mode = prefs.getInt("led_mode2", old == 2 ? STATIC : old);
        topOn = prefs.getBoolean("led_top_on", true);
        bottomOn = prefs.getBoolean("led_bottom_on", true);
        topColor = prefs.getInt("led_top_color", COLORS[0]);
        topColor2 = prefs.getInt("led_top_color2", COLORS[8]);
        bottomColor = prefs.getInt("led_bottom_color", COLORS[0]);
        bottomColor2 = prefs.getInt("led_bottom_color2", COLORS[8]);
        brightness = prefs.getInt("led_brightness", 80);
        redline = prefs.getInt("led_redline", 5500);
        brake = prefs.getFloat("led_brake", 8f);
        barFrom = prefs.getInt("led_bar_from", 800);
        topPixels = prefs.getInt("led_top_pixels", 0);
        topMethod = prefs.getInt("led_top_method", LedProtocol.AUTO);
        chase = prefs.getInt("led_chase", 0);
        musicSense = prefs.getInt("led_music_sense", 1);
        musicSource = prefs.getInt("led_music_source", Sound.OUTPUT);
        bottomFuel = prefs.getBoolean("led_bottom_fuel", false);
        for (int role = 0; role < 2; role++) {
            String key = role == LedStrips.TOP ? "led_top" : "led_bottom";
            leds.use(role, prefs.getString(key + "_address", null), prefs.getString(key + "_name", null),
                    prefs.getInt(key + "_product", -1));
        }
        leds.method(LedStrips.TOP, topMethod);
    }

    void save() {
        prefs.edit().putInt("led_mode2", mode).putBoolean("led_top_on", topOn).putBoolean("led_bottom_on", bottomOn)
                .putInt("led_top_color", topColor).putInt("led_top_color2", topColor2)
                .putInt("led_bottom_color", bottomColor).putInt("led_bottom_color2", bottomColor2)
                .putInt("led_brightness", brightness).putInt("led_redline", redline).putFloat("led_brake", brake)
                .putInt("led_bar_from", barFrom).putInt("led_top_pixels", topPixels).putInt("led_top_method", topMethod)
                .putInt("led_chase", chase).putInt("led_music_sense", musicSense)
                .putInt("led_music_source", musicSource).putBoolean("led_bottom_fuel", bottomFuel).apply();
        leds.method(LedStrips.TOP, topMethod);
        listen();
        apply();
    }

    /** A device chosen for a strip, with the model it said (-1 if none), kept for the next start. */
    void use(int role, String address, String name, int product) {
        String key = role == LedStrips.TOP ? "led_top" : "led_bottom";
        prefs.edit().putString(key + "_address", address).putString(key + "_name", name).putInt(key + "_product", product)
                .apply();
        leds.use(role, address, name, product);
        apply();
    }

    /** Listens to the music while Music mode needs it, from where it is set to; stops otherwise. */
    void listen() {
        if (mode == MUSIC && (topOn || bottomOn)) sound.start(musicSource);
        else sound.stop();
    }

    /** Whether Music mode hears anything: allowed to listen, and the source opened. */
    boolean hearing() {
        return sound.allowed() && sound.working;
    }

    boolean mayListen() {
        return sound.allowed();
    }

    void close() {
        handler.removeCallbacks(frame);
        framing = false;
        sound.stop();
    }

    /** The car's latest: rpm, km/h and litres in the tank, below zero unknown. */
    void update(int rpm, int kmh, float litres) {
        this.rpm = rpm;
        this.litres = litres;
        if (kmh != speed) {
            speed = kmh;
            sample(kmh);
        }
        apply();
    }

    @Override public void onSound(float level, float bass) {
        if (mode != MUSIC) return;
        float gate = BASS_GATE[clamp(musicSense)];
        float hit = Math.max(0f, (bass - gate) / (1 - gate));
        // Up at once, down gently: the meter falls back over a few readings, the bass a little faster.
        this.level = Math.max(Math.min(1f, level * MUSIC_GAIN[clamp(musicSense)]), this.level * 0.8f);
        this.bass = Math.max(hit, this.bass * 0.65f);
        apply();
    }

    private static int clamp(int i) {
        return Math.max(0, Math.min(2, i));
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
        if (change(now) <= -brake) brakeUntil = now + BRAKE_HOLD_MS;
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

    /** Runs the rev bar up to the redline and its shift light once, whatever the mode, then back as it was. */
    void sweep() {
        sweepFrom = SystemClock.elapsedRealtime();
        apply();
    }

    /** The dashboard's LEDs: as set by hand, or as its controller says; 0 if neither. */
    int pixels() {
        return topPixels > 0 ? topPixels : leds.pixels(LedStrips.TOP);
    }

    /** Works the colours out again and sends them; keeps going while something moves, flashes or holds. */
    void apply() {
        long now = SystemClock.elapsedRealtime();
        boolean trial = sweepFrom > 0 && now - sweepFrom < SWEEP_MS;
        if (!trial) sweepFrom = 0;
        int revs = trial ? sweepRevs(now) : rpm;
        boolean shifting = revs >= redline && redline > 0;
        boolean braking = now < brakeUntil;
        boolean flash = now / FLASH_MS % 2 == 0;
        // As many steps as the strip takes in one write; PREVIEW of them on the tab while that is not known.
        int n = pixels(), steps = n > 0 ? leds.fit(LedStrips.TOP, n) : 0, count = steps > 0 ? steps : n > 0 ? n : PREVIEW;
        int top, bottom;
        topBar = null;
        switch (mode) {
            case RACE:
                top = topColor;
                bottom = braking ? (flash ? RED : OFF) : bottomColor;
                break;
            case MUSIC:
                topBar = meter(count, level, topColor, topColor2);
                top = scale(mix(topColor, topColor2, level), 0.15f + 0.85f * level);
                bottom = scale(mix(bottomColor, bottomColor2, bass), 0.12f + 0.88f * bass);
                break;
            case STATIC:
                top = topColor;
                bottom = bottomColor;
                break;
            default:
                float phase = now % CHASE_MS[clamp(chase)] / (float) CHASE_MS[clamp(chase)];
                topBar = chase(count, phase, topColor, topColor2);
                top = mix(topColor, topColor2, wave(phase));
                bottom = mix(bottomColor, bottomColor2, wave(phase));
                break;
        }
        if (fuelShows()) bottom = fuel(litres);
        // The rev bar: its colours by place, lit up to the revs; all of it flashing at the redline.
        if ((mode == RACE || trial) && revs >= 0) {
            float f = fill(revs);
            topBar = bar(count, shifting ? (flash ? count : 0) : Math.max(Math.max(1, Math.round(count / 20f)),
                    Math.round(f * count)));
            top = shifting ? (flash ? barColor(1f) : OFF) : barColor(f);
        }
        shownTop = top;
        shownBottom = bottom;
        leds.set(LedStrips.TOP, topOn || trial, dim(top), topBar != null && steps > 0 ? dim(topBar) : null);
        leds.set(LedStrips.BOTTOM, bottomOn, dim(bottom));
        listener.onAmbientChanged();
        boolean moving = trial || mode == NORMAL && (topOn || bottomOn)
                || mode == RACE && (shifting && topOn || braking && bottomOn);
        if (moving && !framing) {
            framing = true;
            handler.postDelayed(frame, FRAME_MS);
        }
    }

    /** Whether the mode can show the fuel on the footwells (Normal and Static). */
    boolean fuelMode() {
        return mode == NORMAL || mode == STATIC;
    }

    /** Whether the footwells show the fuel now: chosen, in such a mode, and the car says how much there is. */
    boolean fuelShows() {
        return bottomFuel && fuelMode() && litres >= 0;
    }

    /** The litres in the tank as the car last said, below zero unknown. */
    float litres() {
        return litres;
    }

    /** The footwells' colour for the litres in the tank. */
    static int fuel(float litres) {
        int l = Math.round(litres), i = 0;
        while (i < FUEL_MARKS.length && l > FUEL_MARKS[i]) i++;
        return FUEL_COLORS[i];
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

    /** A rev bar of count LEDs, the first lit of them in their colours, the rest off. */
    static int[] bar(int count, int lit) {
        int[] out = new int[count];
        for (int i = 0; i < Math.min(lit, count); i++) out[i] = barColor(count == 1 ? 1f : i / (float) (count - 1));
        return out;
    }

    /** The rev bar's colour at t along it: green at the left, yellow, red at RED_AT, then magenta to purple. */
    static int barColor(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float hue = t <= RED_AT ? 120f * (1 - t / RED_AT) : 360f - (360f - PURPLE) * (t - RED_AT) / (1 - RED_AT);
        return Color.HSVToColor(new float[] {hue % 360f, 1f, 1f}) & 0xFFFFFF;
    }

    /** Normal along a strip: a wave from one colour to the other and back, moved right by phase (0 to 1). */
    static int[] chase(int count, float phase, int a, int b) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = mix(a, b, wave(i / (float) count - phase));
        return out;
    }

    /** 0 to 1 and back over each whole turn of t, smoothly. */
    private static float wave(float t) {
        return (float) (1 - Math.cos(2 * Math.PI * t)) / 2;
    }

    /** Music along a strip: lit from the left as far as the level, from one colour to the other. */
    static int[] meter(int count, float level, int a, int b) {
        int[] out = new int[count];
        int lit = Math.round(Math.max(0f, Math.min(1f, level)) * count);
        for (int i = 0; i < lit; i++) out[i] = mix(a, b, count == 1 ? 1f : i / (float) (count - 1));
        return out;
    }

    private int dim(int rgb) {
        return scale(rgb, Math.max(10, Math.min(100, brightness)) / 100f);
    }

    private int[] dim(int[] bar) {
        int[] out = new int[bar.length];
        for (int i = 0; i < bar.length; i++) out[i] = dim(bar[i]);
        return out;
    }

    static int scale(int rgb, float f) {
        f = Math.max(0f, Math.min(1f, f));
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
