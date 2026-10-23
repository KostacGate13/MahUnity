package com.mahunity.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;

/**
 * Car data from the head unit's CANBus decoder, read the way the unit's own apps read it: NWD's CAN service
 * (com.nwd.can.setting) over Binder, and the outside temperature it broadcasts for the status bar. Read only:
 * nothing is ever sent towards the car. On units without that service nothing happens.
 *
 * The Binder calls mirror NWD's ICanRemote4OuterFeature AIDL; packets start with 0x6E, then a data type,
 * a length, and the payload from byte 3 (see the decoders below).
 */
final class CanBus {
    interface Listener {
        void onCanChanged();
    }

    /** Connection state, for the Vehicle tab. */
    static final int MISSING = 0, CONNECTING = 1, CONNECTED = 2;

    private static final ComponentName SERVICE =
            new ComponentName("com.nwd.can.setting", "com.nwd.can.service.CanService");
    private static final String ACTION_SERVICE = "com.nwd.can.service.impl.carcase.ACTION_CAN_SERVICE";
    private static final String FEATURE = "com.nwd.can.sdk.outer.adil.ICanRemote4OuterFeature";
    private static final String CALLBACK = "com.nwd.can.sdk.outer.adil.ICanRemoteModelCallback";
    private static final String ACTION_STATUS_TEXT = "com.nwd.android.ACTION_STATUSBAR_STRING";
    private static final String EXTRA_STATUS_TEXT = "extra_string";

    // ICanRemote4OuterFeature transaction codes.
    private static final int INIT_SDK = 2;
    private static final int ADD_AIR = 9, REMOVE_AIR = 10, ADD_DOOR = 11, REMOVE_DOOR = 12;
    private static final int ADD_RADAR = 15, REMOVE_RADAR = 16, ADD_CAR_INFO = 17, REMOVE_CAR_INFO = 18;
    private static final int[] ADD = {ADD_AIR, ADD_DOOR, ADD_RADAR, ADD_CAR_INFO};
    private static final int[] REMOVE = {REMOVE_AIR, REMOVE_DOOR, REMOVE_RADAR, REMOVE_CAR_INFO};

    // Packet data types.
    private static final byte HEAD = 0x6E;
    private static final int AIR = 1, CAR_INFO = 2, DOOR = 3, RADAR = 4;
    private static final int OFFSET = 3;

    /** Door bits, as the decoder sends them. */
    static final int FRONT_LEFT = 1 << 7, FRONT_RIGHT = 1 << 6, REAR_LEFT = 1 << 5, REAR_RIGHT = 1 << 4,
            TRUNK = 1 << 3, HOOD = 1 << 2;

    // The latest values; -1 or null while unknown.
    int state = MISSING;
    int packets;
    int speed = -1, rpm = -1;
    long speedAt;
    int doors = -1;
    /** Parking sensor levels, rear left to right then front left to right; 0 is clear. Null until reported. */
    int[] radar;
    boolean acOn;
    String acLeft, acRight;
    int fan = -1;
    String outside;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private IBinder remote;
    private boolean bound, receiving;

