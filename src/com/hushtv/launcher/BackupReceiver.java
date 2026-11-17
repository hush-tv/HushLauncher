package com.hushtv.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * For tools/backup.sh and tools/restore.sh. Protected by android.permission.DUMP, which adb's
 * shell holds and ordinary apps can't get, so only a computer connected over adb can use it.
 *
 * IMPORT copies pictures from a folder in shared storage (extra "from", e.g. /sdcard/Download/hush)
 * into Hush's own folder, and takes the settings file as extra "json_b64". Fire OS doesn't let adb
 * write into an app's folder directly.
 *
 * After a restore the process ends itself so the home screen reloads with the new settings. It
 * isn't force-stopped: on Fire TV that would also switch off the Home button service.
 */
public final class BackupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        try {
            if ("com.hushtv.launcher.BACKUP".equals(action)) {
                Backup.save(ctx);
                setResultCode(1);
                return;
            }
            if ("com.hushtv.launcher.IMPORT".equals(action)) {
                String from = intent.getStringExtra("from");
                if (from == null) throw new IOException("no folder");
                File into = ctx.getExternalFilesDir(null);
                for (String dir : new String[]{"packs", "tiles", "wallpapers"}) {
                    copy(new File(from, dir), new File(into, dir));
                }
                // Android 11 lets Hush read pictures from shared storage but not other files, so
                // the settings themselves come in the broadcast, base64-encoded.
                String json = intent.getStringExtra("json_b64");
                if (json != null) {
                    File f = Backup.file(ctx);
                    f.getParentFile().mkdirs();
                    try (OutputStream out = new FileOutputStream(f)) {
                        out.write(android.util.Base64.decode(json, android.util.Base64.DEFAULT));
                    }
                }
            } else if (!"com.hushtv.launcher.RESTORE".equals(action)) {
                return;
            }
            Backup.restore(ctx);
            setResultCode(1);
            new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 500);
        } catch (Exception e) {
            setResultCode(2);
        }
    }

    private static void copy(File from, File to) throws IOException {
        if (from.isDirectory()) {
            File[] kids = from.listFiles();
            if (kids == null) return;
            to.mkdirs();
            for (File k : kids) copy(k, new File(to, k.getName()));
        } else if (from.isFile()) {
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }
}
