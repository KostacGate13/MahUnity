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

/**
 * The Light settings sheet: which Bluetooth device each strip is, the mode, brightness, the fuel and rev marks,
 * where the rev bar starts and how many LEDs it has, a trial run of it, how hard a stop must be to count as
 * braking, and what each strip is doing. Choosing a device scans for a while and lists what answers, LED
 * controllers first.
 */
final class LightsSettings {
    interface Host {
        void hideLights();

        void lightsChanged();
    }

    static final int REQUEST = 41;
    private static final float[] BRAKES = {5f, 8f, 12f};

    final Panel panel;
    private final Activity activity;
    private final Ambient ambient;
    private final LedStrips leds;
    private final Host host;
    private final SettingsRows.Row rowTop, rowBottom, rowMode, rowBright, rowLow, rowHigh, rowRedline, rowBarFrom,
            rowPixels, rowBrake, rowStatus;
    private int pendingRole = -1;

    LightsSettings(final Activity activity, Ambient ambient, LedStrips leds, final Host host) {
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
        rowLow = rows.add(R.string.set_led_fuel_low, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickNumber(R.string.set_led_fuel_low, LightsSettings.this.ambient.fuelLow, false, 0);
            }
        });
        rowHigh = rows.add(R.string.set_led_fuel_high, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickNumber(R.string.set_led_fuel_high, LightsSettings.this.ambient.fuelHigh, false, 1);
            }
        });
        rowRedline = rows.add(R.string.set_led_redline, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickNumber(R.string.set_led_redline, LightsSettings.this.ambient.redline, true, 2);
            }
        });
        rowBarFrom = rows.add(R.string.set_led_bar_from, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickNumber(R.string.set_led_bar_from, LightsSettings.this.ambient.barFrom, true, 3);
            }
        });
        rowPixels = rows.add(R.string.set_led_pixels, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickNumber(R.string.set_led_pixels, LightsSettings.this.ambient.topPixels, true, 4);
            }
        });
        rows.add(R.string.set_led_sweep, new View.OnClickListener() {
            @Override public void onClick(View v) {
                LightsSettings.this.ambient.sweep();
            }
        });
        rowBrake = rows.add(R.string.set_led_brake, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickBrake();
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
        rowLow.setValue(litres(ambient.fuelLow), false, 0);
        rowHigh.setValue(litres(ambient.fuelHigh), false, 0);
        rowRedline.setValue(ambient.redline + " rpm", false, 0);
        rowBarFrom.setValue(ambient.barFrom + " rpm", false, 0);
        int said = leds.pixels(LedStrips.TOP);
        rowPixels.setValue(ambient.topPixels > 0 ? activity.getString(R.string.led_pixels_count, ambient.topPixels)
                : said > 0 ? activity.getString(R.string.led_pixels_auto_found, said) : activity.getString(R.string.led_pixels_auto),
                false, 0);
        rowBrake.setValue(activity.getResources().getStringArray(R.array.led_brakes)[brakeIndex()], false, 0);
        rowStatus.setValue(status(LedStrips.TOP) + " · " + status(LedStrips.BOTTOM), false, 0);
    }

    private String device(int role) {
        LedStrips.Strip s = leds.strips[role];
        return s.address == null ? activity.getString(R.string.led_choose) : s.name != null ? s.name : s.address;
    }

    /** A strip's connection in a few words, for the tab and the sheet. */
    String status(int role) {
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

    private String statusReport() {
        StringBuilder b = new StringBuilder();
        int[] names = {R.string.led_top, R.string.led_bottom};
        for (int role = 0; role < 2; role++) {
            LedStrips.Strip s = leds.strips[role];
            if (b.length() > 0) b.append("\n\n");
            b.append(activity.getString(names[role])).append(": ").append(status(role));
            if (s.address != null) b.append("\n").append(s.name).append(" · ").append(s.address);
            if (s.state == LedStrips.READY && s.protocol.addressable()) {
                int n = role == LedStrips.TOP ? ambient.pixels() : s.pixels;
                b.append("\n").append(activity.getString(R.string.led_report_pixels, s.pixels > 0
                        ? String.valueOf(s.pixels) : activity.getString(R.string.led_report_unsaid)));
                if (role == LedStrips.TOP) {
                    b.append("\n").append(activity.getString(leds.lightsEach(role, n) ? R.string.led_report_each
                            : n > 0 ? R.string.led_report_too_long : R.string.led_report_one));
                }
            }
            if (s.answer != null) b.append("\n").append(activity.getString(R.string.led_report_answer, s.answer));
            if (s.services != null) b.append("\n").append(s.services);
        }
        return b.toString();
    }

    private static String litres(float l) {
        return (l == Math.floor(l) ? String.valueOf((int) l) : String.format(Locale.ROOT, "%.1f", l)) + " L";
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
                    labels.add(f.name + "   " + f.address + "   " + f.rssi + " dBm");
                }
                adapter.notifyDataSetChanged();
            }
        };
        fill.run();
        // Its own app holds a controller while connected: let it go first.
        leds.use(role, null, null);
        final AlertDialog d = new AlertDialog.Builder(activity)
                .setTitle(role == LedStrips.TOP ? R.string.set_led_device_top : R.string.set_led_device_bottom)
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        leds.stopScan();
                        if (which == 0) {
                            ambient.use(role, null, null);
                        } else if (which - 1 < leds.found.size()) {
                            LedStrips.Found f = leds.found.get(which - 1);
                            ambient.use(role, f.address, f.name);
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
                    leds.use(role, p.getString(key + "_address", null), p.getString(key + "_name", null));
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

    /**
     * A number: litres (with a decimal) for the fuel marks, rpm for the redline and the bar's start, a count of
     * LEDs (0 for the controller's). which: 0 low, 1 high, 2 redline, 3 bar start, 4 LEDs.
     */
    private void pickNumber(int titleRes, float current, boolean whole, final int which) {
        final EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | (whole ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL));
        field.setText(whole ? String.valueOf((int) current) : litres(current).replace(" L", ""));
        field.setSelectAllOnFocus(true);
        if (which == 4) field.setHint(R.string.led_pixels_hint);
        new AlertDialog.Builder(activity).setTitle(titleRes).setView(field)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        float v;
                        try {
                            v = Float.parseFloat(field.getText().toString().trim().replace(',', '.'));
                        } catch (NumberFormatException e) {
                            return;
                        }
                        if (which == 0) ambient.fuelLow = Math.max(0f, Math.min(v, ambient.fuelHigh));
                        else if (which == 1) ambient.fuelHigh = Math.max(ambient.fuelLow, Math.min(v, 200f));
                        else if (which == 2) ambient.redline = Math.round(Math.max(Math.max(1500f, ambient.barFrom + 500f), Math.min(v, 9000f)));
                        else if (which == 3) ambient.barFrom = Math.round(Math.max(0f, Math.min(v, ambient.redline - 500f)));
                        else ambient.topPixels = Math.round(Math.max(0f, Math.min(v, 1000f)));
                        changed();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pickBrake() {
        new AlertDialog.Builder(activity).setTitle(R.string.set_led_brake)
                .setSingleChoiceItems(R.array.led_brakes, brakeIndex(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        ambient.brake = BRAKES[which];
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
