package com.mahunity.launcher;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.graphics.Color;

import java.util.Locale;
import java.util.UUID;

/**
 * The command sets of the cheap Bluetooth LED controllers sold for cars, as their apps send them: ZENGGE's
 * (LEDnetWF devices), ELK-BLEDOM's (Lotus Lantern, Duoco Strip and many footwell kits), and the Triones and Magic
 * Home BLE ones (HappyLighting). A connected device is told apart by the characteristic it can be written on.
 * Each only switches on and off and takes a colour: brightness is in the colour.
 */
abstract class LedProtocol {
    final String name;
    final UUID service, write;

    private LedProtocol(String name, String service, String write) {
        this.name = name;
        this.service = uuid(service);
        this.write = uuid(write);
    }

    abstract byte[] power(boolean on);

    /** The colour, 0xRRGGBB, already dimmed to the brightness wanted. */
    abstract byte[] color(int rgb);

    /** Sent once after connecting, before anything else; null if none. */
    byte[] hello() {
        return null;
    }

    private static UUID uuid(String shortId) {
        return UUID.fromString("0000" + shortId + "-0000-1000-8000-00805f9b34fb");
    }

    /** The protocol a connected device speaks, from its services; null if none is known. */
    static LedProtocol of(BluetoothGatt gatt) {
        LedProtocol[] all = {new Zengge(), new Elk(), new Triones("Triones", "ffd5", "ffd9"),
            new Triones("Magic Home", "ffe5", "ffe9")};
        for (LedProtocol p : all) {
            if (p.characteristic(gatt) != null) return p;
        }
        return null;
    }

    BluetoothGattCharacteristic characteristic(BluetoothGatt gatt) {
        BluetoothGattService s = gatt.getService(service);
        return s == null ? null : s.getCharacteristic(write);
    }

    /** Whether an advertised name looks like one of these controllers, to list those first when scanning. */
    static boolean looksLikeLed(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.ROOT);
        String[] prefixes = {"LEDNET", "IOTBT", "ZENGGE", "ELK", "MELK", "BLEDOM", "TRIONES", "LEDBLUE", "LEDBLE",
            "DREAM", "QHM", "BJ_LED", "LED", "KS03", "MOHUAN", "SP1", "SP6"};
        for (String p : prefixes) if (n.startsWith(p)) return true;
        return n.contains("LED");
    }

    /**
     * ZENGGE's LEDnetWF controllers: every command in an envelope (a counter, the length twice) on FF01; the
     * colour as hue (halved), saturation and value in percent, then a sum of the command's bytes.
     */
    static final class Zengge extends LedProtocol {
        private int sequence;

        Zengge() {
            super("ZENGGE", "ffff", "ff01");
        }

        @Override byte[] hello() {
            return hex("00 01 80 00 00 04 05 0a 81 8a 8b 96");
        }

        @Override byte[] power(boolean on) {
            byte[] p = hex("00 00 80 00 00 0d 0e 0b 3b 23 00 00 00 00 00 00 00 32 00 00 00");
            p[9] = (byte) (on ? 0x23 : 0x24);
            return seal(p);
        }

        @Override byte[] color(int rgb) {
            float[] hsv = new float[3];
            Color.RGBToHSV(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, hsv);
            byte[] p = hex("00 00 80 00 00 0d 0e 0b 3b a1 00 64 64 00 00 00 00 00 00 00 00");
            p[10] = (byte) Math.round(hsv[0] / 2);
            p[11] = (byte) Math.round(hsv[1] * 100);
            p[12] = (byte) Math.round(hsv[2] * 100);
            return seal(p);
        }

        /** The counter, and the sum of the command (bytes 8 to 19) in its last byte. */
        private byte[] seal(byte[] p) {
            sequence = (sequence + 1) & 0xFF;
            p[1] = (byte) sequence;
            int sum = 0;
            for (int i = 8; i < p.length - 1; i++) sum += p[i] & 0xFF;
            p[p.length - 1] = (byte) sum;
            return p;
        }
    }

    /** ELK-BLEDOM and its many relabellings: nine-byte commands framed by 7E and EF, on FFF3. */
    static final class Elk extends LedProtocol {
        Elk() {
            super("ELK-BLEDOM", "fff0", "fff3");
        }

        @Override byte[] power(boolean on) {
            return on ? hex("7e 00 04 f0 00 01 ff 00 ef") : hex("7e 00 04 00 00 00 ff 00 ef");
        }

        @Override byte[] color(int rgb) {
            return new byte[] {0x7e, 0x00, 0x05, 0x03, (byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb, 0x00, (byte) 0xef};
        }
    }

    /** Triones (HappyLighting) and Magic Home BLE: CC 23 33 on, CC 24 33 off, 56 R G B 00 F0 AA for a colour. */
    static final class Triones extends LedProtocol {
        Triones(String name, String service, String write) {
            super(name, service, write);
        }

        @Override byte[] power(boolean on) {
            return new byte[] {(byte) 0xcc, (byte) (on ? 0x23 : 0x24), 0x33};
        }

        @Override byte[] color(int rgb) {
            return new byte[] {0x56, (byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb, 0x00, (byte) 0xf0, (byte) 0xaa};
        }
    }

    static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02X", x));
        return s.toString();
    }
}
