package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** The line under the top bar: where you are in large type on the left ("Μουσική"), a small note on the right. */
final class Header extends View {
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String titleText = "", noteText = "";
    private CharSequence titleFit = "";
    private int inset;
    private float baseline;

    Header(Context context) {
        super(context);
        title.setTypeface(Chrome.MEDIUM);
        title.setColor(Chrome.TEXT);
        note.setTypeface(Chrome.MEDIUM);
        note.setColor(Chrome.TEXT_DIM);
        note.setLetterSpacing(0.12f);
        note.setTextAlign(Paint.Align.RIGHT);
    }

    void set(String titleText, String noteText) {
        titleText = titleText == null ? "" : titleText;
        noteText = noteText == null ? "" : Text.upper(noteText);
        if (titleText.equals(this.titleText) && noteText.equals(this.noteText)) return;
        this.titleText = titleText;
        this.noteText = noteText;
        fit();
        invalidate();
    }

    /** Side margins, set by the layout to line up with the cards below. */
    void setInset(int px) {
        if (px == inset) return;
        inset = px;
        fit();
        invalidate();
    }

    private void fit() {
        float room = getWidth() - 2 * inset - note.measureText(noteText) - note.getTextSize() * 2;
        titleFit = TextUtils.ellipsize(titleText, title, Math.max(0f, room), TextUtils.TruncateAt.END);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float u = Chrome.unit(getContext());
        title.setTextSize(Math.min(h * 0.62f, u * Chrome.TYPE_TITLE));
        note.setTextSize(u * Chrome.TYPE_CAPTION);
        baseline = h * 0.5f + title.getTextSize() * 0.36f;
        title.setShader(Chrome.metal(baseline - title.getTextSize() * 0.72f, baseline));
        fit();
    }

    @Override protected void onDraw(Canvas c) {
        c.drawText(titleFit, 0, titleFit.length(), inset, baseline, title);
        c.drawText(noteText, getWidth() - inset, baseline, note);
    }
}
