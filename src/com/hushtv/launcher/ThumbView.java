package com.hushtv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** A picture thumbnail for the wallpaper gallery: rounded image, name below, tick when chosen. */
final class ThumbView extends View {

    private final int w, h, labelH;
    private final float radius;
    private final String label;
    private final RectF rect;
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean hasImage, checked;
    /** Colour of the selection ring; follows the launcher's outline colour. */
    static int accent = 0xFFFFFFFF;

    ThumbView(Context ctx, String label, int width) {
        super(ctx);
        this.label = label;
        this.w = width;
        this.h = width * 9 / 16;
        this.labelH = label.isEmpty() ? 0 : (int) (h * 0.30f); // no name, no space kept for one
        this.radius = h * 0.10f;
        this.rect = new RectF(0, 0, w, h);
        empty.setColor(0xFF1A1D22);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(3f, h * 0.030f));
        ring.setColor(0xFFFFFFFF);
        badge.setColor(0xFFFFFFFF);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(Math.max(2f, h * 0.022f));
        tick.setStrokeCap(Paint.Cap.ROUND);
        tick.setStrokeJoin(Paint.Join.ROUND);
        tick.setColor(0xFF101216);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextSize(labelH * 0.50f);
        setFocusable(true);
        setClickable(true);
        setContentDescription(label);
    }

    /** Shows the picture, scaled to fill the thumbnail. Pass null for a plain dark card. */
    void setBitmap(Bitmap bmp) {
        hasImage = bmp != null;
        if (hasImage) {
            BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - bmp.getWidth() * scale) / 2f, (h - bmp.getHeight() * scale) / 2f);
            shader.setLocalMatrix(m);
            image.setShader(shader);
        }
        invalidate();
    }

    void setChecked(boolean checked) {
        this.checked = checked;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(w, h + labelH);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRoundRect(rect, radius, radius, empty); // card behind pictures with transparent edges
        if (hasImage) c.drawRoundRect(rect, radius, radius, image);
        boolean focused = isFocused();
        if (focused) {
            ring.setColor(accent);
            float inset = ring.getStrokeWidth() / 2f;
            c.drawRoundRect(inset, inset, w - inset, h - inset, radius - inset, radius - inset, ring);
        }
        if (checked) {
            float r = h * 0.13f, cx = w - r * 1.6f, cy = r * 1.6f;
            c.drawCircle(cx, cy, r, badge);
            c.drawLine(cx - r * 0.45f, cy, cx - r * 0.10f, cy + r * 0.35f, tick);
            c.drawLine(cx - r * 0.10f, cy + r * 0.35f, cx + r * 0.50f, cy - r * 0.35f, tick);
        }
        if (labelH == 0) return;
        text.setColor(focused ? 0xFFFFFFFF : 0xB3FFFFFF);
        CharSequence name = TextUtils.ellipsize(label, text, w, TextUtils.TruncateAt.END);
        c.drawText(name, 0, name.length(), 0, h + labelH * 0.66f, text);
    }
}
