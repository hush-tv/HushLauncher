package com.hushtv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Every user-adjustable option. Each one is a short list of named choices stored as an index. */
final class Prefs {

    static final class Setting {
        final String key, group, title;
        final String[] options;
        final int def;

        Setting(String group, String key, String title, int def, String... options) {
            this.group = group;
            this.key = key;
            this.title = title;
            this.def = def;
            this.options = options;
        }
    }

    static final Setting[] ALL = {
            new Setting("Tiles", "tile_size", "Tile size", 1, "Small", "Medium", "Large", "Extra large"),
            new Setting("Tiles", "row_pos", "Row position", 2, "Upper", "Middle", "Lower", "Bottom"),
            new Setting("Tiles", "spacing", "Tile spacing", 1, "Tight", "Normal", "Wide"),
            new Setting("Tiles", "corners", "Corners", 1, "Square", "Soft", "Round"),
            new Setting("Tiles", "finish", "Tile finish", 1, "Flat", "Glass", "Deep"),
            new Setting("Tiles", "dark_tiles", "Dark tile style", 0, "Off", "On"),
            new Setting("Tiles", "soften", "Soften bright tiles", 1, "Off", "On"),
            new Setting("Tiles", "labels", "App names", 0, "Off", "Selected tile only", "Always"),
            new Setting("Tiles", "ambient", "Hide tiles when idle", 0, "Off", "After 1 minute", "After 5 minutes"),
            new Setting("Selection", "focus", "Selected tile effect", 0, "Outline and zoom", "Zoom only", "Outline only"),
            new Setting("Selection", "zoom", "Zoom amount", 1, "Subtle", "Normal", "Strong"),
            new Setting("Selection", "accent", "Outline colour", 0, "White", "Green", "Blue", "Amber", "Red", "Purple", "Match wallpaper"),
            new Setting("Selection", "glow", "Glow", 1, "Off", "Soft", "Strong"),
            new Setting("Selection", "colour_glow", "Colour glow", 1, "Off", "Subtle", "Soft", "Strong"),
            new Setting("Selection", "shine", "Shine", 1, "Off", "Subtle", "Soft", "Strong"),
            new Setting("Selection", "dim", "Dim other tiles", 1, "Off", "Light", "Strong"),
            new Setting("Selection", "anim", "Animation speed", 1, "Instant", "Fast", "Smooth"),
            new Setting("Clock", "clock", "Clock", 2, "Off", "Time", "Time and date"),
            new Setting("Clock", "clock_size", "Clock size", 1, "Small", "Normal", "Large"),
            new Setting("Clock", "clock_fmt", "Time format", 0, "24-hour", "12-hour"),
            new Setting("Clock", "status", "Corner icons", 0, "Show", "Hide"),
            new Setting("Clock", "clock_pos", "Clock position", 0, "Top right", "Top left"),
            new Setting("Clock", "greeting", "Greeting", 0, "Off", "On"),
            new Setting("Clock", "weather", "Weather", 0, "Off", "On"),
            new Setting("Clock", "temp_unit", "Temperature", 0, "\u00B0C", "\u00B0F"),
            new Setting("Wallpaper", "wp_auto", "Follow the time of day", 0, "Off", "On"),
            new Setting("Wallpaper", "stars", "Stars on clear nights", 1, "Off", "On"),
            new Setting("Wallpaper", "sky", "Weather effects", 2, "Off", "Subtle", "Normal"),
            new Setting("Wallpaper", "wp_dim", "Wallpaper dimming", 1, "None", "Light", "Medium", "Heavy"),
            new Setting("Wallpaper", "night_dim", "Darker at night", 0, "Off", "On"),
            new Setting("Wallpaper", "wp_blur", "Wallpaper blur", 0, "Off", "Light", "Strong"),
            new Setting("Wallpaper", "wp_rotate", "Rotate your images", 0, "Off", "Every 30 minutes", "Every hour", "Every day"),
            new Setting("Screensaver", "saver_style", "Shows", 0, "Night scenes", "Current wallpaper",
                    "Your pictures", "Clock on black"),
            new Setting("Screensaver", "saver_every", "Change picture", 1, "Every minute", "Every 5 minutes",
                    "Every 15 minutes"),
            new Setting("Screensaver", "saver_clock", "Clock", 2, "Off", "Small", "Large"),
            new Setting("Screensaver", "saver_dim", "Brightness", 1, "Bright", "Dimmed", "Dark"),
            new Setting("All apps", "down_opens", "Down opens all apps", 0, "Off", "On"),
            new Setting("All apps", "columns", "Columns", 1, "5", "6", "7"),
            new Setting("All apps", "drawer_groups", "Group by type", 1, "Off", "On"),
            new Setting("System", "auto_update", "Check for updates", 1, "Off", "Once a day"),
            new Setting("System", "lite", "Lite mode", 0, "Off", "On"),
    };

