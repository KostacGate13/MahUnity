package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.Locale;

/**
 * The Vehicle tab: speed in large metal digits (from the car's CANBus, else GPS), then outside temperature,
 * battery voltage, coolant temperature, fuel and tyre pressures. Rows with no source show a dash; the last
 * lines say which values the unit does not share and how the CANBus link is doing.
 */
final class VehicleView extends View {
    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint digits = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final String unitText, dash;
    private final String[] labels;
    private final String[] values = new String[5];
    private String captionText = "", speedText = "–", headingText = "", noteText = "", missingText = "", statusText = "";
    private float pad, captionY, digitsY, noteY, lineY, rowsY, rowH, missingY, statusY;

    VehicleView(Context context) {
        super(context);
        unitText = context.getString(R.string.kmh);
        dash = context.getString(R.string.unknown);
        labels = new String[] {
            Text.upper(context.getString(R.string.veh_outside)), Text.upper(context.getString(R.string.veh_battery)),
            Text.upper(context.getString(R.string.veh_coolant)), Text.upper(context.getString(R.string.veh_fuel)),
            Text.upper(context.getString(R.string.veh_tyres)),
        };
        for (int i = 0; i < values.length; i++) values[i] = dash;
        caption.setTypeface(Chrome.MEDIUM);
        caption.setColor(Chrome.TEXT_DIM);
        caption.setLetterSpacing(0.28f);
        digits.setTypeface(Chrome.THIN);
        unit.setTypeface(Chrome.LIGHT);
        unit.setColor(Chrome.TEXT_DIM);
        unit.setLetterSpacing(0.2f);
        label.setTypeface(Chrome.MEDIUM);
        label.setColor(Chrome.TEXT_DIM);
        label.setLetterSpacing(0.2f);
        value.setTypeface(Chrome.REGULAR);
        value.setColor(Chrome.TEXT);
        value.setTextAlign(Paint.Align.RIGHT);
        note.setTypeface(Chrome.LIGHT);
        note.setColor(Chrome.TEXT_DIM);
    }

    /** value null: no speed source (CAN silent and GPS switched off in Settings). */
    void setSpeed(String value, String heading, boolean fromCan) {
        captionText = Text.upper(getContext().getString(fromCan ? R.string.veh_speed_can : R.string.veh_speed_gps));
        speedText = value == null ? "–" : value;
        headingText = heading == null ? "" : "·  " + heading;
        noteText = value == null ? getContext().getString(R.string.speed_off_hint) : "";
        invalidate();
    }

    /**
     * What the car reports; capacity is the tank size from Settings, for the fuel share; hint, if not null, says
     * why fuel, battery, coolant and tyres are missing.
     */
    void setCar(CanBus can, float capacity, String hint) {
        Context c = getContext();
        Locale l = Locale.getDefault();
        values[0] = can.outside != null ? can.outside : dash;
        values[1] = can.battery > 0 ? String.format(l, "%.1f V", can.battery) : dash;
        values[2] = Float.isNaN(can.coolant) ? dash : Math.round(can.coolant) + "°C";
        values[3] = can.fuel >= 0 ? String.format(l, "%s L  ·  %d%%",
                can.fuel == Math.floor(can.fuel) ? String.valueOf((int) can.fuel) : String.format(l, "%.1f", can.fuel),
                Math.round(100 * Math.min(1f, can.fuel / Math.max(1f, capacity)))) : dash;
        if (can.tyres == null) {
            values[4] = dash;
        } else {
            StringBuilder t = new StringBuilder();
            for (float bar : can.tyres) {
                t.append(t.length() == 0 ? "" : " · ").append(bar < 0 ? dash : String.format(l, "%.1f", bar));
            }
            values[4] = t.append(" bar").toString();
        }
        missingText = hint == null ? "" : hint;
        statusText = can.state == CanBus.MISSING ? c.getString(R.string.can_missing)
                : can.packets == 0 ? c.getString(R.string.can_waiting)
                : c.getString(R.string.can_live, can.packets);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        pad = Math.min(w, h) * 0.07f;
        caption.setTextSize(h * 0.034f);
        float size = Math.min(h * 0.2f, w * 0.3f);
        digits.setTextSize(size);
        unit.setTextSize(size * 0.17f);
        label.setTextSize(h * 0.03f);
        value.setTextSize(h * 0.042f);
        note.setTextSize(h * 0.031f);
        captionY = pad + caption.getTextSize();
        digitsY = captionY + size * 0.18f + size * 0.71f;
        noteY = digitsY + note.getTextSize() * 1.8f;
        lineY = noteY + h * 0.035f;
        rowH = h * 0.085f;
        rowsY = lineY + rowH * 0.85f;
        statusY = h - pad * 0.7f;
        missingY = statusY - note.getTextSize() * 1.7f;
        digits.setShader(Chrome.metal(digitsY - size * 0.71f, digitsY));
        line.setShader(Chrome.fadeRight(pad, w - pad, 0x90));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth();
        c.drawText(captionText, pad, captionY, caption);
        c.drawText(speedText, pad - digits.getTextSize() * 0.03f, digitsY, digits);
        float x = pad + digits.measureText(speedText) + unit.getTextSize() * 0.6f;
        c.drawText(unitText, x, digitsY, unit);
        if (!headingText.isEmpty()) {
            c.drawText(headingText, x + unit.measureText(unitText) + unit.getTextSize() * 0.9f, digitsY, unit);
        }
        if (!noteText.isEmpty()) c.drawText(noteText, pad, noteY, note);
        c.drawRect(pad, lineY, w - pad, lineY + 1, line);

        float avail = w - 2 * pad - label.measureText(labels[2]) * 1.3f;
        for (int i = 0; i < labels.length; i++) {
            float y = rowsY + i * rowH;
            c.drawText(labels[i], pad, y, label);
            CharSequence v = TextUtils.ellipsize(values[i], value, avail, TextUtils.TruncateAt.END);
            c.drawText(v, 0, v.length(), w - pad, y, value);
        }
        if (!missingText.isEmpty()) {
            CharSequence m = TextUtils.ellipsize(missingText, note, w - 2 * pad, TextUtils.TruncateAt.END);
            c.drawText(m, 0, m.length(), pad, missingY, note);
        }
        c.drawText(statusText, pad, statusY, note);
    }
}
