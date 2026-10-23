package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

/** Now playing: album art, the source, title and artist, three media keys and a thin progress line in accent light. */
final class MediaCard extends ViewGroup {
    final ChromeButton prev, play, next;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artFrame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint track = new Paint();
    private final Paint progress = new Paint();
    private final TextPaint source = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint artist = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF artBox = new RectF();
    private final Matrix artMatrix = new Matrix();

    private String sourceText = "", titleText = "", artistText = "";
    private CharSequence sourceFit = "", titleFit = "", artistFit = "";
    private Bitmap art, noteMask;
    private boolean playing;
    private long position = -1, duration, updated;
    private float rate;

    private float radius, artRadius, pad, textX, textWidth, sourceY, titleY, artistY;
    private float trackLeft, trackRight, trackY, trackH;
    private int keySize, playSize;
    /** A long, low card (portrait screens): one row with the keys at the right end. Otherwise keys in a row below. */
    private boolean wide;

    MediaCard(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(true);
        prev = new ChromeButton(context, R.drawable.ic_prev, R.string.media_prev);
        play = new ChromeButton(context, R.drawable.ic_play, R.string.media_play);
        next = new ChromeButton(context, R.drawable.ic_next, R.string.media_next);
        addView(prev);
        addView(play);
        addView(next);
        frame.setStyle(Paint.Style.STROKE);
        artFrame.setStyle(Paint.Style.STROKE);
        source.setTypeface(Chrome.MEDIUM);
        source.setColor(Chrome.TEXT_DIM);
        source.setLetterSpacing(0.25f);
        title.setTypeface(Chrome.REGULAR);
        title.setColor(Chrome.TEXT);
        artist.setTypeface(Chrome.LIGHT);
        artist.setColor(Chrome.TEXT_DIM);
        track.setColor(0x26FFFFFF);
    }

