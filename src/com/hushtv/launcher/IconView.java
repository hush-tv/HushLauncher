package com.hushtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Solid status glyphs drawn in code on a 24-unit grid, so the launcher needs no image files. */
final class IconView extends View {

    static final int GEAR = 0, ETHERNET = 1, WIFI = 2, OFFLINE = 3, VPN = 4, APPS = 5,
            TILES = 6, SELECTION = 7, CLOCK = 8, IMAGE = 9, MOON = 10;

    private int type;
    private boolean dim;
    private android.graphics.Bitmap mask;
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int bars = 3;
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF arcBox = new android.graphics.RectF();
    private final int size;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    IconView(Context ctx, int type, int size) {
        super(ctx);
        this.size = size;
        disc.setColor(0xFFFFFFFF);
        setOnFocusChangeListener((v, has) -> invalidate());
        setType(type);
    }

    void setType(int type) {
        this.type = type;
        build();
        invalidate();
    }

    /**
     * Uses a picture's outline as this button's glyph (drawn in a single colour), in place of
     * the built-in one. Pass null to go back to the built-in glyph.
     */
    void setMask(android.graphics.Bitmap mask) {
        this.mask = mask;
        invalidate();
    }

    /** Wi-Fi signal strength, 1 to 3 bars. */
    void setBars(int bars) {
        bars = Math.max(1, Math.min(3, bars));
        if (this.bars != bars) {
            this.bars = bars;
            invalidate();
        }
    }

    /**
     * The usual Wi-Fi fan: a dot and three arcs. Arcs beyond the current signal strength are
     * drawn faintly; with no connection they all are, and a bar is struck through them.
     */
    private void drawWifi(Canvas c, int bright, int faint) {
        float u = size / 24f, cx = size / 2f, by = size / 2f + 6.2f * u;
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(2.3f * u);
        boolean off = type == OFFLINE;
        for (int i = 1; i <= 3; i++) {
            float r = (1.0f + i * 3.7f) * u;
            arcBox.set(cx - r, by - r, cx + r, by + r);
            arc.setColor(!off && i <= bars ? bright : faint);
            c.drawArc(arcBox, 225f, 90f, false, arc);
        }
        fill.setColor(off ? faint : bright);
        c.drawCircle(cx, by - 0.2f * u, 1.7f * u, fill);
        if (off) {
            arc.setColor(bright);
            c.drawLine(cx - 6.5f * u, size / 2f - 6.5f * u, cx + 6.5f * u, size / 2f + 6.5f * u, arc);
        }
    }

    /** Draws the glyph faintly, for a state that is off (e.g. no VPN connected). */
    void setDim(boolean dim) {
        if (this.dim != dim) {
            this.dim = dim;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean focused = isFocused();
        if (focused) c.drawCircle(size / 2f, size / 2f, size / 2f, disc);
        int bright = focused ? 0xFF101216 : dim ? 0x59FFFFFF : 0xE6FFFFFF;
        fill.setColor(bright);
        if (mask != null) {
            // The picture's shape, filled with the tray's colour.
            maskPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(bright,
                    android.graphics.PorterDuff.Mode.SRC_IN));
            float inset = size * 0.10f;
            arcBox.set(inset, inset, size - inset, size - inset);
            c.drawBitmap(mask, null, arcBox, maskPaint);
            return;
        }
        if (type == WIFI || type == OFFLINE) {
            drawWifi(c, bright, focused ? 0x40101216 : 0x40FFFFFF);
            return;
        }
        c.drawPath(path, fill);
    }

