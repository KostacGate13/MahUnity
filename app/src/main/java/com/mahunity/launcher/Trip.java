package com.mahunity.launcher;

import android.content.SharedPreferences;

/**
 * Distance and time since setting off, from speed readings (the car's own over CANBus, which keeps coming
 * with another app in front, or GPS while Home shows). A new trip starts when the car moves after more than ten
 * minutes without moving: the unit was off, or the car was parked. Saved, so it survives Home restarting.
 */
final class Trip {
    private static final long GAP_MS = 10 * 60_000;
    private static final long MAX_STEP_MS = 5_000;

    private final SharedPreferences prefs;
    /** When the trip began, the last reading, and the last reading above standstill. */
    private long startedAt, lastAt, movedAt;
    private double metres;
    private float lastSpeed;

    Trip(SharedPreferences prefs) {
        this.prefs = prefs;
        startedAt = prefs.getLong("trip_start", 0);
        lastAt = prefs.getLong("trip_last", 0);
        movedAt = prefs.getLong("trip_moved", lastAt);
        metres = prefs.getFloat("trip_m", 0f);
    }

    /**
     * The current speed in km/h, given about once a second (HomeActivity's tick). The distance since the last
     * call is covered at the previous speed. Wall-clock time, since the unit sleeps with the ignition off.
     */
    void sample(float kmh) {
        long now = System.currentTimeMillis();
        boolean parked = movedAt == 0 || now - movedAt > GAP_MS || now < lastAt;
        if (parked && kmh <= 0) {
            // Nothing new: the time since the last stretch of driving does not count.
            return;
        }
        if (parked) {
            startedAt = now;
            metres = 0;
        } else {
            // A longer silence than a few seconds counts as standing still, not as driving.
            long step = Math.min(now - lastAt, MAX_STEP_MS);
            metres += lastSpeed / 3.6 * step / 1000.0;
        }
        lastSpeed = Math.max(0f, kmh);
        lastAt = now;
        if (kmh > 0) movedAt = now;
    }

    boolean started() {
        return startedAt > 0;
    }

    float km() {
        return (float) (metres / 1000.0);
    }

    /** Minutes from the start to the last reading. */
    long minutes() {
        return startedAt == 0 ? 0 : (lastAt - startedAt) / 60_000;
    }

    /** Average speed in km/h over the trip, or -1 before there is enough of it. */
    float average() {
        long ms = lastAt - startedAt;
        return ms < 60_000 ? -1 : (float) (metres / 1000.0 / (ms / 3_600_000.0));
    }

    void save() {
        prefs.edit().putLong("trip_start", startedAt).putLong("trip_last", lastAt).putLong("trip_moved", movedAt)
                .putFloat("trip_m", (float) metres).apply();
    }
}
