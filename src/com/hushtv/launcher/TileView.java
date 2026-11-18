package com.hushtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;

/** One app tile: rounded art, a steady selection outline and zoom, no pulsing. */
final class TileView extends View {

    /** Art is drawn slightly oversized so stray edge pixels in an app's banner are cropped off. */
    private static final float OVERSCAN = 1.04f;

    /** Starts quickly and settles gently; shared by every tile movement. */
    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    final Apps.App app;
    /** Called when this tile gains focus. */
    Runnable onFocused;
    /** Called when this tile stops being the selected one. */
    Runnable onBlurred;
    private float zoom;
    private android.animation.ObjectAnimator wobble;

    private final Prefs prefs;
    private final int artW, artH, labelH, labelMode;
    private final float radius;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF artRect, ringRect;
    private final float ringRadius;
    private final boolean hasDim, outline;
    private final CharSequence labelText;
    private final boolean pinned;
    /** The colour this tile's picture is mostly made of; white if it has no real colour. */
    int tint = 0xFFFFFFFF;
    private int shineAlpha;
    private float shineAt = -1f; // 0 to 1 while a band of light is crossing the tile
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix shineMatrix = new android.graphics.Matrix();
    private android.graphics.Shader shineShader;
    private final float dotR;
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private android.graphics.Bitmap glow;
    private int glowSpread;

    /** @param labelMode 0 = no name, 1 = name under the selected tile only, 2 = always */
    TileView(Context ctx, Apps.App app, Prefs prefs, int artW, int labelMode) {
        this(ctx, app, prefs, artW, labelMode, 1f, false);
    }

    /**
     * @param labelScale makes the name under the tile larger or smaller than usual
     * @param pinned     marks the name with a small dot, for apps that are on the home row
     */
    TileView(Context ctx, Apps.App app, Prefs prefs, int artW, int labelMode, float labelScale, boolean pinned) {
        super(ctx);
        this.pinned = pinned;
        this.app = app;
        this.prefs = prefs;
        this.artW = artW;
        this.artH = artW * 9 / 16;
        this.labelMode = labelMode;
        this.labelH = labelMode == 0 ? 0 : (int) (artH * 0.30f * labelScale * Math.min(1.3f, prefs.textScale()));
        this.radius = artH * prefs.cornerFrac();
        this.artRect = new RectF(0, 0, artW, artH);

        cardPaint.setColor(0xFF15171B);
        dimPaint.setColor(prefs.dimAlpha() << 24);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(3f, artH * (prefs.highContrast() ? 0.040f : 0.024f)));
        ringPaint.setColor(prefs.accent());
        textPaint.setColor(0xFFE6E8EC);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        float inset = ringPaint.getStrokeWidth() / 2f;
        ringRect = new RectF(inset, inset, artW - inset, artH - inset);
        ringRadius = Math.max(0f, radius - inset);
        hasDim = prefs.dimAlpha() > 0;
        outline = prefs.outline();
        textPaint.setTextSize(Math.max(1f, labelH * 0.42f));
        if (labelH > 0) textPaint.setShadowLayer(labelH * 0.22f, 0f, labelH * 0.03f, 0xB3000000);
        dotR = pinned ? labelH * 0.085f : 0f;
        labelText = TextUtils.ellipsize(app.label, textPaint, artW - dotR * 4f, TextUtils.TruncateAt.END);
        pendingFace = renderFace();

