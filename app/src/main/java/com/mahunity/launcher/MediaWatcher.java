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

import java.util.List;

/**
 * The player behind the media card. With track info allowed (MediaListener enabled) it follows the active
 * media session: title, artist, art, position. Without it the keys go out as media button events and
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
    private MediaController controller;
    private boolean watching;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            listener.onMediaChanged();
        }

        @Override public void onMetadataChanged(MediaMetadata metadata) {
            listener.onMediaChanged();
        }

        @Override public void onSessionDestroyed() {
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
        if (hasAccess()) {
            try {
                sessions.addOnActiveSessionsChangedListener(this, service, handler);
                refresh();
                return;
            } catch (SecurityException ignored) {
                // Access was withdrawn between the check and the call.
            }
        }
        use(null);
    }

    void stop() {
        watching = false;
        try {
            sessions.removeOnActiveSessionsChangedListener(this);
        } catch (RuntimeException ignored) {
            // Never added.
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
    }

    @Override public void onActiveSessionsChanged(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                if (isPlaying(c.getPlaybackState())) {
                    pick = c;
                    break;
                }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
        }
        use(pick);
    }

    boolean hasSession() {
        return controller != null;
    }

    String packageName() {
        return controller == null ? null : controller.getPackageName();
    }

    String title() {
        return text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    }

    String artist() {
        return text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
    }

    Bitmap art() {
        MediaMetadata m = metadata();
        if (m == null) return null;
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    boolean playing() {
        return controller != null ? isPlaying(controller.getPlaybackState()) : audio.isMusicActive();
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
        try {
            onActiveSessionsChanged(sessions.getActiveSessions(service));
        } catch (SecurityException e) {
            use(null);
        }
    }

    private void use(MediaController next) {
        boolean same = controller != null && next != null && controller.getSessionToken().equals(next.getSessionToken());
        if (!same) {
            if (controller != null) controller.unregisterCallback(callback);
            controller = next;
            if (next != null) next.registerCallback(callback, handler);
        }
        listener.onMediaChanged();
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
