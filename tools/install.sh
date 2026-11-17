#!/bin/bash
# Installs launcher.apk on the TV and pre-compiles it there. Usage: tools/install.sh <tv-ip>
#
# The pre-compile step matters: without it Android compiles the launcher's code while it runs,
# which on a Shield cost about 50 MB of extra memory on some launches and slowed the first
# seconds after a restart.
set -euo pipefail
[ -f ~/android-tools/env.sh ] && source ~/android-tools/env.sh
ADB=${ADB:-adb}; DEV="${1:?usage: tools/install.sh <tv-ip>}:5555"; PKG=com.hushtv.launcher
APK="$(cd "$(dirname "$0")/.." && pwd)/launcher.apk"
[ -f "$APK" ] || { echo "launcher.apk not found: run ./build.sh first"; exit 1; }
"$ADB" connect "$DEV" >/dev/null
"$ADB" -s "$DEV" install -r "$APK" | tail -1
"$ADB" -s "$DEV" shell cmd package compile -m speed -f $PKG | tail -1
"$ADB" -s "$DEV" shell cmd package set-home-activity $PKG/.HomeActivity >/dev/null 2>&1 || true
"$ADB" -s "$DEV" shell input keyevent HOME
echo "installed $("$ADB" -s "$DEV" shell dumpsys package $PKG | grep versionName | tr -d ' \r')"
