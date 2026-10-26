package com.mahunity.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Light settings sheet: which Bluetooth device each strip is, the mode, brightness, how fast Normal moves,
 * what Music listens to and how keenly, the redline, where the rev bar starts, how many LEDs it has and how they
 * are lit one by one, a trial run of it, how hard a stop must be to count as braking, and what each strip is
 * doing. Choosing a device scans for a while and lists what answers, LED controllers first.
 */
final class LightsSettings {
    interface Host {
        void hideLights();

        void lightsChanged();
    }

    /** A choice made from a list. */
    private interface Choice {
        void chosen(int which);
    }

    static final int REQUEST = 41;
    private static final float[] BRAKES = {5f, 8f, 12f};

    final Panel panel;
    private final Activity activity;
    private final Ambient ambient;
    private final LedStrips leds;
    private final Host host;
    private final SettingsRows.Row rowTop, rowBottom, rowMode, rowBright, rowChase, rowSource, rowSense, rowRedline,
            rowBarFrom, rowPixels, rowMethod, rowBrake, rowStatus;
    private int pendingRole = -1;

    LightsSettings(final Activity activity, final Ambient ambient, LedStrips leds, final Host host) {
        this.activity = activity;
        this.ambient = ambient;
        this.leds = leds;
        this.host = host;
        panel = new Panel(activity);
        panel.setVisibility(View.GONE);
        panel.setTitle(activity.getString(R.string.led_settings), null);
        panel.close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.hideLights();
            }
        });
        SettingsRows rows = new SettingsRows(activity);
        rowTop = rows.add(R.string.set_led_device_top, new View.OnClickListener() {
            @Override public void onClick(View v) {
                choose(LedStrips.TOP);
            }
        });
        rowBottom = rows.add(R.string.set_led_device_bottom, new View.OnClickListener() {
            @Override public void onClick(View v) {
                choose(LedStrips.BOTTOM);
            }
        });
        rowMode = rows.add(R.string.set_led_mode, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickMode();
            }
        });
        rowBright = rows.add(R.string.set_led_brightness, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickBrightness();
            }
        });
        rowChase = rows.add(R.string.set_led_chase, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick(R.string.set_led_chase, R.array.led_chases, ambient.chase, new Choice() {
                    @Override public void chosen(int which) {
                        ambient.chase = which;
                    }
                });
            }
        });
        rowSource = rows.add(R.string.set_led_music_source, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick(R.string.set_led_music_source, R.array.led_music_sources, ambient.musicSource, new Choice() {
                    @Override public void chosen(int which) {
                        ambient.musicSource = which;
                    }
                });
            }
        });
        rowSense = rows.add(R.string.set_led_music_sense, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick(R.string.set_led_music_sense, R.array.led_senses, ambient.musicSense, new Choice() {
                    @Override public void chosen(int which) {
                        ambient.musicSense = which;
                    }
                });
            }
        });
        rowRedline = rows.add(R.string.set_led_redline, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickNumber(R.string.set_led_redline, ambient.redline, 0);
            }
        });
        rowBarFrom = rows.add(R.string.set_led_bar_from, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickNumber(R.string.set_led_bar_from, ambient.barFrom, 1);
            }
        });
        rowPixels = rows.add(R.string.set_led_pixels, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickNumber(R.string.set_led_pixels, ambient.topPixels, 2);
            }
        });
        rowMethod = rows.add(R.string.set_led_method, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick(R.string.set_led_method, R.array.led_methods, ambient.topMethod, new Choice() {
                    @Override public void chosen(int which) {
                        ambient.topMethod = which;
                    }
                });
            }
        });
        rows.add(R.string.set_led_sweep, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ambient.sweep();
            }
        });
        rowBrake = rows.add(R.string.set_led_brake, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick(R.string.set_led_brake, R.array.led_brakes, brakeIndex(), new Choice() {
                    @Override public void chosen(int which) {
                        ambient.brake = BRAKES[which];
                    }
                });
            }
        });
        rowStatus = rows.add(R.string.set_led_status, new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(activity).setTitle(R.string.set_led_status).setMessage(statusReport())
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });
        rows.add(R.string.set_led_help, new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(activity).setTitle(R.string.set_led_help).setMessage(R.string.led_help)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(rows, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.setContent(scroll);
    }

    void refresh() {
        rowTop.setValue(device(LedStrips.TOP), leds.strips[LedStrips.TOP].address == null, 0);
        rowBottom.setValue(device(LedStrips.BOTTOM), leds.strips[LedStrips.BOTTOM].address == null, 0);
        rowMode.setValue(activity.getString(Ambient.MODE_NAMES[ambient.mode]), false, 0);
        rowBright.setValue(activity.getString(R.string.led_brightness_value, ambient.brightness), false, 0);
        rowChase.setValue(item(R.array.led_chases, ambient.chase), false, 0);
        rowSource.setValue(item(R.array.led_music_sources, ambient.musicSource), false, 0);
        rowSense.setValue(item(R.array.led_senses, ambient.musicSense), false, 0);
        rowRedline.setValue(ambient.redline + " rpm", false, 0);
        rowBarFrom.setValue(ambient.barFrom + " rpm", false, 0);
        int said = leds.pixels(LedStrips.TOP);
        rowPixels.setValue(ambient.topPixels > 0 ? activity.getString(R.string.led_pixels_count, ambient.topPixels)
                : said > 0 ? activity.getString(R.string.led_pixels_auto_found, said) : activity.getString(R.string.led_pixels_auto),
                false, 0);
        rowMethod.setValue(method(), false, 0);
        rowBrake.setValue(item(R.array.led_brakes, brakeIndex()), false, 0);
        rowStatus.setValue(status(LedStrips.TOP) + " · " + status(LedStrips.BOTTOM), false, 0);
    }

    private String item(int array, int index) {
        String[] items = activity.getResources().getStringArray(array);
        return items[Math.max(0, Math.min(items.length - 1, index))];
    }

    /** The way the dashboard lights its LEDs one by one; for Automatic, the one it came to. */
    private String method() {
        String chosen = item(R.array.led_methods, ambient.topMethod);
        LedStrips.Strip s = leds.strips[LedStrips.TOP];
        if (ambient.topMethod != LedProtocol.AUTO || s.protocol == null || !s.protocol.addressable()) return chosen;
        return chosen + " · " + item(R.array.led_methods, s.protocol.method());
    }

    private String device(int role) {
        LedStrips.Strip s = leds.strips[role];
        return s.address == null ? activity.getString(R.string.led_choose) : s.name != null ? s.name : s.address;
    }

    /**
     * A strip's connection in a few words, for the tab and the sheet; in Music, also whether it hears; for the
     * footwells showing the fuel, the litres.
     */
    String status(int role) {
        String link = link(role);
        if (role == LedStrips.BOTTOM && ambient.bottomFuel && ambient.fuelMode() && leds.strips[role].address != null) {
            return link + " · " + (ambient.fuelShows() ? Math.round(ambient.litres()) + " L"
                    : activity.getString(R.string.led_status_no_fuel));
        }
        if (ambient.mode != Ambient.MUSIC || leds.strips[role].address == null) return link;
        if (!ambient.mayListen()) return link + " · " + activity.getString(R.string.led_status_no_mic);
        if (!ambient.hearing()) return link + " · " + activity.getString(R.string.led_status_no_sound);
        return link;
    }

    private String link(int role) {
        LedStrips.Strip s = leds.strips[role];
        if (!leds.hasAdapter()) return activity.getString(R.string.led_status_no_ble);
        if (s.address == null) return activity.getString(R.string.led_status_none);
        if (!leds.allowed()) return activity.getString(R.string.led_status_permission);
        if (!leds.available()) return activity.getString(R.string.led_status_bt_off);
        if (s.unsupported) return activity.getString(R.string.led_status_unknown);
        if (s.state == LedStrips.READY) {
            return s.pixels > 0 ? s.protocol.name + " · " + activity.getString(R.string.led_pixels_count, s.pixels) : s.protocol.name;
        }
        if (s.state == LedStrips.CONNECTING) return activity.getString(R.string.led_status_connecting);
        return activity.getString(R.string.led_status_off);
    }

    /** All there is to know of each strip, to send over when one does not do what it should. */
    private String statusReport() {
        StringBuilder b = new StringBuilder();
        int[] names = {R.string.led_top, R.string.led_bottom};
        for (int role = 0; role < 2; role++) {
            LedStrips.Strip s = leds.strips[role];
            if (b.length() > 0) b.append("\n\n");
            b.append(activity.getString(names[role])).append(": ").append(status(role));
            if (s.address != null) b.append("\n").append(s.name).append(" · ").append(s.address);
            if (s.address != null && (s.product >= 0 || s.protocol != null && s.protocol.addressable())) {
                b.append("\n").append(activity.getString(R.string.led_report_model, s.product >= 0
                        ? String.format(Locale.ROOT, "0x%02X", s.product) : activity.getString(R.string.led_report_model_unknown)));
            }
            if (s.state == LedStrips.READY) {
                b.append("\n").append(activity.getString(R.string.led_report_link, s.mtu, leds.room(role)));
            }
            if (s.state == LedStrips.READY && s.protocol.addressable()) {
                int n = role == LedStrips.TOP ? ambient.pixels() : s.pixels;
                b.append("\n").append(activity.getString(R.string.led_report_pixels, s.pixels > 0
                        ? String.valueOf(s.pixels) : activity.getString(R.string.led_report_unsaid)));
                if (role == LedStrips.TOP) {
                    b.append("\n").append(activity.getString(R.string.led_report_method, method()));
                    int steps = leds.fit(role, n);
                    b.append("\n").append(steps >= n && n > 0 ? activity.getString(R.string.led_report_each)
                            : steps > 0 ? activity.getString(R.string.led_report_steps, steps, n)
                            : activity.getString(R.string.led_report_one));
                }
            }
            for (Map.Entry<Integer, String> e : s.answers.entrySet()) {
                b.append("\n").append(activity.getString(R.string.led_report_answer, e.getValue()));
            }
            if (s.services != null) b.append("\n").append(s.services);
        }
        return b.toString();
    }

    private int brakeIndex() {
        int best = 1;
        for (int i = 0; i < BRAKES.length; i++) if (Math.abs(BRAKES[i] - ambient.brake) < Math.abs(BRAKES[best] - ambient.brake)) best = i;
        return best;
    }

    /** Picks a device for a strip: Bluetooth on, permission given, then a scan. */
    private void choose(int role) {
        if (!leds.hasAdapter()) {
            new AlertDialog.Builder(activity).setTitle(R.string.led_settings).setMessage(R.string.led_no_ble)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        if (!leds.allowed()) {
            pendingRole = role;
            activity.requestPermissions(LedStrips.permissions(), REQUEST);
            return;
        }
        if (!leds.available()) {
            new AlertDialog.Builder(activity).setTitle(R.string.led_settings).setMessage(R.string.led_bt_off)
                    .setPositiveButton(R.string.led_open_bluetooth, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            try {
                                activity.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                            } catch (RuntimeException ignored) {
                                // No such screen on this unit.
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        scan(role);
    }

    /** After the permission request: on with the choice, if it was given. */
    void onPermission() {
        int role = pendingRole;
        pendingRole = -1;
        leds.start();
        if (role >= 0 && leds.allowed()) choose(role);
        refresh();
    }

    private void scan(final int role) {
        final List<String> labels = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels);
        final Runnable fill = new Runnable() {
            @Override public void run() {
                labels.clear();
                labels.add(activity.getString(R.string.led_scan_none));
                for (LedStrips.Found f : leds.found) {
                    String model = f.product >= 0 ? String.format(Locale.ROOT, "   0x%02X", f.product) : "";
                    labels.add(f.name + model + "   " + f.address + "   " + f.rssi + " dBm");
                }
                adapter.notifyDataSetChanged();
            }
        };
        fill.run();
        // Its own app holds a controller while connected: let it go first.
        leds.use(role, null, null, -1);
        final AlertDialog d = new AlertDialog.Builder(activity)
                .setTitle(role == LedStrips.TOP ? R.string.set_led_device_top : R.string.set_led_device_bottom)
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        leds.stopScan();
                        if (which == 0) {
                            ambient.use(role, null, null, -1);
                        } else if (which - 1 < leds.found.size()) {
                            LedStrips.Found f = leds.found.get(which - 1);
                            ambient.use(role, f.address, f.name, f.product);
                        }
                        refresh();
                        host.lightsChanged();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                leds.stopScan();
                // Cancelled: back to the device it had.
                if (leds.strips[role].address == null) {
                    String key = role == LedStrips.TOP ? "led_top" : "led_bottom";
                    android.content.SharedPreferences p = activity.getSharedPreferences("home", Activity.MODE_PRIVATE);
                    leds.use(role, p.getString(key + "_address", null), p.getString(key + "_name", null),
                            p.getInt(key + "_product", -1));
                }
            }
        });
        d.show();
        leds.scan(fill);
    }

    private void pickMode() {
        String[] items = new String[Ambient.MODE_NAMES.length];
        for (int i = 0; i < items.length; i++) items[i] = activity.getString(Ambient.MODE_NAMES[i]);
        new AlertDialog.Builder(activity).setTitle(R.string.set_led_mode)
                .setSingleChoiceItems(items, ambient.mode, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        ambient.mode = which;
                        changed();
                        askMicrophone(activity, ambient);
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Music listens through the microphone permission, whichever source: asked for when it is chosen. */
    static void askMicrophone(Activity activity, Ambient ambient) {
        if (ambient.mode == Ambient.MUSIC && !ambient.mayListen()) activity.requestPermissions(Sound.permissions(), Sound.REQUEST);
    }

    private void pick(int title, int array, int current, final Choice choice) {
        new AlertDialog.Builder(activity).setTitle(title)
                .setSingleChoiceItems(array, current, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        choice.chosen(which);
                        changed();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pickBrightness() {
        final SeekBar bar = new SeekBar(activity);
        bar.setMax(90);
        bar.setProgress(ambient.brightness - 10);
        int p = Math.round(activity.getResources().getDisplayMetrics().density * 24);
        bar.setPadding(p, p, p, p);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                ambient.brightness = value + 10;
                ambient.apply();
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}

            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        new AlertDialog.Builder(activity).setTitle(R.string.set_led_brightness).setView(bar)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        changed();
                    }
                }).setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        changed();
                    }
                }).show();
    }

    /** A whole number: which 0 the redline, 1 the bar's start (both rpm), 2 the LEDs (0 for the controller's). */
    private void pickNumber(int titleRes, int current, final int which) {
        final EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(current));
        field.setSelectAllOnFocus(true);
        if (which == 2) field.setHint(R.string.led_pixels_hint);
        new AlertDialog.Builder(activity).setTitle(titleRes).setView(field)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        float v;
                        try {
                            v = Float.parseFloat(field.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            return;
                        }
                        if (which == 0) ambient.redline = Math.round(Math.max(Math.max(1500f, ambient.barFrom + 500f), Math.min(v, 9000f)));
                        else if (which == 1) ambient.barFrom = Math.round(Math.max(0f, Math.min(v, ambient.redline - 500f)));
                        else ambient.topPixels = Math.round(Math.max(0f, Math.min(v, 1000f)));
                        changed();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void changed() {
        ambient.save();
        refresh();
        host.lightsChanged();
    }
}
