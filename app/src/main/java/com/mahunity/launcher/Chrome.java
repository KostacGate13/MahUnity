package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/** Palette, type and the shaders every surface shares: black glass, polished silver, one ambient accent. */
final class Chrome {
    static final int SILVER_LIGHT = 0xFFF4F6F8;
    static final int SILVER = 0xFFB4BAC1;
    static final int TEXT = 0xFFE2E5E9;
    static final int TEXT_DIM = 0xFF7E858E;
    static final int PANEL_TOP = 0xFF15181B;
    static final int PANEL_BOTTOM = 0xFF060708;

    /** Accent (ambient lighting) colours, as listed in Settings; names in R.array.ambient_names. */
    static final int[] ACCENTS = {
        0xFFDDE4EB, 0xFF5CB8FF, 0xFFFFA23A, 0xFFFF3A3A, 0xFFA37BFF, 0xFF3DDC84,
        0xFFE8C45A, 0xFFD45CE8, 0xFF2EE6D6, 0xFFFF5FA2, 0xFFFFFFFF,
    };
    static int accent = ACCENTS[0];

    static final Typeface THIN = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    private Chrome() {}

    /** Polished metal, top to bottom: bright, a hard horizon just below the middle, a darker half that lifts again. */
    static Shader metal(float top, float bottom) {
        return new LinearGradient(0, top, 0, bottom,
                new int[] {SILVER_LIGHT, 0xFFD9DDE2, 0xFF878D95, 0xFFC4C9CF},
                new float[] {0f, 0.48f, 0.54f, 1f}, Shader.TileMode.CLAMP);
    }

    /** A frame's edge, with light sweeping across it on the diagonal. */
    static Shader edge(float left, float top, float right, float bottom) {
        return new LinearGradient(left, top, right, bottom,
                new int[] {0xFF5E646C, SILVER_LIGHT, 0xFF3A3F45, 0xFF8E949B},
                new float[] {0f, 0.32f, 0.68f, 1f}, Shader.TileMode.CLAMP);
    }

    /** A hairline at the given peak alpha that fades out at both ends. */
    static Shader hairline(float x0, float y0, float x1, float y1, int alpha) {
        int clear = SILVER & 0x00FFFFFF;
        return new LinearGradient(x0, y0, x1, y1, new int[] {clear, withAlpha(SILVER_LIGHT, alpha), clear},
                null, Shader.TileMode.CLAMP);
    }

    /** A hairline that starts at full strength on the left and fades out to the right. */
    static Shader fadeRight(float x0, float x1, int alpha) {
        return new LinearGradient(x0, 0, x1, 0, withAlpha(SILVER_LIGHT, alpha), SILVER & 0x00FFFFFF,
                Shader.TileMode.CLAMP);
    }

    /** The black glass of tiles and cards: a touch of grey at the top, near black at the bottom. */
    static Shader panel(float top, float bottom) {
        return new LinearGradient(0, top, 0, bottom, PANEL_TOP, PANEL_BOTTOM, Shader.TileMode.CLAMP);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** The layout unit for screens Home does not lay out itself (Settings rows): the short side, capped for wide screens. */
    static float unit(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return Math.min(dm.heightPixels, dm.widthPixels * 0.6f);
    }

    /** A drawable flattened to an alpha mask, so a Paint's shader can colour it in metal or in accent light. */
    static Bitmap mask(Context context, int drawableRes, int size) {
        if (size <= 0) return null;
        Drawable d = context.getDrawable(drawableRes).mutate();
        Bitmap argb = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(argb));
        Bitmap alpha = argb.extractAlpha();
        argb.recycle();
        return alpha;
    }

    /** Pressed controls sink a little and settle back when let go. */
    static void press(View v, boolean down) {
        float target = down ? 0.955f : 1f;
        v.animate().scaleX(target).scaleY(target).setDuration(down ? 90 : 200).setInterpolator(EASE).start();
    }

    /** The accent changed: every view redraws, children included (a parent's invalidate does not reach them). */
    static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }
}
