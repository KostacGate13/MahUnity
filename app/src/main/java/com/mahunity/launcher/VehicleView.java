package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.Locale;

/**
 * The Vehicle tab: four cards in a grid (temperature outside, battery, coolant, fuel with its share of the tank)
 * over a card with the tyre pressures around a plan of the car. A value the car has not sent is a dash; the
 * line at the bottom says why, or how the CANBus link is doing.
 */
final class VehicleView extends View {
    private static final int CARDS = 5, TYRES = 4;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plan = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint level = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint side = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pressure = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] cards = new RectF[CARDS];
    private final Shader[] fills = new Shader[CARDS], frames = new Shader[CARDS];
    private final RectF body = new RectF(), bar = new RectF();
    private final String[] labels;
    private final String[] values = new String[4];
    private final String[] tyres = new String[4];
    private final String dash;
    private String fuelShare = "", unitText = "kPa", noteText = "";
    private float fuelFraction = -1;
    private float radius, pad, captionDy, valueDy, tyreY0, tyreY1, tyreLeft, tyreRight;

    VehicleView(Context context) {
        super(context);
        dash = context.getString(R.string.unknown);
        labels = new String[] {
            Text.upper(context.getString(R.string.veh_outside)), Text.upper(context.getString(R.string.veh_battery)),
            Text.upper(context.getString(R.string.veh_coolant)), Text.upper(context.getString(R.string.veh_fuel)),
            Text.upper(context.getString(R.string.veh_tyres)),
        };
        for (int i = 0; i < CARDS; i++) cards[i] = new RectF();
        for (int i = 0; i < 4; i++) values[i] = tyres[i] = dash;
        frame.setStyle(Paint.Style.STROKE);
        plan.setStyle(Paint.Style.STROKE);
        plan.setColor(0x80B4BAC1);
        track.setColor(0x26FFFFFF);
        caption.setTypeface(Chrome.MEDIUM);
        caption.setColor(Chrome.TEXT_DIM);
        caption.setLetterSpacing(0.16f);
        value.setTypeface(Chrome.LIGHT);
        side.setTypeface(Chrome.REGULAR);
        side.setColor(Chrome.TEXT_DIM);
        side.setTextAlign(Paint.Align.RIGHT);
        pressure.setTypeface(Chrome.LIGHT);
        note.setTypeface(Chrome.REGULAR);
        note.setColor(Chrome.TEXT_DIM);
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
        if (can.fuel >= 0) {
            values[3] = (can.fuel == Math.floor(can.fuel) ? String.valueOf((int) can.fuel) : String.format(l, "%.1f", can.fuel)) + " L";
            fuelFraction = Math.max(0f, Math.min(1f, can.fuel / Math.max(1f, capacity)));
            fuelShare = Math.round(100 * fuelFraction) + "%";
        } else {
            values[3] = dash;
            fuelFraction = -1;
            fuelShare = "";
        }
        for (int i = 0; i < 4; i++) {
            float p = can.tyres == null ? -1 : can.tyres[i];
            tyres[i] = p <= 0 ? dash : TyreCard.format(p, can.tyresPsi);
        }
        unitText = can.tyresPsi ? "psi" : "kPa";
        noteText = hint != null ? hint
                : can.state == CanBus.MISSING ? c.getString(R.string.can_missing)
                : can.packets == 0 ? c.getString(R.string.can_waiting)
                : c.getString(R.string.can_live, can.packets);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float u = Chrome.unit(getContext());
        float gap = u * 0.024f;
        radius = Chrome.radius(getContext());
        pad = u * 0.028f;
        // The grid takes a little over half the height; the tyres the rest.
        float gridH = (h - gap) * 0.52f;
        float cw = (w - gap) / 2f, ch = (gridH - gap) / 2f;
        for (int i = 0; i < 4; i++) {
            float x = (i % 2) * (cw + gap), y = (i / 2) * (ch + gap);
            cards[i].set(x, y, x + cw, y + ch);
        }
        cards[TYRES].set(0, gridH + gap, w, h);

        float stroke = Math.max(1f, u * 0.0022f);
        frame.setStrokeWidth(stroke);
        plan.setStrokeWidth(stroke * 1.4f);
        for (int i = 0; i < CARDS; i++) {
            RectF r = cards[i];
            r.inset(stroke / 2, stroke / 2);
            fills[i] = Chrome.panel(r.top, r.bottom);
            frames[i] = Chrome.edge(r.left, r.top, r.right, r.bottom);
        }

        caption.setTextSize(Math.min(ch * 0.17f, u * Chrome.TYPE_CAPTION));
        value.setTextSize(Math.min(ch * 0.42f, u * Chrome.TYPE_TITLE * 0.95f));
        side.setTextSize(Math.min(ch * 0.22f, u * Chrome.TYPE_BODY));
        captionDy = pad * 0.8f + caption.getTextSize();
        valueDy = ch - pad * 0.9f;
        value.setShader(Chrome.metal(-value.getTextSize() * 0.71f, 0));

        // Tyres: a plan of the car, nose up, in the middle; front pressures beside its top, rear beside its bottom.
        RectF t = cards[TYRES];
        float noteRoom = u * Chrome.TYPE_CAPTION * 1.9f;
        float planH = Math.min((t.height() - captionDy - noteRoom) * 0.92f, t.width() * 0.6f);
        float planW = planH * 0.46f;
        float cy = t.top + captionDy + (t.height() - captionDy - noteRoom) / 2f;
        body.set(t.centerX() - planW / 2, cy - planH / 2, t.centerX() + planW / 2, cy + planH / 2);
        pressure.setTextSize(Math.min(planH * 0.26f, u * Chrome.TYPE_TITLE * 0.85f));
        pressure.setShader(Chrome.metal(body.top, body.bottom));
        tyreY0 = body.top + planH * 0.22f + pressure.getTextSize() * 0.36f;
        tyreY1 = body.bottom - planH * 0.18f + pressure.getTextSize() * 0.36f;
        tyreLeft = body.left - planW * 0.45f;
        tyreRight = body.right + planW * 0.45f;
        note.setTextSize(u * Chrome.TYPE_CAPTION);
        bar.set(0, 0, 0, Math.max(2f, ch * 0.04f));
    }

    @Override protected void onDraw(Canvas c) {
        for (int i = 0; i < CARDS; i++) {
            RectF r = cards[i];
            fill.setShader(fills[i]);
            frame.setShader(frames[i]);
            c.drawRoundRect(r, radius, radius, fill);
            c.drawRoundRect(r, radius, radius, frame);
            c.drawText(labels[i], r.left + pad, r.top + captionDy, caption);
        }
        for (int i = 0; i < 4; i++) {
            RectF r = cards[i];
            float y = r.top + valueDy;
            c.save();
            c.translate(r.left + pad, y);
            c.drawText(values[i], -value.getTextSize() * 0.02f, 0, value);
            c.restore();
            if (i == 3 && fuelFraction >= 0) {
                // The share of the tank beside the litres, and a bar between the caption and the litres.
                c.drawText(fuelShare, r.right - pad, y, side);
                float h = bar.height();
                float top = (r.top + captionDy + r.top + valueDy - value.getTextSize() * 0.75f) / 2f - h / 2f;
                bar.set(r.left + pad, top, r.right - pad, top + h);
                float rr = bar.height() / 2;
                c.drawRoundRect(bar, rr, rr, track);
                level.setColor(fuelFraction < 0.125f ? InfoCard.RED : fuelFraction < 0.25f ? InfoCard.AMBER : Chrome.accent);
                bar.right = bar.left + (bar.right - bar.left) * fuelFraction;
                c.drawRoundRect(bar, rr, rr, level);
            }
        }

        RectF t = cards[TYRES];
        c.drawText(unitText, t.right - pad, t.top + captionDy, side);
        float pr = body.width() * 0.32f;
        c.drawRoundRect(body, pr, pr, plan);
        pressure.setTextAlign(Paint.Align.RIGHT);
        c.drawText(tyres[0], tyreLeft, tyreY0, pressure);
        c.drawText(tyres[2], tyreLeft, tyreY1, pressure);
        pressure.setTextAlign(Paint.Align.LEFT);
        c.drawText(tyres[1], tyreRight, tyreY0, pressure);
        c.drawText(tyres[3], tyreRight, tyreY1, pressure);
        if (!noteText.isEmpty()) {
            CharSequence fit = TextUtils.ellipsize(noteText, note, t.width() - 2 * pad, TextUtils.TruncateAt.END);
            c.drawText(fit, 0, fit.length(), t.left + pad, t.bottom - pad * 0.8f, note);
        }
    }
}
