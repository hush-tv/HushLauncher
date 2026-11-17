package com.hushtv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed apps, and the ordered list of the ones shown on the home row. */
final class Apps {

    static final class App {
        String pkg;
        ComponentName component;
        String label;
        Bitmap banner;     // the app's own 16:9 TV banner, if it has one
        Bitmap icon;       // the app's square icon
        Bitmap custom;     // image the user chose for this tile, if any
        Bitmap packIcon;   // this app's icon from the chosen icon pack, if it has one
        Bitmap packArt;    // this app's picture from the chosen pack folder, if it has one
        boolean ownOnly;   // the user chose this app's own banner, whatever the pack
        int group = TOOLS; // which heading it goes under in the app drawer
    }

    /** Headings in the app drawer, in the order they're shown. */
    static final int WATCH = 0, LISTEN = 1, PLAY = 2, TOOLS = 3;
    static final String[] GROUP_NAMES = {"Watch", "Listen", "Play", "Apps and tools"};

    /*
     * Hints of four letters or fewer must start a part of the package name (split on '.' and '_'),
     * so "file" doesn't match "profile" and "itv" doesn't match every "...itv..." by accident.
     * Longer hints can appear anywhere in the name.
     */
    private static final String[] WATCH_HINTS = {"netflix", "youtube", "primevideo", "amazon.avod", "disney",
            "hulu", "hbo", "plex", "jellyfin", "emby", "kodi", "xbmc", "stremio", "vlc", "mxtech",
            "tivimate", "iptv", "m3u", "mpv", "smarttube", "twitch", "bbc", "iplayer", "itv", "channel4",
            "nowtv", "sky", "paramount", "peacock", "appletv", "atve", "crunchyroll", "pluto", "plutotv",
            "tubi", "wbd", "cbs", "dazn", "espn", "video", "player", "movie"};
    private static final String[] LISTEN_HINTS = {"spotify", "music", "tidal", "deezer", "soundcloud",
            "pandora", "audible", "podcast", "radio", "tunein", "sonos", "plexamp", "mp3", "sirius"};
    private static final String[] PLAY_HINTS = {"game", "geforcenow", "stadia", "xcloud", "xbox",
            "retroarch", "emulator", "moonlight", "limelight", "steamlink", "playstation", "tegrazone"};
    /** Sport and live-stream apps whose names might otherwise look like games. */
    private static final String[] LIVE_HINTS = {"nba", "nfl", "nhl", "mlb", "dazn", "espn", "kick", "twitch", "fubo"};
    private static final String[] TOOL_HINTS = {"settings", "downloader", "browser", "file", "xplore",
            "vending", "vpn", "nordvpn", "expressvpn", "surfshark", "gallery", "weather", "mapper",
            "launcher", "upgrade", "aptoide", "mozilla", "firefox"};

