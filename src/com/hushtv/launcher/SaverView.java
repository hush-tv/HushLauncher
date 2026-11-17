package com.hushtv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The screensaver's picture: night scenes that drift slowly and fade from one to the next,
 * with the time, date and weather in a corner that changes with each picture so nothing
 * stays in one place. Used by the screensaver itself and by its preview in settings.
 */
final class SaverView extends FrameLayout {
    private static final String[] SCENES = {
            "asset:dusk_ridge.jpg", "asset:moonrise.jpg", "asset:northern_lights.jpg", "asset:blue_hour.jpg",
            "asset:ember_dunes.jpg", "asset:emerald_pines.jpg", "asset:alpine_peaks.jpg",
            "asset:still_water.jpg", "asset:city_lights.jpg"};
    private static final long FADE = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Prefs prefs;
    private final Weather weather;
    private final int w, h;
    private final ImageView[] layers = new ImageView[2];
    private final LinearLayout info, weatherRow;
    private final TextView time, date, weatherText;
    private final WeatherView weatherIcon;
    private final List<String> pictures = new ArrayList<>();
    private final boolean plain;
    private int front, index = -1, corner = -1;
    private boolean running;
    private android.animation.ValueAnimator creep;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateWords();
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 50);
        }
    };
    private final Runnable next = this::showNext;

    SaverView(Context ctx) {
        super(ctx);
        prefs = new Prefs(ctx);
        weather = new Weather(ctx);
        w = getResources().getDisplayMetrics().widthPixels;
        h = getResources().getDisplayMetrics().heightPixels;
        setBackgroundColor(0xFF000000);
        plain = prefs.saverStyle() == 3;

        for (int i = 0; i < 2; i++) {
            layers[i] = new ImageView(ctx);
            layers[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            layers[i].setAlpha(0f);
            addView(layers[i], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        // One shade over the pictures: the chosen darkness, and a little more at the edges for the words.
        View shade = new View(ctx);
        shade.setBackgroundColor(prefs.saverShade() << 24);
        addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        float scale = prefs.saverClockScale();
        info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setAlpha(0f);
        time = words(h * 0.150f * scale, plain ? 0xB3FFFFFF : 0xF5FFFFFF, "sans-serif-thin");
        date = words(h * 0.024f * Math.max(0.8f, scale), plain ? 0x80FFFFFF : 0xC8FFFFFF, "sans-serif-medium");
        date.setLetterSpacing(0.16f);
        date.setPadding(0, (int) (h * 0.010f), 0, 0);
        weatherIcon = new WeatherView(ctx, (int) (h * 0.040f));
        weatherText = words(h * 0.028f, plain ? 0x99FFFFFF : 0xE6FFFFFF, "sans-serif-light");
        weatherText.setPadding((int) (w * 0.006f), 0, 0, 0);
        weatherRow = new LinearLayout(ctx);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherRow.setPadding(0, (int) (h * 0.020f), 0, 0);
        weatherRow.addView(weatherIcon);
        weatherRow.addView(weatherText);
        if (plain) weatherIcon.setAlpha(0.6f);
        info.addView(time);
        info.addView(date);
        info.addView(weatherRow);
        info.setVisibility(scale == 0f ? GONE : VISIBLE);
        addView(info, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        if (!plain) choosePictures(ctx);
    }

    private TextView words(float px, int colour, String family) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(1f, px));
        t.setTextColor(colour);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setShadowLayer(h * 0.014f, 0f, h * 0.002f, 0x99000000);
        return t;
    }

    private void choosePictures(Context ctx) {
        int style = prefs.saverStyle();
        if (style == 1) {
            String current = new Apps(ctx).wallpaper();
            if (!current.equals("none")) pictures.add(current);
        } else if (style == 2) {
            File[] mine = new File(ctx.getExternalFilesDir(null), "wallpapers").listFiles();
            if (mine != null) {
                Arrays.sort(mine);
                for (File f : mine) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                        pictures.add("file:" + f.getAbsolutePath());
                    }
                }
            }
        }
        if (pictures.isEmpty()) pictures.addAll(Arrays.asList(SCENES)); // nothing of the owner's to show
        if (pictures.size() > 1) Collections.shuffle(pictures, random);
    }

    void start() {
        if (running) return;
        running = true;
        handler.post(tick);
        handler.post(next);
        if (prefs.weather() && weather.due()) {
            new Thread(() -> {
                try {
                    weather.refresh();
                } catch (IOException | RuntimeException ignored) {
                    // Keep whatever reading there is.
                }
                handler.post(this::updateWords);
            }, "weather").start();
        }
    }

    void stop() {
        running = false;
        if (creep != null) creep.cancel();
        handler.removeCallbacksAndMessages(null);
        for (ImageView layer : layers) {
            layer.animate().cancel();
            layer.setImageDrawable(null);
        }
    }

    private void updateWords() {
        Date now = new Date();
        time.setText(new SimpleDateFormat(prefs.clock12h() ? "h:mm" : "HH:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)
                .toUpperCase(Locale.getDefault()));
        boolean show = prefs.weather() && weather.hasReading();
        weatherRow.setVisibility(show ? VISIBLE : GONE);
        if (show) {
            weatherIcon.set(weather.kind(), weather.day());
            float c = weather.tempC();
            weatherText.setText(Math.round(prefs.fahrenheit() ? c * 9f / 5f + 32f : c) + "°   " + weather.words());
        }
    }

    /** Fades to the next picture and moves the words to another corner. */
    private void showNext() {
        if (!running) return;
        final long hold = prefs.saverMillis();
        moveWords();
        handler.postDelayed(next, hold);
        if (plain || pictures.isEmpty()) return;
        if (pictures.size() == 1 && index == 0) {
            drift(layers[front], hold); // one picture: it just keeps drifting
            return;
        }
        index = (index + 1) % pictures.size();
        final String value = pictures.get(index);
        new Thread(() -> {
            final Bitmap picture = decode(value);
            handler.post(() -> {
                if (!running || picture == null) return;
                final ImageView in = layers[1 - front], out = layers[front];
                in.setImageBitmap(picture);
                drift(in, hold + FADE);
                in.bringToFront();
                reorder();
                in.setAlpha(0f);
                in.animate().alpha(1f).setDuration(FADE).withEndAction(() -> {
                    out.setAlpha(0f);
                    out.setImageDrawable(null);
                }).start();
                front = 1 - front;
            });
        }, "saver").start();
    }

    /** Keeps the shade and the words above whichever picture was just brought forward. */
    private void reorder() {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v != layers[0] && v != layers[1] && v != info) {
                v.bringToFront();
                break;
            }
        }
        info.bringToFront();
    }

    /** A slow push in and across, in a different direction each time. */
    private void drift(ImageView v, long millis) {
        float fromScale = 1.06f, toScale = 1.16f;
        if (random.nextBoolean()) {
            fromScale = 1.16f;
            toScale = 1.06f;
        }
        float dx = (random.nextFloat() - 0.5f) * w * 0.05f, dy = (random.nextFloat() - 0.5f) * h * 0.04f;
        v.animate().cancel();
        v.setScaleX(fromScale);
        v.setScaleY(fromScale);
        v.setTranslationX(-dx);
        v.setTranslationY(-dy);
        v.animate().scaleX(toScale).scaleY(toScale).translationX(dx).translationY(dy)
                .setDuration(millis).setInterpolator(new LinearInterpolator()).start();
    }

    private void moveWords() {
        if (info.getVisibility() != VISIBLE) return;
        info.animate().alpha(0f).setDuration(corner < 0 ? 0 : 1400).withEndAction(() -> {
            LayoutParams lp = (LayoutParams) info.getLayoutParams();
            int mx = (int) (w * 0.06f), my = (int) (h * 0.09f);
            if (plain) {
                lp.gravity = Gravity.CENTER;
                info.setGravity(Gravity.CENTER_HORIZONTAL);
                info.setTranslationX((random.nextFloat() - 0.5f) * w * 0.5f);
                info.setTranslationY((random.nextFloat() - 0.5f) * h * 0.5f);
            } else {
                // Bottom left, top right, bottom right, top left, round and round.
                corner = (corner + 1) % 4;
                boolean right = corner == 1 || corner == 2, bottom = corner == 0 || corner == 2;
                lp.gravity = (right ? Gravity.END : Gravity.START) | (bottom ? Gravity.BOTTOM : Gravity.TOP);
                info.setGravity(right ? Gravity.END : Gravity.START);
                weatherRow.setGravity((right ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
                lp.setMargins(mx, my, mx, my);
            }
            if (corner < 0) corner = 0;
            info.setLayoutParams(lp);
            info.animate().alpha(1f).setDuration(2200).start();
            creepFrom(plain ? info.getTranslationX() : 0f, plain ? info.getTranslationY() : 0f);
        }).start();
    }

    /**
     * Between moves the words never sit quite still: they creep a short way, too slowly to
     * notice, so no pixel shows the same thing for a whole interval.
     */
    private void creepFrom(final float x, final float y) {
        if (creep != null) creep.cancel();
        double angle = random.nextDouble() * Math.PI * 2;
        final float dx = (float) Math.cos(angle) * h * 0.035f, dy = (float) Math.sin(angle) * h * 0.035f;
        creep = android.animation.ValueAnimator.ofFloat(0f, 1f);
        creep.setDuration(prefs.saverMillis());
        creep.setInterpolator(new LinearInterpolator());
        creep.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            info.setTranslationX(x + dx * t);
            info.setTranslationY(y + dy * t);
        });
        creep.start();
    }

    private Bitmap decode(String value) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = open(value)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream in = open(value)) {
                return Apps.gpuOnly(BitmapFactory.decodeStream(in, null, opts));
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private InputStream open(String value) throws IOException {
        if (value.startsWith("asset:")) return getContext().getAssets().open("wallpapers/" + value.substring(6));
        if (value.startsWith("file:")) return new FileInputStream(value.substring(5));
        if (value.startsWith("content:")) {
            InputStream in = getContext().getContentResolver()
                    .openInputStream(android.net.Uri.parse(value.substring(8)));
            if (in != null) return in;
        }
        throw new IOException("Unknown picture: " + value);
    }
}
