package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * Palette, type and the shaders every surface shares, in one of two styles picked in Settings: frosted glass
 * (light, see-through cards with a bright rim over a softly lit background) or simple (flat dark panels). One
 * accent colour on top. Views take their surfaces from here, so a style is a change in this file only.
 */
final class Chrome {
    static final int GLASS = 0, SIMPLE = 1;
    /** Set before any view is built (HomeActivity.onCreate); changing it means rebuilding Home. */
    static int style = GLASS;

    static final int SILVER_LIGHT = 0xFFF4F6F8;
    static final int SILVER = 0xFFB4BAC1;
    static final int TEXT = 0xFFF2F4F7;
    static final int TEXT_DIM = 0xFF9AA1AA;

    // Type sizes, as shares of the screen unit (unit()): big enough to read at a glance from the driver's seat.
    static final float TYPE_TITLE = 0.058f, TYPE_HEADLINE = 0.042f, TYPE_BODY = 0.031f, TYPE_CAPTION = 0.023f;
    static final float ICON = 0.075f;

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

    /** Large digits, titles and icons: white that softens a little towards the bottom (glass), or plain white. */
    static Shader metal(float top, float bottom) {
        int low = style == GLASS ? 0xFFD3D8DF : TEXT;
        return new LinearGradient(0, top, 0, bottom, 0xFFFFFFFF, low, Shader.TileMode.CLAMP);
    }

    /** A card's rim: bright along the top and fading down the sides (glass), or a plain dark line. */
    static Shader edge(float left, float top, float right, float bottom) {
        if (style == SIMPLE) return new LinearGradient(0, top, 0, bottom, 0xFF2E3238, 0xFF2E3238, Shader.TileMode.CLAMP);
        return new LinearGradient(0, top, 0, bottom, new int[] {0x8CFFFFFF, 0x2EFFFFFF, 0x1AFFFFFF},
                new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
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

    /** The fill of tiles, cards and keys: frosted, see-through white (glass), or a flat dark panel. */
    static Shader panel(float top, float bottom) {
        if (style == SIMPLE) return new LinearGradient(0, top, 0, bottom, 0xFF1A1C20, 0xFF1A1C20, Shader.TileMode.CLAMP);
        return new LinearGradient(0, top, 0, bottom, 0x30FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP);
    }

    /** Corner radius of cards and tiles, the same everywhere: rounder for glass. */
    static float radius(Context context) {
        return unit(context) * (style == GLASS ? 0.03f : 0.018f);
    }

    /**
     * Home's background over the whole screen. Glass: dark grey lit from the top left, with a faint pool of the
     * accent low on the right, so the see-through cards have light to catch. Simple: nearly black, flat.
     */
    static void background(Canvas c, int w, int h, Paint base, Paint glow) {
        if (base.getShader() == null || glow.getColor() != accent) {
            glow.setColor(accent);
            if (style == SIMPLE) {
                base.setShader(new LinearGradient(0, 0, 0, h, 0xFF0E0F11, 0xFF0B0C0E, Shader.TileMode.CLAMP));
                glow.setShader(null);
            } else {
                base.setShader(new RadialGradient(w * 0.15f, -h * 0.2f, Math.max(w, h) * 1.1f,
                        new int[] {0xFF3A4048, 0xFF1C1F24, 0xFF0C0D10}, new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                glow.setShader(new RadialGradient(w * 0.8f, h * 0.95f, Math.max(w, h) * 0.55f,
                        withAlpha(accent, 0x22), accent & 0x00FFFFFF, Shader.TileMode.CLAMP));
            }
        }
        c.drawRect(0, 0, w, h, base);
        if (style == GLASS) c.drawRect(0, 0, w, h, glow);
    }

    /** Forget cached background shaders (size or accent changed). */
    static void resetBackground(Paint base) {
        base.setShader(null);
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
