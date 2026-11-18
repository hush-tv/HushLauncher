# Set up Hush with an AI assistant

If you have [Claude Code](https://claude.com/claude-code) (or another AI assistant that can run
commands on your computer), it can find your TV on your Wi-Fi and do the whole setup for you,
including the parts that normally need typed commands: tracker blocking and, on some TVs, making
Hush the home screen.

## 1. On the TV (one time)

1. Turn on developer options. "You are now a developer" appears.
   - Android TV: Settings → Device Preferences → About → press **Build** seven times.
   - Google TV: Settings → System → About → press **Android TV OS build** seven times.
   - Fire TV: Settings → My Fire TV → About → press the device name seven times.
2. In Developer options, turn on **USB debugging** and, if listed, **Network debugging** or
   **Wireless debugging**. Fire TV: turn on **ADB debugging**.
3. Keep the TV on. The first time the computer connects, the TV asks **"Allow debugging?"**.
   Choose **Always allow from this computer** and **OK**.

Your computer and TV must be on the same network.

## 2. Paste this into Claude Code

```text
Set up the Hush Launcher on my Android TV. Instructions are at
https://github.com/hush-tv/HushLauncher/blob/main/SETUP_WITH_CLAUDE.md (section "For the assistant").
Find the TV on my local network yourself; ask me only to accept the "Allow debugging?" prompt on the TV.
```

## For the assistant

Follow these steps in order. Ask the user before anything not listed here, and never change TV
settings other than the ones below.

1. **Get adb.** Use `adb` if it is on the PATH. Otherwise download Google's official
   platform-tools for this OS from
   `https://dl.google.com/android/repository/platform-tools-latest-{darwin|linux|windows}.zip`,
   unzip it into a temporary folder and use the `adb` inside it.
2. **Find the TV.** Try, in order, until one connects:
   - `adb mdns services`. An `_adb-tls-connect` entry gives the address and port for
     `adb connect`. If only `_adb-tls-pairing` is listed, the TV needs pairing first: ask the user
     for the six-digit code under Wireless debugging → Pair device with pairing code, run
     `adb pair <ip>:<pairing-port> <code>`, then `adb connect` to the connect port.
   - Scan only the computer's own /24 subnet, port 5555 only, with a timeout of one second or
     less (`nc -z -w1 <ip> 5555` on macOS/Linux, `Test-NetConnection <ip> -Port 5555` on Windows),
     then `adb connect <ip>:5555`.
   - If several devices answer, show the user the list with `adb -s <ip>:5555 shell getprop ro.product.model`
     and ask which one.
   If the connection shows `unauthorized`, ask the user to accept "Allow debugging?" on the TV,
   then retry.
3. **Install.**
   ```bash
   curl -fL -o "${TMPDIR:-/tmp}/launcher.apk" https://github.com/hush-tv/HushLauncher/releases/latest/download/launcher.apk
   adb install -r "${TMPDIR:-/tmp}/launcher.apk"
   adb shell cmd package compile -m speed -f com.hushtv.launcher
   ```
4. **Make it the home screen.**
   - Android TV / Google TV:
     `adb shell cmd package set-home-activity com.hushtv.launcher/.HomeActivity`
   - Fire TV (`adb shell getprop ro.product.manufacturer` returns `Amazon`), or if the command above
     is not accepted, or Home still opens the TV's own home screen afterwards (Nvidia Shield, and
     Google TV devices such as Chromecast with Google TV, Google TV Streamer and Sony, TCL or
     Philips Google TV sets), turn on Hush's Home button service while keeping any existing
     services. It works with Amazon's, Google TV's and Android TV's home screens:
     ```bash
     adb shell 'S=com.hushtv.launcher/com.hushtv.launcher.HomeRedirectService; C=$(settings get secure enabled_accessibility_services); case "$C" in null|"") N=$S;; *$S*) N=$C;; *) N=$C:$S;; esac; settings put secure enabled_accessibility_services "$N"; settings put secure accessibility_enabled 1'
     ```
5. **Allow tracker blocking** (Android 9 or newer; skip on older versions):
   `adb shell pm grant com.hushtv.launcher android.permission.WRITE_SECURE_SETTINGS`
   Do not switch blocking on; the user chooses it in Settings → Privacy.
6. **Open Hush:** `adb shell input keyevent HOME`. The Get started guide appears on the TV.
7. **Tidy up:** tell the user they can turn off USB/Network debugging again in Developer options.
   Report what was done and anything that failed.
