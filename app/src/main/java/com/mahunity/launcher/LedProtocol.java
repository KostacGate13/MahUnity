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

    /** The characteristic it answers on, for controllers that report their strip; null if none. */
    UUID answers() {
        return null;
    }

    /**
     * Asks the controller how many LEDs its strip has, the answer coming on answers(); null if it cannot say.
     * Variants differ between models, so a second try may ask differently.
     */
    byte[] askPixels(int attempt) {
        return null;
    }

    /** The LED count in an answer from the controller, or -1 if the answer is not that. */
    int pixels(byte[] answer) {
        return -1;
    }

    /** Whether it can light each LED of an addressable ("symphony") strip in its own colour. */
    boolean addressable() {
        return false;
    }

    /** A colour for each LED along the strip, left to right, exactly as many as it has; null if not addressable(). */
    byte[] pixels(int[] rgb) {
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
     * ZENGGE's LEDnetWF controllers: every command in an envelope (a counter, its length, a type: 0B a command,
     * 0A a question) on FF01, answers on FF02. Inside, the same commands as ZENGGE's Wi-Fi "addressable A3"
     * controllers (see flux_led): power 3B 23/24, a colour as 3B A1 and hue (halved), saturation and value in
     * percent; for a symphony strip, 59 and a colour for every LED; 63 asks for the strip's settings. Each ends
     * in the sum of its bytes. (The envelope as in lednetwf_ble, the commands as in flux_led.)
     */
    static final class Zengge extends LedProtocol {
        private int sequence;

        Zengge() {
            super("ZENGGE", "ffff", "ff01");
        }

        @Override byte[] hello() {
            return hex("00 01 80 00 00 04 05 0a 81 8a 8b 96");
        }

        @Override UUID answers() {
            return LedProtocol.uuid("ff02");
        }

        @Override byte[] power(boolean on) {
            byte[] c = hex("3b 23 00 00 00 00 00 00 00 32 00 00");
            if (!on) c[1] = 0x24;
            return wrap(c, 0x0b);
        }

        @Override byte[] color(int rgb) {
            float[] hsv = new float[3];
            Color.RGBToHSV(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, hsv);
            byte[] c = hex("3b a1 00 64 64 00 00 00 00 00 00 00");
            c[2] = (byte) Math.round(hsv[0] / 2);
            c[3] = (byte) Math.round(hsv[1] * 100);
            c[4] = (byte) Math.round(hsv[2] * 100);
            return wrap(c, 0x0b);
        }

        /** 63 12 21 and F0 (as the BLE controllers are asked) or 0F (as ZENGGE's Wi-Fi ones are). */
        @Override byte[] askPixels(int attempt) {
            byte[] q = hex(attempt == 0 ? "63 12 21 f0" : "63 12 21 0f");
            return wrap(q, 0x0a);
        }

        /**
         * The answer to askPixels, in the same envelope: 63 (or 00 63), then the LEDs in a segment (two bytes),
         * 00, the segments, the chip, the wiring... Some controllers put it in JSON instead, the bytes in hex as
         * "payload".
         */
        @Override int pixels(byte[] answer) {
            int n = answer.length > 8 ? setting(java.util.Arrays.copyOfRange(answer, 8, answer.length)) : -1;
            return n > 0 ? n : setting(answer);
        }

        private static int setting(byte[] b) {
            if (b.length > 0 && b[0] == '{') {
                String text = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
                int at = text.indexOf("\"payload\"");
                int q = at < 0 ? -1 : text.indexOf('"', text.indexOf(':', at) + 1);
                int end = q < 0 ? -1 : text.indexOf('"', q + 1);
                if (end < 0 || (end - q - 1) % 2 != 0) return -1;
                String h = text.substring(q + 1, end);
                b = new byte[h.length() / 2];
                for (int i = 0; i < b.length; i++) {
                    int hi = Character.digit(h.charAt(2 * i), 16), lo = Character.digit(h.charAt(2 * i + 1), 16);
                    if (hi < 0 || lo < 0) return -1;
                    b[i] = (byte) (hi << 4 | lo);
                }
            }
            int at = b.length > 0 && b[0] == 0x63 ? 0 : b.length > 1 && b[0] == 0x00 && b[1] == 0x63 ? 1 : -1;
            if (at < 0 || b.length < at + 6) return -1;
            int n = (b[at + 1] & 0xFF) << 8 | b[at + 2] & 0xFF;
            return n > 0 && n <= 2048 ? n : -1;
        }

        @Override boolean addressable() {
            return true;
        }

        /** 59, the length of it all (two bytes), a colour for every LED, then 00 1E, the effect (01: static), its speed and 00. */
        @Override byte[] pixels(int[] rgb) {
            int n = rgb.length, total = 9 + n * 3;
            byte[] c = new byte[total - 1];
            c[0] = 0x59;
            c[1] = (byte) (total >> 8);
            c[2] = (byte) total;
            for (int i = 0; i < n; i++) {
                c[3 + i * 3] = (byte) (rgb[i] >> 16);
                c[4 + i * 3] = (byte) (rgb[i] >> 8);
                c[5 + i * 3] = (byte) rgb[i];
            }
            int e = 3 + n * 3;
            c[e] = 0x00;
            c[e + 1] = 0x1e;
            c[e + 2] = 0x01;
            c[e + 3] = 0x64;
            c[e + 4] = 0x00;
            return wrap(c, 0x0b);
        }

        /** The envelope: 00, the counter, 80 00, the length (two bytes) and plus one, the type; then the command and its sum. */
        private byte[] wrap(byte[] command, int type) {
            sequence = (sequence + 1) & 0xFF;
            int length = command.length, inner = length + 1;
            byte[] p = new byte[8 + inner];
            p[0] = 0x00;
            p[1] = (byte) sequence;
            p[2] = (byte) 0x80;
            p[3] = 0x00;
            p[4] = (byte) (inner >> 8);
            p[5] = (byte) inner;
            p[6] = (byte) (inner + 1);
            p[7] = (byte) type;
            int sum = 0;
            for (int i = 0; i < length; i++) {
                p[8 + i] = command[i];
                sum += command[i] & 0xFF;
            }
            p[8 + length] = (byte) sum;
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
