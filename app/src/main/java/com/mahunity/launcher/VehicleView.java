package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The Vehicle tab: speed in large metal digits (from the car's CANBus, else GPS), then what the car reports:
 * outside temperature, climate, engine speed, doors, and the parking sensors as bars around a plan of the car.
 * Rows the car has not reported yet show a dash; the last line says how the CANBus link is doing.
 */
final class VehicleView extends View {
    private static final int RED = 0xFFFF4B45, AMBER = 0xFFFFB13B, GREEN = 0xFF4FD08A;

    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint digits = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final Paint plan = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final String unitText, dash;
    private final String[] labels;
    private final String[] values = new String[4];
    private final boolean[] alert = new boolean[4];
    private String captionText = "", speedText = "–", headingText = "", noteText = "", statusText = "";
    private int[] radar;
    private float pad, captionY, digitsY, noteY, lineY, rowsY, rowH, statusY;

    VehicleView(Context context) {
        super(context);
        unitText = context.getString(R.string.kmh);
        dash = context.getString(R.string.unknown);
        labels = new String[] {
            Text.upper(context.getString(R.string.veh_outside)), Text.upper(context.getString(R.string.veh_climate)),
            Text.upper(context.getString(R.string.veh_rpm)), Text.upper(context.getString(R.string.veh_doors)),
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
        value.setTextAlign(Paint.Align.RIGHT);
        note.setTypeface(Chrome.LIGHT);
        note.setColor(Chrome.TEXT_DIM);
        plan.setStyle(Paint.Style.STROKE);
        plan.setColor(0x80B4BAC1);
        bar.setStrokeCap(Paint.Cap.ROUND);
    }

    /** value null: no speed source (CAN silent and GPS switched off in Settings). */
    void setSpeed(String value, String heading, boolean fromCan) {
        captionText = Text.upper(getContext().getString(fromCan ? R.string.veh_speed_can : R.string.veh_speed_gps));
        speedText = value == null ? "–" : value;
        headingText = heading == null ? "" : "·  " + heading;
        noteText = value == null ? getContext().getString(R.string.speed_off_hint) : "";
        invalidate();
    }

    /** Everything else the car reports. */
    void setCar(CanBus can) {
        Context c = getContext();
        values[0] = can.outside != null ? can.outside : dash;
        if (can.fan < 0) {
            values[1] = dash;
        } else if (!can.acOn) {
            values[1] = c.getString(R.string.climate_off);
        } else {
            String temps = can.acLeft == null ? "" : can.acLeft
                    + (can.acRight != null && !can.acRight.equals(can.acLeft) ? " · " + can.acRight : "");
            values[1] = (temps.isEmpty() ? "" : temps + "  ") + c.getString(R.string.climate_fan, can.fan);
        }
        values[2] = can.rpm > 0 ? String.valueOf(can.rpm) : dash;
        alert[3] = false;
        if (can.doors < 0) {
            values[3] = dash;
        } else if (can.doors == 0) {
            values[3] = c.getString(R.string.doors_closed);
        } else {
            List<String> open = new ArrayList<>();
            int[] bits = {CanBus.FRONT_LEFT, CanBus.FRONT_RIGHT, CanBus.REAR_LEFT, CanBus.REAR_RIGHT, CanBus.TRUNK, CanBus.HOOD};
            int[] names = {R.string.door_fl, R.string.door_fr, R.string.door_rl, R.string.door_rr, R.string.door_trunk, R.string.door_hood};
            for (int i = 0; i < bits.length; i++) {
                if ((can.doors & bits[i]) != 0) open.add(c.getString(names[i]));
            }
            values[3] = TextUtils.join(", ", open);
            alert[3] = true;
        }
        radar = can.radar;
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
        value.setTextSize(h * 0.04f);
        note.setTextSize(h * 0.032f);
        captionY = pad + caption.getTextSize();
        digitsY = captionY + size * 0.18f + size * 0.71f;
        noteY = digitsY + note.getTextSize() * 1.8f;
        lineY = noteY + h * 0.035f;
        rowH = h * 0.075f;
        rowsY = lineY + rowH * 0.85f;
        statusY = h - pad * 0.7f;
        // The plan of the car for the parking sensors, between the rows and the status line.
        float top = rowsY + rowH * 3.6f, bottom = statusY - note.getTextSize() * 1.8f;
        float planH = Math.max(0, bottom - top), planW = planH * 0.45f;
        body.set(w / 2f - planW / 2, top + planH * 0.12f, w / 2f + planW / 2, bottom - planH * 0.12f);
        plan.setStrokeWidth(Math.max(1f, h * 0.003f));
        bar.setStrokeWidth(Math.max(2f, planH * 0.05f));
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

        float avail = w - 2 * pad - label.measureText(labels[2]) * 1.4f;
        for (int i = 0; i < labels.length; i++) {
            float y = rowsY + i * rowH;
            c.drawText(labels[i], pad, y, label);
            value.setColor(alert[i] ? RED : Chrome.TEXT);
            CharSequence v = TextUtils.ellipsize(values[i], value, avail, TextUtils.TruncateAt.END);
            c.drawText(v, 0, v.length(), w - pad, y, value);
        }
        if (radar != null && body.height() > 0) drawSensors(c);
        c.drawText(statusText, pad, statusY, note);
    }

    /** The car from above, nose up, with four bars ahead of it and four behind, coloured by how close things are. */
    private void drawSensors(Canvas c) {
        float r = body.width() * 0.3f;
        c.drawRoundRect(body, r, r, plan);
        float gap = bar.getStrokeWidth() * 1.6f;
        for (int k = 0; k < 4; k++) {
            float l = body.left + body.width() * k / 4f + gap / 2, rgt = body.left + body.width() * (k + 1) / 4f - gap / 2;
            int rear = radar[k], front = radar[4 + k];
            drawBar(c, l, rgt, body.top - gap * 1.6f, front);
            drawBar(c, l, rgt, body.bottom + gap * 1.6f, rear);
        }
    }

    private void drawBar(Canvas c, float l, float r, float y, int level) {
        // NWD's danger levels run 1 to 10, higher meaning closer.
        bar.setColor(level <= 0 ? 0x33FFFFFF : level <= 3 ? GREEN : level <= 7 ? AMBER : RED);
        c.drawLine(l, y, r, y, bar);
    }
}
