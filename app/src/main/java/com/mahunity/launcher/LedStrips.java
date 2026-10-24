package com.mahunity.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * The two LED strips over Bluetooth LE: the one on the dashboard and the ones in the footwells (several strips on
 * one controller count as one). Each is a device chosen once from a scan and kept connected while Home lives,
 * reconnecting when it drops. What each should show is set at any rate; only the newest state goes out, one
 * command at a time, so a fast-changing colour never queues up.
 *
 * A controller takes one connection at a time: while its own app (ZENGGE, Lotus Lantern...) holds it, on the
 * unit or on a phone, Cockpit cannot reach it.
 */
@SuppressLint("MissingPermission")
final class LedStrips {
    interface Listener {
        /** A strip connected, dropped or was found, on the main thread. */
        void onLedsChanged();
    }

    static final int TOP = 0, BOTTOM = 1;
    static final int NONE = 0, CONNECTING = 1, READY = 2;
    private static final long RETRY_MS = 6_000, WRITE_TIMEOUT_MS = 400, SCAN_MS = 12_000;

    /** A device seen while scanning. */
    static final class Found {
        final String address, name;
        int rssi;

        Found(String address, String name, int rssi) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
        }
    }

    final class Strip {
        final int role;
        String address, name;
        int state = NONE;
        LedProtocol protocol;
        /** Connected once and spoke no protocol known here; its services, for the status screen. */
        boolean unsupported;
        String services;
        private BluetoothGatt gatt;
        private BluetoothGattCharacteristic channel;
        private boolean busy;
        private long busySince;
        // Wanted and sent: on or off, and the colour.
        private boolean on;
        private int color;
        private Boolean sentOn;
        private int sentColor = -1;
        private boolean hello;

        Strip(int role) {
            this.role = role;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    final Strip[] strips = {new Strip(TOP), new Strip(BOTTOM)};
    final List<Found> found = new ArrayList<>();
    private boolean scanning, closed;
    private Runnable onFound;

    private final Runnable retry = new Runnable() {
        @Override public void run() {
            for (Strip s : strips) connect(s);
            main.postDelayed(this, RETRY_MS);
        }
    };

    private final Runnable unstick = new Runnable() {
        @Override public void run() {
            // A write never confirmed: carry on rather than wait for ever.
            long now = SystemClock.elapsedRealtime();
            for (Strip s : strips) {
                if (s.busy && now - s.busySince > WRITE_TIMEOUT_MS) {
                    s.busy = false;
                    pump(s);
                }
            }
        }
    };

    LedStrips(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        BluetoothManager m = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = m == null ? null : m.getAdapter();
    }

    /** The permissions scanning and connecting take on this Android version. */
    static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    }

    boolean allowed() {
        for (String p : permissions()) {
            if (context.checkPermission(p, Process.myPid(), Process.myUid()) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** Whether the unit has Bluetooth LE at all, and it is on. */
    boolean available() {
        return adapter != null && adapter.isEnabled();
    }

    boolean hasAdapter() {
        return adapter != null;
    }

    /** Remembered devices; connects to them from now on. */
    void use(int role, String address, String name) {
        Strip s = strips[role];
        if (address != null && address.equals(s.address)) return;
        disconnect(s);
        s.address = address;
        s.name = name;
        s.unsupported = false;
        s.services = null;
        connect(s);
        listener.onLedsChanged();
    }

    void start() {
        closed = false;
        main.removeCallbacks(retry);
        main.post(retry);
    }

    void close() {
        closed = true;
        main.removeCallbacks(retry);
        stopScan();
        for (Strip s : strips) disconnect(s);
    }

    /** What a strip should show; sent at once if it differs from what it shows. */
    void set(int role, boolean on, int rgb) {
        Strip s = strips[role];
        s.on = on;
        s.color = rgb & 0xFFFFFF;
        pump(s);
    }

    private void connect(Strip s) {
        if (closed || s.address == null || s.state != NONE || s.unsupported || !available() || !allowed()) return;
        BluetoothDevice d;
        try {
            d = adapter.getRemoteDevice(s.address);
        } catch (IllegalArgumentException e) {
            return;
        }
        s.state = CONNECTING;
        s.gatt = Build.VERSION.SDK_INT >= 23 ? d.connectGatt(context, false, callback(s), BluetoothDevice.TRANSPORT_LE)
                : d.connectGatt(context, false, callback(s));
        if (s.gatt == null) s.state = NONE;
    }

    private void disconnect(Strip s) {
        if (s.gatt != null) {
            try {
                s.gatt.disconnect();
                s.gatt.close();
            } catch (SecurityException ignored) {
                // Permission withdrawn: the system drops the link anyway.
            }
        }
        s.gatt = null;
        s.channel = null;
        s.protocol = null;
        s.state = NONE;
        s.busy = false;
        s.sentOn = null;
        s.sentColor = -1;
        s.hello = false;
    }

    private BluetoothGattCallback callback(final Strip s) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(final BluetoothGatt gatt, int status, final int newState) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (gatt != s.gatt) return;
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.discoverServices();
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            disconnect(s);
                            listener.onLedsChanged();
                        }
                    }
                });
            }

            @Override public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (gatt != s.gatt) return;
                        LedProtocol p = LedProtocol.of(gatt);
                        if (p == null) {
                            // Not a controller we know: note its services and let go, so its own app can have it.
                            StringBuilder list = new StringBuilder();
                            for (android.bluetooth.BluetoothGattService service : gatt.getServices()) {
                                if (list.length() > 0) list.append('\n');
                                list.append(service.getUuid());
                                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                                    list.append("\n  ").append(c.getUuid()).append(" p").append(c.getProperties());
                                }
                            }
                            disconnect(s);
                            s.unsupported = true;
                            s.services = list.toString();
                            listener.onLedsChanged();
                            return;
                        }
                        s.protocol = p;
                        s.channel = p.characteristic(gatt);
                        s.state = READY;
                        listener.onLedsChanged();
                        pump(s);
                    }
                });
            }

            @Override public void onCharacteristicWrite(final BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (gatt != s.gatt) return;
                        s.busy = false;
                        pump(s);
                    }
                });
            }
        };
    }

    /** Sends what differs between wanted and shown, one command at a time: hello, power, then the colour. */
    private void pump(Strip s) {
        if (s.state != READY || s.busy || s.channel == null) return;
        byte[] command = null;
        if (!s.hello) {
            s.hello = true;
            command = s.protocol.hello();
        }
        if (command == null && (s.sentOn == null || s.sentOn != s.on)) {
            command = s.protocol.power(s.on);
            s.sentOn = s.on;
            if (s.on) s.sentColor = -1;
        }
        if (command == null && s.on && s.color != s.sentColor) {
            command = s.protocol.color(s.color);
            s.sentColor = s.color;
        }
        if (command == null) return;
        write(s, command);
    }

    @SuppressWarnings("deprecation")
    private void write(Strip s, byte[] command) {
        BluetoothGattCharacteristic c = s.channel;
        boolean quick = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        int type = quick ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        boolean sent;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                sent = s.gatt.writeCharacteristic(c, command, type) == BluetoothGatt.GATT_SUCCESS;
            } else {
                c.setWriteType(type);
                c.setValue(command);
                sent = s.gatt.writeCharacteristic(c);
            }
        } catch (SecurityException e) {
            sent = false;
        }
        if (!sent) {
            // The stack was busy: try again shortly with whatever is wanted by then.
            s.sentOn = null;
            s.sentColor = -1;
            s.busy = true;
        } else {
            s.busy = true;
        }
        s.busySince = SystemClock.elapsedRealtime();
        main.removeCallbacks(unstick);
        main.postDelayed(unstick, WRITE_TIMEOUT_MS + 20);
    }

    /** Scans for a while, listing every named device, LED controllers first; onFound runs as they come. */
    void scan(Runnable onFound) {
        this.onFound = onFound;
        found.clear();
        if (!available() || !allowed()) return;
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        stopScan();
        scanning = true;
        try {
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        } catch (SecurityException e) {
            scanning = false;
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                stopScan();
            }
        }, SCAN_MS);
    }

    void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (SecurityException | IllegalStateException ignored) {
            // Bluetooth went off meanwhile.
        }
    }

    boolean scanning() {
        return scanning;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, final ScanResult r) {
            main.post(new Runnable() {
                @Override public void run() {
                    String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                    if (name == null) {
                        try {
                            name = r.getDevice().getName();
                        } catch (SecurityException ignored) {
                            // No name then.
                        }
                    }
                    if (name == null || name.trim().isEmpty()) return;
                    String address = r.getDevice().getAddress();
                    for (Found f : found) {
                        if (f.address.equals(address)) {
                            f.rssi = r.getRssi();
                            return;
                        }
                    }
                    Found f = new Found(address, name.trim(), r.getRssi());
                    // LED controllers first, the rest after.
                    int at = found.size();
                    if (LedProtocol.looksLikeLed(f.name)) {
                        at = 0;
                        while (at < found.size() && LedProtocol.looksLikeLed(found.get(at).name)) at++;
                    }
                    found.add(at, f);
                    if (onFound != null) onFound.run();
                }
            });
        }
    };
}
