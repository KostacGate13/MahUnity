package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** One app in the grid: its own icon, the name below, a polished frame only while pressed or focused. */
final class AppCell extends View {
    private final Apps apps;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private AppEntry entry;
    private CharSequence fit = "";
    private int iconSize, glowColor;
    private float iconX, iconY, textY, radius;
    private boolean sunk;

    AppCell(Context context, Apps apps) {
        super(context);
        this.apps = apps;
        text.setTypeface(Chrome.REGULAR);
        text.setTextAlign(Paint.Align.CENTER);
        frame.setStyle(Paint.Style.STROKE);
        placeholder.setColor(0x14FFFFFF);
    }

    void bind(AppEntry e) {
        if (e == entry) return;
        entry = e;
        setContentDescription(e.label);
        fitLabel();
        invalidate();
    }

    private void fitLabel() {
        if (entry != null) fit = TextUtils.ellipsize(entry.label, text, getWidth() * 0.9f, TextUtils.TruncateAt.END);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float inset = Math.max(2f, w * 0.05f);
        box.set(inset, inset * 0.5f, w - inset, h - inset * 0.5f);
        radius = h * 0.07f;
        iconSize = Math.round(Math.min(w * 0.56f, h * 0.46f));
        iconX = (w - iconSize) / 2f;
        iconY = h * 0.13f;
        text.setTextSize(h * 0.082f);
        textY = iconY + iconSize + h * 0.17f;
        fill.setShader(Chrome.panel(box.top, box.bottom));
        frame.setStrokeWidth(Math.max(1f, h * 0.006f));
        frame.setShader(Chrome.edge(box.left, box.top, box.right, box.bottom));
        glowColor = 0;
        fitLabel();
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
        if (on) {
            c.drawRoundRect(box, radius, radius, fill);
            if (glowColor != Chrome.accent) {
                glowColor = Chrome.accent;
                glow.setShader(new RadialGradient(getWidth() / 2f, iconY + iconSize / 2f, getWidth() * 0.7f,
                        Chrome.withAlpha(glowColor, 0x38), 0, Shader.TileMode.CLAMP));
            }
            c.drawRoundRect(box, radius, radius, glow);
            c.drawRoundRect(box, radius, radius, frame);
        }
        Bitmap icon = apps.icon(entry, iconSize, this);
        if (icon != null) {
            c.drawBitmap(icon, iconX, iconY, iconPaint);
        } else {
            c.drawCircle(getWidth() / 2f, iconY + iconSize / 2f, iconSize * 0.42f, placeholder);
        }
        text.setColor(on ? Chrome.accent : Chrome.TEXT);
        c.drawText(fit, 0, fit.length(), getWidth() / 2f, textY, text);
    }
}
