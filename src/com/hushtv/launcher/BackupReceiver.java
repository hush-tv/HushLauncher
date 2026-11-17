package com.hushtv.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * For tools/backup.sh and tools/restore.sh. Protected by android.permission.DUMP, which adb's
 * shell holds and ordinary apps can't get, so only a computer connected over adb can use it.
 */
public final class BackupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if ("com.hushtv.launcher.BACKUP".equals(intent.getAction())) {
                Backup.save(ctx);
                setResultCode(1);
            } else if ("com.hushtv.launcher.RESTORE".equals(intent.getAction())) {
                Backup.restore(ctx);
                setResultCode(1);
            }
        } catch (Exception e) {
            setResultCode(2);
        }
    }
}
