package com.hushtv.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updates from the project's GitHub releases. A check sends one request to GitHub's
 * public API, at most once a day while the home screen is showing, and only if "Check for
 * updates" is on. Installing downloads the release's launcher.apk and hands it to Android's own
 * installer, which asks the user to confirm and refuses any file not signed with the same key.
 */
final class Updater {
    private static final String LATEST = "https://api.github.com/repos/hush-tv/HushLauncher/releases/latest";
    private static final long DAY = 24 * 60 * 60 * 1000L;

    interface Listener { void changed(); }

    /** What the last check found. */
    static volatile String latestVersion, apkUrl;
    static volatile int state; // 0 idle, 1 checking, 2 downloading, 3 failed
    static volatile String error;
    static volatile int progress; // download percent

    private Updater() {}

    static String installedVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean updateAvailable(Context ctx) {
        return latestVersion != null && newer(latestVersion, installedVersion(ctx));
    }

    /** True if version a (like "1.28") is newer than b. */
    static boolean newer(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("\\."), y = b.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); } catch (NumberFormatException e) { return 0; }
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** Checks if a day has passed since the last check (or always, if {@code force}). */
    static void check(Context ctx, boolean force, Listener done) {
        final Context app = ctx.getApplicationContext();
        long last = sp(app).getLong("update_checked", 0);
        if (!force && System.currentTimeMillis() - last < DAY) {
            if (latestVersion == null) latestVersion = sp(app).getString("update_latest", null);
            if (apkUrl == null) apkUrl = sp(app).getString("update_url", null);
            done.changed();
            return;
        }
        if (state == 1 || state == 2) return;
        state = 1;
        error = null;
        done.changed();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(new String(get(LATEST), "UTF-8"));
                String tag = rel.getString("tag_name");
                String url = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) url = a.getString("browser_download_url");
                }
                latestVersion = tag.replaceFirst("^v", "");
                apkUrl = url;
                sp(app).edit().putLong("update_checked", System.currentTimeMillis())
                        .putString("update_latest", latestVersion).putString("update_url", url).apply();
                state = 0;
            } catch (Exception e) {
                state = 3;
                error = "Couldn't reach GitHub";
            }
            main.post(done::changed);
        }, "update-check").start();
    }

    /** True when Android lets Hush install updates; if not, opens the setting that allows it. */
    static boolean ensureAllowed(Context ctx) {
        if (ctx.getPackageManager().canRequestPackageInstalls()) return true;
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            ctx.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return false;
    }

    /** Downloads the newest release and hands it to Android's installer. */
    static void install(Context ctx, Listener changed) {
        final Context app = ctx.getApplicationContext();
        final String url = apkUrl;
        if (url == null || state == 2) return;
        state = 2;
        progress = 0;
        error = null;
        changed.changed();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                int id = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(id)) {
                    HttpURLConnection c = open(url);
                    long total = c.getContentLengthLong(), done = 0;
                    try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("hush.apk", 0, total)) {
                        byte[] buf = new byte[64 * 1024];
                        int n, lastPct = -1;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            if (pct != lastPct) {
                                lastPct = pct;
                                progress = pct;
                                main.post(changed::changed);
                            }
                        }
                        session.fsync(out);
                    }
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pending = PendingIntent.getBroadcast(app, id,
                            new Intent(app, Result.class), flags);
                    session.commit(pending.getIntentSender());
                }
                state = 0;
            } catch (Exception e) {
                state = 3;
                error = "Download failed";
            }
            main.post(changed::changed);
        }, "update-install").start();
    }

    private static HttpURLConnection open(String address) throws Exception {
        String u = address;
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "tv-launcher");
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                if (!u.startsWith("https://")) throw new Exception("insecure redirect");
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            return c;
        }
        throw new Exception("too many redirects");
    }

    private static byte[] get(String address) throws Exception {
        HttpURLConnection c = open(address);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Receives the installer's answer; shows its confirmation screen when it asks for one. */
    public static final class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); } catch (Exception ignored) { }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                state = 3;
                error = "Android didn't install the update";
            }
        }
    }
}