    /** Puts an app under Watch, Listen, Play or Apps and tools, from what it says it is and its name. */
    static int groupOf(String pkg, ApplicationInfo info) {
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        String[] parts = p.split("[._]");
        // Names first: apps often label themselves oddly (VLC says audio, file managers say game).
        if (matches(p, parts, LIVE_HINTS)) return WATCH;
        if (matches(p, parts, TOOL_HINTS)) return TOOLS;
        if (matches(p, parts, PLAY_HINTS)) return PLAY;
        if (matches(p, parts, LISTEN_HINTS)) return LISTEN;
        if (matches(p, parts, WATCH_HINTS)) return WATCH;
        if (info != null) {
            if (info.category == ApplicationInfo.CATEGORY_VIDEO) return WATCH;
            if (info.category == ApplicationInfo.CATEGORY_AUDIO) return LISTEN;
            if (info.category == ApplicationInfo.CATEGORY_GAME || (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return PLAY;
        }
        return TOOLS;
    }

    private static boolean matches(String p, String[] parts, String[] hints) {
        for (String h : hints) {
            if (h.length() > 4) {
                if (p.contains(h)) return true;
            } else {
                for (String part : parts) if (part.startsWith(h)) return true;
            }
        }
        return false;
    }

    /**
     * Apps put first on a new user's home row when they are installed, in this order. Anything
     * else installed fills the rest of the row, so a new home screen is never empty.
     */
    private static final String[] DEFAULT_HOME = {
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.plexapp.android",
            "org.jellyfin.androidtv",
            "org.xbmc.kodi",
            "com.spotify.tv.android",
            "com.hbo.hbonow",
            "com.apple.atve.androidtv.appletv",
            "tv.twitch.android.app",
            "com.stremio.one",
            "com.teamsmart.videomanager.tv",
    };
    /** Never put on a new user's row by themselves: system tools, stores and Hush itself. */
    private static final String[] NOT_DEFAULT = {
            "com.android.tv.settings", "com.android.vending", "com.hushtv.launcher",
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
    };
    private static final int DEFAULT_COUNT = 8;

    private static final String PREFS = "home";
    private static final String KEY_HOME = "home_packages";

    private final Context ctx;
    private final SharedPreferences prefs;

    Apps(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        lowRam = am != null && (am.isLowRamDevice() || am.getMemoryClass() <= 192);
    }

    /** True on devices with little memory, where banners are kept smaller. */
    private final boolean lowRam;

    /** The launcher's own folder on shared storage, or its private one when that isn't mounted. */
    static java.io.File filesDir(Context ctx) {
        java.io.File d = ctx.getExternalFilesDir(null);
        return d != null ? d : ctx.getFilesDir();
    }

    // Values of the wrong type (e.g. from an edited backup) read as the default rather than crashing.
    private String str(String key, String def) {
        try { return prefs.getString(key, def); } catch (ClassCastException e) { return def; }
    }

    private long num(String key, long def) {
        try { return prefs.getLong(key, def); } catch (ClassCastException e) { return def; }
    }

    private int num(String key, int def) {
        try { return prefs.getInt(key, def); } catch (ClassCastException e) { return def; }
    }

    /** Every launchable app, TV entry points preferred, sorted by name. */
    List<App> loadAll() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || byPkg.containsKey(ai.packageName)) continue;
                if (ai.packageName.equals(ctx.getPackageName())) continue;
                App app = new App();
                app.pkg = ai.packageName;
                app.component = new ComponentName(ai.packageName, ai.name);
                app.label = ai.packageName;
                app.group = groupOf(ai.packageName, ai.applicationInfo);
                try {
                    CharSequence label = ri.loadLabel(pm);
                    if (label != null && label.toString().trim().length() > 0) app.label = label.toString();
                    loadArt(pm, ai, app);
                    loadCustomInto(app);
                } catch (Throwable t) {
                    // One app with broken artwork must not take the home screen down with it:
                    // it still gets a tile, drawn from whatever did load.
                }
                byPkg.put(app.pkg, app);
                // Each app's full-size banner is finished with once it has been drawn small, but
                // Android only clears such leftovers when this app's own small heap fills, which
                // loading pictures doesn't do. Left alone, thirty of them pile up to tens of
                // megabytes that then stay claimed. Clearing every few apps keeps the pile small.
                if (byPkg.size() % 4 == 0) {
                    System.gc();
                    System.runFinalization();
                }
            }
        }
        List<App> all = new ArrayList<>(byPkg.values());
        applyPack(all, iconPack());
        Collections.sort(all, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return all;
    }

    /** How wide app banners are kept, in pixels: as wide as the largest tile that will show them. */
    private int artWidth = 512;

    int artWidth() {
        return artWidth;
    }

    void setArtWidth(int px) {
        artWidth = Math.max(256, Math.min(lowRam ? 320 : 512, (px + 15) / 16 * 16)); // less memory per banner on small devices
    }

    private void loadArt(PackageManager pm, ActivityInfo ai, App app) {
        int bannerH = artWidth * 9 / 16;
        // Most banners are plain picture files, often far larger than a tile. Those are read
        // straight from the app at a reduced size, so the full-size picture is never in memory.
        app.banner = smallBanner(pm, ai, artWidth, bannerH);
        if (app.banner == null) {
            Drawable banner = null;
            try {
                banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            } catch (RuntimeException ignored) {
            }
            if (banner != null) app.banner = render(banner, artWidth, bannerH);
        }
        Drawable icon = null;
        try {
            icon = ai.loadIcon(pm);
        } catch (RuntimeException ignored) {
        }
        if (icon != null) app.icon = render(icon, 144, 144);
    }

    /** An app's banner decoded at about the size wanted, or null if it isn't a plain picture file. */
    private static Bitmap smallBanner(PackageManager pm, ActivityInfo ai, int w, int h) {
        int id = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        if (id == 0) return null;
        try {
            android.content.res.Resources res = pm.getResourcesForApplication(ai.applicationInfo);
            android.util.TypedValue value = new android.util.TypedValue();
            res.getValue(id, value, true);
            String file = value.string == null ? "" : value.string.toString().toLowerCase(java.util.Locale.ROOT);
            if (!(file.endsWith(".png") || file.endsWith(".jpg") || file.endsWith(".jpeg") || file.endsWith(".webp"))
                    || file.endsWith(".9.png")) {
                return null;
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = res.openRawResource(id)) {
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap decoded;
            try (java.io.InputStream in = res.openRawResource(id)) {
                decoded = android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            if (decoded == null) return null;
            // Stretched to the tile's shape exactly as the ordinary route would draw it.
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(decoded, null, new android.graphics.Rect(0, 0, w, h),
                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            decoded.recycle();
            return out;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap render(Drawable d, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return bmp;
    }

    /** Package names on the home row, in order. */
    List<String> homePackages() {
        String saved = str(KEY_HOME, null);
        if (saved == null) return new ArrayList<>(Arrays.asList(DEFAULT_HOME));
        List<String> out = new ArrayList<>();
        for (String s : saved.split(",")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    void saveHome(List<String> pkgs) {
        prefs.edit().putString(KEY_HOME, TextUtils.join(",", pkgs)).apply();
    }

    /** Home row apps in order, skipping anything not installed. */
    List<App> homeApps(List<App> all) {
        List<App> out = new ArrayList<>();
        boolean chosen = str(KEY_HOME, null) != null;
        for (String pkg : homePackages()) {
            for (App a : all) {
                if (a.pkg.equals(pkg)) {
                    out.add(a);
                    break;
                }
            }
        }
        // Nothing chosen yet: fill the row with what this TV has, so it never starts empty.
        if (!chosen) {
            java.util.Set<String> launchers = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo r : ctx.getPackageManager().queryIntentActivities(
                    new android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_HOME), 0)) {
                launchers.add(r.activityInfo.packageName);
            }
            for (App a : all) {
                if (launchers.contains(a.pkg)) continue;
                if (out.size() >= DEFAULT_COUNT) break;
                if (out.contains(a) || Arrays.asList(NOT_DEFAULT).contains(a.pkg)) continue;
                out.add(a);
            }
        }
        return out;
    }

    /** Placeholder tiles for previewing the layout on a device that doesn't have the real apps. */
    List<App> demoApps() {
        Object[][] specs = {
                {"ar.tvplayer.tv", "TiviMate", 0xFFFFFFFF, 0xFF1583C6},
                {"com.stremio.one", "Stremio", 0xFF120B2E, 0xFFFFFFFF},
                {"com.google.android.youtube.tv", "YouTube", 0xFFF1F1F1, 0xFFE00000},
                {"com.nbaimd.gametime.nba2011", "NBA", 0xFF1A1C22, 0xFFFFFFFF},
                {"com.nordvpn.android", "NordVPN", 0xFF3E5FFF, 0xFFFFFFFF},
                {"com.spotify.tv.android", "Spotify", 0xFF000000, 0xFF1ED760},
                {"org.xbmc.kodi", "Kodi", 0xFFD8D8D8, 0xFF1B1B1B},
                {"demo.netflix", "Netflix", 0xFF000000, 0xFFE50914},
                {"demo.prime", "Prime Video", 0xFF0F79AF, 0xFFFFFFFF},
                {"demo.dazn", "DAZN", 0xFF0C161C, 0xFFF8FC00},
                {"demo.itvx", "ITVX", 0xFFDEEB52, 0xFF0F2A33},
                {"demo.kick", "Kick", 0xFF0B0E0F, 0xFF53FC18},
                {"demo.vlc", "VLC", 0xFFFF8800, 0xFFFFFFFF},
                {"demo.mpv", "mpv", 0xFF6B2A77, 0xFFFFFFFF},
                {"demo.downloader", "Downloader", 0xFFF47A20, 0xFFFFFFFF},
                {"demo.xplore", "X-plore", 0xFF24364B, 0xFFFFFFFF},
                {"demo.settings", "Settings", 0xFF2A2D33, 0xFFFFFFFF},
        };
        List<App> out = new ArrayList<>();
        android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        text.setTextAlign(android.graphics.Paint.Align.CENTER);
        for (Object[] spec : specs) {
            App a = new App();
            a.pkg = (String) spec[0];
            a.label = (String) spec[1];
            a.group = groupOf(a.pkg, null);
            a.banner = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(a.banner);
            c.drawColor((Integer) spec[2]);
            text.setColor((Integer) spec[3]);
            text.setTextSize(a.label.length() > 9 ? 78 : 104);
            c.drawText(a.label, 320, 180 - (text.ascent() + text.descent()) / 2, text);
            out.add(a);
        }
        return out;
    }

    /** The image file the user picked for this app's tile, or null. */
    String tileImage(String pkg) {
        return str("tileimg_" + pkg, null);
    }

    void setTileImage(String pkg, String path) {
        if (path == null) prefs.edit().remove("tileimg_" + pkg).apply();
        else prefs.edit().putString("tileimg_" + pkg, path).apply();
    }

    /** Value of a tile-image choice meaning "always this app's own banner". */
    static final String OWN = "own";

    /** Re-reads the user's picture choice for one app. */
    void loadCustomInto(App app) {
        app.ownOnly = OWN.equals(tileImage(app.pkg));
        app.custom = app.ownOnly ? null : loadCustom(app.pkg);
    }

    Bitmap loadCustom(String pkg) {
        String path = tileImage(pkg);
        if (OWN.equals(path)) return null;
        if (path == null) {
            // No explicit choice: use a picture in the tiles folder named after the app's package.
            java.io.File dir = new java.io.File(filesDir(ctx), "tiles");
            for (String ext : new String[]{".png", ".jpg", ".webp"}) {
                java.io.File f = new java.io.File(dir, pkg + ext);
                if (f.isFile()) path = f.getAbsolutePath();
            }
        }
        return path == null ? null : decodeSampled(path);
    }

    /** Decodes a tile picture at no more than about 512 pixels across, however large the file is. */
    static Bitmap decodeSampled(String path) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            int sample = 1;
            // By the longer side, so a very tall picture is shrunk too.
            int longest = Math.max(o.outWidth, o.outHeight);
            while (longest / (sample * 2) >= 512) sample *= 2;
            // And never more than about four tiles' worth of pixels (512 x 288 each).
            while ((long) (o.outWidth / sample) * (o.outHeight / sample) > 4L * 512 * 288) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(path, o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    long lastRotation() {
        return num("wallpaper_rotated", 0L);
    }

    void setLastRotation(long when) {
        prefs.edit().putLong("wallpaper_rotated", when).apply();
    }

    /** Prefix marking an icon pack that is a folder of pictures rather than an installed app. */
    static final String DIR_PACK = "dir:";

    /** Each sub-folder here is an icon pack: pictures named after the app's package. */
    java.io.File packsDir() {
        java.io.File d = new java.io.File(filesDir(ctx), "packs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Every pack that can be chosen, as {value, name}; the first entry is "no pack". */
    List<String[]> packChoices() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{null, "Standard (apps' own banners)"});
        java.io.File[] dirs = packsDir().listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (java.io.File d : dirs) {
                if (d.isDirectory()) out.add(new String[]{DIR_PACK + d.getName(), d.getName()});
            }
        }
        Map<String, String> installed = IconPacks.installed(ctx);
        for (String pkg : installed.keySet()) out.add(new String[]{pkg, installed.get(pkg)});
        return out;
    }

    /** This app's picture in a folder pack, or null if the pack has none (or isn't a folder pack). */
    java.io.File packFile(String pack, String pkg) {
        if (pack == null || !pack.startsWith(DIR_PACK)) return null;
        java.io.File dir = new java.io.File(packsDir(), pack.substring(DIR_PACK.length()));
        for (String ext : new String[]{".png", ".webp", ".jpg"}) {
            java.io.File f = new java.io.File(dir, pkg + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    /**
     * Gives every app its picture from {@code pack} (null for none), without reloading the apps'
     * own artwork. This is what makes stepping through packs quick.
     */
    void applyPack(List<App> all, String pack) {
        IconPacks installed = null;
        if (pack != null && !pack.startsWith(DIR_PACK)) {
            try {
                installed = new IconPacks(ctx, pack);
            } catch (Exception e) {
                // The pack was uninstalled or is unreadable: fall back to the apps' own art.
            }
        }
        for (App a : all) {
            Bitmap art = null, icon = null;
            try {
                java.io.File f = packFile(pack, a.pkg);
                if (f != null) art = decodeSampled(f.getAbsolutePath());
                else if (installed != null) icon = installed.icon(a);
            } catch (Throwable t) {
                // A bad image just means this app keeps its own picture.
            }
            a.packArt = art;
            a.packIcon = icon;
        }
    }

    /**
     * Moves a finished picture into graphics memory only. A normal bitmap that gets drawn is
     * held twice, once by the app and once as a texture; this keeps just the texture.
     * The result can only be drawn to the screen, not edited or read back.
     */
    static Bitmap gpuOnly(Bitmap bmp) {
        if (bmp == null || bmp.getConfig() == Bitmap.Config.HARDWARE) return bmp;
        try {
            Bitmap hw = bmp.copy(Bitmap.Config.HARDWARE, false);
            if (hw != null) {
                bmp.recycle();
                return hw;
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            // Keep the ordinary bitmap.
        }
        return bmp;
    }

    /** Package name of the chosen icon pack, a DIR_PACK folder, or null for none. */
    String iconPack() {
        return str("icon_pack", null);
    }

    void setIconPack(String pkg) {
        if (pkg == null) prefs.edit().remove("icon_pack").apply();
        else prefs.edit().putString("icon_pack", pkg).apply();
    }

    String wallpaper() {
        return str("wallpaper", "asset:dusk_ridge.jpg");
    }

    void setWallpaper(String value) {
        prefs.edit().putString("wallpaper", value).apply();
    }

    /** Package of the app to open when the TV starts, or null for none. */
    String bootApp() {
        return str("boot_app", null);
    }

    void setBootApp(String pkg) {
        if (pkg == null) prefs.edit().remove("boot_app").apply();
        else prefs.edit().putString("boot_app", pkg).apply();
    }

    /**
     * True the first time it is asked after each start of the TV. {@code bootCount} is Android's
     * count of how many times the device has booted, or -1 if it isn't available.
     */
    boolean firstRunThisBoot(int bootCount) {
        if (bootCount < 0 || num(Backup.BOOT_SEEN, -1) == bootCount) return false;
        prefs.edit().putInt(Backup.BOOT_SEEN, bootCount).apply();
        return true;
    }

    /** Allows the launcher to restart itself to shed memory at most twice per start of the TV. */
    boolean maySlimDown(int bootCount) {
        int count = num("slim_boot", -2) == bootCount ? num("slim_count", 0) : 0;
        if (count >= 2) return false;
        prefs.edit().putInt("slim_boot", bootCount).putInt("slim_count", count + 1).commit();
        return true;
    }
}
