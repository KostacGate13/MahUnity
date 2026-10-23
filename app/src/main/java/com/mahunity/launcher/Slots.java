package com.mahunity.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;

import java.util.List;
import java.util.Locale;

/** The six home tiles: what each one is, the app it opens, and the guess made until you choose one. */
final class Slots {
    static final int NAV = 0, MUSIC = 1, RADIO = 2, PHONE = 3, LINK = 4, APPS = 5;
    static final int COUNT = 6;

    static final int[] ICONS = {
        R.drawable.ic_nav, R.drawable.ic_music, R.drawable.ic_radio,
        R.drawable.ic_phone, R.drawable.ic_link, R.drawable.ic_apps,
    };
    static final int[] TITLES = {
        R.string.slot_nav, R.string.slot_music, R.string.slot_radio,
        R.string.slot_phone, R.string.slot_link, R.string.slot_apps,
    };

    /**
     * Known packages, best first. Phone-style apps lead; then the firmware apps of the common head-unit
     * platforms (FYT com.syu.*, MTCD com.microntek.*, TopWay com.tw.*). Anything else is found by WORDS.
     */
    private static final String[][] PACKAGES = {
        {"com.waze", "com.google.android.apps.maps", "com.sygic.aura", "net.osmand.plus", "net.osmand",
            "com.here.app.maps", "com.tomtom.gplay.navapp"},
        {"com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app",
            "com.apple.android.music", "com.syu.music", "com.microntek.music", "com.tw.music"},
        {"com.syu.radio", "com.microntek.radio", "com.tw.radio", "com.android.fmradio"},
        {"com.syu.bt", "com.microntek.bluetooth", "com.tw.bt"},
        {"com.zjinnova.zlink", "cn.manstep.phonemirrorBox"},
    };

    /** Keywords tried on labels and package names, best first. Under four letters they must match a whole word. */
    private static final String[][] WORDS = {
        {"navi", "maps", "πλοηγ", "gps", "χαρτ"},
        {"music", "μουσικ", "mp3"},
        {"radio", "ραδιο", "fm", "dab", "tuner"},
        {"phone", "dialer", "τηλεφ", "κλησ", "bluetooth", "bt"},
        {"carplay", "android auto", "zlink", "autokit", "carlink", "autolink", "tlink", "phonelink", "mirror"},
    };

    /** A Bluetooth "music" app is not the phone. */
    private static final String[] NOT_PHONE = {"music", "μουσικ", "audio", "a2dp"};

    private final Context context;
    private final SharedPreferences prefs;

    Slots(Context context, SharedPreferences prefs) {
        this.context = context;
        this.prefs = prefs;
    }

    /** The app a tile opens: the one chosen with a long press if it is still installed, otherwise the best guess. */
    AppEntry resolve(int slot, List<AppEntry> apps) {
        String chosen = prefs.getString(key(slot), null);
        if (chosen != null) {
            for (AppEntry e : apps) {
                if (e.key.equals(chosen)) return e;
            }
        }
        return guess(slot, apps);
    }

    void choose(int slot, AppEntry entry) {
        prefs.edit().putString(key(slot), entry.key).apply();
    }

    void forget(int slot) {
        prefs.edit().remove(key(slot)).apply();
    }

    void forgetAll() {
        SharedPreferences.Editor edit = prefs.edit();
        for (int i = 0; i < COUNT; i++) edit.remove(key(i));
        edit.apply();
    }

    private AppEntry guess(int slot, List<AppEntry> apps) {
        if (slot >= PACKAGES.length) return null;
        for (String p : PACKAGES[slot]) {
            for (AppEntry e : apps) {
                if (e.component.getPackageName().equals(p)) return e;
            }
        }
        String category = slot == NAV ? Intent.CATEGORY_APP_MAPS : slot == MUSIC ? Intent.CATEGORY_APP_MUSIC : null;
        if (category != null) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            List<ResolveInfo> found = context.getPackageManager().queryIntentActivities(intent, 0);
            for (ResolveInfo r : found) {
                for (AppEntry e : apps) {
                    if (e.component.getPackageName().equals(r.activityInfo.packageName)) return e;
                }
            }
        }
        for (String word : WORDS[slot]) {
            for (AppEntry e : apps) {
                if (matches(e, word) && !(slot == PHONE && any(e, NOT_PHONE))) return e;
            }
        }
        return null;
    }

    private static boolean any(AppEntry e, String[] words) {
        for (String w : words) {
            if (matches(e, w)) return true;
        }
        return false;
    }

    private static boolean matches(AppEntry e, String word) {
        String pkg = e.component.getPackageName().toLowerCase(Locale.ROOT);
        if (word.length() >= 4) return e.folded.contains(word) || pkg.contains(word);
        for (String token : e.folded.split("[^\\p{L}\\p{N}]+")) {
            if (token.equals(word)) return true;
        }
        for (String token : pkg.split("\\.")) {
            if (token.equals(word)) return true;
        }
        return false;
    }

    private static String key(int slot) {
        return "slot_" + slot;
    }
}
