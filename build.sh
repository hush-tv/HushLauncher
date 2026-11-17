#!/bin/sh
# Builds launcher.apk with the SDK tools directly (no Gradle needed).
set -e
cd "$(dirname "$0")"
# Usage: ./build.sh [--version-code N] [--version-name X.Y] [--debuggable]
# --debuggable turns on the adb test hooks in HomeActivity; release builds leave it off.
VERSION_CODE=30; VERSION_NAME=1.29; DEBUG_MODE=
while [ $# -gt 0 ]; do
  case "$1" in
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --debuggable) DEBUG_MODE=--debug-mode; shift ;;
    *) echo "usage: $0 [--version-code N] [--version-name X.Y] [--debuggable]" >&2; exit 1 ;;
  esac
done
. "$HOME/android-tools/env.sh"
BT="$ANDROID_HOME/build-tools/36.0.0"; JAR="$ANDROID_HOME/platforms/android-36/android.jar"
# Remove the old APK first, so a failed build can never leave a stale one to be installed.
rm -rf build launcher.apk launcher.apk.idsig && mkdir -p build/classes build/gen build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip -A assets \
  --java build/gen --min-sdk-version 26 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" $DEBUG_MODE
javac -nowarn -Xlint:-deprecation -Xlint:-options --release 8 -cp "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../base.apk classes.dex)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
# Releases are signed with this key; updates only install over a copy signed with the same one.
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out launcher.apk build/aligned.apk
ls -l launcher.apk | awk '{print "built launcher.apk", $5, "bytes"}'
