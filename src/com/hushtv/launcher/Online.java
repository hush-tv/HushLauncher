package com.hushtv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Online wallpapers: lists of pictures from Wikimedia Commons' featured photos and from NASA's
 * public-domain image library. Nothing is bundled and nothing is fetched until the online gallery
 * is opened; a picture is only downloaded when it is chosen. Every call here is for a background thread.
 */
final class Online {
    /**
     * Set to "Hush-TV-launcher/<version> (https://github.com/hush-tv/HushLauncher)" when the gallery
     * opens; Wikimedia asks for a descriptive agent with a contact address.
     */
    static String agent = "Hush-TV-launcher (https://github.com/hush-tv/HushLauncher)";

    /** Wikimedia Commons featured-picture categories: how each is shown, and its category name. */
    static final String[][] COMMONS = {
            {"Landscapes", "Featured_pictures_of_landscapes"}, {"Mountains", "Featured_pictures_of_mountains"},
            {"Coasts", "Featured_pictures_of_coasts"}, {"Lakes", "Featured_pictures_of_lakes"},
            {"Waterfalls", "Featured_pictures_of_waterfalls"}, {"Sunsets", "Featured_pictures_of_sunsets"},
            {"Snow", "Featured_pictures_of_snow"}, {"Forests", "Featured_pictures_of_forests"},
            {"Cities", "Featured_pictures_of_cityscapes"}, {"Astronomy", "Featured_pictures_of_astronomy"}};
    private static final String COMMONS_API = "https://commons.wikimedia.org/w/api.php?action=query&format=json";
    /** NASA library searches, each shown as its own source. */
    static final String[] NASA = {"earth from space", "galaxy", "nebula", "aurora", "milky way"};

    private static final long CACHE_MILLIS = 6 * 60 * 60_000L;
    private static final int LIST_MAX = 2_000_000, THUMB_MAX = 3_000_000, PICTURE_MAX = 25 * 1024 * 1024;

    /**
     * One picture: {@code full} is the picture itself, or for NASA the list of its sizes. For Commons,
     * {@code page} is the file's page ("File:...") and {@code width} its original width.
     */
    static final class Item {
        final String title, credit, thumb, full, page;
        final boolean nasa;
        final int width;

        Item(String title, String credit, String thumb, String full, boolean nasa, String page, int width) {
            this.title = title;
            this.credit = credit;
            this.thumb = thumb;
            this.full = full;
            this.nasa = nasa;
            this.page = page;
            this.width = width;
        }
    }

    private static final Map<String, List<Item>> CACHE = new HashMap<>();
    private static final Map<String, Long> CACHED_AT = new HashMap<>();

    private Online() {
    }

