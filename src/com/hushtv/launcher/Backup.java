package com.hushtv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Saves every launcher setting (the options, the home row, wallpaper, icon pack and tile
 * pictures) to one file and reads it back. The file sits in the launcher's own folder on the
 * TV, which Android deletes if the app is uninstalled, so tools/backup.sh copies it to a computer.
 */
final class Backup {
    /** The two settings stores. */
    private static final String[] STORES = {"settings", "home"};
    /** Describes this boot of this device, so it is not carried over. */
    static final String BOOT_SEEN = "boot_seen";
    /** Belong to this install, not to the user's choices, so a restore never brings them back. */
    private static final List<String> NOT_RESTORED =
            Arrays.asList("update_checked", "update_latest", "update_url", "boot_app",
                    Apps.KIDS_PIN_HASH, Apps.KIDS_PIN_SALT);
    /** The kids mode PIN never leaves the TV, not even as a hash. */
    private static final List<String> NOT_SAVED = Arrays.asList(Apps.KIDS_PIN_HASH, Apps.KIDS_PIN_SALT);

    private Backup() {
    }

    static File file(Context ctx) {
        return new File(Apps.filesDir(ctx), "backup/launcher-backup.json");
    }

    static void save(Context ctx) throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("saved", System.currentTimeMillis());
            for (String store : STORES) {
                JSONObject out = new JSONObject();
                for (Map.Entry<String, ?> e : ctx.getSharedPreferences(store, Context.MODE_PRIVATE)
                        .getAll().entrySet()) {
                    Object v = e.getValue();
                    if (e.getKey().equals(BOOT_SEEN) || e.getKey().startsWith("slim_")
                            || NOT_SAVED.contains(e.getKey())) continue;
                    String type = v instanceof Integer ? "i" : v instanceof Long ? "l"
                            : v instanceof Boolean ? "b" : v instanceof Float ? "f"
                            : v instanceof String ? "s" : null;
                    if (type == null) continue;
                    out.put(e.getKey(), new JSONObject().put("t", type).put("v", v));
                }
                root.put(store, out);
            }
            File f = file(ctx);
            File dir = f.getParentFile();
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new IOException("no folder");
            // Written to a second file first, so a failure can't leave half a backup behind.
            File tmp = new File(dir, f.getName() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(root.toString(2).getBytes("UTF-8"));
            }
            if (!tmp.renameTo(f)) throw new IOException("could not replace the old backup");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Replaces the current settings with the saved ones. Nothing changes if the file is unreadable. */
    static void restore(Context ctx) throws IOException {
        try {
            File f = file(ctx);
            // A settings file is a few kilobytes; anything huge is not one and would only use up memory.
            if (f.length() > 1024 * 1024) throw new IOException("backup too large");
            byte[] data = new byte[(int) f.length()];
            try (FileInputStream is = new FileInputStream(f)) {
                int at = 0, n;
                while (at < data.length && (n = is.read(data, at, data.length - at)) > 0) at += n;
            }
            JSONObject root = new JSONObject(new String(data, "UTF-8"));
            // Later versions may add keys; those this version doesn't know are simply skipped.
            if (root.optInt("version") < 1) throw new IOException("unknown backup version");
            String here = Apps.filesDir(ctx).getAbsolutePath();
            // Read everything first, so a damaged file throws before any setting is cleared.
            List<SharedPreferences.Editor> edits = new ArrayList<>();
            for (String store : STORES) {
                JSONObject in = root.optJSONObject(store);
                if (in == null) continue;
                SharedPreferences sp = ctx.getSharedPreferences(store, Context.MODE_PRIVATE);
                SharedPreferences.Editor ed = sp.edit();
                ed.clear();
                for (String key : NOT_RESTORED) keep(sp, ed, key);
                keep(sp, ed, BOOT_SEEN);
                for (Iterator<String> it = in.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (key.equals(BOOT_SEEN) || NOT_RESTORED.contains(key)) continue;
                    JSONObject item = in.optJSONObject(key);
                    if (item == null) continue;
                    String t = item.optString("t");
                    // A value of a type the app doesn't read that key as would crash it at startup.
                    String want = expectedType(sp, key);
                    if (want != null && !want.equals(t)) continue;
                    switch (t) {
                        case "i": ed.putInt(key, item.getInt("v")); break;
                        case "l": ed.putLong(key, item.getLong("v")); break;
                        case "b": ed.putBoolean(key, item.getBoolean("v")); break;
                        case "f": ed.putFloat(key, (float) item.getDouble("v")); break;
                        case "s": ed.putString(key, portable(item.getString("v"), here)); break;
                        default: break;
                    }
                }
                edits.add(ed);
            }
            for (SharedPreferences.Editor ed : edits) ed.commit();
        } catch (JSONException | RuntimeException e) {
            throw new IOException(e);
        }
    }

    private static final List<String> STRINGS = Arrays.asList("home_packages", "wallpaper", "icon_pack",
            "weather_place", "boot_app", "update_latest", "update_url", Apps.KIDS_APPS, Apps.HIDDEN_APPS);
    private static final List<String> FLOATS = Arrays.asList("weather_temp", "weather_hi", "weather_lo",
            "weather_lat", "weather_lon");
    private static final List<String> LONGS = Arrays.asList("weather_at", "weather_tried_at", "update_checked",
            "wallpaper_rotated");

    /** The type code the app reads {@code key} as, or null if it can't tell (then anything goes). */
    private static String expectedType(SharedPreferences sp, String key) {
        for (Prefs.Setting s : Prefs.ALL) if (s.key.equals(key)) return "i";
        if (STRINGS.contains(key) || key.startsWith("tileimg_")
                || key.startsWith(Apps.LABEL_PREFIX)) return "s";
        if (FLOATS.contains(key)) return "f";
        if (LONGS.contains(key)) return "l";
        if (key.equals("weather_code")) return "i";
        if (key.equals("weather_day") || key.startsWith("setup_") || key.equals("home_setting_tried")
                || key.equals(Apps.KIDS_ON)) return "b";
        Object v = sp.getAll().get(key);
        return v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Boolean ? "b"
                : v instanceof Float ? "f" : v instanceof String ? "s" : null;
    }

    private static final String OWN_FOLDER = "/Android/data/com.hushtv.launcher/files";

    /**
     * Points a picture path saved on another device or user (e.g. /storage/emulated/10/...) at
     * this one's folder, so a backup carries over.
     */
    private static String portable(String v, String here) {
        if (v == null) return null;
        int at = v.indexOf(OWN_FOLDER + "/");
        if (at < 0) return v;
        String prefix = v.startsWith("file:") ? "file:" : "";
        if (!prefix.isEmpty() && !v.startsWith("file:/")) return v;
        return prefix + here + v.substring(at + OWN_FOLDER.length());
    }

    /** Puts the current value of {@code key} back after the editor's clear(). */
    private static void keep(SharedPreferences sp, SharedPreferences.Editor ed, String key) {
        Object v = sp.getAll().get(key);
        if (v instanceof Integer) ed.putInt(key, (Integer) v);
        else if (v instanceof Long) ed.putLong(key, (Long) v);
        else if (v instanceof Boolean) ed.putBoolean(key, (Boolean) v);
        else if (v instanceof Float) ed.putFloat(key, (Float) v);
        else if (v instanceof String) ed.putString(key, (String) v);
    }
}
