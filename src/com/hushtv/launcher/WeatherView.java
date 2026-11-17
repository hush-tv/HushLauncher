package com.hushtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

/** A small line-drawn weather picture on a 24-unit grid: sun, moon, cloud, rain and so on. */
final class WeatherView extends View {
    private final int size;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint erase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cloud = new Path();
    private final Path moon = new Path();
    private int kind = Weather.CLEAR;
    private boolean day = true;

    WeatherView(Context ctx, int size) {
        super(ctx);
        this.size = size;
        float u = size / 24f;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.7f * u);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xF2FFFFFF);
        erase.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        // The cloud's outline is the edge of three circles and a base joined together.
        Path part = new Path();
        cloud.addCircle(8.2f * u, 15.2f * u, 3.6f * u, Path.Direction.CW);
        part.addCircle(12.6f * u, 12.2f * u, 4.9f * u, Path.Direction.CW);
        cloud.op(part, Path.Op.UNION);
        part.rewind();
        part.addCircle(17.2f * u, 15.0f * u, 3.8f * u, Path.Direction.CW);
        cloud.op(part, Path.Op.UNION);
        part.rewind();
        part.addRect(8.2f * u, 14.0f * u, 17.2f * u, 18.8f * u, Path.Direction.CW);
        cloud.op(part, Path.Op.UNION);

        moon.addCircle(12f * u, 12f * u, 6.6f * u, Path.Direction.CW);
        part.rewind();
        part.addCircle(15.6f * u, 9.2f * u, 5.6f * u, Path.Direction.CW);
        moon.op(part, Path.Op.DIFFERENCE);
        setLayerType(LAYER_TYPE_HARDWARE, null); // erasing needs the picture on its own layer
    }

    void set(int kind, boolean day) {
        if (this.kind == kind && this.day == day) return;
        this.kind = kind;
        this.day = day;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float u = size / 24f;
        switch (kind) {
            case Weather.CLEAR:
                if (day) sun(c, 12f * u, 12f * u, 4.2f * u, 7.0f * u, 9.4f * u);
                else c.drawPath(moon, line);
                break;
            case Weather.PARTLY:
                // The sun or moon sits behind the cloud's top-left shoulder.
                if (day) {
                    sun(c, 8.4f * u, 8.6f * u, 3.0f * u, 5.0f * u, 6.8f * u);
                } else {
                    c.save();
                    c.translate(-3.2f * u, -3.0f * u);
                    c.scale(0.62f, 0.62f, 12f * u, 12f * u);
                    c.drawPath(moon, line);
                    c.restore();
                }
                c.save();
                c.translate(1.2f * u, 1.6f * u);
                c.scale(0.86f, 0.86f, 12f * u, 14f * u);
                erase.setStyle(Paint.Style.FILL_AND_STROKE);
                erase.setStrokeWidth(4.6f * u);
                c.drawPath(cloud, erase);
                c.drawPath(cloud, line);
                c.restore();
                break;
            case Weather.CLOUD:
                c.save();
                c.translate(0, -1.5f * u);
                c.drawPath(cloud, line);
                c.restore();
                break;
            default:
                // A smaller cloud with something falling from it, or lying under it.
                c.save();
                c.translate(0, -5.2f * u);
                c.scale(0.9f, 0.9f, 12f * u, 14f * u);
                c.drawPath(cloud, line);
                c.restore();
                float y = 16.6f * u;
                if (kind == Weather.RAIN) {
                    for (int i = 0; i < 3; i++) {
                        float x = (8.6f + i * 3.8f) * u;
                        c.drawLine(x, y, x - 1.3f * u, y + 3.6f * u, line);
                    }
                } else if (kind == Weather.SNOW) {
                    for (int i = 0; i < 3; i++) {
                        float x = (7.8f + i * 4.0f) * u;
                        c.drawPoint(x, y + (i == 1 ? 3.4f : 1.0f) * u, line);
                    }
                } else if (kind == Weather.FOG) {
                    c.drawLine(6.4f * u, y + 0.6f * u, 17.6f * u, y + 0.6f * u, line);
                    c.drawLine(8.6f * u, y + 4.0f * u, 15.4f * u, y + 4.0f * u, line);
                } else { // storm
                    Path bolt = new Path();
                    bolt.moveTo(13.2f * u, y - 1.0f * u);
                    bolt.lineTo(10.4f * u, y + 2.6f * u);
                    bolt.lineTo(13.4f * u, y + 2.6f * u);
                    bolt.lineTo(11.2f * u, y + 6.0f * u);
                    c.drawPath(bolt, line);
                }
                break;
        }
    }

    private void sun(Canvas c, float cx, float cy, float r, float from, float to) {
        c.drawCircle(cx, cy, r, line);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine(cx + dx * from, cy + dy * from, cx + dx * to, cy + dy * to, line);
        }
    }
}
