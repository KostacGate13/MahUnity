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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Home: the cockpit with the car (or, with the car switched off, the classic tiles), with all apps and
 * Settings as sheets over it.
 */
public final class HomeActivity extends Activity
        implements Apps.Listener, MediaWatcher.Listener, SpeedWatcher.Listener, CanBus.Listener {
    private static final int REQUEST_LOCATION = 1;
    private static final String PREF_ACCENT = "accent";
    private static final String PREF_SPEED = "speed";
    private static final String PREF_NAME = "name";
    private static final String PREF_CAR = "car";
    private static final String PREF_HOLOGRAM = "hologram";
    private static final String PREF_LOOK = "look";
    private static final String PREF_SHADE = "shade";
    private static final String PREF_FROST = "frost";
    private static final String PREF_TOP_WIDGET = "top_widget";
    private static final String PREF_BOTTOM_WIDGET = "bottom_widget";
    private static final String PREF_TANK = "tank";
    /** Upper widget choices, and lower ones, in the order Settings lists them. */
    private static final int TOP_CLOCK = 0, TOP_GAUGE_60S = 1, TOP_GAUGE_80S = 2;
    private static final int BOTTOM_MUSIC = 0, BOTTOM_FUEL = 1, BOTTOM_TYRES = 2, BOTTOM_TRIP = 3;
    private static final int[] TOP_NAMES = {R.string.widget_clock, R.string.widget_gauge60, R.string.widget_gauge80};
    private static final int[] BOTTOM_NAMES = {R.string.slot_music, R.string.widget_fuel, R.string.widget_tyres,
            R.string.widget_trip};
    /** The Corsa E's tank, in litres. */
    private static final float DEFAULT_TANK = 45f;
    /** Door bits in the order they are named and turned to: the driver's door (left-hand drive) first. */
    private static final int[] DOOR_BITS = {CanBus.FRONT_LEFT, CanBus.FRONT_RIGHT, CanBus.REAR_LEFT,
            CanBus.REAR_RIGHT, CanBus.TRUNK, CanBus.HOOD};
    private static final String[] DOOR_PARTS = {"door_left", "door_right", "door_left", "door_right", "trunk", "hood"};
    private static final int[] DOOR_LABELS = {R.string.open_driver, R.string.open_passenger, R.string.open_rear_left,
            R.string.open_rear_right, R.string.open_trunk, R.string.open_hood};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AppEntry[] slotApps = new AppEntry[Slots.COUNT];
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);

    private SharedPreferences prefs;
    private Slots slots;
    private Apps apps;
    private MediaWatcher media;
    private SpeedWatcher speed;
    private CanBus can;
    private Trip trip;

    private ViewGroup home;
    /** Null on the classic Home. */
    private CockpitLayout cockpit;
    private DockBar.Item[] tabItems;
    private VehicleView vehicle;
    private CarFrames carFrames;
    private TopBar top;
    private Header header;
    /** Whichever upper widget Settings picked; the other is null. */
    private ClockView clock;
    private GaugeView gauge;
    private MediaCard card;
    /** The lower widget, when it is not the media card; null otherwise. */
    private FuelCard fuelCard;
    private TyreCard tyreCard;
    private TripCard tripCard;
    private CarView car;
    private TileView[] tiles;
    private Panel appsPanel, settingsPanel, shown;
    private AppsGrid grid;
    private SettingsRows.Row rowHome, rowCar, rowUiStyle, rowFrost, rowShade, rowStyle, rowTop, rowBottom, rowTank, rowAmbient, rowSpeed, rowMedia,
            rowCarData, rowName;
    private String[] headings;
    /** The last GPS speed in km/h, or -1: none, or GPS is off or Home is not showing. */
    private float gpsKmh = -1;
    private float gaugeShown = -2;
    /** The door bits last shown on the car, or -1. */
    private int doorsShown = -1;
    private boolean resumed;
    private int ticks;
    private int picking = -1;

    /**
     * Once a second for as long as Home lives: the trip counts the speed (the car's keeps coming with another app
     * in front), and the trip card and gauge follow it while Home shows.
     */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            float kmh = currentSpeed();
            if (kmh >= 0) trip.sample(kmh);
            if (++ticks % 30 == 0) trip.save();
            if (resumed) {
                if (tripCard != null) tripCard.set(trip);
                showGaugeSpeed();
                // Car Info's logging resets when it restarts; this turns it back on.
                if (ticks % 60 == 0) {
                    can.wake(false);
                    // The greeting and the date move on with the clock.
                    showHeader();
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable recheckMedia = new Runnable() {
        @Override public void run() {
            onMediaChanged();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        Chrome.accent = Chrome.ACCENTS[accentIndex()];
        Chrome.style = prefs.getInt(PREF_LOOK, Chrome.CLASSIC) == Chrome.GLASS ? Chrome.GLASS : Chrome.CLASSIC;
        Chrome.shade = Math.max(0, Math.min(2, prefs.getInt(PREF_SHADE, 0)));
        Chrome.frost = Math.max(0, Math.min(10, prefs.getInt(PREF_FROST, 3)));
        slots = new Slots(this, prefs);
        media = new MediaWatcher(this, this);
        speed = new SpeedWatcher(this, this);
        can = new CanBus(this, this);
        trip = new Trip(prefs);
        apps = new Apps(this, this);

        buildHome();
        buildAppsPanel();
        buildSettingsPanel();
        FrameLayout root = new FrameLayout(this);
        root.addView(home);
        root.addView(appsPanel);
        root.addView(settingsPanel);
        setContentView(root);
        hideStatusBar();
        can.start();
        handler.post(tick);
    }

    private void buildHome() {
        top = new TopBar(this);
        header = new Header(this);
        top.setText(prefs.getString(PREF_NAME, getString(R.string.app_name)));
        ChromeButton gear = new ChromeButton(this, R.drawable.ic_settings, R.string.settings_title);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSettings();
            }
        });
        int topWidget = prefs.getInt(PREF_TOP_WIDGET, TOP_CLOCK);
        if (topWidget == TOP_GAUGE_60S) gauge = new Gauge60(this);
        else if (topWidget == TOP_GAUGE_80S) gauge = new Gauge80(this);
        else clock = new ClockView(this);
        View upper = clock != null ? clock : gauge;

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

        View lower = card;
        switch (prefs.getInt(PREF_BOTTOM_WIDGET, BOTTOM_MUSIC)) {
            case BOTTOM_FUEL:
                lower = fuelCard = new FuelCard(this);
                break;
            case BOTTOM_TYRES:
                lower = tyreCard = new TyreCard(this);
                break;
            case BOTTOM_TRIP:
                lower = tripCard = new TripCard(this);
                tripCard.set(trip);
                break;
            default:
                break;
        }

        tiles = new TileView[Slots.COUNT];
        if (!prefs.getBoolean(PREF_CAR, true)) {
            for (int i = 0; i < Slots.COUNT; i++) tiles[i] = tile(i);
            home = new HomeLayout(this, top, gear, header, upper, lower, tiles);
            showHeader();
            return;
        }

        // The cockpit: music sources as tiles on the Music tab, everything else in the dock.
        TileView[] sources = {tile(Slots.MUSIC), tile(Slots.RADIO), tile(Slots.LINK)};
        tiles[Slots.MUSIC] = sources[0];
        tiles[Slots.RADIO] = sources[1];
        tiles[Slots.LINK] = sources[2];
        vehicle = new VehicleView(this);
        car = new CarView(this);
        if (!prefs.getBoolean(PREF_HOLOGRAM, false)) carFrames = CarFrames.open(this);
        car.setFrames(carFrames);
        DockBar dock = new DockBar(this);
        DockBar.Item homeTab = dock.add(R.drawable.ic_home, R.string.tab_home);
        DockBar.Item nav = dock.add(R.drawable.ic_nav, R.string.slot_nav);
        DockBar.Item musicTab = dock.add(R.drawable.ic_music, R.string.slot_music);
        DockBar.Item phone = dock.add(R.drawable.ic_phone, R.string.slot_phone);
        DockBar.Item vehicleTab = dock.add(R.drawable.ic_car, R.string.tab_vehicle);
        DockBar.Item all = dock.add(R.drawable.ic_apps, R.string.slot_apps);
        tabItems = new DockBar.Item[] {homeTab, musicTab, vehicleTab};
        int[] tabs = {CockpitLayout.HOME, CockpitLayout.MUSIC, CockpitLayout.VEHICLE};
        for (int i = 0; i < tabs.length; i++) {
            final int tab = tabs[i];
            tabItems[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showTab(tab);
                }
            });
        }
        slotItem(nav, Slots.NAV);
        slotItem(phone, Slots.PHONE);
        slotItem(all, Slots.APPS);
        cockpit = new CockpitLayout(this, top, gear, header, upper, lower, card, sources, vehicle, car, dock);
        home = cockpit;
        showTab(CockpitLayout.HOME);
    }

    private TileView tile(final int slot) {
        TileView tile = new TileView(this, Slots.ICONS[slot], getString(Slots.TITLES[slot]));
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
        return tile;
    }

    /** A dock entry that opens a tile's app; a long press chooses the app, as on a tile. */
    private void slotItem(DockBar.Item item, final int slot) {
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openSlot(slot, v);
            }
        });
        item.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (slot == Slots.APPS) showSettings();
                else showPicker(slot);
                return true;
            }
        });
    }

    private void showTab(int tab) {
        boolean animate = cockpit.isLaidOut();
        if (animate && tab == cockpit.tab()) return;
        cockpit.showTab(tab, animate);
        int[] order = {CockpitLayout.HOME, CockpitLayout.MUSIC, CockpitLayout.VEHICLE};
        for (int i = 0; i < order.length; i++) tabItems[i].setChosen(order[i] == tab);
        showHeader();
    }

    /** The header names the tab: the greeting and the date on Home, Music and its sources, Vehicle and the date. */
    private void showHeader() {
        int tab = cockpit == null ? CockpitLayout.HOME : cockpit.tab();
        if (tab == CockpitLayout.MUSIC) header.set(getString(R.string.slot_music), getString(R.string.header_sources));
        else if (tab == CockpitLayout.VEHICLE) header.set(getString(R.string.tab_vehicle), Text.today());
        else header.set(Text.greeting(this), Text.today());
    }

    /** Back to the Home tab, the car turning to face you; false if it was there already. */
    private boolean homeTab() {
        if (cockpit == null || cockpit.tab() == CockpitLayout.HOME) return false;
        showTab(CockpitLayout.HOME);
        return true;
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
        rowCar = rows.add(R.string.set_car, new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean(PREF_CAR, !prefs.getBoolean(PREF_CAR, true)).commit();
                // Home is built for one layout or the other; rebuilding it is simplest and rare.
                recreate();
            }
        });
        rowUiStyle = rows.add(R.string.set_ui_style, new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putInt(PREF_LOOK, Chrome.style == Chrome.GLASS ? Chrome.CLASSIC : Chrome.GLASS).commit();
                recreate();
            }
        });
        rowFrost = rows.add(R.string.set_frost, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickFrost();
            }
        });
        rowShade = rows.add(R.string.set_shade, new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putInt(PREF_SHADE, (Chrome.shade + 1) % 3).commit();
                recreate();
            }
        });
        rowStyle = rows.add(R.string.set_car_style, new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean(PREF_HOLOGRAM, !prefs.getBoolean(PREF_HOLOGRAM, false)).commit();
                recreate();
            }
        });
        rowTop = rows.add(R.string.set_top_widget, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickWidget(R.string.set_top_widget, PREF_TOP_WIDGET, TOP_NAMES);
            }
        });
        rowBottom = rows.add(R.string.set_bottom_widget, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickWidget(R.string.set_bottom_widget, PREF_BOTTOM_WIDGET, BOTTOM_NAMES);
            }
        });
        rowTank = rows.add(R.string.set_tank, new View.OnClickListener() {
            @Override public void onClick(View v) {
                editTank();
            }
        });
        rowAmbient = rows.add(R.string.set_ambient, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickAccent();
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
        rowCarData = rows.add(R.string.set_car_data, new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(HomeActivity.this)
                        .setTitle(R.string.set_car_data)
                        .setMessage(getString(R.string.car_data_help, getPackageName()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
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
        resumed = true;
        if (clock != null) clock.start();
        if (gauge != null) gauge.start();
        top.start();
        media.start();
        if (prefs.getBoolean(PREF_SPEED, false) && speed.allowed()) {
            speed.start();
        } else {
            if (clock != null) clock.hideSpeed();
            gpsKmh = -1;
            showVehicleSpeed();
        }
        can.wake(true);
        // Whatever changed while another app was in front.
        onCanChanged();
        if (tripCard != null) tripCard.set(trip);
        if (shown == settingsPanel) refreshSettings();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        if (clock != null) clock.stop();
        if (gauge != null) gauge.stop();
        top.stop();
        media.stop();
        speed.stop();
        // GPS stops with Home off screen; the trip goes on with the car's speed alone.
        gpsKmh = -1;
        trip.save();
        handler.removeCallbacks(recheckMedia);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(tick);
        trip.save();
        apps.close();
        can.close();
        if (carFrames != null) carFrames.close();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Home is showing: close the sheet, or turn back to the Home tab.
        if (shown != null) hide(shown);
        else homeTab();
    }

    @Override public void onBackPressed() {
        // Home has nowhere to go back to; Back only closes a sheet or returns to the Home tab.
        if (shown != null) hide(shown);
        else homeTab();
    }

    /** Back from another app or a dialog, the system may have brought its bar back. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
    }

    /**
     * Full screen: Home's top bar carries the time and the temperature, so the system's status bar is hidden
     * (the theme asks for it too). A swipe down from the top edge still shows it for a moment.
     */
    @SuppressWarnings("deprecation")
    private void hideStatusBar() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c == null) return;
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsets.Type.statusBars());
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
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
                if (tiles[i] != null) tiles[i].setSubtitle(appsCount());
                continue;
            }
            slotApps[i] = slots.resolve(i, list);
            if (tiles[i] != null) {
                tiles[i].setSubtitle(slotApps[i] != null ? slotApps[i].label : getString(R.string.hold_to_choose));
            }
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
        if (e.info == null) {
            // A head-unit app without an app-drawer entry: opened by name, as the stock launcher does.
            if (!startSafely(Intent.makeMainActivity(e.component))) {
                Toast.makeText(this, getString(R.string.cannot_open, e.label), Toast.LENGTH_SHORT).show();
            }
            return;
        }
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
        // Until track info is allowed, the card says so and a tap goes straight to the switch.
        if (!media.hasAccess()) {
            openTrackInfoAccess();
            return;
        }
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
            if (clock != null) clock.hideSpeed();
            gpsKmh = -1;
            showVehicleSpeed();
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
        String value = kmh < 0 ? "–" : String.valueOf(Math.round(kmh));
        if (clock != null) clock.setSpeed(value, heading);
        gpsKmh = kmh;
        showVehicleSpeed();
    }

    // CANBus

    @Override public void onCanChanged() {
        // Doors first and even off screen, so the car already faces an open door when Home comes back.
        showDoors();
        if (!resumed) return;
        top.setOutside(can.outside);
        float tank = tank();
        String hint = carDataHint();
        if (fuelCard != null) fuelCard.set(can.fuel, tank, hint);
        if (tyreCard != null) tyreCard.set(can.tyres, can.tyresPsi, hint);
        if (vehicle != null) vehicle.setCar(can, tank, hint);
        showVehicleSpeed();
        if (shown == settingsPanel) refreshSettings();
    }

    /** Why fuel, battery, coolant and tyres are missing: Car Info's log not readable yet, or not heard from. */
    private String carDataHint() {
        if (can.state == CanBus.MISSING) return null;
        if (!can.log.allowed()) return getString(R.string.car_data_permission);
        return can.log.frames == 0 ? getString(R.string.car_data_waiting) : null;
    }

    /** Turns the car to the first open door (the driver's before the rest) and names every one open. */
    private void showDoors() {
        if (car == null || can.doors == doorsShown) return;
        doorsShown = can.doors;
        String part = null;
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < DOOR_BITS.length; i++) {
            if (can.doors < 0 || (can.doors & DOOR_BITS[i]) == 0) continue;
            if (part == null) part = DOOR_PARTS[i];
            labels.add(getString(DOOR_LABELS[i]));
        }
        if (part == null) {
            car.showClosed();
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String l : labels) text.append(text.length() == 0 ? "" : "  ·  ").append(l);
        car.showOpen(part, text.toString());
    }

    /** km/h: the car's own speed while it reports one; otherwise GPS, if that is on; otherwise -1. */
    private float currentSpeed() {
        return can.hasSpeed() ? can.speed : gpsKmh;
    }

    /** The speed on the gauge: the car's own while it reports one, otherwise GPS, if that is switched on. */
    private void showVehicleSpeed() {
        showGaugeSpeed();
    }

    private void showGaugeSpeed() {
        float kmh = currentSpeed();
        if (gauge == null || kmh == gaugeShown) return;
        gaugeShown = kmh;
        gauge.setSpeed(kmh);
    }

    // Settings

    private void refreshSettings() {
        boolean isDefault = isDefaultHome();
        rowHome.setValue(getString(isDefault ? R.string.home_is_default : R.string.home_make_default), !isDefault, 0);
        boolean carOn = prefs.getBoolean(PREF_CAR, true);
        rowCar.setValue(getString(carOn ? R.string.on : R.string.off), carOn, 0);
        rowUiStyle.setValue(getString(Chrome.style == Chrome.GLASS ? R.string.ui_glass : R.string.ui_classic), false, 0);
        rowShade.setValue(getResources().getStringArray(R.array.shade_names)[Chrome.shade], false, 0);
        rowFrost.setValue(getString(R.string.frost_value, Chrome.frost), false, 0);
        boolean hologram = prefs.getBoolean(PREF_HOLOGRAM, false);
        rowStyle.setValue(getString(hologram ? R.string.style_hologram : R.string.style_photo), false, 0);
        rowTop.setValue(getString(TOP_NAMES[choice(PREF_TOP_WIDGET, TOP_NAMES)]), false, 0);
        rowBottom.setValue(getString(BOTTOM_NAMES[choice(PREF_BOTTOM_WIDGET, BOTTOM_NAMES)]), false, 0);
        rowTank.setValue(getString(R.string.tank_value, litres(tank())), false, 0);
        rowAmbient.setValue(getResources().getStringArray(R.array.ambient_names)[accentIndex()], false, Chrome.accent);
        boolean speedOn = prefs.getBoolean(PREF_SPEED, false);
        rowSpeed.setValue(getString(speedOn ? R.string.on : R.string.off), speedOn, 0);
        boolean access = media.hasAccess();
        rowMedia.setValue(getString(access ? R.string.media_on : R.string.media_allow), !access, 0);
        boolean logs = can.log.allowed();
        rowCarData.setValue(getString(!logs ? R.string.car_data_adb : can.log.frames > 0 ? R.string.car_data_live
                : R.string.car_data_waiting), !logs, 0);
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

    /** One of a widget's choices, saved by its index; Home is rebuilt with it. */
    private void pickWidget(int titleRes, final String pref, final int[] names) {
        String[] items = new String[names.length];
        for (int i = 0; i < names.length; i++) items[i] = getString(names[i]);
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(items, choice(pref, names), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == choice(pref, names)) return;
                        prefs.edit().putInt(pref, which).commit();
                        recreate();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int choice(String pref, int[] names) {
        int i = prefs.getInt(pref, 0);
        return i >= 0 && i < names.length ? i : 0;
    }

    /** The colours, each named beside a dot of itself; applied at once, no rebuild needed. */
    private void pickAccent() {
        String[] names = getResources().getStringArray(R.array.ambient_names);
        CharSequence[] items = new CharSequence[Chrome.ACCENTS.length];
        for (int i = 0; i < items.length; i++) {
            SpannableString s = new SpannableString("\u25CF   " + names[i]);
            s.setSpan(new ForegroundColorSpan(Chrome.ACCENTS[i]), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = s;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_ambient)
                .setSingleChoiceItems(items, accentIndex(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        prefs.edit().putInt(PREF_ACCENT, which).apply();
                        Chrome.accent = Chrome.ACCENTS[which];
                        Chrome.invalidateTree(getWindow().getDecorView());
                        refreshSettings();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The glass from clear (0) to frosted (10) on a slider; Home is rebuilt with it, in the Glass look. */
    private void pickFrost() {
        int pad = Math.round(Chrome.unit(this) * 0.04f);
        final android.widget.TextView value = new android.widget.TextView(this);
        value.setText(getString(R.string.frost_value, Chrome.frost));
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Chrome.unit(this) * Chrome.TYPE_BODY);
        value.setGravity(android.view.Gravity.CENTER);
        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(10);
        bar.setProgress(Chrome.frost);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int progress, boolean fromUser) {
                value.setText(getString(R.string.frost_value, progress));
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}

            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        android.widget.TextView ends = new android.widget.TextView(this);
        ends.setText(getString(R.string.frost_ends));
        ends.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(value);
        box.addView(bar);
        box.addView(ends);
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_frost)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putInt(PREF_FROST, bar.getProgress()).putInt(PREF_LOOK, Chrome.GLASS).commit();
                        recreate();
                    }
                })
                .show();
    }

    private float tank() {
        float t = prefs.getFloat(PREF_TANK, DEFAULT_TANK);
        return t > 0 ? t : DEFAULT_TANK;
    }

    private static String litres(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format(Locale.getDefault(), "%.1f", v);
    }

    private void editTank() {
        final EditText field = new EditText(this);
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(litres(tank()));
        field.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        int pad = Math.round(Chrome.unit(this) * 0.04f);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_tank)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        float v;
                        try {
                            v = Float.parseFloat(field.getText().toString().trim().replace(',', '.'));
                        } catch (NumberFormatException e) {
                            return;
                        }
                        if (v < 5 || v > 200) return;
                        prefs.edit().putFloat(PREF_TANK, v).apply();
                        onCanChanged();
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
