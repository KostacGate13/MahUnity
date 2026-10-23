package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/** A text button in a polished capsule, as wide as its label. Height comes from the parent. */
final class ChipButton extends View {
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String label = "";
    private boolean sunk;

    ChipButton(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        text.setTypeface(Chrome.MEDIUM);
        text.setLetterSpacing(0.18f);
        text.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
    }

    void setLabel(String s) {
        label = Text.upper(s);
        setContentDescription(s);
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int h = MeasureSpec.getSize(heightSpec);
        text.setTextSize(h * 0.34f);
        int w = Math.round(text.measureText(label) + h * 1.2f);
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) w = Math.min(w, MeasureSpec.getSize(widthSpec));
        setMeasuredDimension(w, h);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float stroke = Math.max(1f, h * 0.025f);
        box.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
        ring.setStrokeWidth(stroke);
        ring.setShader(Chrome.edge(0, 0, w, h));
        fill.setShader(Chrome.panel(0, h));
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (sunk != isPressed()) {
            sunk = isPressed();
            Chrome.press(this, sunk);
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        boolean on = isPressed() || isFocused();
        float r = box.height() / 2;
        c.drawRoundRect(box, r, r, fill);
        c.drawRoundRect(box, r, r, ring);
        text.setColor(on ? Chrome.accent : Chrome.TEXT);
        c.drawText(label, getWidth() / 2f, getHeight() / 2f + text.getTextSize() * 0.36f, text);
    }
}