    private static final int[] ACCENTS = {0xFFFFFFFF, 0xFF76B900, 0xFF4C8DFF, 0xFFFFB020, 0xFFFF4D4D, 0xFFA070FF};

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        // On a first run on a small TV, start in Lite mode. An existing setup (any saved setting)
        // keeps its effects, so an update never changes how someone's screen looks.
        if (!sp.contains("lite") && sp.getAll().isEmpty()) {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && (am.isLowRamDevice() || am.getMemoryClass() <= 128)) {
                sp.edit().putInt("lite", 1).apply();
            } else {
                sp.edit().putInt("lite", 0).apply();
            }
        }
    }

    /** Lite mode: the moving and costly effects are off, whatever their own settings say. */
    boolean lite() { return get("lite") == 1; }

    /** Animation speed, read as Fast in Lite mode. */
    private int anim() { return lite() ? 1 : get("anim"); }

    int get(Setting s) {
        int v;
        try {
            v = sp.getInt(s.key, s.def);
        } catch (ClassCastException e) {
            return s.def; // a value of the wrong type, e.g. from a hand-edited backup
        }
        return v < 0 || v >= s.options.length ? s.def : v;
    }

    private int get(String key) {
        for (Setting s : ALL) if (s.key.equals(key)) return get(s);
        throw new IllegalArgumentException(key);
    }

    void step(Setting s, int delta) {
        int n = s.options.length;
        sp.edit().putInt(s.key, ((get(s) + delta) % n + n) % n).apply();
    }

    void set(Setting s, int index) {
        if (index >= 0 && index < s.options.length) sp.edit().putInt(s.key, index).apply();
    }

    void set(String key, int index) {
        for (Setting s : ALL) if (s.key.equals(key)) set(s, index);
    }

    void reset() {
        sp.edit().clear().apply();
    }

    float tileWidthFrac() { return new float[]{0.125f, 0.150f, 0.178f, 0.210f}[get("tile_size")]; }
    float rowTopFrac() { return new float[]{0.42f, 0.56f, 0.70f, 0.78f}[get("row_pos")]; }
    float gapFrac() { return new float[]{0.009f, 0.016f, 0.026f}[get("spacing")]; }
    float cornerFrac() { return new float[]{0f, 0.11f, 0.24f}[get("corners")]; }
    /** 0 = flat artwork, 1 = light glass finish, 2 = stronger. */
    int finish() { return get("finish"); }
    boolean darkTiles() { return get("dark_tiles") == 1; }
    int labelMode() { return get("labels"); }
    boolean softenBright() { return get("soften") == 1; }
    boolean outline() { return get("focus") != 1; }
    float zoom() { return get("focus") == 2 ? 1f : new float[]{1.08f, 1.18f, 1.28f}[get("zoom")]; }
    /** A colour taken from the wallpaper on show, for the "Match wallpaper" outline colour. */
    static volatile int wallpaperAccent = 0xFFFFFFFF;

    boolean accentFromWallpaper() { return get("accent") >= ACCENTS.length; }
    int accent() { return accentFromWallpaper() ? wallpaperAccent : ACCENTS[get("accent")]; }
    float clockScale() { return new float[]{0.80f, 1f, 1.28f}[get("clock_size")]; }
    boolean greeting() { return get("greeting") == 1; }
    boolean weather() { return get("weather") == 1; }
    boolean fahrenheit() { return get("temp_unit") == 1; }
    /** How long the home screen sits untouched before the tiles fade away; 0 = never. */
    long ambientMillis() { return new long[]{0, 60_000L, 300_000L}[get("ambient")]; }
    /** 0 night scenes, 1 the current wallpaper, 2 the owner's pictures, 3 a clock on black. */
    int saverStyle() { return get("saver_style"); }
    long saverMillis() { return new long[]{60_000L, 300_000L, 900_000L}[get("saver_every")]; }
    float saverClockScale() { return new float[]{0f, 0.6f, 1f}[get("saver_clock")]; }
    int saverShade() { return new int[]{0x00, 0x40, 0x80}[get("saver_dim")]; }
    /** Opacity of the light behind the row at its centre, out of 255; 0 = off. */
    int colourGlow() { return lite() ? 0 : new int[]{0, 0x26, 0x44, 0x78}[get("colour_glow")]; }
    /** How bright the band of light that crosses a newly selected tile is, out of 255; 0 = off. */
    int shine() { return lite() ? 0 : new int[]{0, 0x16, 0x30, 0x52}[get("shine")]; }
    boolean wallpaperAuto() { return get("wp_auto") == 1; }
    boolean stars() { return !lite() && get("stars") == 1; }
    boolean autoUpdate() { return get("auto_update") == 1; }

    float skyStrength() { return lite() ? 0f : new float[]{0f, 0.55f, 1f}[get("sky")]; }
    boolean nightDim() { return get("night_dim") == 1; }
    int animMillis() { return new int[]{0, 170, 280}[anim()]; }
    /** Stiffness of the row's scrolling spring; higher settles faster. 0 = jump instantly. */
    float scrollStiffness() { return new float[]{0f, 17f, 11f}[anim()]; }
    /** While Left or Right is held, the shortest time between steps along the row. */
    long holdStepMillis() { return new long[]{70L, 105L, 135L}[anim()]; }
    /** 0 = no glow, otherwise its opacity out of 255. */
    int glowAlpha() { return lite() ? 0 : new int[]{0, 0x70, 0xC8}[get("glow")]; }
    float glowSpreadFrac() { return lite() ? 0f : new float[]{0f, 0.20f, 0.30f}[get("glow")]; }
    int dimAlpha() { return new int[]{0, 0x40, 0x80}[get("dim")]; }
    int clockMode() { return get("clock"); }
    boolean clock12h() { return get("clock_fmt") == 1; }
    boolean statusIcons() { return get("status") == 0; }
    boolean clockLeft() { return get("clock_pos") == 1; }
    int wallpaperDimAlpha() { return new int[]{0, 0x40, 0x80, 0xB3}[get("wp_dim")]; }
    /** The smallest size the wallpaper is shrunk to when blurring it, as a divisor; 0 = no blur. */
    int wallpaperBlur() { return lite() ? 0 : new int[]{0, 8, 32}[get("wp_blur")]; }
    long rotateMillis() { return lite() ? 0 : new long[]{0, 30 * 60000L, 60 * 60000L, 24 * 60 * 60000L}[get("wp_rotate")]; }
    boolean downOpensDrawer() { return get("down_opens") == 1; }
    int columns() { return 5 + get("columns"); }
    boolean drawerGroups() { return get("drawer_groups") == 1; }
}
