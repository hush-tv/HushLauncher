package com.hushtv.launcher;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Tracker blocking through Android's Private DNS (Android 9 and newer): apps that use the system
 * resolver look up addresses through an encrypted DNS service that refuses known advertising and
 * tracking domains.
 *
 * Changing it needs WRITE_SECURE_SETTINGS, which Android only gives through adb:
 * {@code adb shell pm grant com.hushtv.launcher android.permission.WRITE_SECURE_SETTINGS}
 */
final class Privacy {
    /** Name shown, what it blocks, and the DNS-over-TLS host (null = off). */
    static final String[][] SERVICES = {
            {"Off", "Uses the TV's previous DNS setting", null},
            {"AdGuard", "Blocks the most ads and trackers", "dns.adguard-dns.com"},
            {"Mullvad", "Ads and trackers, from a no-logs VPN company", "adblock.dns.mullvad.net"},
            {"Quad9", "Lightest: scam and malware sites only. Try it if an app breaks", "dns.quad9.net"},
    };

    static final String GRANT_COMMAND =
            "adb shell pm grant com.hushtv.launcher android.permission.WRITE_SECURE_SETTINGS";

    private static final String MODE = "private_dns_mode", HOST = "private_dns_specifier";
    private static final String CUSTOM = "dns_custom_host";
    private static final String PREV_MODE = "dns_prev_mode", PREV_HOST = "dns_prev_host", SAVED = "dns_saved";

    interface Result { void done(boolean ok); }

    /** Bumped by every set(), so the check from an earlier choice stops once a newer one is made. */
    private static int generation;

    private Privacy() {}

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    static boolean canChange(Context ctx) {
        return supported() && ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Index into {@link #SERVICES} of what the TV uses now, or -1 for a host we don't list. */
    static int current(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        if (!"hostname".equals(Settings.Global.getString(cr, MODE))) return 0;
        String host = Settings.Global.getString(cr, HOST);
        for (int i = 1; i < SERVICES.length; i++) if (SERVICES[i][2].equalsIgnoreCase(host)) return i;
        return -1;
    }

    /** The host the TV uses for Private DNS now, or null when it isn't set to a named host. */
    static String currentHost(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        if (!"hostname".equals(Settings.Global.getString(cr, MODE))) return null;
        return Settings.Global.getString(cr, HOST);
    }

    /** The user's own DNS-over-TLS host, or null if none has been typed. */
    static String customHost(Context ctx) {
        return store(ctx).getString(CUSTOM, null);
    }

    static void setCustomHost(Context ctx, String host) {
        store(ctx).edit().putString(CUSTOM, host).apply();
    }

    /** True when the TV uses the user's own host. */
    static boolean customInUse(Context ctx) {
        String host = currentHost(ctx), custom = customHost(ctx);
        return host != null && custom != null && host.equalsIgnoreCase(custom);
    }

    /**
     * Tidies a typed host: drops "https://" or "tls://" and anything from the first "/" on.
     * Returns null unless what is left looks like a hostname: letters, digits, dots and hyphens,
     * with at least one dot.
     */
    static String cleanHost(String typed) {
        if (typed == null) return null;
        String h = typed.trim().toLowerCase(java.util.Locale.ROOT);
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.length() > 253 || !h.contains(".") || !h.matches("[a-z0-9.-]+")
                || h.startsWith(".") || h.startsWith("-") || h.contains("..")) return null;
        return h;
    }

    private static SharedPreferences store(Context ctx) {
        return ctx.getSharedPreferences("privacy", Context.MODE_PRIVATE);
    }

    /**
     * Switches the TV to service {@code index}. Before the first change the TV's own setting is
     * remembered, and "Off" puts that back. After switching to a blocking service the connection
     * is checked; if it doesn't come back within about eight seconds the previous setting is
     * restored, so a blocked or unreachable service can't leave the TV offline.
     * Returns false straight away if the change isn't allowed.
     */
    static boolean set(Context ctx, int index, Result result) {
        return setHost(ctx, SERVICES[index][2], result);
    }

    /** As {@link #set}, for any DNS-over-TLS host; null switches blocking off. */
    static boolean setHost(Context ctx, final String host, Result result) {
        if (!canChange(ctx)) return false;
        final int gen = ++generation;
        final Context app = ctx.getApplicationContext();
        final ContentResolver cr = app.getContentResolver();
        final String oldMode = Settings.Global.getString(cr, MODE);
        final String oldHost = Settings.Global.getString(cr, HOST);
        SharedPreferences sp = store(app);
        if (!sp.getBoolean(SAVED, false) && current(app) <= 0 && !customInUse(app)) {
            sp.edit().putBoolean(SAVED, true).putString(PREV_MODE, oldMode).putString(PREV_HOST, oldHost).apply();
        }
        try {
            if (host == null) {
                String mode = sp.getString(PREV_MODE, "opportunistic");
                if (!"off".equals(mode) && !"opportunistic".equals(mode) && !"hostname".equals(mode)) mode = "opportunistic";
                write(cr, mode, sp.getString(PREV_HOST, null));
                sp.edit().putBoolean(SAVED, false).apply();
                result.done(true);
                return true;
            }
            write(cr, "hostname", host);
        } catch (SecurityException e) {
            return false;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final long start = System.currentTimeMillis();
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != generation) return;
                if (online(app, host)) {
                    result.done(true);
                } else if (System.currentTimeMillis() - start < 8000) {
                    h.postDelayed(this, 1000);
                } else {
                    try { write(cr, oldMode, oldHost); } catch (SecurityException ignored) { }
                    result.done(false);
                }
            }
        }, 1500);
        return true;
    }

    private static void write(ContentResolver cr, String mode, String host) {
        if (host != null) Settings.Global.putString(cr, HOST, host);
        Settings.Global.putString(cr, MODE, mode == null ? "opportunistic" : mode);
    }

    /** True when the active network is validated and using Private DNS through {@code host}. */
    private static boolean online(Context ctx, String host) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return true;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false;
        LinkProperties lp = cm.getLinkProperties(n);
        return lp == null || (lp.isPrivateDnsActive() && host.equalsIgnoreCase(lp.getPrivateDnsServerName()));
    }
}
