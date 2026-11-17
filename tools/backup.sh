#!/bin/bash
# Saves the launcher's settings on the TV, then copies them (with your own wallpapers and tile
# pictures) to extras/backups/<date>/ on this computer. Usage: tools/backup.sh <tv-ip>
set -euo pipefail
[ -f ~/android-tools/env.sh ] && source ~/android-tools/env.sh
ADB=${ADB:-adb}; DEV="${1:?usage: tools/backup.sh <tv-ip>}:5555"
PKG=com.hushtv.launcher; DIR=/sdcard/Android/data/$PKG/files
OUT="$(cd "$(dirname "$0")/.." && pwd)/extras/backups/$(date +%Y-%m-%d_%H%M)"
"$ADB" connect "$DEV" >/dev/null
"$ADB" -s "$DEV" shell rm -f "$DIR/backup/launcher-backup.json"
"$ADB" -s "$DEV" shell am start -n $PKG/.HomeActivity --ez backup true >/dev/null
for i in 1 2 3 4 5 6 7 8 9 10; do
  "$ADB" -s "$DEV" shell "[ -f $DIR/backup/launcher-backup.json ]" && break; sleep 1
done
mkdir -p "$OUT"
for d in backup wallpapers tiles; do
  "$ADB" -s "$DEV" shell "[ -d $DIR/$d ]" && "$ADB" -s "$DEV" pull "$DIR/$d" "$OUT/" >/dev/null
done
[ -f "$OUT/backup/launcher-backup.json" ] || { echo "no backup file came back"; exit 1; }
echo "saved to $OUT"
