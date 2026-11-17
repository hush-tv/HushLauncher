# Changelog

## 1.30

- Updates are only downloaded from the project's GitHub releases, and the file is checked before
  installing: it must be complete and signed with the same key as the installed copy.
- Saying no on Android's install screen no longer shows an error; a real install failure now
  shows a notice.
- Restoring a backup no longer brings back the old update check or the start-up app, and a
  damaged backup file leaves the current settings untouched.
- Tracker blocking: switching services quickly no longer lets an earlier check undo a later
  choice, the check now confirms the chosen service is the one in use, and "Don't block" always
  puts back a valid setting.
- The commands for testing from a computer only work in debug builds, so other apps on the TV
  can't use them.
- Android's own cloud backup of Hush's settings is turned off.

## 1.29

Fixes from testing setup as a new user on an Nvidia Shield.

- **Home screen step:** detects correctly which app the Home button opens (it could wrongly
  show as done). On TVs that accept Hush in their home-screen setting but keep opening their
  own home screen (Nvidia Shield, Android TV 11), the guide notices and switches to the
  Hush Home button, with the exact menu path for that TV.
- The guide reopens when Hush is opened again and isn't the home screen yet, and starts on the
  first unfinished step.
- Back from "Choose your apps" or "Pick a wallpaper" returns to the guide.
- The weather step ticks itself once a town is set.
- "Usage and diagnostics" falls back to the TV's Settings with the menu path on TVs that don't
  link to it directly.

## 1.28

- **In-app updates.** Settings → System → Updates checks GitHub and installs new versions with
  Android's own installer. A daily check (can be switched off) shows a notice when one is ready.
- **New logo:** a quiet TV screen with one row of apps and a privacy shield.
- The installer's **Open** button now works straight after installing.

## 1.27

Easier setup.

- **Get started guide** opens the first time Hush runs: home screen, apps, wallpaper, weather
  and privacy, each one step away.
- A new home row starts with the apps already on the TV instead of a fixed list, so it is never
  empty.
- **Hush Home button** now works on Google TV devices that have no home-screen setting, as well
  as Fire TV, and can be switched on from the TV's Accessibility settings.
- New guide for setting up with an AI assistant that finds the TV on your network.

## 1.26

First public release.

- One-row home screen with no ads, sponsored rows or recommendations.
- Privacy settings: tracker blocking for the whole TV through AdGuard, Mullvad or Quad9 encrypted
  DNS (Android 9 or newer), with an automatic return to the previous setting if the service
  can't be reached; shortcuts to the TV's ad and usage-data settings.
- Live wallpaper: time of day, weather effects (rain, storm, snow, cloud, fog), stars on clear nights.
- Tile, selection, clock, wallpaper and screensaver settings with live preview.
- Icon packs and custom tile pictures.
- Screensaver with a drifting clock.
- Backup and restore; open an app at start-up.
- Fire TV Home button support (optional, enabled over adb).
