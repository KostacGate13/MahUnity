package com.mahunity.launcher;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Being an enabled notification listener is what lets Home ask MediaSessionManager which player is active
 * (MediaWatcher). Beyond that it looks only at media notifications, the "now playing" one a player posts:
 * some head-unit players never publish a media session, and their notification is the only place the title
 * and artist appear. Every other notification is ignored.
 */
public final class MediaListener extends NotificationListenerService {
    /** The newest media notification: its player, title, artist, art and session (any of them may be null). */
    static final class Track {
        final String key, packageName, title, artist;
        final Bitmap art;
        final MediaSession.Token token;

        Track(String key, String packageName, String title, String artist, Bitmap art, MediaSession.Token token) {
            this.key = key;
            this.packageName = packageName;
            this.title = title;
            this.artist = artist;
            this.art = art;
            this.token = token;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Track track;
    /** Told on the main thread when the track changes; set by MediaWatcher while Home is on screen. */
    static volatile Runnable observer;

    static Track track() {
        return track;
    }

    @Override public void onListenerConnected() {
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) consider(sbn);
        } catch (RuntimeException ignored) {
            // Not bound after all.
        }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        consider(sbn);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        Track t = track;
        if (t != null && t.key.equals(sbn.getKey())) publish(null);
    }

    private void consider(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        if (extras == null) return;
        MediaSession.Token token = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        String template = Build.VERSION.SDK_INT >= 24 ? extras.getString(Notification.EXTRA_TEMPLATE) : null;
        boolean media = token != null || Notification.CATEGORY_TRANSPORT.equals(n.category)
                || (template != null && template.contains("MediaStyle"));
        if (!media) return;
        publish(new Track(sbn.getKey(), sbn.getPackageName(), text(extras, Notification.EXTRA_TITLE),
                text(extras, Notification.EXTRA_TEXT), art(n), token));
    }

    private Bitmap art(Notification n) {
        Bitmap b = n.extras.getParcelable(Notification.EXTRA_LARGE_ICON);
        if (b != null) return b;
        Icon icon = n.getLargeIcon();
        if (icon == null) return null;
        try {
            android.graphics.drawable.Drawable d = icon.loadDrawable(this);
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            }
        } catch (RuntimeException ignored) {
            // The player's resources are gone.
        }
        return null;
    }

    private static String text(Bundle extras, String key) {
        CharSequence t = extras.getCharSequence(key);
        return t == null || t.length() == 0 ? null : t.toString();
    }

    private static void publish(Track next) {
        track = next;
        MAIN.post(new Runnable() {
            @Override public void run() {
                Runnable o = observer;
                if (o != null) o.run();
            }
        });
    }
}