    /** position -1: unknown, no progress line. updated is the elapsedRealtime the position was taken at. */
    void show(String sourceText, String titleText, String artistText, Bitmap art, boolean playing,
              long position, long duration, float rate, long updated) {
        this.sourceText = sourceText == null ? "" : sourceText;
        this.titleText = titleText == null ? "" : titleText;
        this.artistText = artistText == null ? "" : artistText;
        this.playing = playing;
        this.position = position;
        this.duration = duration;
        this.rate = rate;
        this.updated = updated;
        if (art != this.art) {
            this.art = art;
            artPaint.setShader(art == null ? null : new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            fitArt();
        }
        fit();
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        wide = w > h * 2.6f;
        pad = h * (wide ? 0.13f : 0.09f);
        float art = wide ? h - 2 * pad : h * 0.42f;
        artBox.set(pad, pad, pad + art, pad + art);
        keySize = Math.round(h * (wide ? 0.4f : 0.25f));
        playSize = Math.round(keySize * 1.18f);
        textX = artBox.right + pad * 0.9f;
        float right = w - pad;
        if (wide) right -= 2 * keySize + playSize + 2 * gap() + pad;
        textWidth = Math.max(0f, right - textX);
        int key = MeasureSpec.makeMeasureSpec(keySize, MeasureSpec.EXACTLY);
        int big = MeasureSpec.makeMeasureSpec(playSize, MeasureSpec.EXACTLY);
        prev.measure(key, key);
        play.measure(big, big);
        next.measure(key, key);
        setMeasuredDimension(w, h);
    }

    private int gap() {
        return Math.round(keySize * 0.45f);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int row = 2 * keySize + playSize + 2 * gap();
        // The keys: at the right end of a wide card, otherwise centred in the space under the art.
        int x = wide ? Math.round(w - pad) - row : (w - row) / 2;
        int cy = wide ? h / 2 : Math.round((artBox.bottom + h - pad) / 2f);
        prev.layout(x, cy - keySize / 2, x + keySize, cy - keySize / 2 + keySize);
        x += keySize + gap();
        play.layout(x, cy - playSize / 2, x + playSize, cy - playSize / 2 + playSize);
        x += playSize + gap();
        next.layout(x, cy - keySize / 2, x + keySize, cy - keySize / 2 + keySize);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float stroke = Math.max(1f, h * 0.006f);
        box.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
        radius = Math.min(h, w) * 0.07f;
        artRadius = radius * 0.6f;
        fill.setShader(Chrome.panel(0, h));
        frame.setStrokeWidth(stroke);
        frame.setShader(Chrome.edge(0, 0, w, h));
        artFill.setShader(Chrome.panel(artBox.top, artBox.bottom));
        artFrame.setStrokeWidth(stroke);
        artFrame.setShader(Chrome.edge(artBox.left, artBox.top, artBox.right, artBox.bottom));
        int noteSize = Math.round(artBox.width() * 0.42f);
        noteMask = Chrome.mask(getContext(), R.drawable.ic_music, noteSize);
        note.setShader(Chrome.metal(artBox.centerY() - noteSize / 2f, artBox.centerY() + noteSize / 2f));
        note.setColor(Color.WHITE);

        // Source, title and artist from the top of the art; the progress line along its bottom.
        float t = artBox.height();
        source.setTextSize(t * 0.13f);
        title.setTextSize(t * 0.22f);
        artist.setTextSize(t * 0.17f);
        sourceY = artBox.top + source.getTextSize() * 0.9f;
        titleY = sourceY + title.getTextSize() * 1.4f;
        artistY = titleY + artist.getTextSize() * 1.45f;
        trackH = Math.max(1.5f, h * 0.008f);
        trackLeft = textX;
        trackRight = textX + textWidth;
        trackY = artBox.bottom - trackH;
        fitArt();
        fit();
    }

    private void fit() {
        sourceFit = TextUtils.ellipsize(sourceText, source, textWidth, TextUtils.TruncateAt.END);
        titleFit = TextUtils.ellipsize(titleText, title, textWidth, TextUtils.TruncateAt.END);
        artistFit = TextUtils.ellipsize(artistText, artist, textWidth, TextUtils.TruncateAt.END);
    }

    /** Centre-crops the album art into its square. */
    private void fitArt() {
        if (art == null || artBox.isEmpty()) return;
        float scale = Math.max(artBox.width() / art.getWidth(), artBox.height() / art.getHeight());
        artMatrix.setScale(scale, scale);
        artMatrix.postTranslate(artBox.centerX() - art.getWidth() * scale / 2f, artBox.centerY() - art.getHeight() * scale / 2f);
        artPaint.getShader().setLocalMatrix(artMatrix);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawRoundRect(box, radius, radius, fill);
        c.drawRoundRect(box, radius, radius, frame);

        if (art != null) {
            c.drawRoundRect(artBox, artRadius, artRadius, artPaint);
        } else {
            c.drawRoundRect(artBox, artRadius, artRadius, artFill);
            if (noteMask != null) {
                c.drawBitmap(noteMask, artBox.centerX() - noteMask.getWidth() / 2f,
                        artBox.centerY() - noteMask.getHeight() / 2f, note);
            }
        }
        c.drawRoundRect(artBox, artRadius, artRadius, artFrame);

        c.drawText(sourceFit, 0, sourceFit.length(), textX, sourceY, source);
        c.drawText(titleFit, 0, titleFit.length(), textX, titleY, title);
        c.drawText(artistFit, 0, artistFit.length(), textX, artistY, artist);

        if (duration > 0 && position >= 0) {
            long at = position;
            if (playing) at += (long) ((SystemClock.elapsedRealtime() - updated) * rate);
            float f = Math.max(0f, Math.min(1f, at / (float) duration));
            c.drawRect(trackLeft, trackY, trackRight, trackY + trackH, track);
            progress.setColor(Chrome.accent);
            c.drawRect(trackLeft, trackY, trackLeft + (trackRight - trackLeft) * f, trackY + trackH, progress);
            if (playing) postInvalidateDelayed(1000);
        }
    }
}
