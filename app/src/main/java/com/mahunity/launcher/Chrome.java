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
 * Palette, type and the shaders every surface shares, in one of two styles picked in Settings: classic (black
 * panels framed in polished silver, digits in metal) or glass (clear, see-through cards with a bright rim, as on
 * a phone). The background is black, or a shade lighter, as Settings says, and one accent colour lights it all.
 * Views take their surfaces from here, so a style is a change in this file only.
 */
final class Chrome {
    static final int CLASSIC = 0, GLASS = 1;
    /** Set before any view is built (HomeActivity.onCreate); changing it means rebuilding Home. */
    static int style = CLASSIC;
    /** Background: 0 pitch black, 1 dark grey, 2 lighter grey. Set with style. */
    static int shade = 0;
    /** How frosted the glass is, 0 (clear: only the rim shows) to 10 (milky). Set with style. */
    static int frost = 3;

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

    /**
     * Large digits, titles and icons. Classic: polished metal, bright, a hard horizon just below the middle, a
     * darker half that lifts again. Glass: white that softens a little towards the bottom.
     */
    static Shader metal(float top, float bottom) {
        if (style == GLASS) return new LinearGradient(0, top, 0, bottom, 0xFFFFFFFF, 0xFFD6DBE1, Shader.TileMode.CLAMP);
        return new LinearGradient(0, top, 0, bottom,
                new int[] {SILVER_LIGHT, 0xFFD9DDE2, 0xFF878D95, 0xFFC4C9CF},
                new float[] {0f, 0.48f, 0.54f, 1f}, Shader.TileMode.CLAMP);
    }

    /**
     * A card's rim. Classic: polished silver with light sweeping across it on the diagonal. Glass: a bright
     * catch-light at the top left that fades, and a faint reflection again at the bottom right.
     */
    static Shader edge(float left, float top, float right, float bottom) {
        if (style == GLASS) {
            return new LinearGradient(left, top, right, bottom, new int[] {0xE6FFFFFF, 0x40FFFFFF, 0x1AFFFFFF, 0x66FFFFFF},
                    new float[] {0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        }
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

    /**
     * The fill of tiles, cards and keys. Classic: black glass, a touch of grey at the top. Glass: white as thin
     * as the frost setting asks, from none at all to milky.
     */
    static Shader panel(float top, float bottom) {
        if (style == GLASS) {
            return new LinearGradient(0, top, 0, bottom, withAlpha(0xFFFFFF, frosted(0x1C)), withAlpha(0xFFFFFF, frosted(0x08)),
                    Shader.TileMode.CLAMP);
        }
        return new LinearGradient(0, top, 0, bottom, 0xFF15181B, 0xFF060708, Shader.TileMode.CLAMP);
    }

    /** Corner radius of cards and tiles, the same everywhere: rounder for glass. */
    static float radius(Context context) {
        return unit(context) * (style == GLASS ? 0.032f : 0.022f);
    }

    /**
     * Home's background over the whole screen: pitch black, or lit from above in dark or lighter grey (Settings).
     * Glass also gets faint pools of the accent, low on the right and under the cards on the left, so the clear
     * cards have light behind them to show through.
     */
    static void background(Canvas c, int w, int h, Paint base, Paint glow) {
        if (base.getShader() == null || glow.getColor() != accent) {
            glow.setColor(accent);
            int[] grey = shade == 2 ? new int[] {0xFF2E333A, 0xFF181B1F, 0xFF0A0B0D}
                    : shade == 1 ? new int[] {0xFF15191D, 0xFF08090B, 0xFF000000}
                    : new int[] {0xFF000000, 0xFF000000, 0xFF000000};
            base.setShader(new RadialGradient(w * 0.3f, -h * 0.2f, Math.max(w, h) * 1.05f, grey,
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            int clear = accent & 0x00FFFFFF;
            glow.setShader(new android.graphics.ComposeShader(
                    new RadialGradient(w * 0.8f, h * 0.95f, Math.max(w, h) * 0.55f, withAlpha(accent, 0x26), clear,
                            Shader.TileMode.CLAMP),
                    new RadialGradient(w * 0.18f, h * 0.85f, Math.max(w, h) * 0.42f, withAlpha(accent, 0x1E), clear,
                            Shader.TileMode.CLAMP),
                    android.graphics.PorterDuff.Mode.SCREEN));
        }
        c.drawRect(0, 0, w, h, base);
        if (style == GLASS) c.drawRect(0, 0, w, h, glow);
    }

    /** Forget cached background shaders (size or accent changed). */
    static void resetBackground(Paint base) {
        base.setShader(null);
    }

    /** An alpha for the fully frosted glass, scaled to the frost setting. */
    static int frosted(int alpha) {
        return Math.round(alpha * frost / 10f);
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
