package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** A round control inside a polished ring: the settings key, the media keys, close. */
final class ChromeButton extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int iconRes;
    private Bitmap mask;
    private Shader metal;
    private float cx, cy, radius, iconX, iconY;
    private int glowColor;
    private boolean active, sunk;

    ChromeButton(Context context, int iconRes, int descriptionRes) {
        super(context);
        this.iconRes = iconRes;
        setClickable(true);
        setFocusable(true);
        setContentDescription(context.getString(descriptionRes));
        ring.setStyle(Paint.Style.STROKE);
        lit.setStyle(Paint.Style.STROKE);
    }

    void setIcon(int res) {
        if (res == iconRes) return;
        iconRes = res;
        buildMask();
        invalidate();
    }

    /** Active: lit by the ambient accent while it is not pressed, as the play key is while music plays. */
    void setActive(boolean on) {
        if (on == active) return;
        active = on;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float s = Math.min(w, h);
        float stroke = Math.max(1f, s * 0.02f);
        cx = w / 2f;
        cy = h / 2f;
        radius = s / 2f - stroke;
        ring.setStrokeWidth(stroke);
        ring.setShader(Chrome.edge(cx - radius, cy - radius, cx + radius, cy + radius));
        lit.setStrokeWidth(stroke * 1.4f);
        fill.setShader(Chrome.panel(cy - radius, cy + radius));
        glowColor = 0;
        buildMask();
    }

    private void buildMask() {
        int size = Math.round(Math.min(getWidth(), getHeight()) * 0.42f);
        mask = Chrome.mask(getContext(), iconRes, size);
        iconX = cx - size / 2f;
        iconY = cy - size / 2f;
        metal = Chrome.metal(iconY, iconY + size);
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
        c.drawCircle(cx, cy, radius, fill);
        if (on || active) {
            int color = Chrome.withAlpha(Chrome.accent, on ? 0x55 : 0x2A);
            if (color != glowColor) {
                glowColor = color;
                glow.setShader(new RadialGradient(cx, cy, radius, color, 0, Shader.TileMode.CLAMP));
            }
            c.drawCircle(cx, cy, radius, glow);
        }
        c.drawCircle(cx, cy, radius, ring);
        if (on || active) {
            lit.setColor(Chrome.withAlpha(Chrome.accent, on ? 0xD0 : 0x90));
            c.drawCircle(cx, cy, radius, lit);
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
    }
}
