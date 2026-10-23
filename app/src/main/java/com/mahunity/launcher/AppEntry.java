package com.mahunity.launcher;

import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.os.UserHandle;

/** One launchable activity, as Home lists it. */
final class AppEntry {
    /** Null for a head-unit app opened directly, without an app-drawer entry (Apps.HIDDEN). */
    final LauncherActivityInfo info;
    final ComponentName component;
    final UserHandle user;
    final String label;
    /** The label folded for keyword matching (Slots). */
    final String folded;
    /** The flattened component: the key for saved tile choices and for the icon cache. */
    final String key;

    AppEntry(LauncherActivityInfo info) {
        this.info = info;
        component = info.getComponentName();
        user = info.getUser();
        label = String.valueOf(info.getLabel());
        folded = Text.fold(label);
        key = component.flattenToString();
    }

    AppEntry(ComponentName component, UserHandle user, String label) {
        info = null;
        this.component = component;
        this.user = user;
        this.label = label;
        folded = Text.fold(label);
        key = component.flattenToString();
    }
}