    /** Featured photos in one Commons category (wide JPEG or PNG only); kept in memory for six hours. */
    static List<Item> commons(String category) throws IOException {
        String key = "commons/" + category;
        List<Item> kept = cached(key);
        if (kept != null) return kept;
        List<Item> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(text(COMMONS_API + "&generator=categorymembers&gcmtitle=Category:"
                    + URLEncoder.encode(category, "UTF-8") + "&gcmtype=file&gcmlimit=50&prop=imageinfo"
                    + "&iiprop=" + URLEncoder.encode("url|size|extmetadata", "UTF-8") + "&iiurlwidth=640"));
            JSONObject query = root.optJSONObject("query");
            JSONObject pages = query == null ? null : query.optJSONObject("pages");
            if (pages == null) return out;
            java.util.Iterator<String> keys = pages.keys();
            while (keys.hasNext()) {
                JSONObject page = pages.optJSONObject(keys.next());
                JSONArray infos = page == null ? null : page.optJSONArray("imageinfo");
                if (infos == null || infos.length() == 0) continue;
                JSONObject info = infos.getJSONObject(0);
                int w = info.optInt("width"), h = info.optInt("height");
                if (w < 1920 || w <= h) continue;
                // Wikimedia adds tracking parameters to its links; they aren't needed, so they go.
                String url = noQuery(info.optString("url", "")), thumb = noQuery(info.optString("thumburl", ""));
                String lower = url.toLowerCase(Locale.US);
                if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png")) continue;
                if (!url.startsWith("https://") || !thumb.startsWith("https://")) continue;
                String pageTitle = page.optString("title", "File:Picture");
                JSONObject meta = info.optJSONObject("extmetadata");
                String title = plain(meta, "ObjectName");
                if (title.isEmpty()) {
                    title = pageTitle.startsWith("File:") ? pageTitle.substring(5) : pageTitle;
                    int dot = title.lastIndexOf('.');
                    if (dot > 0) title = title.substring(0, dot);
                }
                String artist = plain(meta, "Artist"), licence = plain(meta, "LicenseShortName");
                StringBuilder credit = new StringBuilder();
                if (!artist.isEmpty()) credit.append(artist);
                if (!licence.isEmpty()) credit.append(credit.length() > 0 ? ", " : "").append(licence);
                credit.append(credit.length() > 0 ? " \u00B7 " : "").append("Wikimedia Commons");
                out.add(new Item(title, credit.toString(), thumb, url, false, pageTitle, w));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        java.util.Collections.shuffle(out);
        return keep(key, out);
    }

    private static String noQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    /** One extmetadata value as plain text: tags removed, common entities decoded, spaces tidied. */
    private static String plain(JSONObject meta, String name) {
        JSONObject field = meta == null ? null : meta.optJSONObject(name);
        if (field == null) return "";
        String v = field.optString("value", "").replaceAll("<[^>]*>", " ");
        v = v.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
        v = v.replaceAll("\\s+", " ").trim();
        return v.length() > 120 ? v.substring(0, 120).trim() + "\u2026" : v;
    }

    /** For a very large Commons original, a 3840-wide copy Wikimedia scales for us. */
    private static String commonsScaled(Item item) throws IOException {
        try {
            JSONObject root = new JSONObject(text(COMMONS_API + "&prop=imageinfo&iiprop=url&iiurlwidth=3840&titles="
                    + URLEncoder.encode(item.page, "UTF-8")));
            JSONObject pages = root.getJSONObject("query").getJSONObject("pages");
            java.util.Iterator<String> keys = pages.keys();
            while (keys.hasNext()) {
                JSONArray infos = pages.getJSONObject(keys.next()).optJSONArray("imageinfo");
                if (infos == null || infos.length() == 0) continue;
                String thumb = noQuery(infos.getJSONObject(0).optString("thumburl", ""));
                if (thumb.startsWith("https://")) return thumb;
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return item.full;
    }

    /** NASA Image and Video Library pictures for one search; kept in memory for six hours. */
    static List<Item> nasa(String query) throws IOException {
        String key = "nasa/" + query;
        List<Item> kept = cached(key);
        if (kept != null) return kept;
        List<Item> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(text("https://images-api.nasa.gov/search?q="
                    + URLEncoder.encode(query, "UTF-8") + "&media_type=image"));
            JSONArray items = root.getJSONObject("collection").getJSONArray("items");
            for (int i = 0; i < items.length() && out.size() < 60; i++) {
                JSONObject it = items.getJSONObject(i);
                JSONArray links = it.optJSONArray("links");
                JSONArray data = it.optJSONArray("data");
                String manifest = https(it.optString("href", ""));
                if (links == null || links.length() == 0 || manifest == null) continue;
                String thumb = https(links.getJSONObject(0).optString("href", ""));
                if (thumb == null) continue;
                String title = data != null && data.length() > 0
                        ? data.getJSONObject(0).optString("title", "Picture") : "Picture";
                out.add(new Item(title, "NASA", thumb, manifest, true, null, 0));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return keep(key, out);
    }

    private static List<Item> cached(String key) {
        synchronized (CACHE) {
            Long at = CACHED_AT.get(key);
            if (at == null || System.currentTimeMillis() - at > CACHE_MILLIS) return null;
            return CACHE.get(key);
        }
    }

    private static List<Item> keep(String key, List<Item> list) {
        if (list.isEmpty()) return list; // an empty answer is tried again next time
        synchronized (CACHE) {
            CACHE.put(key, list);
            CACHED_AT.put(key, System.currentTimeMillis());
        }
        return list;
    }

    /** The address as https, or null if it is anything else. NASA's lists still name some files over http. */
    private static String https(String url) {
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        return url.startsWith("https://") ? url.replace(" ", "%20") : null;
    }

    /** A small thumbnail, decoded at about the size it is shown. Null if it isn't a landscape picture. */
    static Bitmap thumbnail(String url, int targetW, int targetH) throws IOException {
        byte[] bytes = bytes(url, THUMB_MAX, 20_000);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outWidth <= bounds.outHeight) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    /** Downloads the full picture into {@code dest}. Throws (leaving no file) if anything goes wrong. */
    static void download(Item item, File dest) throws IOException {
        String url = item.full;
        if (item.nasa) url = largestNasa(item.full);
        else if (item.page != null && item.width > 3840) url = commonsScaled(item);
        long deadline = System.currentTimeMillis() + 30_000;
        HttpURLConnection c = connect(url, deadline);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            long length = c.getContentLengthLong();
            if (length > PICTURE_MAX) throw new IOException("picture too large");
            byte[] buf = new byte[65536];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > PICTURE_MAX) throw new IOException("picture too large");
                if (System.currentTimeMillis() > deadline) throw new IOException("too slow");
                out.write(buf, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            dest.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            c.disconnect();
        }
        // Only keep it if it really is a picture, and a landscape one.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(dest.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outWidth <= bounds.outHeight) {
            dest.delete();
            throw new IOException("not a landscape picture");
        }
    }

    /** From a NASA asset list, the original JPEG, or failing that the largest one. */
    private static String largestNasa(String manifest) throws IOException {
        try {
            JSONArray files = new JSONArray(text(manifest));
            String orig = null, large = null, medium = null, any = null;
            for (int i = 0; i < files.length(); i++) {
                String f = https(files.optString(i, ""));
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.US);
                if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) continue;
                if (lower.contains("~orig.")) orig = f;
                else if (lower.contains("~large.")) large = f;
                else if (lower.contains("~medium.")) medium = f;
                else if (any == null) any = f;
            }
            String pick = orig != null ? orig : large != null ? large : medium != null ? medium : any;
            if (pick == null) throw new IOException("no JPEG");
            return pick;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static String text(String url) throws IOException {
        return new String(bytes(url, LIST_MAX, 20_000), "UTF-8");
    }

    private static byte[] bytes(String url, int max, long within) throws IOException {
        long deadline = System.currentTimeMillis() + within;
        HttpURLConnection c = connect(url, deadline);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                // The read timeout is per read, so a slow trickle could otherwise go on for ever.
                if (System.currentTimeMillis() > deadline) throw new IOException("too slow");
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("reply too large");
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Opens an https address, following up to five redirects, each of which must also be https. */
    private static HttpURLConnection connect(String url, long deadline) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            if (!url.startsWith("https://")) throw new IOException("not https");
            if (System.currentTimeMillis() > deadline) throw new IOException("too slow");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", agent);
            int code = c.getResponseCode();
            if (code == 200) return c;
            String next = c.getHeaderField("Location");
            c.disconnect();
            if (code < 300 || code >= 400 || next == null) throw new IOException("HTTP " + code);
            url = new URL(new URL(url), next).toString();
        }
        throw new IOException("too many redirects");
    }

    /** A file name for the picture, safe on any file system and not already used. */
    static File fileFor(File dir, Item item) {
        String safe = item.title.replaceAll("[^A-Za-z0-9 _-]", " ").replaceAll("\\s+", " ").trim();
        if (safe.length() > 50) safe = safe.substring(0, 50).trim();
        if (safe.isEmpty()) safe = "Picture";
        String base = (item.nasa ? "NASA - " : "Wikimedia - ") + safe;
        // A scaled Commons copy of a PNG may come back as PNG too; a large one is fetched as resized JPEG.
        String ext = item.full.toLowerCase(Locale.US).endsWith(".png") && item.width <= 3840 ? ".png" : ".jpg";
        File pick = new File(dir, base + ext);
        for (int i = 2; pick.exists(); i++) pick = new File(dir, base + " " + i + ext);
        return pick;
    }
}
