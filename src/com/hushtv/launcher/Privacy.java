package com.hushtv.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Tracker blocking through Android's Private DNS: every app on the TV looks up addresses through
 * an encrypted DNS service that refuses known advertising and tracking servers.
 *
 * Changing it needs WRITE_SECURE_SETTINGS, which Android only gives through adb:
 * {@code adb shell pm grant com.hushtv.launcher android.permission.WRITE_SECURE_SETTINGS}
 */
final class Privacy {
    /** Name shown, what it blocks, and the DNS-over-TLS host (null = off). */
    static final String[][] SERVICES = {
            {"Off", "The TV uses your network's normal DNS", null},
            {"AdGuard", "Blocks ads and trackers", "dns.adguard-dns.com"},
            {"Mullvad", "Blocks ads and trackers", "adblock.dns.mullvad.net"},
            {"Quad9", "Blocks malware and phishing sites", "dns.quad9.net"},
    };

    static final String GRANT_COMMAND =
            "adb shell pm grant com.hushtv.launcher android.permission.WRITE_SECURE_SETTINGS";

    private Privacy() {}

    static boolean canChange(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Index into {@link #SERVICES} of what the TV uses now, or -1 for a host we don't list. */
    static int current(Context ctx) {
        String mode = Settings.Global.getString(ctx.getContentResolver(), "private_dns_mode");
        if (!"hostname".equals(mode)) return 0;
        String host = Settings.Global.getString(ctx.getContentResolver(), "private_dns_specifier");
        for (int i = 1; i < SERVICES.length; i++) if (SERVICES[i][2].equalsIgnoreCase(host)) return i;
        return -1;
    }

    /** Switches the TV to service {@code index}. Returns false if the permission is missing. */
    static boolean set(Context ctx, int index) {
        if (!canChange(ctx)) return false;
        try {
            String host = SERVICES[index][2];
            if (host == null) {
                Settings.Global.putString(ctx.getContentResolver(), "private_dns_mode", "opportunistic");
            } else {
                Settings.Global.putString(ctx.getContentResolver(), "private_dns_specifier", host);
                Settings.Global.putString(ctx.getContentResolver(), "private_dns_mode", "hostname");
            }
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
