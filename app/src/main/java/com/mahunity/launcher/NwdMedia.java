package com.mahunity.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Locale;

/**
 * The NWD unit's own radio and music player (USB, SD, the unit's storage), which publish no media session: what
 * they play comes in their broadcasts, the ones NWD's own launcher shows on its widgets, and they take their keys
 * the same way. Which of them is on is the unit's current source, kept in system settings and broadcast on every
 * change. Bluetooth music, phones and other apps have sessions, and MediaWatcher follows those.
 */
final class NwdMedia {
    interface Listener {
        void onNwdMediaChanged();
    }

    /** The unit's sources (SourceConstant): the radio, its music player. */
    static final int SOURCE_RADIO = 4, SOURCE_MUSIC = -128;
    private static final String CURRENT_SOURCE = "mcu_current_source";
    private static final String ACTION_SOURCE = "com.nwd.action.ACTION_CHANGE_SOURCE", EXTRA_SOURCE = "extra_source_id";
    // The radio's frequency in plain extras (its other broadcast carries a parcel only NWD's apps can read).
    private static final String ACTION_FREQUENCY = "com.nwd.action.ACTION_SEND_RADIO_FREQUENCE_NEW";
    private static final String ACTION_REQUEST_FREQUENCY = "com.nwd.action.radiowidget_request_frequence";
    private static final String ACTION_TRACK = "com.nwd.action.send_media_play_info";
    private static final String ACTION_TIME = "com.nwd.action.send_media_play_time";
    /** The music player's keys, as NWD's music widget sends them: 2 previous, 3 next; play/pause apart. */
    private static final String ACTION_PLAY_COMMAND = "com.nwd.ACTION_PLAY_COMMAND";
    private static final String ACTION_PLAY_PAUSE = "com.nwd.action.music.on_widget_button_click";
    private static final ComponentName RADIO_SERVICE =
            new ComponentName("com.nwd.radio.service", "com.nwd.radio.service.RadioService");
    private static final String RADIO_DESCRIPTOR = "com.nwd.radio.service.RadioFeature";
    /** RadioFeature.prefeb(boolean next): the next or previous preset, as NWD's radio widget steps. */
    private static final int RADIO_PRESET = 26;
    static final ComponentName RADIO_APP = new ComponentName("com.nwd.radio", "com.nwd.radio.home_horizontalActivity");
    static final ComponentName MUSIC_APP =
            new ComponentName("com.nwd.android.music.ui", "com.nwd.android.music.ui.home_horizontalActivity");
    /** The player sends the time about once a second while it plays. */
    private static final long PLAYING_MS = 2500;

