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

/**
 * A home tile: a card with a line icon, the name in large type and the app it opens below it. Sizes come from the
 * screen, not the tile, so a big tile gets the same readable type as a small one. Tall tiles stack icon over
 * text in the left column; wide ones put the icon beside the text.
 */
final class TileView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int iconRes;
    private final String title;
    private String subtitle = "";
    private CharSequence titleFit = "", subtitleFit = "";
    private Bitmap mask;
    private Shader metal;
    private float radius, iconX, iconY, textX, textWidth, labelY, noteY, labelSize;
    private int glowColor;
    private boolean sunk;

    TileView(Context context, int iconRes, String title) {
        super(context);
        this.iconRes = iconRes;
        this.title = title;
        setClickable(true);
        setLongClickable(true);
        setFocusable(true);
        setContentDescription(title);
        frame.setStyle(Paint.Style.STROKE);
        lit.setStyle(Paint.Style.STROKE);
        label.setTypeface(Chrome.MEDIUM);
        label.setColor(Chrome.TEXT);
        note.setTypeface(Chrome.REGULAR);
        note.setColor(Chrome.TEXT_DIM);
    }

    void setSubtitle(String s) {
        if (s == null) s = "";
        if (s.equals(subtitle)) return;
        subtitle = s;
        fit();
        invalidate();
    }

    private void fit() {
        // A long name is set up to a fifth smaller before it is cut short.
        label.setTextSize(labelSize);
        float wide = label.measureText(title);
        if (wide > textWidth && textWidth > 0) label.setTextSize(Math.max(labelSize * 0.8f, labelSize * textWidth / wide));
        titleFit = TextUtils.ellipsize(title, label, textWidth, TextUtils.TruncateAt.END);
        subtitleFit = TextUtils.ellipsize(subtitle, note, textWidth, TextUtils.TruncateAt.END);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float u = Chrome.unit(getContext());
        float stroke = Math.max(1f, u * 0.0022f);
        box.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
        radius = Chrome.radius(getContext());
        fill.setShader(Chrome.panel(0, h));
        frame.setStrokeWidth(stroke);
        frame.setShader(Chrome.edge(0, 0, w, h));
        lit.setStrokeWidth(stroke * 2f);

        float pad = u * 0.032f;
        // Type and icon from the screen, trimmed only when a tile is too small to hold them: by its height for a
        // wide row, by its short side otherwise.
        boolean wide = w > h * 1.3f;
        float scale = Math.min(1f, wide ? h / (u * 0.11f) : Math.min(w, h) / (u * 0.3f));
        int size = Math.round(Math.min(u * Chrome.ICON * scale, h * 0.6f));
        labelSize = u * Chrome.TYPE_HEADLINE * scale;
        label.setTextSize(labelSize);
        note.setTextSize(u * Chrome.TYPE_CAPTION * 1.15f * scale);
        if (wide) {
            iconX = pad;
            iconY = (h - size) / 2f;
            textX = iconX + size + pad * 0.8f;
            float block = label.getTextSize() * 0.75f + note.getTextSize() * 1.5f;
            labelY = h / 2f - block / 2f + label.getTextSize() * 0.75f;
        } else {
            iconX = pad;
            iconY = pad;
            textX = pad;
            labelY = h - pad - note.getTextSize() * 1.55f;
        }
        noteY = labelY + note.getTextSize() * 1.5f;
        textWidth = Math.max(0f, w - textX - pad * 0.7f);
        mask = Chrome.mask(getContext(), iconRes, size);
        metal = Chrome.metal(iconY, iconY + size);
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
        int w = getWidth(), h = getHeight();
        c.drawRoundRect(box, radius, radius, fill);
        if (on) {
            if (glowColor != Chrome.accent) {
                glowColor = Chrome.accent;
                glow.setShader(new RadialGradient(iconX, iconY, Math.max(w, h) * 0.9f,
                        Chrome.withAlpha(glowColor, 0x3A), 0, Shader.TileMode.CLAMP));
            }
            c.drawRoundRect(box, radius, radius, glow);
        }
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
        label.setColor(on ? Chrome.accent : Chrome.TEXT);
        c.drawText(titleFit, 0, titleFit.length(), textX, labelY, label);
        c.drawText(subtitleFit, 0, subtitleFit.length(), textX, noteY, note);
    }
}
