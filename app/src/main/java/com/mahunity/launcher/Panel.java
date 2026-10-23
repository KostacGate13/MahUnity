package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

/** A full-screen sheet over Home (all apps, settings): a large title, a note on the right, a close key, one content view. */
final class Panel extends ViewGroup {
    final ChromeButton close;
    final ChipButton action;
    private View content;
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint notePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint background = new Paint();
    private final Paint backgroundGlow = new Paint();
    private final Paint line = new Paint();
    private String title = "", note = "";
    private int headerH, pad;
    private float titleY;

    Panel(Context context) {
        super(context);
        setWillNotDraw(false);
        // Swallows touches so nothing reaches Home underneath.
        setClickable(true);
        close = new ChromeButton(context, R.drawable.ic_close, R.string.close);
        action = new ChipButton(context);
        action.setVisibility(GONE);
        addView(close);
        addView(action);
        titlePaint.setTypeface(Chrome.MEDIUM);
        notePaint.setTypeface(Chrome.MEDIUM);
        notePaint.setColor(Chrome.TEXT_DIM);
        notePaint.setLetterSpacing(0.12f);
        notePaint.setTextAlign(Paint.Align.RIGHT);
    }

    void setContent(View v) {
        if (content != null) removeView(content);
        content = v;
        addView(v, 0);
    }

    void setTitle(String title, String note) {
        this.title = title == null ? "" : title;
        this.note = note == null ? "" : Text.upper(note);
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        float s = Math.min(h, w * 0.6f);
        pad = Math.round(s * 0.035f);
        headerH = Math.round(s * 0.13f);
        int cs = Math.round(headerH * 0.56f);
        close.measure(MeasureSpec.makeMeasureSpec(cs, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cs, MeasureSpec.EXACTLY));
        if (action.getVisibility() != GONE) {
            action.measure(MeasureSpec.makeMeasureSpec(w / 3, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(Math.round(headerH * 0.46f), MeasureSpec.EXACTLY));
        }
        if (content != null) {
            content.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h - headerH, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int cs = close.getMeasuredWidth();
        int cx = w - pad - cs, cy = (headerH - cs) / 2;
        close.layout(cx, cy, cx + cs, cy + cs);
        if (action.getVisibility() != GONE) {
            int aw = action.getMeasuredWidth(), ah = action.getMeasuredHeight();
            int ax = cx - Math.round(pad * 0.8f) - aw, ay = (headerH - ah) / 2;
            action.layout(ax, ay, ax + aw, ay + ah);
        }
        if (content != null) content.layout(0, headerH, w, h);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        Chrome.resetBackground(background);
        float u = Chrome.unit(getContext());
        float size = Math.min(headerH * 0.5f, u * Chrome.TYPE_TITLE);
        titlePaint.setTextSize(size);
        notePaint.setTextSize(u * Chrome.TYPE_CAPTION);
        titleY = headerH / 2f + size * 0.36f;
        titlePaint.setShader(Chrome.metal(titleY - size * 0.72f, titleY));
        line.setShader(Chrome.hairline(0, 0, w, 0, 0xC0));
    }

    @Override protected void onDraw(Canvas c) {
        Chrome.background(c, getWidth(), getHeight(), background, backgroundGlow);
        c.drawText(title, pad, titleY, titlePaint);
        if (!note.isEmpty()) {
            // Right-aligned, just left of the action chip or the close key.
            View left = action.getVisibility() != GONE ? action : close;
            c.drawText(note, left.getLeft() - pad, titleY, notePaint);
        }
        c.drawRect(0, headerH - 1, getWidth(), headerH, line);
    }
}
