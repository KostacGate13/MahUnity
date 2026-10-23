package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;

/** The Vehicle tab: GPS speed and heading in large metal digits, and the place where CANBus data will go. */
final class VehicleView extends View {
    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint digits = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint unit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final String captionText, unitText, canText;
    private String speedText = "–", headingText = "", noteText = "";
    private float pad, captionY, digitsY, lineY, noteY, canY;

    VehicleView(Context context) {
        super(context);
        captionText = Text.upper(context.getString(R.string.set_speed));
        unitText = context.getString(R.string.kmh);
        canText = context.getString(R.string.can_pending);
        caption.setTypeface(Chrome.MEDIUM);
        caption.setColor(Chrome.TEXT_DIM);
        caption.setLetterSpacing(0.28f);
        digits.setTypeface(Chrome.THIN);
        unit.setTypeface(Chrome.LIGHT);
        unit.setColor(Chrome.TEXT_DIM);
        unit.setLetterSpacing(0.2f);
        note.setTypeface(Chrome.LIGHT);
        note.setColor(Chrome.TEXT_DIM);
    }

    /** value null: speed is switched off in Settings. */
    void setSpeed(String value, String heading) {
        speedText = value == null ? "–" : value;
        headingText = heading == null ? "" : "·  " + heading;
        noteText = value == null ? getContext().getString(R.string.speed_off_hint) : "";
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        pad = Math.min(w, h) * 0.08f;
        caption.setTextSize(h * 0.045f);
        float size = Math.min(h * 0.34f, w * 0.38f);
        digits.setTextSize(size);
        unit.setTextSize(size * 0.16f);
        note.setTextSize(h * 0.045f);
        captionY = pad + caption.getTextSize();
        digitsY = captionY + size * 0.2f + size * 0.71f;
        noteY = digitsY + note.getTextSize() * 2f;
        lineY = noteY + h * 0.08f;
        canY = lineY + h * 0.09f;
        digits.setShader(Chrome.metal(digitsY - size * 0.71f, digitsY));
        line.setShader(Chrome.fadeRight(pad, w - pad, 0x90));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawText(captionText, pad, captionY, caption);
        c.drawText(speedText, pad - digits.getTextSize() * 0.03f, digitsY, digits);
        float x = pad + digits.measureText(speedText) + unit.getTextSize() * 0.6f;
        c.drawText(unitText, x, digitsY, unit);
        if (!headingText.isEmpty()) {
            c.drawText(headingText, x + unit.measureText(unitText) + unit.getTextSize() * 0.9f, digitsY, unit);
        }
        if (!noteText.isEmpty()) c.drawText(noteText, pad, noteY, note);
        c.drawRect(pad, lineY, getWidth() - pad, lineY + 1, line);
        c.drawText(canText, pad, canY, note);
    }
}
