package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** A home tile: black glass in a polished frame, a line icon in metal, the name spaced out, the app it opens below. */
final class TileView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint();
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int iconRes;
    private final String title;
    private String subtitle = "";
    private CharSequence subtitleFit = "";
    private Bitmap mask;
    private Shader metal;
    private float radius, iconX, iconY, iconSize, labelY, noteY, dashY, dashHalf, sheenY;
    private int glowColor;
    private boolean sunk;

    TileView(Context context, int iconRes, String title) {
        super(context);
        this.iconRes = iconRes;
        this.title = Text.upper(title);
        setClickable(true);
        setLongClickable(true);
        setFocusable(true);
        setContentDescription(title);
        frame.setStyle(Paint.Style.STROKE);
        lit.setStyle(Paint.Style.STROKE);
        label.setTypeface(Chrome.MEDIUM);
        label.setColor(Chrome.TEXT);
        label.setLetterSpacing(0.16f);
        label.setTextAlign(Paint.Align.CENTER);
        note.setTypeface(Chrome.LIGHT);
        note.setColor(Chrome.TEXT_DIM);
        note.setTextAlign(Paint.Align.CENTER);
        dash.setStrokeCap(Paint.Cap.ROUND);
    }

    void setSubtitle(String s) {
        if (s == null) s = "";
        if (s.equals(subtitle)) return;
        subtitle = s;
        fit();
        invalidate();
    }

    private void fit() {
        subtitleFit = TextUtils.ellipsize(subtitle, note, getWidth() * 0.84f, TextUtils.TruncateAt.END);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float s = Math.min(w, h);
        float stroke = Math.max(1f, s * 0.006f);
        box.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
        radius = s * 0.05f;
        fill.setShader(Chrome.panel(0, h));
        frame.setStrokeWidth(stroke);
        frame.setShader(Chrome.edge(0, 0, w, h));
        lit.setStrokeWidth(stroke * 1.6f);
        sheenY = stroke * 2.5f;
        sheen.setShader(Chrome.hairline(radius, 0, w - radius, 0, 0x55));

        int size = Math.round(s * 0.30f);
        iconSize = size;
        iconX = (w - size) / 2f;
        iconY = h * 0.40f - size / 2f;
        mask = Chrome.mask(getContext(), iconRes, size);
        metal = Chrome.metal(iconY, iconY + size);

        float labelSize = s * 0.072f;
        label.setTextSize(labelSize);
        float wide = label.measureText(title);
        if (wide > w * 0.9f) label.setTextSize(labelSize * w * 0.9f / wide);
        note.setTextSize(s * 0.056f);
        labelY = h * 0.73f;
        noteY = labelY + s * 0.085f;
        dashY = h - s * 0.075f;
        dashHalf = s * 0.08f;
        dash.setStrokeWidth(Math.max(1.5f, s * 0.009f));
        glowColor = 0;
        fit();
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
        int w = getWidth();
        c.drawRoundRect(box, radius, radius, fill);
        if (on) {
            if (glowColor != Chrome.accent) {
                glowColor = Chrome.accent;
                glow.setShader(new RadialGradient(w / 2f, iconY + iconSize / 2f, Math.max(w, getHeight()) * 0.6f,
                        Chrome.withAlpha(glowColor, 0x40), 0, Shader.TileMode.CLAMP));
            }
            c.drawRoundRect(box, radius, radius, glow);
        }
        c.drawRect(radius, sheenY, w - radius, sheenY + 1, sheen);
        c.drawRoundRect(box, radius, radius, frame);
        if (on) {
            lit.setColor(Chrome.withAlpha(Chrome.accent, 0xC0));
            c.drawRoundRect(box, radius, radius, lit);
        }
        if (mask != null) {
            if (on) {
                icon.setShader(null);
                icon.setColor(Chrome.accent);
            } else {
                icon.setShader(metal);
                icon.setColor(Color.WHITE);
            }
            c.drawBitmap(mask, iconX, iconY, icon);
        }
        c.drawText(title, w / 2f, labelY, label);
        c.drawText(subtitleFit, 0, subtitleFit.length(), w / 2f, noteY, note);
        dash.setColor(Chrome.withAlpha(Chrome.accent, on ? 0xFF : 0x66));
        c.drawLine(w / 2f - dashHalf, dashY, w / 2f + dashHalf, dashY, dash);
    }
}
