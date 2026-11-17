package com.hushtv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Weather over the wallpaper: rain, snow, drifting cloud or mist, and a rare soft flash in a
 * storm. Each effect is drawn once into a small repeating texture, and every frame only slides
 * two or three of those textures along, so the whole effect is a handful of draws however many
 * drops or flakes it shows. (Drawing each drop separately cost about 8 ms a frame on a Shield.)
 */
final class SkyView extends View {
    private static final int TILE = 512;
    private final int w, h;
    private final Paint near = new Paint(Paint.FILTER_BITMAP_FLAG), far = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint flash = new Paint();
    private final Matrix nearM = new Matrix(), farM = new Matrix();
    private int kind = Weather.CLEAR;
    private float strength = 1f;
    /** How much is falling: 0.3 for drizzle up to 1 for heavy rain. */
    private float amount = 1f;
    private boolean running;
    private long last;
    private float nearY, farY, nearX, farX, flashLeft, clock;
    private final Random random = new Random(11);

    SkyView(Context ctx, int w, int h) {
        super(ctx);
        this.w = w;
        this.h = h;
        flash.setShader(new LinearGradient(0, 0, 0, h * 0.6f, 0x30B4C3E6, 0x00B4C3E6, Shader.TileMode.CLAMP));
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** @param strength 0 switches the effect off; 1 is normal, 0.55 subtle. */
    void set(int kind, float strength, float amount) {
        if (this.kind == kind && this.strength == strength && this.amount == amount) return;
        this.kind = kind;
        this.strength = strength;
        this.amount = amount;
        build();
        update();
    }

    void setRunning(boolean on) {
        if (running == on) return;
        running = on;
        update();
    }

    private boolean active() {
        return strength > 0f && (kind == Weather.RAIN || kind == Weather.STORM || kind == Weather.SNOW
                || kind == Weather.FOG || kind == Weather.CLOUD);
    }

    private void update() {
        boolean show = running && active();
        setVisibility(show ? VISIBLE : GONE);
        last = 0;
        if (show) invalidate();
    }

    /** Draws this weather's textures, once. */
    private void build() {
        near.setShader(null);
        far.setShader(null);
        if (!active()) return;
        boolean snow = kind == Weather.SNOW, storm = kind == Weather.STORM;
        if (kind == Weather.RAIN || storm || snow) {
            // Fewer, shorter drops for drizzle; the full count for heavy rain and storms.
            float n = storm ? 1f : amount, drop = 0.6f + 0.4f * n;
            near.setShader(texture(Math.max(6, Math.round((storm ? 70 : snow ? 26 : 38) * n)), snow, drop));
            far.setShader(texture(Math.max(8, Math.round((storm ? 110 : snow ? 40 : 60) * n)), snow, 0.55f * drop));
        } else {
            boolean mist = kind == Weather.FOG;
            near.setShader(haze(mist ? 64 : 40, mist, 3));
            far.setShader(haze(mist ? 40 : 26, mist, 7));
        }
    }

    private BitmapShader texture(int count, boolean snow, float size) {
        Bitmap b = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(1.4f, 2f * size));
        float k = strength;
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * TILE, y = random.nextFloat() * TILE, a = 0.35f + random.nextFloat() * 0.65f;
            if (snow) {
                p.setColor(0xF0F4FF | ((int) (a * 210 * k) << 24));
                float r = (1.6f + random.nextFloat() * 2.6f) * size;
                // Drawn again at the tile's edges so a flake crossing the seam is not cut in half.
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) c.drawCircle(x + dx * TILE, y + dy * TILE, r, p);
            } else {
                p.setColor(0xCDDAEE | ((int) (a * 110 * k) << 24));
                float len = (40f + random.nextFloat() * 40f) * size;
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                    c.drawLine(x + dx * TILE, y + dy * TILE, x + dx * TILE + len * 0.16f, y + dy * TILE + len, p);
            }
        }
        return new BitmapShader(Apps.gpuOnly(b), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }

    private BitmapShader haze(int peak, boolean mist, int bands) {
        int tw = 1024, th = 256;
        Bitmap b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int tone = mist ? 0xA6B4C8 : 0x8C9BB8;
        int top = (int) (peak * strength);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(0, 0, 1f, new int[]{top << 24 | tone, (top / 3) << 24 | tone, tone},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        for (int i = 0; i < bands; i++) {
            float bw = tw * (0.30f + random.nextFloat() * 0.35f), bh = th * (0.30f + random.nextFloat() * 0.35f);
            float x = random.nextFloat() * tw, y = th * (0.25f + random.nextFloat() * 0.5f);
            for (int dx = -1; dx <= 1; dx++) {
                c.save();
                c.translate(x + dx * tw, y);
                c.scale(bw / 2f, bh / 2f);
                c.drawCircle(0, 0, 1f, p);
                c.restore();
            }
        }
        return new BitmapShader(Apps.gpuOnly(b), Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 1f / 60f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        clock += dt;
        if (near.getShader() == null) return;
        boolean snow = kind == Weather.SNOW;
        if (kind == Weather.RAIN || kind == Weather.STORM || snow) {
            float speed = snow ? h * 0.045f : h * 0.95f * (kind == Weather.STORM ? 1f : 0.65f + 0.35f * amount);
            nearY = (nearY + speed * dt) % TILE;
            farY = (farY + speed * 0.6f * dt) % TILE;
            nearX = snow ? (float) Math.sin(clock * 0.6f) * 14f : (nearX + speed * 0.16f * dt) % TILE;
            farX = snow ? (float) Math.sin(clock * 0.45f + 1f) * 9f : (farX + speed * 0.6f * 0.16f * dt) % TILE;
            farM.setScale(0.7f, 0.7f);
            farM.postTranslate(farX, farY);
            far.getShader().setLocalMatrix(farM);
            c.drawRect(0, 0, w, h, far);
            nearM.setTranslate(nearX, nearY);
            near.getShader().setLocalMatrix(nearM);
            c.drawRect(0, 0, w, h, near);
            if (kind == Weather.STORM) {
                if (flashLeft > 0f) {
                    flashLeft -= dt;
                    flash.setAlpha((int) (255 * Math.max(0f, flashLeft / 0.35f) * strength));
                    c.drawRect(0, 0, w, h * 0.6f, flash);
                } else if (random.nextFloat() < dt / 9f) {
                    flashLeft = 0.35f; // about one soft flash every nine seconds
                }
            }
        } else {
            boolean mist = kind == Weather.FOG;
            float top = mist ? h * 0.40f : h * 0.02f, band = mist ? h * 0.32f : h * 0.42f;
            nearX = (nearX + w * 0.010f * dt) % 1024f;
            farX = (farX - w * 0.006f * dt) % 1024f;
            float s = band / 256f;
            farM.setScale(s * 1.6f, s);
            farM.postTranslate(farX, top);
            far.getShader().setLocalMatrix(farM);
            c.drawRect(0, top, w, top + band, far);
            nearM.setScale(s * 1.2f, s);
            nearM.postTranslate(nearX, top);
            near.getShader().setLocalMatrix(nearM);
            c.drawRect(0, top, w, top + band, near);
        }
        if (running) postInvalidateOnAnimation();
    }
}
