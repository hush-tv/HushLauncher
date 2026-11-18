# Changelog

## 1.36

- Tracker blocking with your own service (Settings → Privacy): type a DNS-over-TLS address such
  as dns.nextdns.io. It is checked and switched back if it can't be reached, like the others.
- Text size (Settings → System): Normal, Large or Extra large, for the clock, weather, settings,
  messages and app names.
- High contrast (Settings → System): settings and messages on solid black, white text, and a
  thicker yellow outline on the selected tile or row.
- On the home screen, the Previous track, Last channel or red button reopens the last app you
  started from Hush. In kids mode, only if it is a kids' app.
- The guides now say clearly that the Hush Home button works on Google TV devices and the Shield
  as well as Fire TV, and explain setting up several TVs from a computer.

## 1.35

- Kids mode (Settings → Kids mode): the home row shows only the apps you choose, all apps and tile
  options are locked, and Settings need a 4-number PIN. The PIN is never saved in backups.
- Rename any app from its tile options, and hide apps from all apps. Settings → All apps → Hidden
  apps brings them back.
- Lite mode (Settings → System): turns off the costly effects and animations for slower TVs. On by
  default on low-memory TVs.

## 1.34

Stability release, from stress tests on Android TV 12, Google TV 14 (720p, 1 GB), Nvidia Shield
and Fire TV.

- A damaged or hand-edited backup can no longer stop Hush from starting; values of the wrong type
  are skipped and fall back to defaults.
- Backups move between TVs: picture and wallpaper paths are adjusted to the new device.
- A failed import keeps the previous backup.
- Changing the TV's resolution, dark mode or language while Hush is open no longer leaves the
  layout or wallpaper the wrong size, or crashes it.
- Weather comes back as soon as the network does, and a slow weather server can't hold it up.
- Very large wallpapers and tile pictures are scaled down safely instead of failing; on low-memory
  TVs app pictures use less memory.
- Adding pictures with the same or a non-Latin name no longer overwrites earlier ones.
- An icon pack with a broken entry still works; apps with no name show their package name.
- The screensaver and background tasks can't crash Hush when they finish late.

## 1.33

- Fire TV: "Set as screensaver" now works. Fire TV has no screensaver picker for other apps, so
  Hush sets itself directly (needs the same one-time permission as tracker blocking).
- Restoring a backup from a computer no longer switches off the Home button service on Fire TV.
- New import from a computer, for copying one TV's Hush setup to another (Fire OS doesn't let a
  computer write into Hush's folder directly).

## 1.32

- All apps groups the apps that aren't on your home row under Watch, Listen, Play, and Apps and
  tools. Turn it off in Settings → All apps → Group by type.
- Group titles are easier to tell apart from app names: larger, with a bar in your outline colour
  and the number of apps beside them. App names show on the selected app only.
- Better sorting into groups: fewer apps land in the wrong place because of a short word in their
  name (for example "tv" or "file"), and more well-known apps are recognised.
- Up and Down in all apps go to the tile directly above or below, even when the next row is
  shorter, and the group's title comes into view as you reach its first row.
- Privacy page: clearer titles for the computer and router steps, and a note that tracker blocking
  is for privacy and can't remove ads inside videos.
- Get started: before opening Accessibility, explains Android's screen warning and what Hush does
  with it. On TVs whose home-screen setting doesn't work, it goes straight to the Home button step.
- Fog and other weather without rain or snow no longer redraw the wallpaper effect needlessly.
- After a failed weather update, Hush waits 15 minutes before trying again.
- Uses less memory while another app is open, and skips some preloading on low-memory TVs.

## 1.31

- Rain on the wallpaper now matches the forecast: drizzle is a few light drops, heavy rain is
  the full shower. Light and moderate snow are gentler too.
- Tracker blocking: AdGuard is marked as recommended, and each choice says when you'd pick it.
- Without the one-time computer step, the Privacy page now suggests setting AdGuard's DNS on your
  router instead.

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
