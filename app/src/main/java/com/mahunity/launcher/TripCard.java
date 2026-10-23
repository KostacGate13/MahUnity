package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Canvas;

import java.util.Locale;

/** The trip so far: distance, time and average speed in three columns. */
final class TripCard extends InfoCard {
    private final String[] values = new String[3];
    private final String[] labels;
    private String noteText = "";
    private float valueY, labelY;

    TripCard(Context context) {
        super(context, R.string.widget_trip);
        labels = new String[] {
            Text.upper(context.getString(R.string.trip_distance)), Text.upper(context.getString(R.string.trip_time)),
            Text.upper(context.getString(R.string.trip_average)),
        };
        set(null);
    }

    void set(Trip trip) {
        String dash = getContext().getString(R.string.unknown);
        if (trip == null || !trip.started()) {
            values[0] = values[1] = values[2] = dash;
            noteText = getContext().getString(R.string.trip_waiting);
        } else {
            values[0] = String.format(Locale.getDefault(), "%.1f", trip.km());
            long m = trip.minutes();
            values[1] = String.format(Locale.getDefault(), "%d:%02d", m / 60, m % 60);
            float avg = trip.average();
            values[2] = avg < 0 ? dash : String.valueOf(Math.round(avg));
            noteText = getContext().getString(R.string.trip_units);
        }
        invalidate();
    }

    @Override protected void laidOut(int w, int h) {
        big.setTextSize(h * 0.22f);
        small.setTextSize(h * 0.05f);
        small.setLetterSpacing(0.2f);
        valueY = captionY + h * 0.08f + big.getTextSize() * 0.71f;
        labelY = valueY + h * 0.12f;
        big.setShader(Chrome.metal(valueY - big.getTextSize() * 0.71f, valueY));
    }

    @Override protected void drawContent(Canvas c) {
        float col = (getWidth() - 2 * pad) / 3f;
        for (int i = 0; i < 3; i++) {
            float x = pad + i * col;
            c.drawText(values[i], x - big.getTextSize() * 0.03f, valueY, big);
            c.drawText(labels[i], x, labelY, small);
        }
        drawNote(c, noteText);
    }
}
