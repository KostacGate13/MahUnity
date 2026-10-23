package com.mahunity.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** GPS speed and heading for the clock panel, read only while Home is on screen and the setting is on. */
final class SpeedWatcher implements LocationListener {
    interface Listener {
        /** kmh below zero: no fix. bearing below zero: unknown, or standing still. */
        void onSpeed(float kmh, float bearing);
    }

    private static final long STALE_MS = 5000;

    private final Context context;
    private final LocationManager locations;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final Runnable stale = new Runnable() {
        @Override public void run() {
            listener.onSpeed(-1, -1);
        }
    };

    SpeedWatcher(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        locations = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    boolean allowed() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (running || locations == null || !allowed()) return;
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            running = true;
        } catch (RuntimeException e) {
            // No GPS provider on this unit, or permission withdrawn.
        }
        listener.onSpeed(-1, -1);
    }

    void stop() {
        handler.removeCallbacks(stale);
        if (!running) return;
        running = false;
        locations.removeUpdates(this);
    }

    @Override public void onLocationChanged(Location location) {
        handler.removeCallbacks(stale);
        handler.postDelayed(stale, STALE_MS);
        float kmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        float bearing = location.hasBearing() && kmh >= 5f ? location.getBearing() : -1f;
        listener.onSpeed(kmh, bearing);
    }

    // Abstract before Android 11, so they stay even though nothing here needs them.
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override public void onProviderEnabled(String provider) {}

    @Override public void onProviderDisabled(String provider) {
        listener.onSpeed(-1, -1);
    }
}
