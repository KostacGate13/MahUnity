package com.mahunity.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * What NWD's Car Info decodes but keeps to itself (fuel left, battery voltage, coolant, tyre pressures), read
 * from the system log instead of the CANBus. Car Info has a debug switch that logs every frame it receives from
 * the CAN box; any app may flip it, and it changes nothing but the logging. Home turns it on and picks the frames
 * that carry these out of the log, so the CANBus link itself is never touched. The climate frame comes too: Car
 * Info broadcasts the outside temperature only when it changes, so a Home started later would not hear it.
 *
 * Reading other apps' logs takes READ_LOGS, which only ADB can grant (see R.string.car_data_help); Android 13+
 * also asks on screen, and only for the app in front, so reading starts from wake(true) in onResume.
 */
final class NwdLog {
    interface Listener {
        /** A Raise frame of type 4A or 03, on the main thread. */
        void onCarFrame(byte[] frame);
    }

    static final String PERMISSION = "android.permission.READ_LOGS";
    private static final String CAR_INFO = "com.nwd.can.setting";
    // Car Info's ALog debug switch (ALog's receiver, DebugDoor's extras).
    private static final String ACTION_DEBUG = "com.nwd.netapp.ACTION_DEBUG_DOOR";
    /** Car Info's log tag, and what ALog puts between the caller and a frame in hex. */
    private static final String TAG = "Can";
    private static final String SEPARATOR = "------";
    /** Raise frames: 2E, type, payload length, payload, checksum. */
    private static final int RAISE = 0x2E, CAR_STATE = 0x4A;
    /** Raise's climate frame, which carries the outside temperature. */
    static final int CLIMATE = 0x03;
    /** Quiet this long and Car Info probably restarted, its switch back off: flip it again. */
    private static final long QUIET_MS = 60_000;
    /** logcat ending this soon means access was refused (Android 13+); asked again only after BACKOFF_MS. */
    private static final long REFUSED_MS = 5_000, BACKOFF_MS = 10 * 60_000;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private java.lang.Process logcat;
    private long lastFrameAt, lastSwitchAt = -QUIET_MS, refusedAt = -BACKOFF_MS;
    /** Frames read since Home started, for the status in Settings. */
    int frames;

    NwdLog(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean allowed() {
        return context.checkPermission(PERMISSION, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }

    private Boolean gmRaise;

    /**
     * Whether Car Info decodes this car with GM's Raise decoder (the Corsa's), whose frames these are; read from
     * the car type Car Info keeps in system settings. Unknown counts as yes: the frames are checked anyway.
     */
    boolean gmRaise() {
        if (gmRaise == null) {
            String manager = "";
            try {
                String json = android.provider.Settings.System.getString(context.getContentResolver(),
                        "can_config_app_cartype_json");
                if (json != null) manager = new org.json.JSONObject(json).optString("canManagerClassName", "");
            } catch (Exception e) {
                // Unreadable: unknown.
            }
            gmRaise = manager.isEmpty() || manager.contains(".gm.raise.");
        }
        return gmRaise;
    }

    boolean reading() {
        return logcat != null;
    }

    /**
     * Turns Car Info's logging back on when it has gone quiet; with start, also starts reading if not already
     * (only from onResume: on Android 13+ that is when the system asks, and only then).
     */
    void wake(boolean start) {
        if (!allowed()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameAt > QUIET_MS && now - lastSwitchAt > QUIET_MS) {
            lastSwitchAt = now;
            context.sendBroadcast(new Intent(ACTION_DEBUG).setPackage(CAR_INFO)
                    .putExtra("ext_appkey", "canapp")
                    .putExtra("ext_cmd", "business_debug")
                    .putExtra("ext_cmd_data", "1"));
        }
        if (start && logcat == null && now - refusedAt > BACKOFF_MS) start();
    }

    void stop() {
        if (logcat != null) logcat.destroy();
        logcat = null;
    }

    private void start() {
        final java.lang.Process p;
        try {
            // Only Car Info's tag, message text only; the buffer first, so the last values show at once.
            p = new ProcessBuilder("logcat", "-v", "raw", "-s", TAG + ":D").redirectErrorStream(true).start();
        } catch (IOException e) {
            return;
        }
        logcat = p;
        final long startedAt = SystemClock.elapsedRealtime();
        Thread reader = new Thread("nwd-log") {
            @Override public void run() {
                byte[] last1 = null, last4 = null, last3 = null;
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()), 4096);
                    for (String line; (line = in.readLine()) != null; ) {
                        final byte[] f = frame(line);
                        if (f == null) continue;
                        // Only changes reach the main thread; the box repeats itself.
                        if (f[1] == CLIMATE) {
                            if (java.util.Arrays.equals(f, last3)) continue;
                            last3 = f;
                        } else if (f[3] == 1) {
                            if (java.util.Arrays.equals(f, last1)) continue;
                            last1 = f;
                        } else {
                            if (java.util.Arrays.equals(f, last4)) continue;
                            last4 = f;
                        }
                        main.post(new Runnable() {
                            @Override public void run() {
                                lastFrameAt = SystemClock.elapsedRealtime();
                                frames++;
                                listener.onCarFrame(f);
                            }
                        });
                    }
                } catch (IOException e) {
                    // logcat went away: denied on Android 13+, or killed. wake() starts it again.
                } finally {
                    p.destroy();
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (logcat != p) return;
                            logcat = null;
                            if (SystemClock.elapsedRealtime() - startedAt < REFUSED_MS) {
                                refusedAt = SystemClock.elapsedRealtime();
                            }
                        }
                    });
                }
            }
        };
        reader.setPriority(Thread.MIN_PRIORITY);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * The Raise frame in a line of Car Info's log ("package--file--method--line------2E4A..."), if it is a whole,
     * valid frame of type 4A with payload 01 (tyres) or 04 (coolant, fuel, battery), or of type 03 (the climate,
     * with the outside temperature); null otherwise.
     */
    static byte[] frame(String line) {
        int at = line.lastIndexOf(SEPARATOR);
        if (at < 0) return null;
        String hex = line.substring(at + SEPARATOR.length()).trim();
        int n = hex.length() / 2;
        if (n < 6 || hex.length() % 2 != 0 || !hex.startsWith("2E4A") && !hex.startsWith("2E03")) return null;
        byte[] f = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16), lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            f[i] = (byte) (hi << 4 | lo);
        }
        int type = f[1] & 0xFF;
        if ((f[0] & 0xFF) != RAISE || type != CAR_STATE && type != CLIMATE || (f[2] & 0xFF) + 4 != n) return null;
        // Raise's checksum: the bytes from type to the end of the payload, summed, inverted.
        int sum = 0;
        for (int i = 1; i < n - 1; i++) sum += f[i];
        if ((byte) (sum ^ 0xFF) != f[n - 1]) return null;
        if (type == CLIMATE) return n >= 3 + 8 + 1 ? f : null;
        return f[3] == 1 || f[3] == 4 ? f : null;
    }
}