    /** NWD's callback interface, implemented by hand: one method, a byte array per packet. */
    private final Binder callback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(CALLBACK);
                return true;
            }
            if (code != 1) return false;
            data.enforceInterface(CALLBACK);
            final byte[] packet = data.createByteArray();
            if (reply != null) reply.writeNoException();
            main.post(new Runnable() {
                @Override public void run() {
                    decode(packet);
                }
            });
            return true;
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = binder;
            state = CONNECTED;
            // The service's own key for third-party apps; it reads every app's data the same way regardless.
            call(INIT_SDK, "nwdthirdapp", "d39df3d908cf7136227987e37d5b2c7d", (byte) 0, "cockpit");
            for (int code : ADD) call(code);
            listener.onCanChanged();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            state = CONNECTING;
            listener.onCanChanged();
        }
    };

    private final BroadcastReceiver temperature = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String s = intent.getStringExtra(EXTRA_STATUS_TEXT);
            // The status-bar text is the outside temperature on this firmware, like "18℃"; anything else is skipped.
            if (s == null || !s.trim().matches("-?\\d+(\\.\\d+)?\\s*[℃℉]")) return;
            outside = s.trim().replace("℃", "°C").replace("℉", "°F");
            listener.onCanChanged();
        }
    };

    CanBus(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Listens for the outside temperature for as long as Home lives: it is only sent when it changes. */
    void listen() {
        if (receiving) return;
        IntentFilter filter = new IntentFilter(ACTION_STATUS_TEXT);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(temperature, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(temperature, filter);
        receiving = true;
    }

    void close() {
        stop();
        if (receiving) context.unregisterReceiver(temperature);
        receiving = false;
    }

    /** Connects while Home is on screen. */
    void start() {
        if (bound) return;
        try {
            bound = context.bindService(new Intent(ACTION_SERVICE).setComponent(SERVICE), connection,
                    Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        state = bound ? (remote != null ? CONNECTED : CONNECTING) : MISSING;
        listener.onCanChanged();
    }

    void stop() {
        if (!bound) return;
        if (remote != null) {
            for (int code : REMOVE) call(code);
        }
        context.unbindService(connection);
        bound = false;
        remote = null;
    }

    /** Speed from the car, if it reported one in the last few seconds. */
    boolean hasSpeed() {
        return speed >= 0 && SystemClock.elapsedRealtime() - speedAt < 3000;
    }

    /** A callback registration (no arguments but our callback) or initSdkCfg (the arguments given). */
    private void call(int code, Object... args) {
        IBinder r = remote;
        if (r == null) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(FEATURE);
            if (args.length == 0) {
                data.writeStrongBinder(callback);
            } else {
                data.writeString((String) args[0]);
                data.writeString((String) args[1]);
                data.writeByte((Byte) args[2]);
                data.writeString((String) args[3]);
            }
            r.transact(code, data, reply, 0);
            // NWD's service answers false from onTransact even when it handled the call; its reply is still read.
            reply.readException();
        } catch (Exception e) {
            // The service went away or rejected the call; the next start() tries again.
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void decode(byte[] p) {
        if (p == null || p.length < OFFSET + 1 || p[0] != HEAD) return;
        packets++;
        switch (p[1]) {
            case AIR:
                if (p.length < OFFSET + 9) return;
                acOn = (p[OFFSET] & 0x80) != 0;
                fan = p[OFFSET + 2] & 0x0F;
                boolean fahrenheit = ((p[OFFSET + 3] >> 2) & 1) == 1;
                acLeft = temperature(p[OFFSET + 7], fahrenheit);
                acRight = temperature(p[OFFSET + 8], fahrenheit);
                break;
            case CAR_INFO:
                if (p.length < OFFSET + 4) return;
                int s = u16(p, OFFSET);
                speed = s == 0xFFFF ? -1 : s;
                speedAt = SystemClock.elapsedRealtime();
                int r = u16(p, OFFSET + 2);
                rpm = r == 0xFFFF ? -1 : r;
                break;
            case DOOR:
                doors = p[OFFSET] & (FRONT_LEFT | FRONT_RIGHT | REAR_LEFT | REAR_RIGHT | TRUNK | HOOD);
                break;
            case RADAR:
                if (p.length < OFFSET + 8) return;
                int[] levels = new int[8];
                for (int i = 0; i < 8; i++) levels[i] = Math.max(0, p[OFFSET + i]);
                radar = levels;
                break;
            default:
                return;
        }
        listener.onCanChanged();
    }

    private static int u16(byte[] p, int at) {
        return ((p[at] & 0xFF) << 8) | (p[at + 1] & 0xFF);
    }

    /** NWD's encoding: half degrees, 0 for LO, 0xFF for HI, 0xFE for no reading. */
    private static String temperature(byte v, boolean fahrenheit) {
        if (v == 0) return "LO";
        if (v == (byte) 0xFF) return "HI";
        if (v == (byte) 0xFE) return null;
        float t = (v & 0xFF) / 2f;
        String n = t == Math.floor(t) ? String.valueOf((int) t) : String.valueOf(t);
        return n + (fahrenheit ? "°F" : "°C");
    }
}
