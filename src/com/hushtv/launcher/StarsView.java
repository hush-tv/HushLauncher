package com.hushtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/** A few faint stars over the sky of a night scene that slowly brighten and fade. */
final class StarsView extends View {
    private static final int COUNT = 44;
    private final float[] x = new float[COUNT], y = new float[COUNT], size = new float[COUNT];
    private final float[] phase = new float[COUNT], speed = new float[COUNT], peak = new float[COUNT];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean running;

    StarsView(Context ctx, int w, int h) {
        super(ctx);
        Random r = new Random(97);
        for (int i = 0; i < COUNT; i++) {
            x[i] = r.nextFloat() * w;
            y[i] = (float) Math.pow(r.nextFloat(), 1.6) * h * 0.42f; // mostly high in the sky
            size[i] = h * (0.0010f + r.nextFloat() * 0.0012f);
            phase[i] = r.nextFloat() * 6.28f;
            speed[i] = 6.28f / (3.5f + r.nextFloat() * 5f);          // one cycle every 3.5 to 8.5 seconds
            peak[i] = (0.35f + r.nextFloat() * 0.5f) * (1f - y[i] / (h * 0.5f));
        }
    }

    void setRunning(boolean on) {
        if (running == on) return;
        running = on;
        setVisibility(on ? VISIBLE : GONE);
        if (on) invalidate();
    }

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
        float t = (System.nanoTime() / 1_000_000) % 10_000_000 / 1000f;
        for (int i = 0; i < COUNT; i++) {
            float glow = 0.5f + 0.5f * (float) Math.sin(phase[i] + t * speed[i]);
            paint.setColor(0xFFE8ECFF);
            paint.setAlpha((int) (255 * peak[i] * glow * glow));
            c.drawCircle(x[i], y[i], size[i], paint);
        }
        // Twelve frames a second is plenty for something this slow, and costs very little.
        if (running) postInvalidateDelayed(80);
    }
}
