#!/bin/bash
# Puts a backup made by tools/backup.sh back on the TV and applies it.
# Usage: tools/restore.sh <tv-ip> <backup-folder>
set -euo pipefail
[ -f ~/android-tools/env.sh ] && source ~/android-tools/env.sh
ADB=${ADB:-adb}; DEV="${1:?usage: tools/restore.sh <tv-ip> <backup-folder>}:5555"; FROM="${2:?backup folder}"
PKG=com.hushtv.launcher; DIR=/sdcard/Android/data/$PKG/files
[ -f "$FROM/backup/launcher-backup.json" ] || { echo "no backup in $FROM"; exit 1; }
"$ADB" connect "$DEV" >/dev/null
for d in backup wallpapers tiles; do
  [ -d "$FROM/$d" ] && "$ADB" -s "$DEV" push "$FROM/$d" "$DIR/" >/dev/null
done
"$ADB" -s "$DEV" shell am broadcast -n $PKG/.BackupReceiver -a $PKG.RESTORE >/dev/null
"$ADB" -s "$DEV" shell am force-stop $PKG
"$ADB" -s "$DEV" shell am start -n $PKG/.HomeActivity >/dev/null
echo "restored"
