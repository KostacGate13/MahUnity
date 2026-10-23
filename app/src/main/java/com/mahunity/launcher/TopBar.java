package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;

/** The strip across the top: a name in spaced-out metal letters over a full-width hairline. */
final class TopBar extends View {
    private final TextPaint mark = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private String text = "";
    private float baseline;
    private int inset;

    TopBar(Context context) {
        super(context);
        mark.setTypeface(Chrome.LIGHT);
        mark.setLetterSpacing(0.42f);
    }

    void setText(String s) {
        text = Text.upper(s);
        invalidate();
    }

    /** Left margin of the name, set by HomeLayout to line up with the panels below. */
    void setInset(int px) {
        if (px == inset) return;
        inset = px;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float size = h * 0.28f;
        mark.setTextSize(size);
        baseline = h / 2f + size * 0.36f;
        mark.setShader(Chrome.metal(baseline - size * 0.72f, baseline));
        line.setShader(Chrome.hairline(0, 0, w, 0, 0xC0));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawText(text, inset, baseline, mark);
        int h = getHeight();
        c.drawRect(0, h - 1, getWidth(), h, line);
    }
}
