package com.hushtv.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** A wide, soft pool of light behind the home row, in the colour of the selected app. */
final class GlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final float radius, squash;
    private float cx, cy;
    private int colour = 0xFFFFFFFF, strength;
    private ValueAnimator fade;

    GlowView(Context ctx, int screenW, int screenH) {
        super(ctx);
        radius = screenW * 0.44f;
        squash = 0.46f; // wider than it is tall
        cy = screenH;
    }

    /** How strong the light is at its centre, out of 255; 0 switches it off. */
    void setStrength(int strength) {
        this.strength = strength;
        rebuild();
    }

    void moveTo(float x, float y) {
        if (x == cx && y == cy) return;
        cx = x;
        cy = y;
        invalidate();
    }

    /** Changes colour smoothly. */
    void tintTo(int target, int millis) {
        if (fade != null) fade.cancel();
        if (millis == 0 || target == colour) {
            colour = target;
            rebuild();
            return;
        }
        fade = ValueAnimator.ofObject(new ArgbEvaluator(), colour, target);
        fade.setDuration(millis);
        fade.addUpdateListener(a -> {
            colour = (Integer) a.getAnimatedValue();
            rebuild();
        });
        fade.start();
    }

    private int builtStrength = -1;

    /**
     * The gradient is made once, in white, and coloured when drawn. Making a new gradient for
     * every frame of a colour change, as this used to, leaves rubbish for Android to clear up,
     * and those clear-ups are what make a held scroll drop frames.
     */
    private void rebuild() {
        if (strength != builtStrength) {
            builtStrength = strength;
            paint.setShader(strength == 0 ? null : new RadialGradient(0f, 0f, radius,
                    new int[]{strength << 24 | 0xFFFFFF, (strength * 11 / 20) << 24 | 0xFFFFFF, (strength / 5) << 24 | 0xFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.30f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        }
        if ((colour | 0xFF000000) != filterColour) {
            filterColour = colour | 0xFF000000;
            paint.setColorFilter(new android.graphics.PorterDuffColorFilter(filterColour, android.graphics.PorterDuff.Mode.MULTIPLY));
        }
        invalidate();
    }

    private int filterColour;

    /**
     * Tells Android this view can be faded by simply drawing it fainter. Otherwise a fading view
     * is first drawn into a working buffer of its own size, and for a full-screen view those
     * buffers cost tens of megabytes that are then kept.
     */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (strength == 0) return;
        c.save();
        c.translate(cx, cy);
        c.scale(1f, squash);
        c.drawCircle(0f, 0f, radius, paint);
        c.restore();
    }
}
