package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

/** The glass bar along the bottom of the cockpit: one item per tab or app, the open tab lit from above. */
final class DockBar extends ViewGroup {
    private final Paint glass = new Paint();
    private final Paint line = new Paint();
    private final Paint strip = new Paint();
    private int stripColor;

    DockBar(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    Item add(int iconRes, int titleRes) {
        Item item = new Item(getContext(), iconRes, getContext().getString(titleRes));
        addView(item);
        return item;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        int n = Math.max(1, getChildCount());
        int each = w / n;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(each, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = Math.max(1, getChildCount());
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(w * i / n, 0, w * (i + 1) / n, b - t);
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (Chrome.style == Chrome.GLASS) {
            glass.setShader(new LinearGradient(0, 0, 0, h, 0x24FFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP));
            line.setShader(Chrome.hairline(0, 0, w, 0, 0xB0));
        } else {
            glass.setShader(null);
            glass.setColor(0xFF121417);
            line.setShader(null);
            line.setColor(0xFF2A2D33);
        }
        stripColor = 0;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, glass);
        c.drawRect(0, 0, w, 1, line);
        // The ambient light runs along the bottom edge, as on the classic Home.
        if (stripColor != Chrome.accent) {
            stripColor = Chrome.accent;
            int clear = stripColor & 0x00FFFFFF;
            strip.setShader(new LinearGradient(w * 0.08f, 0, w * 0.92f, 0,
                    new int[] {clear, Chrome.withAlpha(stripColor, 0xC0), clear}, null, Shader.TileMode.CLAMP));
        }
        float y = h - Math.max(2f, h * 0.025f);
        c.drawRect(w * 0.08f, y, w * 0.92f, y + Math.max(1.5f, h * 0.014f), strip);
    }

    /** One entry: a line icon beside its name, in large type. Selected: icon and name in accent, a light above. */
    static final class Item extends View {
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int iconRes;
        private final String title;
        private Bitmap mask;
        private Shader metal;
        private float iconX, iconY, labelX, labelY;
        private int glowColor;
        private boolean selected, sunk;

        Item(Context context, int iconRes, String title) {
            super(context);
            this.iconRes = iconRes;
            this.title = title;
            setClickable(true);
            setLongClickable(true);
            setFocusable(true);
            setContentDescription(title);
            label.setTypeface(Chrome.MEDIUM);
            bar.setStrokeCap(Paint.Cap.ROUND);
        }

        void setChosen(boolean on) {
            if (on == selected) return;
            selected = on;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float u = Chrome.unit(getContext());
            int size = Math.round(Math.min(h * 0.42f, u * 0.052f));
            float text = Math.min(h * 0.3f, u * Chrome.TYPE_BODY * 1.08f);
            label.setTextSize(text);
            float gap = size * 0.4f;
            // Icon and name side by side, centred; a long name is set smaller to fit the item.
            float room = w * 0.92f - size - gap;
            float wide = label.measureText(title);
            if (wide > room) label.setTextSize(text * room / wide);
            float row = size + gap + label.measureText(title);
            iconX = (w - row) / 2f;
            iconY = (h - size) / 2f;
            labelX = iconX + size + gap;
            labelY = h / 2f + label.getTextSize() * 0.36f;
            mask = Chrome.mask(getContext(), iconRes, size);
            metal = Chrome.metal(iconY, iconY + size);
            bar.setStrokeWidth(Math.max(2f, h * 0.035f));
            glowColor = 0;
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
            boolean lit = selected || isPressed() || isFocused();
            int w = getWidth(), h = getHeight();
            if (lit) {
                if (glowColor != Chrome.accent) {
                    glowColor = Chrome.accent;
                    glow.setShader(new RadialGradient(w / 2f, 0, Math.max(w, h) * 0.75f,
                            Chrome.withAlpha(glowColor, 0x30), 0, Shader.TileMode.CLAMP));
                }
                c.drawRect(0, 0, w, h, glow);
                bar.setColor(Chrome.accent);
                c.drawLine(w * 0.32f, bar.getStrokeWidth(), w * 0.68f, bar.getStrokeWidth(), bar);
            }
            if (mask != null) {
                if (lit) {
                    icon.setShader(null);
                    icon.setColor(Chrome.accent);
                } else {
                    icon.setShader(metal);
                    icon.setColor(Color.WHITE);
                }
                c.drawBitmap(mask, iconX, iconY, icon);
            }
            label.setColor(selected ? Chrome.accent : lit ? Chrome.TEXT : Chrome.TEXT_DIM);
            c.drawText(title, labelX, labelY, label);
        }
    }
}
