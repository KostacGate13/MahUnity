#!/bin/sh
# Installs Cockpit over ADB (USB cable or wireless debugging) and makes it the Home screen.
# Usage: tools/install.sh [path/to/Cockpit.apk]
set -e
APK=${1:-Cockpit.apk}
PKG=com.mahunity.launcher

adb install -r "$APK"
adb shell cmd package set-home-activity "$PKG/.HomeActivity" \
  || echo "Could not set Home from ADB: press Home on the unit and pick Cockpit, Always."
# Optional extras; each one is harmless if the firmware refuses it.
adb shell cmd notification allow_listener "$PKG/$PKG.MediaListener" || true   # track titles on the media card
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION || true       # GPS speed
# Fuel, battery, coolant and tyres from NWD Car Info's log; canapp_debug keeps Car Info logging from boot.
adb shell pm grant "$PKG" android.permission.READ_LOGS || true
adb shell settings put system canapp_debug 1 || true
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