    /** Builds the glyph once; even-odd filling turns inner shapes into cut-outs. */
    private void build() {
        float u = size / 24f, cx = size / 2f, cy = size / 2f;
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        switch (type) {
            case GEAR: {
                int teeth = 8;
                float outer = 7.6f * u, inner = 5.7f * u;
                for (int i = 0; i < teeth; i++) {
                    double a = 2 * Math.PI * i / teeth, half = Math.PI / teeth;
                    double[] angles = {a - half * 0.50, a - half * 0.30, a + half * 0.30, a + half * 0.50};
                    float[] radii = {inner, outer, outer, inner};
                    for (int k = 0; k < 4; k++) {
                        float x = cx + (float) Math.cos(angles[k]) * radii[k];
                        float y = cy + (float) Math.sin(angles[k]) * radii[k];
                        if (i == 0 && k == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                }
                path.close();
                path.addCircle(cx, cy, 2.5f * u, Path.Direction.CW);
                break;
            }
            case APPS: {
                // a three-by-three grid of rounded squares
                float cell = 3.4f * u, step = 5.2f * u, r = 0.9f * u;
                for (int row = -1; row <= 1; row++) {
                    for (int col = -1; col <= 1; col++) {
                        float x = cx + col * step - cell / 2f, y = cy + row * step - cell / 2f;
                        path.addRoundRect(new RectF(x, y, x + cell, y + cell), r, r, Path.Direction.CW);
                    }
                }
                break;
            }
            case TILES: {
                // one wide tile with the edge of the next beside it
                path.addRoundRect(new RectF(cx - 9f * u, cy - 4.5f * u, cx + 3.5f * u, cy + 4.5f * u),
                        1.8f * u, 1.8f * u, Path.Direction.CW);
                path.addRoundRect(new RectF(cx + 5.5f * u, cy - 4.5f * u, cx + 9f * u, cy + 4.5f * u),
                        1.4f * u, 1.4f * u, Path.Direction.CW);
                break;
            }
            case SELECTION: {
                // a tile inside a selection outline
                path.addRoundRect(new RectF(cx - 9f * u, cy - 6.5f * u, cx + 9f * u, cy + 6.5f * u),
                        2.6f * u, 2.6f * u, Path.Direction.CW);
                path.addRoundRect(new RectF(cx - 7.2f * u, cy - 4.7f * u, cx + 7.2f * u, cy + 4.7f * u),
                        1.6f * u, 1.6f * u, Path.Direction.CW);
                path.addRoundRect(new RectF(cx - 5f * u, cy - 2.6f * u, cx + 5f * u, cy + 2.6f * u),
                        1f * u, 1f * u, Path.Direction.CW);
                break;
            }
            case CLOCK: {
                path.addCircle(cx, cy, 8.4f * u, Path.Direction.CW);
                path.addCircle(cx, cy, 6.6f * u, Path.Direction.CW);
                path.addRect(cx - 0.9f * u, cy - 4.8f * u, cx + 0.9f * u, cy + 0.9f * u, Path.Direction.CW);
                path.addRect(cx + 0.9f * u, cy - 0.9f * u, cx + 4f * u, cy + 0.9f * u, Path.Direction.CW);
                break;
            }
            case MOON: {
                // a crescent: one disc with another bitten out of it
                Path bite = new Path();
                path.addCircle(cx - 0.6f * u, cy + 0.4f * u, 8.2f * u, Path.Direction.CW);
                bite.addCircle(cx + 3.6f * u, cy - 3.0f * u, 6.8f * u, Path.Direction.CW);
                path.op(bite, Path.Op.DIFFERENCE);
                break;
            }
            case IMAGE: {
                path.addRoundRect(new RectF(cx - 9f * u, cy - 6.5f * u, cx + 9f * u, cy + 6.5f * u),
                        2.2f * u, 2.2f * u, Path.Direction.CW);
                path.addRect(cx - 7.2f * u, cy - 4.7f * u, cx + 7.2f * u, cy + 4.7f * u, Path.Direction.CW);
                Path hill = new Path();
                hill.moveTo(cx - 7.2f * u, cy + 4.7f * u);
                hill.lineTo(cx - 2f * u, cy - 1.2f * u);
                hill.lineTo(cx + 1.4f * u, cy + 2.2f * u);
                hill.lineTo(cx + 3.4f * u, cy + 0.2f * u);
                hill.lineTo(cx + 7.2f * u, cy + 4.7f * u);
                hill.close();
                path.addPath(hill);
                path.addCircle(cx + 3.8f * u, cy - 2.2f * u, 1.5f * u, Path.Direction.CW);
                break;
            }
            case ETHERNET: {
                // A network socket: a solid rounded block with the stepped opening cut out of
                // it and four contact pins along the opening's top edge.
                path.addRoundRect(new RectF(cx - 9f * u, cy - 7.5f * u, cx + 9f * u, cy + 7.5f * u),
                        3f * u, 3f * u, Path.Direction.CW);
                Path opening = new Path();
                opening.moveTo(cx - 5f * u, cy - 3.5f * u);
                opening.lineTo(cx + 5f * u, cy - 3.5f * u);
                opening.lineTo(cx + 5f * u, cy + 2.5f * u);
                opening.lineTo(cx + 2.5f * u, cy + 2.5f * u);
                opening.lineTo(cx + 2.5f * u, cy + 4.5f * u);
                opening.lineTo(cx - 2.5f * u, cy + 4.5f * u);
                opening.lineTo(cx - 2.5f * u, cy + 2.5f * u);
                opening.lineTo(cx - 5f * u, cy + 2.5f * u);
                opening.close();
                path.addPath(opening);
                for (int i = 0; i < 4; i++) {
                    float x = cx - 3f * u + i * 2f * u;
                    path.addRect(x - 0.55f * u, cy - 3.5f * u, x + 0.55f * u, cy - 1f * u, Path.Direction.CW);
                }
                break;
            }
            case WIFI:
            case OFFLINE:
                break; // drawn as arcs in onDraw, so each bar can be bright or faint
            case VPN:
            default: {
                path.addCircle(cx - 4.6f * u, cy, 4.4f * u, Path.Direction.CW);
                path.addCircle(cx - 4.6f * u, cy, 1.7f * u, Path.Direction.CW);
                Path shaft = new Path();
                shaft.setFillType(Path.FillType.WINDING);
                shaft.addRect(cx - 0.6f * u, cy - 1.3f * u, cx + 9f * u, cy + 1.3f * u, Path.Direction.CW);
                shaft.addRect(cx + 3.6f * u, cy + 1.3f * u, cx + 5.6f * u, cy + 4.2f * u, Path.Direction.CW);
                shaft.addRect(cx + 7f * u, cy + 1.3f * u, cx + 9f * u, cy + 4.2f * u, Path.Direction.CW);
                path.op(shaft, Path.Op.UNION);
                break;
            }
        }
    }
}
