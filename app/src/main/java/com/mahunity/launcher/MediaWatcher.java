package com.mahunity.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The player behind the media card. With track info allowed (MediaListener enabled) it watches every media
 * session and follows the one playing: title, artist, art, position. Players that publish no session are
 * read from their media notification instead. Without access the keys go out as media button events and
 * AudioManager.isMusicActive() is the only thing known.
 */
final class MediaWatcher implements MediaSessionManager.OnActiveSessionsChangedListener {
    interface Listener {
        void onMediaChanged();
    }

    private final Context context;
    private final MediaSessionManager sessions;
    private final AudioManager audio;
    private final ComponentName service;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Every session we listen to, the followed one among them. */
    private final List<MediaController> watched = new ArrayList<>();
    private MediaController controller;
    /** A session reached only through a player's notification, kept while that notification lasts. */
    private MediaController fromNotification;
    private boolean watching;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            // Another player may have started or stopped: follow whichever plays now.
            pick();
        }

        @Override public void onMetadataChanged(MediaMetadata metadata) {
            listener.onMediaChanged();
        }

        @Override public void onSessionDestroyed() {
            if (watching) refresh();
        }
    };

    private final Runnable notificationChanged = new Runnable() {
        @Override public void run() {
            if (watching) refresh();
        }
    };

    MediaWatcher(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        service = new ComponentName(context, MediaListener.class);
    }

    ComponentName service() {
        return service;
    }

    boolean hasAccess() {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        for (String s : enabled.split(":")) {
            if (service.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    void start() {
        watching = true;
        MediaListener.observer = notificationChanged;
        if (hasAccess()) {
            try {
                sessions.addOnActiveSessionsChangedListener(this, service, handler);
            } catch (SecurityException ignored) {
                // Access was withdrawn between the check and the call.
            }
        }
        refresh();
    }

    void stop() {
        watching = false;
        MediaListener.observer = null;
        try {
            sessions.removeOnActiveSessionsChangedListener(this);
        } catch (RuntimeException ignored) {
            // Never added.
        }
        watch(new ArrayList<MediaController>());
        controller = null;
    }

    @Override public void onActiveSessionsChanged(List<MediaController> list) {
        List<MediaController> next = list == null ? new ArrayList<MediaController>() : new ArrayList<>(list);
        // A player seen only through its notification joins the list unless it is there already.
        MediaListener.Track t = MediaListener.track();
        if (t != null && t.token != null) {
            if (fromNotification == null || !fromNotification.getSessionToken().equals(t.token)) {
                fromNotification = new MediaController(context, t.token);
            }
            boolean listed = false;
            for (MediaController c : next) listed |= c.getSessionToken().equals(t.token);
            if (!listed) next.add(fromNotification);
        } else {
            fromNotification = null;
        }
        watch(next);
        pick();
    }

    /** Registers for every session in the list and drops the ones that left it. */
    private void watch(List<MediaController> next) {
        for (MediaController old : watched) {
            if (!contains(next, old)) old.unregisterCallback(callback);
        }
        List<MediaController> kept = new ArrayList<>();
        for (MediaController c : next) {
            MediaController same = find(watched, c);
            if (same != null) {
                kept.add(same);
            } else {
                c.registerCallback(callback, handler);
                kept.add(c);
            }
        }
        watched.clear();
        watched.addAll(kept);
    }

    /** Follows the first session that is playing, otherwise the most recent one. */
    private void pick() {
        MediaController pick = null;
        for (MediaController c : watched) {
            if (isPlaying(c.getPlaybackState())) {
                pick = c;
                break;
            }
        }
        if (pick == null && !watched.isEmpty()) pick = watched.get(0);
        controller = pick;
        listener.onMediaChanged();
    }

    private static boolean contains(List<MediaController> list, MediaController c) {
        return find(list, c) != null;
    }

    private static MediaController find(List<MediaController> list, MediaController c) {
        for (MediaController x : list) {
            if (x.getSessionToken().equals(c.getSessionToken())) return x;
        }
        return null;
    }

    /** A player is known: a media session, or a media notification from a player without one. */
    boolean hasSession() {
        return controller != null || notificationTrack() != null;
    }

    String packageName() {
        if (controller != null) return controller.getPackageName();
        MediaListener.Track t = notificationTrack();
        return t == null ? null : t.packageName;
    }

    String title() {
        String s = text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        MediaListener.Track t = notificationTrack();
        return s != null || t == null ? s : t.title;
    }

    String artist() {
        String s = text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        MediaListener.Track t = notificationTrack();
        return s != null || t == null ? s : t.artist;
    }

    Bitmap art() {
        MediaMetadata m = metadata();
        Bitmap b = null;
        if (m != null) {
            b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        MediaListener.Track t = notificationTrack();
        return b != null || t == null ? b : t.art;
    }

    /** The notification's track, when it belongs to the followed player or no session is followed. */
    private MediaListener.Track notificationTrack() {
        if (!hasAccess()) return null;
        MediaListener.Track t = MediaListener.track();
        if (t == null) return null;
        if (controller == null || controller.getPackageName().equals(t.packageName)) return t;
        return null;
    }

    boolean playing() {
        // Some players never report their state; any music on the speakers counts.
        PlaybackState state = state();
        return state != null ? isPlaying(state) || audio.isMusicActive() : audio.isMusicActive();
    }

    /** Position in ms at {@link #updated()}, or -1 when unknown. */
    long position() {
        PlaybackState s = state();
        return s == null ? -1 : s.getPosition();
    }

    long updated() {
        PlaybackState s = state();
        return s == null ? 0 : s.getLastPositionUpdateTime();
    }

    float rate() {
        PlaybackState s = state();
        return s == null ? 0f : s.getPlaybackSpeed();
    }

    long duration() {
        MediaMetadata m = metadata();
        return m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    void playPause() {
        if (controller == null) {
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        } else if (playing()) {
            controller.getTransportControls().pause();
        } else {
            controller.getTransportControls().play();
        }
    }

    void next() {
        if (controller == null) key(KeyEvent.KEYCODE_MEDIA_NEXT);
        else controller.getTransportControls().skipToNext();
    }

    void previous() {
        if (controller == null) key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        else controller.getTransportControls().skipToPrevious();
    }

    private void refresh() {
        List<MediaController> list = null;
        if (hasAccess()) {
            try {
                list = sessions.getActiveSessions(service);
            } catch (SecurityException ignored) {
                // Access was withdrawn.
            }
        }
        onActiveSessionsChanged(list);
    }

    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private MediaMetadata metadata() {
        return controller == null ? null : controller.getMetadata();
    }

    private PlaybackState state() {
        return controller == null ? null : controller.getPlaybackState();
    }

    private String text(String... keys) {
        MediaMetadata m = metadata();
        if (m == null) return null;
        for (String k : keys) {
            CharSequence t = m.getText(k);
            if (t != null && t.length() > 0) return t.toString();
        }
        return null;
    }

    private static boolean isPlaying(PlaybackState s) {
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }
}
