package com.mahunity.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Never reads a notification. Being an enabled notification listener is what lets Home ask MediaSessionManager
 * which player is active and what it is playing (MediaWatcher). Without it the media keys still work, blind.
 */
public final class MediaListener extends NotificationListenerService {
}
