package com.mahunity.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.util.List;

/** Home: the tiles, the clock and the media card, with all apps and Settings as sheets over it. */
public final class HomeActivity extends Activity implements Apps.Listener, MediaWatcher.Listener, SpeedWatcher.Listener {
    private static final int REQUEST_LOCATION = 1;
    private static final String PREF_ACCENT = "accent";
    private static final String PREF_SPEED = "speed";
    private static final String PREF_NAME = "name";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AppEntry[] slotApps = new AppEntry[Slots.COUNT];
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);

    private SharedPreferences prefs;
    private Slots slots;
    private Apps apps;
    private MediaWatcher media;
    private SpeedWatcher speed;

    private HomeLayout home;
    private TopBar top;
    private ClockView clock;
    private MediaCard card;
    private TileView[] tiles;
    private Panel appsPanel, settingsPanel, shown;
    private AppsGrid grid;
    private SettingsRows.Row rowHome, rowAmbient, rowSpeed, rowMedia, rowName;
    private String[] headings;
    private int picking = -1;

    private final Runnable recheckMedia = new Runnable() {
        @Override public void run() {
            onMediaChanged();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        Chrome.accent = Chrome.ACCENTS[accentIndex()];
        slots = new Slots(this, prefs);
        media = new MediaWatcher(this, this);
        speed = new SpeedWatcher(this, this);
        apps = new Apps(this, this);

        buildHome();
        buildAppsPanel();
        buildSettingsPanel();
        FrameLayout root = new FrameLayout(this);
        root.addView(home);
        root.addView(appsPanel);
        root.addView(settingsPanel);
        setContentView(root);
    }

    private void buildHome() {
        top = new TopBar(this);
        top.setText(prefs.getString(PREF_NAME, getString(R.string.app_name)));
        ChromeButton gear = new ChromeButton(this, R.drawable.ic_settings, R.string.settings_title);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSettings();
            }
        });
        clock = new ClockView(this);

        card = new MediaCard(this);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openPlayer(v);
            }
        });
        card.prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                media.previous();
                recheckMediaSoon();
            }
        });
        card.play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                media.playPause();
                recheckMediaSoon();
            }
        });
        card.next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                media.next();
                recheckMediaSoon();
            }
        });

        tiles = new TileView[Slots.COUNT];
        for (int i = 0; i < Slots.COUNT; i++) {
            final int slot = i;
            TileView tile = new TileView(this, Slots.ICONS[i], getString(Slots.TITLES[i]));
            tile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openSlot(slot, v);
                }
            });
            tile.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (slot == Slots.APPS) showSettings();
                    else showPicker(slot);
                    return true;
                }
            });
            tiles[i] = tile;
        }
        home = new HomeLayout(this, top, gear, clock, card, tiles);
    }

    private void buildAppsPanel() {
        appsPanel = new Panel(this);
        appsPanel.setVisibility(View.GONE);
        appsPanel.close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hide(appsPanel);
            }
        });
        appsPanel.action.setLabel(getString(R.string.pick_auto));
        appsPanel.action.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (picking >= 0) slots.forget(picking);
                resolveSlots();
                hide(appsPanel);
            }
        });
        grid = new AppsGrid(this, apps);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppEntry e = grid.app(position);
                if (picking >= 0) {
                    slots.choose(picking, e);
                    resolveSlots();
                    hide(appsPanel);
                } else {
                    launch(e, view);
                }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (picking >= 0) return false;
                // App info: uninstall, force stop, permissions.
                startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", grid.app(position).component.getPackageName(), null)));
                return true;
            }
        });
        appsPanel.setContent(grid);
    }

    private void buildSettingsPanel() {
        settingsPanel = new Panel(this);
        settingsPanel.setVisibility(View.GONE);
        settingsPanel.setTitle(getString(R.string.settings_title), null);
        settingsPanel.close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hide(settingsPanel);
            }
        });

        SettingsRows rows = new SettingsRows(this);
        rowHome = rows.add(R.string.set_home, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!startSafely(new Intent(Settings.ACTION_HOME_SETTINGS))) startSafely(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        rowAmbient = rows.add(R.string.set_ambient, new View.OnClickListener() {
            @Override public void onClick(View v) {
                int next = (accentIndex() + 1) % Chrome.ACCENTS.length;
                prefs.edit().putInt(PREF_ACCENT, next).apply();
                Chrome.accent = Chrome.ACCENTS[next];
                Chrome.invalidateTree(getWindow().getDecorView());
                refreshSettings();
            }
        });
        rowSpeed = rows.add(R.string.set_speed, new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleSpeed();
            }
        });
        rowMedia = rows.add(R.string.set_media, new View.OnClickListener() {
            @Override public void onClick(View v) {
                openTrackInfoAccess();
            }
        });
        rowName = rows.add(R.string.set_name, new View.OnClickListener() {
            @Override public void onClick(View v) {
                editName();
            }
        });
        SettingsRows.Row tilesRow = rows.add(R.string.set_tiles, new View.OnClickListener() {
            @Override public void onClick(View v) {
                slots.forgetAll();
                resolveSlots();
                Toast.makeText(HomeActivity.this, R.string.tiles_reset_done, Toast.LENGTH_SHORT).show();
            }
        });
        tilesRow.setValue(getString(R.string.tiles_reset), false, 0);
        SettingsRows.Row systemRow = rows.add(R.string.set_system, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startSafely(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        systemRow.setValue(getString(R.string.open), false, 0);
        rows.add(R.string.set_version, null).setValue(versionName(), false, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(rows, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsPanel.setContent(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        clock.start();
        media.start();
        if (prefs.getBoolean(PREF_SPEED, false) && speed.allowed()) speed.start();
        else clock.hideSpeed();
        if (shown == settingsPanel) refreshSettings();
    }

    @Override protected void onPause() {
        super.onPause();
        clock.stop();
        media.stop();
        speed.stop();
        handler.removeCallbacks(recheckMedia);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        apps.close();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Home is showing: back to the tiles.
        if (shown != null) hide(shown);
    }

    @Override public void onBackPressed() {
        // Home has nowhere to go back to; Back only closes a sheet.
        if (shown != null) hide(shown);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE) apps.trim();
    }

    // Apps and tiles

    @Override public void onAppsChanged(List<AppEntry> list) {
        grid.setApps(list);
        if (shown == appsPanel && picking < 0) appsPanel.setTitle(getString(R.string.apps_title), appsCount());
        resolveSlots();
        // The player's name on the media card comes from this list.
        onMediaChanged();
    }

    private void resolveSlots() {
        List<AppEntry> list = apps.list();
        for (int i = 0; i < Slots.COUNT; i++) {
            if (i == Slots.APPS) {
                tiles[i].setSubtitle(appsCount());
                continue;
            }
            slotApps[i] = slots.resolve(i, list);
            tiles[i].setSubtitle(slotApps[i] != null ? slotApps[i].label : getString(R.string.hold_to_choose));
        }
    }

    private String appsCount() {
        int n = apps.list().size();
        return getResources().getQuantityString(R.plurals.apps_count, n, n);
    }

    private void openSlot(int slot, View from) {
        if (slot == Slots.APPS) showApps();
        else if (slotApps[slot] != null) launch(slotApps[slot], from);
        else showPicker(slot);
    }

    private void launch(AppEntry e, View from) {
        try {
            Rect bounds = new Rect();
            from.getGlobalVisibleRect(bounds);
            Bundle options = ActivityOptions.makeClipRevealAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            apps.launcherApps().startMainActivity(e.component, e.user, bounds, options);
        } catch (RuntimeException ex) {
            // Some firmware refuses LauncherApps for its own apps; a plain launch intent still works there.
            Intent intent = Intent.makeMainActivity(e.component).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (!startSafely(intent)) {
                Toast.makeText(this, getString(R.string.cannot_open, e.label), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean startSafely(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // Sheets

    private void showApps() {
        picking = -1;
        appsPanel.setTitle(getString(R.string.apps_title), appsCount());
        appsPanel.action.setVisibility(View.GONE);
        grid.setSelection(0);
        show(appsPanel);
    }

    private void showPicker(int slot) {
        picking = slot;
        appsPanel.setTitle(getString(R.string.pick_title, getString(Slots.TITLES[slot])), null);
        appsPanel.action.setVisibility(View.VISIBLE);
        grid.setSelection(0);
        show(appsPanel);
    }

    private void showSettings() {
        refreshSettings();
        show(settingsPanel);
    }

    private void show(final Panel panel) {
        if (shown != null && shown != panel) shown.setVisibility(View.GONE);
        shown = panel;
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationY(home.getHeight() * 0.03f);
        panel.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(ease).withEndAction(new Runnable() {
            @Override public void run() {
                // Nothing of Home shows through an open sheet; skip drawing it.
                home.setVisibility(View.INVISIBLE);
            }
        }).start();
    }

    private void hide(final Panel panel) {
        if (shown == panel) shown = null;
        home.setVisibility(View.VISIBLE);
        panel.animate().alpha(0f).translationY(home.getHeight() * 0.03f).setDuration(160).setInterpolator(ease)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        panel.setVisibility(View.GONE);
                    }
                }).start();
    }

    // Media

    @Override public void onMediaChanged() {
        boolean playing = media.playing();
        if (media.hasSession()) {
            String title = media.title();
            String source = Text.upper(appLabel(media.packageName()));
            card.show(source, title != null ? title : appLabel(media.packageName()), media.artist(), media.art(),
                    playing, media.position(), media.duration(), media.rate(), media.updated());
        } else {
            card.show(Text.upper(getString(R.string.slot_music)),
                    getString(playing ? R.string.media_playing : R.string.no_media),
                    getString(media.hasAccess() ? R.string.media_hint : R.string.media_hint_access),
                    null, playing, -1, 0, 0f, 0);
        }
        card.play.setIcon(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        card.play.setActive(playing);
    }

    /** Without a session nothing reports the change; look again once the player has reacted to the key. */
    private void recheckMediaSoon() {
        handler.removeCallbacks(recheckMedia);
        handler.postDelayed(recheckMedia, 700);
    }

    private void openPlayer(View from) {
        String pkg = media.packageName();
        Intent launch = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null && startSafely(launch)) return;
        if (slotApps[Slots.MUSIC] != null) launch(slotApps[Slots.MUSIC], from);
        else showPicker(Slots.MUSIC);
    }

    private String appLabel(String pkg) {
        if (pkg == null) return "";
        for (AppEntry e : apps.list()) {
            if (e.component.getPackageName().equals(pkg)) return e.label;
        }
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            return String.valueOf(getPackageManager().getApplicationLabel(info));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void openTrackInfoAccess() {
        if (startSafely(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return;
        // Some head units strip that screen out of their Settings app.
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_media)
                .setMessage(getString(R.string.media_access_adb, media.service().flattenToString()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // GPS speed

    private void toggleSpeed() {
        if (prefs.getBoolean(PREF_SPEED, false)) {
            prefs.edit().putBoolean(PREF_SPEED, false).apply();
            speed.stop();
            clock.hideSpeed();
        } else if (speed.allowed()) {
            enableSpeed();
        } else {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        }
        refreshSettings();
    }

    private void enableSpeed() {
        prefs.edit().putBoolean(PREF_SPEED, true).apply();
        speed.start();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_LOCATION && speed.allowed()) enableSpeed();
        refreshSettings();
    }

    @Override public void onSpeed(float kmh, float bearing) {
        String heading = null;
        if (bearing >= 0) {
            if (headings == null) headings = getResources().getStringArray(R.array.headings);
            heading = headings[Math.round(bearing / 45f) % headings.length];
        }
        clock.setSpeed(kmh < 0 ? "–" : String.valueOf(Math.round(kmh)), heading);
    }

    // Settings

    private void refreshSettings() {
        boolean isDefault = isDefaultHome();
        rowHome.setValue(getString(isDefault ? R.string.home_is_default : R.string.home_make_default), !isDefault, 0);
        rowAmbient.setValue(getResources().getStringArray(R.array.ambient_names)[accentIndex()], false, Chrome.accent);
        boolean speedOn = prefs.getBoolean(PREF_SPEED, false);
        rowSpeed.setValue(getString(speedOn ? R.string.on : R.string.off), speedOn, 0);
        boolean access = media.hasAccess();
        rowMedia.setValue(getString(access ? R.string.media_on : R.string.media_allow), !access, 0);
        rowName.setValue(prefs.getString(PREF_NAME, getString(R.string.app_name)), false, 0);
    }

    private void editName() {
        final EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(prefs.getString(PREF_NAME, getString(R.string.app_name)));
        field.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        int pad = Math.round(Chrome.unit(this) * 0.04f);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_name)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String name = field.getText().toString().trim();
                        prefs.edit().putString(PREF_NAME, name).apply();
                        top.setText(name);
                        refreshSettings();
                    }
                })
                .show();
    }

    private boolean isDefaultHome() {
        ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }

    private int accentIndex() {
        int i = prefs.getInt(PREF_ACCENT, 0);
        return i >= 0 && i < Chrome.ACCENTS.length ? i : 0;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
