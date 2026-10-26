package com.mahunity.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * How loud the music is and how hard its bass hits, some twenty times a second, for the LED strips' music mode.
 * From what the unit plays (its output mix, through a Visualizer: apps, USB music, Bluetooth music where it goes
 * through Android) or from the microphone (which also hears what bypasses Android, such as the radio on many
 * units, and the car). Each is scaled to the loudest of the last few seconds, so it reacts alike at any volume.
 */
final class Sound {
    interface Listener {
        /** Loudness and bass, 0 to 1, on the main thread. */
        void onSound(float level, float bass);
    }

    static final int OUTPUT = 0, MIC = 1;
    static final int REQUEST = 42;
    /** Below this (of full scale) it is silence, not music to scale up. */
    private static final float FLOOR = 0.012f;
    /** How fast the loudest of late is forgotten, per reading: half in about three seconds. */
    private static final float DECAY = 0.988f;
    /** The bass: what passes a low-pass at this many Hz. */
    private static final float BASS_HZ = 150f;
    private static final int MIC_RATE = 22050, MIC_BLOCK = 512;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Visualizer visualizer;
    private volatile Thread mic;
    private int source = -1;
    private float peak, bassPeak, low;
    /** Whether the source could be opened; false if the unit refused it. */
    boolean working;

    Sound(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    static String[] permissions() {
        return new String[] {Manifest.permission.RECORD_AUDIO};
    }

    boolean allowed() {
        return context.checkPermission(Manifest.permission.RECORD_AUDIO, Process.myPid(), Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Listens to a source, OUTPUT or MIC, until stop(); again from the start if it changed. */
    void start(int source) {
        if (source == this.source && (visualizer != null || mic != null)) return;
        stop();
        this.source = source;
        working = false;
        if (!allowed()) return;
        if (source == MIC) startMic();
        else startOutput();
    }

    void stop() {
        source = -1;
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            visualizer.release();
            visualizer = null;
        }
        Thread t = mic;
        mic = null;
        if (t != null) t.interrupt();
        peak = bassPeak = low = 0;
    }

    private void startOutput() {
        try {
            Visualizer v = new Visualizer(0);
            v.setEnabled(false);
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            // As played: quiet music stays quiet, and the scaling here does the rest.
            v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer vis, byte[] wave, int samplingRate) {
                    if (vis != visualizer) return;
                    // Eight bits unsigned, 128 the middle.
                    float[] s = new float[wave.length];
                    for (int i = 0; i < wave.length; i++) s[i] = ((wave[i] & 0xFF) - 128) / 128f;
                    feed(s, s.length, samplingRate / 1000f);
                }

                @Override public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {}
            }, Visualizer.getMaxCaptureRate(), true, false);
            v.setEnabled(true);
            visualizer = v;
            working = true;
        } catch (RuntimeException e) {
            // No output to listen to on this unit, or it said no.
            visualizer = null;
        }
    }

    private void startMic() {
        final int size = Math.max(AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT), MIC_BLOCK * 4);
        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                return;
            }
            record.startRecording();
        } catch (RuntimeException e) {
            return;
        }
        working = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                short[] block = new short[MIC_BLOCK];
                final float[] s = new float[MIC_BLOCK];
                try {
                    while (mic == Thread.currentThread()) {
                        int n = record.read(block, 0, block.length);
                        if (n <= 0) break;
                        for (int i = 0; i < n; i++) s[i] = block[i] / 32768f;
                        final float[] copy = java.util.Arrays.copyOf(s, n);
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (source == MIC) feed(copy, copy.length, MIC_RATE);
                            }
                        });
                    }
                } finally {
                    try {
                        record.stop();
                    } catch (RuntimeException ignored) {
                        // Stopped already.
                    }
                    record.release();
                }
            }
        }, "cockpit-mic");
        mic = t;
        t.start();
    }

    /** A stretch of sound: its loudness and its bass, each against the loudest of late. */
    private void feed(float[] s, int n, float rate) {
        if (n == 0) return;
        float a = (float) (1 - Math.exp(-2 * Math.PI * BASS_HZ / Math.max(1f, rate)));
        double all = 0, bass = 0;
        for (int i = 0; i < n; i++) {
            low += a * (s[i] - low);
            all += s[i] * s[i];
            bass += low * low;
        }
        float rms = (float) Math.sqrt(all / n), lowRms = (float) Math.sqrt(bass / n);
        peak = Math.max(rms, peak * DECAY);
        bassPeak = Math.max(lowRms, bassPeak * DECAY);
        float level = rms < FLOOR ? 0 : rms / Math.max(peak, FLOOR * 3);
        float hit = lowRms < FLOOR ? 0 : lowRms / Math.max(bassPeak, FLOOR * 3);
        listener.onSound(Math.min(1f, level), Math.min(1f, hit));
    }
}
