package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

/** The Settings list: rows stacked top to bottom, each a fixed share of the screen's height. */
final class SettingsRows extends ViewGroup {
    private int rowHeight;

    SettingsRows(Context context) {
        super(context);
    }

    Row add(int titleRes, View.OnClickListener onClick) {
        Row row = new Row(getContext(), getContext().getString(titleRes));
        if (onClick != null) row.setOnClickListener(onClick);
        else row.setClickable(false);
        addView(row);
        return row;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        rowHeight = Math.round(Chrome.unit(getContext()) * 0.1f);
        int spec = MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), spec);
        }
        setMeasuredDimension(w, rowHeight * getChildCount() + rowHeight / 2);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(0, i * rowHeight, r - l, (i + 1) * rowHeight);
        }
    }

    /** A setting: its name on the left, its value (and a colour swatch for the ambient light) on the right. */
    static final class Row extends View {
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint();
        private final Paint press = new Paint();
        private final Paint swatch = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String titleText;
        private String valueText = "";
        private int swatchColor;
        private boolean emphasis;
        private float pad, baseline;

        Row(Context context, String titleText) {
            super(context);
            this.titleText = titleText;
            setFocusable(true);
            setContentDescription(titleText);
            title.setTypeface(Chrome.REGULAR);
            title.setColor(Chrome.TEXT);
            value.setTypeface(Chrome.LIGHT);
            value.setTextAlign(Paint.Align.RIGHT);
        }

        /** emphasis: the value is drawn in accent light (an action to take, or a state that is on). */
        void setValue(String text, boolean emphasis, int swatchColor) {
            valueText = text == null ? "" : text;
            this.emphasis = emphasis;
            this.swatchColor = swatchColor;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            pad = Chrome.unit(getContext()) * 0.035f;
            title.setTextSize(h * 0.3f);
            value.setTextSize(h * 0.27f);
            baseline = h / 2f + title.getTextSize() * 0.36f;
            line.setShader(Chrome.hairline(0, 0, w, 0, 0x70));
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (isPressed() || isFocused()) {
                press.setColor(Chrome.withAlpha(Chrome.accent, 0x1C));
                c.drawRect(0, 0, w, h, press);
            }
            c.drawText(titleText, pad, baseline, title);
            value.setColor(emphasis ? Chrome.accent : Chrome.TEXT_DIM);
            c.drawText(valueText, w - pad, baseline, value);
            if (swatchColor != 0) {
                float r = h * 0.11f;
                float x = w - pad - value.measureText(valueText) - r * 2.2f;
                swatch.setColor(swatchColor);
                c.drawCircle(x, h / 2f, r, swatch);
            }
            c.drawRect(0, h - 1, w, h, line);
        }
    }
}