    private final Context context;
    private final Listener listener;
    private boolean started;
    private int source;
    private int frequency = -1, band;
    private String title, artist, album;
    private long position = -1, duration, timeAt;
    private IBinder radio;
    private boolean radioBound;
    /** A preset step asked for while the radio's service was still connecting: 1 next, -1 previous, 0 none. */
    private int pending;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (ACTION_SOURCE.equals(action)) {
                source = intent.getByteExtra(EXTRA_SOURCE, (byte) 0);
                if (source == SOURCE_RADIO) requestFrequency();
            } else if (ACTION_FREQUENCY.equals(action)) {
                frequency = intent.getIntExtra("extra_frequence", -1);
                band = intent.getByteExtra("extra_band", (byte) 0);
            } else if (ACTION_TRACK.equals(action)) {
                if (intent.getIntExtra("extra_media_app_src_inout", 0) == 1) {
                    // The player closed.
                    title = artist = album = null;
                    position = -1;
                    duration = 0;
                } else {
                    title = blank(intent.getStringExtra("extra_media_name"));
                    artist = blank(intent.getStringExtra("extra_media_artist"));
                    album = blank(intent.getStringExtra("extra_media_ablum"));
                }
            } else if (ACTION_TIME.equals(action)) {
                position = intent.getLongExtra("extra_meida_duration", -1);
                duration = intent.getLongExtra("extra_media_total_time", 0);
                timeAt = SystemClock.elapsedRealtime();
            } else {
                return;
            }
            listener.onNwdMediaChanged();
        }
    };

    private final ServiceConnection radioConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            radio = service;
            if (pending != 0) preset(pending > 0);
            pending = 0;
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            radio = null;
        }
    };

    NwdMedia(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_SOURCE);
        f.addAction(ACTION_FREQUENCY);
        f.addAction(ACTION_TRACK);
        f.addAction(ACTION_TIME);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, f);
        // The source as it stands; a byte, stored either signed or not.
        source = (byte) Settings.System.getInt(context.getContentResolver(), CURRENT_SOURCE, 0);
        if (source == SOURCE_RADIO) requestFrequency();
    }

    void stop() {
        if (!started) return;
        started = false;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Not registered.
        }
        if (radioBound) {
            try {
                context.unbindService(radioConnection);
            } catch (IllegalArgumentException ignored) {
                // Already gone.
            }
            radioBound = false;
            radio = null;
        }
    }

    /** The radio answers with its frequency, as it does for NWD's radio widget. */
    private void requestFrequency() {
        context.sendBroadcast(new Intent(ACTION_REQUEST_FREQUENCY));
    }

    /** Whether the radio or the music player is the unit's source, so the card should show it. */
    boolean active() {
        return source == SOURCE_RADIO || source == SOURCE_MUSIC && title != null;
    }

    boolean radio() {
        return source == SOURCE_RADIO;
    }

    ComponentName app() {
        return radio() ? RADIO_APP : MUSIC_APP;
    }

    /** The radio's frequency ("FM 101.7"), or the track. */
    String title() {
        if (!radio()) return title;
        if (frequency <= 0) return null;
        // FM in hundredths of a MHz (some tuners in kHz); AM in kHz. Bands 0 to 2 are FM1 to FM3.
        boolean fm = band >= 0 && band <= 2;
        if (!fm) return "AM " + frequency;
        float mhz = frequency >= 20000 ? frequency / 1000f : frequency / 100f;
        String s = String.format(Locale.ROOT, Math.round(mhz * 100) % 10 == 0 ? "%.1f" : "%.2f", mhz);
        return "FM " + s;
    }

    String artist() {
        if (radio()) return null;
        return artist != null ? artist : album;
    }

    boolean playing() {
        return radio() || timeAt > 0 && SystemClock.elapsedRealtime() - timeAt < PLAYING_MS;
    }

    long position() {
        return radio() ? -1 : position;
    }

    long duration() {
        return radio() ? 0 : duration;
    }

    long updated() {
        return timeAt;
    }

    void previous() {
        if (radio()) preset(false);
        else command(2);
    }

    void next() {
        if (radio()) preset(true);
        else command(3);
    }

    /** The music player toggles; the radio has nothing to pause, so its app opens instead (by the caller). */
    void playPause() {
        if (radio()) return;
        Intent i = new Intent(ACTION_PLAY_PAUSE).putExtra("extra_button", 2);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(i);
    }

    private void command(int code) {
        Intent i = new Intent(ACTION_PLAY_COMMAND).putExtra("extra_command", code);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(i);
    }

    /** The next or previous preset through the radio's service, bound the first time it is needed. */
    private void preset(boolean next) {
        if (radio == null) {
            pending = next ? 1 : -1;
            if (!radioBound) {
                try {
                    radioBound = context.bindService(new Intent("com.nwd.radio.service.ACTION_RADIO_SERVICE")
                            .setComponent(RADIO_SERVICE), radioConnection, Context.BIND_AUTO_CREATE);
                } catch (SecurityException e) {
                    radioBound = false;
                }
            }
            return;
        }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(RADIO_DESCRIPTOR);
            data.writeInt(next ? 1 : 0);
            radio.transact(RADIO_PRESET, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            // The radio went away; bound afresh on the next press.
            radio = null;
            try {
                context.unbindService(radioConnection);
            } catch (IllegalArgumentException ignored) {
                // Already gone.
            }
            radioBound = false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static String blank(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
