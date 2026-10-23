package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * A Home widget in the media card's slot (Settings > Lower widget): the same black glass and polished frame,
 * a spaced-out caption, and what subclasses draw inside.
 */
abstract class InfoCard extends View {
    static final int RED = 0xFFFF4B45, AMBER = 0xFFFFB13B;

    protected final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    protected final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    protected final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    protected final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final String title;
    protected float pad, radius, captionY;

    InfoCard(Context context, int titleRes) {
        super(context);
        title = Text.upper(context.getString(titleRes));
        frame.setStyle(Paint.Style.STROKE);
        caption.setTypeface(Chrome.MEDIUM);
        caption.setColor(Chrome.TEXT_DIM);
        caption.setLetterSpacing(0.25f);
        big.setTypeface(Chrome.THIN);
        small.setTypeface(Chrome.LIGHT);
        small.setColor(Chrome.TEXT_DIM);
        small.setLetterSpacing(0.08f);
        note.setTypeface(Chrome.LIGHT);
        note.setColor(Chrome.TEXT_DIM);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float stroke = Math.max(1f, h * 0.006f);
        box.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
        radius = Math.min(w, h) * 0.07f;
        pad = h * 0.1f;
        fill.setShader(Chrome.panel(0, h));
        frame.setStrokeWidth(stroke);
        frame.setShader(Chrome.edge(0, 0, w, h));
        caption.setTextSize(h * 0.058f);
        captionY = pad + caption.getTextSize();
        note.setTextSize(h * 0.06f);
        laidOut(w, h);
    }

    /** Sizes and positions for the content; called when the card's size is known. */
    protected abstract void laidOut(int w, int h);

    protected abstract void drawContent(Canvas c);

    @Override protected void onDraw(Canvas c) {
        c.drawRoundRect(box, radius, radius, fill);
        c.drawRoundRect(box, radius, radius, frame);
        c.drawText(title, pad, captionY, caption);
        drawContent(c);
    }

    /** A dim line along the bottom of the card, cut to fit. */
    protected void drawNote(Canvas c, String text) {
        if (text == null || text.isEmpty()) return;
        CharSequence fit = TextUtils.ellipsize(text, note, getWidth() - 2 * pad, TextUtils.TruncateAt.END);
        c.drawText(fit, 0, fit.length(), pad, getHeight() - pad, note);
    }
}
