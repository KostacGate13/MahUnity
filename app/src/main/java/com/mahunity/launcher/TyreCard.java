package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Tyre pressures around a plan of the car, nose up: front left, front right, rear left, rear right. */
final class TyreCard extends InfoCard {
    private final Paint plan = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final String[] values = new String[4];
    private String noteText = "";
    private float valueY0, valueY1, leftX, rightX;

    TyreCard(Context context) {
        super(context, R.string.widget_tyres);
        plan.setStyle(Paint.Style.STROKE);
        plan.setColor(0x80B4BAC1);
        set(null, null);
    }

    /** bar, front left, front right, rear left, rear right; null: unknown, and hint says why (null: not yet). */
    void set(float[] bar, String hint) {
        String dash = getContext().getString(R.string.unknown);
        for (int i = 0; i < 4; i++) {
            values[i] = bar == null || bar[i] <= 0 ? dash : String.format(java.util.Locale.getDefault(), "%.1f", bar[i]);
        }
        noteText = bar != null ? getContext().getString(R.string.tyres_unit)
                : hint != null ? hint : getContext().getString(R.string.car_data_waiting);
        invalidate();
    }

    @Override protected void laidOut(int w, int h) {
        big.setTextSize(h * 0.15f);
        big.setTypeface(Chrome.LIGHT);
        float planH = h * 0.56f, planW = planH * 0.46f;
        float cy = h * 0.52f;
        body.set(w / 2f - planW / 2, cy - planH / 2, w / 2f + planW / 2, cy + planH / 2);
        plan.setStrokeWidth(Math.max(1f, h * 0.006f));
        valueY0 = body.top + planH * 0.22f + big.getTextSize() * 0.36f;
        valueY1 = body.bottom - planH * 0.18f + big.getTextSize() * 0.36f;
        leftX = body.left - planW * 0.35f;
        rightX = body.right + planW * 0.35f;
        big.setShader(Chrome.metal(valueY0 - big.getTextSize() * 0.71f, valueY1));
    }

    @Override protected void drawContent(Canvas c) {
        float r = body.width() * 0.32f;
        c.drawRoundRect(body, r, r, plan);
        big.setTextAlign(Paint.Align.RIGHT);
        c.drawText(values[0], leftX, valueY0, big);
        c.drawText(values[2], leftX, valueY1, big);
        big.setTextAlign(Paint.Align.LEFT);
        c.drawText(values[1], rightX, valueY0, big);
        c.drawText(values[3], rightX, valueY1, big);
        drawNote(c, noteText);
    }
}