        // A rounded outline lets the system draw a soft shadow under the raised, selected tile.
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, TileView.this.artW, TileView.this.artH, radius);
            }
        });
        if (prefs.glowAlpha() > 0) {
            glowSpread = Math.max(2, (int) (artH * prefs.glowSpreadFrac()));
            glow = glowBitmap(artW, artH, radius, glowSpread);
            glowPaint.setColor(prefs.accent());
            glowPaint.setAlpha(prefs.glowAlpha());
        }
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setContentDescription(app.label);
        zoom = prefs.zoom();
        shineAlpha = prefs.shine();
        final int anim = prefs.animMillis();
        setOnFocusChangeListener((v, has) -> {
            if (has && onFocused != null) onFocused.run();
            if (!has && onBlurred != null) onBlurred.run();
            if (!has && shineRun != null) { // no light left crossing a tile that is no longer selected
                shineRun.cancel();
                shineAt = -1f;
            }
            float s = (has && !sweeping) || held ? zoom : 1f; // reads the field, so a cap set after construction applies
            if (anim == 0 || sweeping) {
                animate().cancel();
                setScaleX(s);
                setScaleY(s);
            } else {
                animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(anim).setInterpolator(EASE).start();
            }
            setTranslationZ((has || held) && !carried ? 12f : 0f);
            if (has && shineAlpha > 0 && anim != 0 && !sweeping) startShine();
            invalidate();
        });
    }

    /** Halo bitmaps by size. The home row and the drawer use different tile sizes, so two are kept. */
    private static final java.util.LinkedHashMap<String, android.graphics.Bitmap> GLOWS =
            new java.util.LinkedHashMap<String, android.graphics.Bitmap>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> e) {
                    return size() > 2;
                }
            };

    /** A blurred rounded-rect halo, made once per tile size and shared by every tile of that size. */
    private static synchronized android.graphics.Bitmap glowBitmap(int w, int h, float radius, int spread) {
        String key = w + "x" + h + "r" + radius + "s" + spread;
        android.graphics.Bitmap glow = GLOWS.get(key);
        if (glow == null) {
            glow = android.graphics.Bitmap.createBitmap(w + 2 * spread, h + 2 * spread,
                    android.graphics.Bitmap.Config.ALPHA_8);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setMaskFilter(new android.graphics.BlurMaskFilter(spread * 0.5f,
                    android.graphics.BlurMaskFilter.Blur.NORMAL));
            new Canvas(glow).drawRoundRect(new RectF(spread, spread, spread + w, spread + h),
                    radius, radius, p);
            GLOWS.put(key, glow);
        }
        return glow;
    }

    /** Limits how much this tile grows when selected, for grids where neighbours sit close. */
    /**
     * A mostly white banner glares on a dark screen. This shades such a tile, more the brighter
     * it is, and leaves ordinary and dark tiles alone.
     */
    private void soften(Canvas c, android.graphics.Bitmap face) {
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(face, 16, 9, true);
        float sum = 0f;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int p = small.getPixel(x, y);
                sum += (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255f;
            }
        }
        if (small != face) small.recycle();
        float brightness = sum / 144f;
        if (brightness <= 0.55f) return;
        int shade = (int) Math.min(0x70, (brightness - 0.55f) / 0.45f * 0x70 + 0x18);
        Paint p = new Paint();
        p.setColor(shade << 24);
        c.drawRect(artRect, p);
    }

    /** A soft band of light crosses the tile once, left to right, as it is selected. */
    private void startShine() {
        if (shineShader == null) {
            // A wide band with a long, gentle fall-off on both sides, so it has no visible edges.
            float band = artW * 0.60f;
            shineShader = new android.graphics.LinearGradient(0f, 0f, band, band * 0.35f,
                    new int[]{0x00FFFFFF, (shineAlpha / 3) << 24 | 0xFFFFFF, shineAlpha << 24 | 0xFFFFFF,
                            (shineAlpha / 3) << 24 | 0xFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.30f, 0.50f, 0.70f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            shinePaint.setShader(shineShader);
        }
        if (shineRun != null) shineRun.cancel();
        android.animation.ValueAnimator a = shineRun = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(1500); // slow enough to read as a gleam, not a flicker
        a.setStartDelay(160);
        a.setInterpolator(EASE);
        a.addUpdateListener(v -> {
            shineAt = (Float) v.getAnimatedValue();
            if (shineAt >= 1f) shineAt = -1f;
            invalidate();
        });
        a.start();
    }

    /** The tile's picture, drawn but not yet handed to the graphics chip. */
    private android.graphics.Bitmap pendingFace;
    /** How much larger than the tile its picture is drawn. */
    private static final float SS = 1.3f;

    /** Uploads this one tile's picture by itself. Tiles made in a batch should use {@link #bake}. */
    private void bakeAlone() {
        if (pendingFace == null) return;
        android.graphics.BitmapShader shader = new android.graphics.BitmapShader(Apps.gpuOnly(pendingFace),
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(artW / (float) pendingFace.getWidth(), artH / (float) pendingFace.getHeight());
        shader.setLocalMatrix(m);
        facePaint.setShader(shader);
        pendingFace = null;
    }

    /**
     * Uploads a batch of same-sized tiles as one sheet of pictures, which each tile then draws
     * its own part of. One large picture costs the graphics chip far less memory than dozens of
     * small ones, each of which carries its own overhead.
     */
    static void bake(java.util.List<TileView> tiles) {
        final int perSheet = 36, gutter = 4;
        for (int from = 0; from < tiles.size(); from += perSheet) {
            java.util.List<TileView> group = tiles.subList(from, Math.min(tiles.size(), from + perSheet));
            TileView first = group.get(0);
            if (first.pendingFace == null) continue;
            int cols = Math.min(group.size(), 6), rows = (group.size() + cols - 1) / cols;
            int fw = first.pendingFace.getWidth(), fh = first.pendingFace.getHeight();
            int cellW = fw + gutter, cellH = fh + gutter;
            android.graphics.Bitmap sheet;
            try {
                sheet = android.graphics.Bitmap.createBitmap(cols * cellW, rows * cellH,
                        android.graphics.Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                return; // each tile will upload its own picture when first drawn
            }
            Canvas c = new Canvas(sheet);
            int[] xs = new int[group.size()], ys = new int[group.size()];
            for (int i = 0; i < group.size(); i++) {
                TileView t = group.get(i);
                if (t.pendingFace == null || t.artW != first.artW) continue;
                xs[i] = (i % cols) * cellW + gutter / 2;
                ys[i] = (i / cols) * cellH + gutter / 2;
                // Drawn slightly oversize first, so the gutter holds the picture's own edge colours
                // and nothing dark creeps in at the edges when the tile is enlarged.
                c.drawBitmap(t.pendingFace, null, new RectF(xs[i] - gutter / 2f, ys[i] - gutter / 2f,
                        xs[i] + fw + gutter / 2f, ys[i] + fh + gutter / 2f), null);
                c.drawBitmap(t.pendingFace, xs[i], ys[i], null);
            }
            android.graphics.Bitmap shared = Apps.gpuOnly(sheet);
            for (int i = 0; i < group.size(); i++) {
                TileView t = group.get(i);
                if (t.pendingFace == null || t.artW != first.artW) continue;
                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(shared,
                        android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(t.artW / (float) fw, t.artH / (float) fh);
                m.preTranslate(-xs[i], -ys[i]);
                shader.setLocalMatrix(m);
                t.facePaint.setShader(shader);
                t.pendingFace.recycle();
                t.pendingFace = null;
            }
        }
    }

    /** True while Left or Right is being held on the home row. */
    static boolean sweeping;

    /** Grows into the selected size after a sweep ends on this tile. */
    void settle() {
        if (!isFocused()) return;
        animate().scaleX(zoom).scaleY(zoom).setStartDelay(0).setDuration(220).setInterpolator(EASE).start();
        if (shineAlpha > 0) startShine();
    }

    private android.animation.ValueAnimator shineRun;
    private boolean carried;
    private boolean held;

    /** Selected, or standing in as selected while its menu is open. */
    private boolean lit() {
        return isFocused() || held;
    }

    /** Keeps the tile looking selected while its menu has the selection, and lets it go afterwards. */
    void setHeld(boolean on) {
        if (held == on) return;
        held = on;
        if (!on && !isFocused()) {
            animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(180).start();
            setTranslationZ(0f);
        }
        invalidate();
    }

    /** The size this tile should be at rest: enlarged if it is the selected one. */
    float restScale() {
        return lit() ? zoom : 1f;
    }

    void capZoom(float max) {
        zoom = Math.min(zoom, max);
    }

    /** Rocks the tile gently to show it has been picked up and can be moved. */
    void setMoving(boolean moving) {
        if (wobble != null) wobble.cancel();
        wobble = null;
        setRotation(0f);
        if (!moving) return;
        // A rocking tile would have its soft shadow worked out afresh for every frame, which is
        // slow enough to drop frames, so a tile that is being carried casts none.
        setOutlineProvider(null);
        setTranslationZ(0f);
        carried = true;
        wobble = android.animation.ObjectAnimator.ofFloat(this, "rotation", -1.4f, 1.4f);
        wobble.setDuration(170);
        wobble.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        wobble.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        wobble.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (wobble != null) wobble.cancel(); // a tile removed mid-move must not keep animating
    }

    /** Makes the tile grow rightwards from its left edge, so the selected tile's left edge never moves. */
    void growFromLeft() {
        setPivotX(0f);
        setPivotY(artH / 2f);
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
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(artW, artH + labelH);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (pendingFace != null) bakeAlone();
        if (glow != null && lit()) c.drawBitmap(glow, -glowSpread, -glowSpread, glowPaint);
        c.drawRoundRect(artRect, radius, radius, facePaint);
        boolean focused = lit();
        if (!focused && hasDim) c.drawRoundRect(artRect, radius, radius, dimPaint);
        if (focused && shineAt >= 0f) {
            float band = artW * 0.60f;
            shineMatrix.setTranslate(-band + shineAt * (artW + band), 0f);
            shineShader.setLocalMatrix(shineMatrix);
            c.drawRoundRect(artRect, radius, radius, shinePaint);
        }
        if (focused && outline) c.drawRoundRect(ringRect, ringRadius, ringRadius, ringPaint);
        if (labelMode == 2 || (labelMode == 1 && focused)) {
            textPaint.setTextSize(labelH * 0.42f);
            textPaint.setAlpha(focused ? 255 : 170);
            float base = artH + labelH * 0.68f;
            if (pinned) {
                ringPaint.setStyle(Paint.Style.FILL);
                ringPaint.setAlpha(focused ? 255 : 190);
                c.drawCircle(dotR, base - labelH * 0.14f, dotR, ringPaint);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setAlpha(255);
            }
            c.drawText(labelText, 0, labelText.length(), dotR * 4f, base, textPaint);
        }
    }

    /** Draws the tile's picture once into a bitmap, so each frame is a single cheap rounded-rect draw. */
    private android.graphics.Bitmap renderFace() {
        // Drawn larger than the tile and shrunk when shown, so the picture is still crisp
        // when the tile is enlarged for selection.
        android.graphics.Bitmap face = android.graphics.Bitmap.createBitmap(Math.round(artW * SS), Math.round(artH * SS),
                android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(face);
        c.scale(face.getWidth() / (float) artW, face.getHeight() / (float) artH);
        c.drawRect(artRect, cardPaint);
        if (app.custom != null) {
            drawFill(c, app.custom);
        } else if (app.packArt != null && !app.ownOnly) {
            drawFill(c, app.packArt);
        } else if (app.packIcon != null && !app.ownOnly) {
            drawPackIcon(c);
        } else if (app.banner != null && !prefs.darkTiles()) {
            drawFill(c, app.banner);
        } else {
            drawDarkCard(c);
        }
        tint = HomeActivity.accentOf(face);
        if (prefs.softenBright()) soften(c, face);
        applyFinish(c);
        return face;
    }

    /**
     * Bakes a consistent finish over the artwork: light catching the top, a shade at the
     * bottom, and a fine bright rim. It is drawn once, so it costs nothing per frame.
     */
    private void applyFinish(Canvas c) {
        int level = prefs.finish();
        if (level == 0) return;
        int top = level == 1 ? 0x24FFFFFF : 0x3DFFFFFF;
        int bottom = level == 1 ? 0x38000000 : 0x66000000;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, artH * 0.55f,
                top, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, artW, artH * 0.55f, p);
        p.setShader(new android.graphics.LinearGradient(0, artH * 0.50f, 0, artH,
                0x00000000, bottom, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, artH * 0.50f, artW, artH, p);
        // rim: brighter along the top edge, fading down the sides
        float w = Math.max(1.5f, artH * 0.010f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, artH,
                level == 1 ? 0x4DFFFFFF : 0x66FFFFFF, 0x0DFFFFFF, android.graphics.Shader.TileMode.CLAMP));
        RectF r = new RectF(w / 2f, w / 2f, artW - w / 2f, artH - w / 2f);
        float rr = Math.max(0f, radius - w / 2f);
        c.drawRoundRect(r, rr, rr, p);
    }

    private void drawFill(Canvas c, android.graphics.Bitmap art) {
        if (art.getWidth() < art.getHeight() * 1.3f) {
            // A square picture can't fill a wide tile: centre it on the card instead.
            int size = (int) (artH * 0.66f);
            int left = (artW - size) / 2, top = (artH - size) / 2;
            c.drawBitmap(art, null, new Rect(left, top, left + size, top + size), bitmapPaint);
            return;
        }
        float w = artW * OVERSCAN, h = artH * OVERSCAN;
        float l = (artW - w) / 2f, t = (artH - h) / 2f;
        c.drawBitmap(art, null, new RectF(l, t, l + w, t + h), bitmapPaint);
    }

    /** An icon-pack icon, centred on the dark card. Packs are square, so it can't fill a wide tile. */
    private void drawPackIcon(Canvas c) {
        int iconSize = (int) (artH * 0.64f);
        int left = (artW - iconSize) / 2, top = (artH - iconSize) / 2;
        c.drawBitmap(app.packIcon, null, new Rect(left, top, left + iconSize, top + iconSize), bitmapPaint);
    }

    /** The app's icon and name on a plain dark card: used for apps without a TV banner, and for dark tile style. */
    private void drawDarkCard(Canvas c) {
        int iconSize = (int) (artH * 0.50f);
        int left = (int) (artW * 0.09f);
        int top = (artH - iconSize) / 2;
        if (app.icon != null) {
            c.drawBitmap(app.icon, null, new Rect(left, top, left + iconSize, top + iconSize), bitmapPaint);
        }
        textPaint.setTextSize(artH * 0.15f);
        textPaint.setAlpha(255);
        float textX = left + iconSize + artW * 0.06f;
        CharSequence text = TextUtils.ellipsize(app.label, textPaint, artW - textX - artW * 0.06f,
                TextUtils.TruncateAt.END);
        c.drawText(text, 0, text.length(), textX, artH / 2f + artH * 0.055f, textPaint);
    }
}
