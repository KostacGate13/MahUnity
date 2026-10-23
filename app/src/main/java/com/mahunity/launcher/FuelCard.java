package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Fuel left: the share of the tank in large digits, the litres beside it, and a bar of ten segments. */
final class FuelCard extends InfoCard {
    private final Paint segment = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private float litres = -1, capacity = 45;
    private String percentText = "", litresText = "", noteText = "";
    private float bigY, litresY, barTop, barH;

    FuelCard(Context context) {
        super(context, R.string.widget_fuel);
    }

    /** litres below zero: unknown. */
    void set(float litres, float capacity) {
        this.litres = litres;
        this.capacity = capacity;
        Context c = getContext();
        if (litres < 0) {
            percentText = c.getString(R.string.unknown);
            litresText = "";
            noteText = c.getString(R.string.fuel_unknown);
        } else {
            percentText = Math.round(100 * fraction()) + "%";
            litresText = c.getString(R.string.fuel_litres, format(litres), format(capacity));
            noteText = c.getString(R.string.fuel_left);
        }
        invalidate();
    }

    private float fraction() {
        return capacity <= 0 ? 0 : Math.max(0f, Math.min(1f, litres / capacity));
    }

    private static String format(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format(java.util.Locale.getDefault(), "%.1f", v);
    }

    @Override protected void laidOut(int w, int h) {
        big.setTextSize(h * 0.3f);
        small.setTextSize(h * 0.075f);
        bigY = captionY + h * 0.06f + big.getTextSize() * 0.71f;
        litresY = bigY;
        barH = h * 0.07f;
        barTop = bigY + h * 0.09f;
        big.setShader(Chrome.metal(bigY - big.getTextSize() * 0.71f, bigY));
    }

    @Override protected void drawContent(Canvas c) {
        int w = getWidth();
        c.drawText(percentText, pad - big.getTextSize() * 0.03f, bigY, big);
        if (!litresText.isEmpty()) {
            small.setTextAlign(Paint.Align.RIGHT);
            c.drawText(litresText, w - pad, litresY, small);
            small.setTextAlign(Paint.Align.LEFT);
        }
        // Ten segments; lit ones in accent light, amber below a quarter, red below an eighth.
        float f = litres < 0 ? 0 : fraction();
        int lit = Math.round(f * 10);
        int color = f < 0.125f ? RED : f < 0.25f ? AMBER : Chrome.accent;
        float gap = barH * 0.35f, cw = (w - 2 * pad - 9 * gap) / 10f;
        for (int i = 0; i < 10; i++) {
            float l = pad + i * (cw + gap);
            cell.set(l, barTop, l + cw, barTop + barH);
            segment.setColor(i < lit ? color : 0x1FFFFFFF);
            c.drawRoundRect(cell, barH / 2, barH / 2, segment);
        }
        drawNote(c, noteText);
    }
}
