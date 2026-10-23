package com.mahunity.launcher;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.util.LruCache;
import android.view.View;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The launchable apps, loaded off the main thread and kept fresh by LauncherApps callbacks, and their icons. */
final class Apps {
    interface Listener {
        void onAppsChanged(List<AppEntry> apps);
    }

    private static final int[] DENSITIES = {160, 240, 320, 480, 640};

    private final Context context;
    private final LauncherApps launcherApps;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("apps", Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler worker;
    private final LruCache<String, Bitmap> icons;
    /** Icons being loaded, and the view to redraw when each arrives. Main thread only. */
    private final Map<String, View> pending = new HashMap<>();
    private List<AppEntry> apps = Collections.emptyList();

    private final LauncherApps.Callback callback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String packageName, UserHandle user) { changed(); }
        @Override public void onPackageAdded(String packageName, UserHandle user) { changed(); }
        @Override public void onPackageChanged(String packageName, UserHandle user) { changed(); }
        @Override public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) { changed(); }
        @Override public void onPackagesUnavailable(String[] packageNames, UserHandle user, boolean replacing) { changed(); }
    };

    private final Runnable load = new Runnable() {
        @Override public void run() {
            List<LauncherActivityInfo> infos = launcherApps.getActivityList(null, Process.myUserHandle());
            String self = context.getPackageName();
            final List<AppEntry> list = new ArrayList<>(infos.size());
            for (LauncherActivityInfo info : infos) {
                if (!self.equals(info.getComponentName().getPackageName())) list.add(new AppEntry(info));
            }
            final Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            Collections.sort(list, new Comparator<AppEntry>() {
                @Override public int compare(AppEntry a, AppEntry b) {
                    return collator.compare(a.label, b.label);
                }
            });
            main.post(new Runnable() {
                @Override public void run() {
                    apps = Collections.unmodifiableList(list);
                    listener.onAppsChanged(apps);
                }
            });
        }
    };

    Apps(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        thread.start();
        worker = new Handler(thread.getLooper());
        int budget = (int) Math.min(Runtime.getRuntime().maxMemory() / 16, 12L * 1024 * 1024);
        icons = new LruCache<String, Bitmap>(budget) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        launcherApps.registerCallback(callback, main);
        worker.post(load);
    }

    List<AppEntry> list() {
        return apps;
    }

    LauncherApps launcherApps() {
        return launcherApps;
    }

    /** The icon at size px, or null while it loads; target is redrawn when it arrives. */
    Bitmap icon(final AppEntry entry, final int size, View target) {
        if (entry == null || size <= 0) return null;
        final String key = entry.key + '@' + size;
        Bitmap cached = icons.get(key);
        if (cached != null) return cached;
        boolean queued = pending.containsKey(key);
        pending.put(key, target);
        if (queued) return null;
        worker.post(new Runnable() {
            @Override public void run() {
                final Bitmap bitmap = render(entry, size);
                main.post(new Runnable() {
                    @Override public void run() {
                        View t = pending.remove(key);
                        if (bitmap != null) icons.put(key, bitmap);
                        if (t != null) t.invalidate();
                    }
                });
            }
        });
        return null;
    }

    void trim() {
        icons.evictAll();
    }

    void close() {
        launcherApps.unregisterCallback(callback);
        thread.quitSafely();
    }

    private void changed() {
        icons.evictAll();
        worker.removeCallbacks(load);
        worker.postDelayed(load, 300);
    }

    private Bitmap render(AppEntry entry, int size) {
        Drawable d;
        try {
            d = entry.info.getIcon(density(size));
        } catch (RuntimeException e) {
            d = null;
        }
        if (d == null) d = context.getPackageManager().getDefaultActivityIcon();
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(b));
        return b;
    }

    /** The smallest density bucket whose 48dp icon is at least size px, so icons stay sharp on a 160 dpi panel. */
    private static int density(int size) {
        for (int dpi : DENSITIES) {
            if (48 * dpi / 160 >= size) return dpi;
        }
        return DENSITIES[DENSITIES.length - 1];
    }
}
