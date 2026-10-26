package com.mahunity.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
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
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The two LED strips over Bluetooth LE: the one on the dashboard and the ones in the footwells (several strips on
 * one controller count as one). Each is a device chosen once from a scan and kept connected while Home lives,
 * reconnecting when it drops. What each should show is set at any rate; only the newest state goes out, one
 * command at a time, so a fast-changing colour never queues up. A controller that can say how many LEDs its
 * strip has is asked once connected; on an addressable strip each LED can then have its own colour.
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
    private static final long RETRY_MS = 6_000, WRITE_TIMEOUT_MS = 400, SCAN_MS = 12_000, SETUP_MS = 3_000,
            ASK_MS = 2_500;
    /** The most a write can carry when the controller takes long writes. */
    private static final int LONG_WRITE = 512;
    private static final UUID CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** A device seen while scanning; a ZENGGE one says its model (-1 otherwise). */
    static final class Found {
        final String address, name;
        int rssi, product = -1;

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
        /** The LEDs its controller says the strip has (per segment); 0 until it says. */
        int pixels;
        /** The model its advertisement gave when it was chosen, -1 if none; how to light LEDs one by one. */
        int product = -1, method = LedProtocol.AUTO;
        /** What the controller sent, the latest of each kind (by its first byte), in hex, for the status screen. */
        final Map<Integer, String> answers = new LinkedHashMap<>();
        /** The MTU agreed with the controller. */
        int mtu = 23;
        private BluetoothGatt gatt;
        private BluetoothGattCharacteristic channel;
        private boolean busy;
        private long busySince;
        // Wanted and sent: on or off, the colour, and a colour for each LED (null: the colour throughout).
        private boolean on;
        private int color;
        private int[] bar;
        private Boolean sentOn;
        private int sentColor = -1;
        private int[] sentBar;
        private boolean hello, listening;
        private int asks;
        private long askAt;

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

    /** Remembered devices, with the model each said when chosen (-1 unknown); connects to them from now on. */
    void use(int role, String address, String name, int product) {
        Strip s = strips[role];
        if (product >= 0) s.product = product;
        if (address != null && address.equals(s.address)) return;
        disconnect(s);
        s.address = address;
        s.name = name;
        s.product = product;
        s.unsupported = false;
        s.services = null;
        s.pixels = 0;
        s.answers.clear();
        connect(s);
        listener.onLedsChanged();
    }

    /** How a strip lights its LEDs one by one, LedProtocol.AUTO, E1 or ZONES. */
    void method(int role, int method) {
        Strip s = strips[role];
        s.method = method;
        if (s.protocol != null) s.protocol.use(method, s.product);
        s.sentBar = null;
        pump(s);
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
        set(role, on, rgb, null);
    }

    /**
     * The same with a colour for each LED, left to right, as many as the strip has; the strip takes rgb
     * throughout instead where its controller cannot light them one by one, or the list does not fit a write.
     */
    void set(int role, boolean on, int rgb, int[] bar) {
        Strip s = strips[role];
        s.on = on;
        s.color = rgb & 0xFFFFFF;
        s.bar = bar;
        pump(s);
    }

    /** The LEDs a strip's controller says it has, 0 if it has not said. */
    int pixels(int role) {
        return strips[role].pixels;
    }

    /** Whether a strip lights its LEDs one by one: connected, addressable, and a bar of that length fits a write. */
    boolean lightsEach(int role, int count) {
        Strip s = strips[role];
        return s.state == READY && s.protocol.addressable() && count > 0 && s.protocol.pixelBytes(count) <= room(s);
    }

    /** The most one write to a strip can carry now. */
    int room(int role) {
        return strips[role].channel == null ? 0 : room(strips[role]);
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
        s.sentBar = null;
        s.hello = false;
        s.listening = false;
        s.asks = 0;
        s.mtu = 23;
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
                        p.use(s.method, s.product);
                        if (p.answers() == null) {
                            ready(s);
                            return;
                        }
                        // It answers: a larger MTU first (its answers, and long bars), then its notifications on.
                        main.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (gatt == s.gatt && s.state == CONNECTING) ready(s);
                            }
                        }, SETUP_MS);
                        if (!gatt.requestMtu(517)) listen(s);
                    }
                });
            }

            @Override public void onMtuChanged(final BluetoothGatt gatt, final int mtu, final int status) {
                main.post(new Runnable() {
                    @Override public void run() {
                        // Android 14 on agrees an MTU by itself too, maybe before the services are known.
                        if (gatt != s.gatt) return;
                        if (status == BluetoothGatt.GATT_SUCCESS) s.mtu = mtu;
                        if (s.protocol != null && s.state == CONNECTING) listen(s);
                    }
                });
            }

            @Override public void onDescriptorWrite(final BluetoothGatt gatt, BluetoothGattDescriptor d, int status) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (gatt == s.gatt && s.state == CONNECTING && s.protocol != null) ready(s);
                    }
                });
            }

            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] value) {
                heard(gatt, s, value);
            }

            @SuppressWarnings("deprecation")
            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c) {
                // Android 13 on calls the one above instead.
                if (Build.VERSION.SDK_INT < 33) heard(gatt, s, c.getValue());
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

    /** What one write can carry at the strip's MTU: more as a long write, where the characteristic takes those. */
    private static int room(Strip s) {
        return Math.max(s.mtu - 3, writes(s, BluetoothGattCharacteristic.PROPERTY_WRITE) ? LONG_WRITE : 0);
    }

    private static boolean writes(Strip s, int property) {
        return s.channel != null && (s.channel.getProperties() & property) != 0;
    }

    /** Turns on the controller's answers; ready either way once that is done or fails. */
    @SuppressWarnings("deprecation")
    private void listen(Strip s) {
        if (s.listening) return;
        s.listening = true;
        BluetoothGattCharacteristic c = s.gatt.getService(s.protocol.service) == null ? null
                : s.gatt.getService(s.protocol.service).getCharacteristic(s.protocol.answers());
        BluetoothGattDescriptor d = c == null ? null : c.getDescriptor(CONFIG);
        boolean asked = false;
        try {
            if (d != null && s.gatt.setCharacteristicNotification(c, true)) {
                if (Build.VERSION.SDK_INT >= 33) {
                    asked = s.gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS;
                } else {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    asked = s.gatt.writeDescriptor(d);
                }
            }
        } catch (SecurityException ignored) {
            // Then without its answers.
        }
        if (!asked) ready(s);
    }

    private void ready(Strip s) {
        s.state = READY;
        listener.onLedsChanged();
        pump(s);
    }

    /** Something from the controller: the LED count, if that is what it says. */
    private void heard(final BluetoothGatt gatt, final Strip s, final byte[] value) {
        if (value == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (gatt != s.gatt || s.protocol == null) return;
                // Kept by kind: the first byte after the envelope where there is one.
                int kind = value.length > 8 ? value[8] & 0xFF : value.length > 0 ? value[0] & 0xFF : 0;
                s.answers.remove(kind);
                s.answers.put(kind, LedProtocol.hex(value.length > 64 ? Arrays.copyOf(value, 64) : value));
                while (s.answers.size() > 4) s.answers.remove(s.answers.keySet().iterator().next());
                int n = s.protocol.pixels(value);
                if (n > 0 && n != s.pixels) {
                    s.pixels = n;
                    listener.onLedsChanged();
                }
            }
        });
    }

    /**
     * Sends what differs between wanted and shown, one command at a time: hello, power, the question of the LED
     * count, then the colours.
     */
    private void pump(final Strip s) {
        if (s.state != READY || s.busy || s.channel == null) return;
        byte[] command = null;
        if (!s.hello) {
            s.hello = true;
            command = s.protocol.hello();
        }
        if (command == null && (s.sentOn == null || s.sentOn != s.on)) {
            command = s.protocol.power(s.on);
            s.sentOn = s.on;
            if (s.on) {
                s.sentColor = -1;
                s.sentBar = null;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (command == null && s.pixels == 0 && s.asks < 2 && now >= s.askAt) {
            command = s.protocol.askPixels(s.asks);
            if (command != null) {
                s.asks++;
                s.askAt = now + ASK_MS;
                // No answer by then: ask the other way.
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        pump(s);
                    }
                }, ASK_MS + 20);
            }
        }
        if (command == null && s.on) {
            if (s.bar != null && lightsEach(s.role, s.bar.length)) {
                if (!Arrays.equals(s.bar, s.sentBar)) {
                    command = s.protocol.pixels(s.bar);
                    s.sentBar = s.bar;
                    s.sentColor = -1;
                }
            } else if (s.color != s.sentColor) {
                command = s.protocol.color(s.color);
                s.sentColor = s.color;
                s.sentBar = null;
            }
        }
        if (command == null) return;
        write(s, command);
    }

    @SuppressWarnings("deprecation")
    private void write(Strip s, byte[] command) {
        BluetoothGattCharacteristic c = s.channel;
        // Without a response where it can, unless it is too long for one packet: then a long write.
        boolean quick = writes(s, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                && (command.length <= s.mtu - 3 || !writes(s, BluetoothGattCharacteristic.PROPERTY_WRITE));
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
            s.sentBar = null;
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

    /** A ZENGGE controller's model, from its advertisement: maker data under a company number from 0x5A00 to 0x5AFF, the model at 8 and 9. */
    private static void model(Found f, ScanResult r) {
        SparseArray<byte[]> maker = r.getScanRecord() == null ? null : r.getScanRecord().getManufacturerSpecificData();
        if (maker == null) return;
        for (int i = 0; i < maker.size(); i++) {
            int company = maker.keyAt(i);
            byte[] b = maker.valueAt(i);
            if (company >= 0x5A00 && company <= 0x5AFF && b != null && b.length >= 11) {
                f.product = (b[8] & 0xFF) << 8 | b[9] & 0xFF;
                return;
            }
        }
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
                    model(f, r);
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
