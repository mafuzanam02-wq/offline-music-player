# What's New — StereoSine Beta 11 (release candidate; not yet distributed)

> **Status.** `0.1.0-beta11` is prepared as a release candidate. It has not been distributed to testers or published, and it
> has not yet passed device testing. The Beta 10.0 summary below is kept as history. StereoSine is a new app identity
> (`com.launchpoint.stereosine`) and cannot update a Wavdrop install in place; move your data with Backup & Migration.

## Share StereoSine

Settings has a Share button that sends a verified copy of the installed app to another Android device through Android's share options (for example Quick Share). It is unavailable for split installs, and there is no download link yet.

## Wrapped videos

A Wrapped report with listening data can be exported as a 720x1280 MP4 video on your device, with progress, cancel and retry. It depends on your device's video encoder.

## More sound control

Equalizer settings can be kept separately for Global, Speaker, Wired and Bluetooth, and Headphones stores named custom EQ presets. You pick the profile yourself: StereoSine does not detect your output or change the audio route. These settings are not included in backups, and EQ availability depends on your device.

## Safer backups

New backups use the `.wdbk` container (older backups still import; older app versions cannot read `.wdbk`). Backup Verification checks the newest usable backup in your folder and refreshes after a backup or folder change. The backup status explains a failure, keeps your last good backup visible and suggests recovery steps. A saved backup is not an independent verification, and automatic backups are best-effort.

## Search and navigation

Global Search keeps up to 30 recent searches for 90 days on your device only, with Clear all. Search entry points and song menus are consistent, and the main tabs open with one tap.

## Library and playback

Optional scan exclusions (Downloads, Recordings, Telegram, Signal, Messenger and custom folders), Save queue as playlist, M3U8 playlist export, Delete from device, transport fades, a Sleep Timer "Finish current track" option, pull to refresh on Home and widget appearance settings.

## Reliability and polish

More accurate listening statistics, clearer music-access recovery, readability and accessibility fixes, and several backup-restore matching fixes.

## Known limits

Not every feature has been validated on a physical device yet (see `RELEASE_NOTES.md`). Creating and verifying a `.wdbk` backup has been confirmed on one phone, but restoring from a `.wdbk` backup, restoring one on a different device, and scheduled automatic backups have not been validated on a device. One cosmetic defect is known: the "Backup file" label wraps on the Backup Verification detail screen.

---

# What's New — Wavdrop Beta 10.0 (now StereoSine)

> **Note (history).** This section summarizes the published **Wavdrop Beta 10.0** release (`0.1.0-beta10`). The app has since
> been renamed **StereoSine** (previously Wavdrop).

New listening features, faster libraries, and major playback reliability improvements.

## Crossfade

Smooth transitions between songs are now available in **Settings → Playback**. It's off by default; pick a fade length from 2 to 12 seconds. Crossfade isn't available while the Equalizer is turned on.

## Synchronized lyrics

Timed `.lrc` lyrics can now follow the song on Now Playing, and your own plain lyrics still work as before.

## More reliable playback

Queues behave correctly even when the same song appears more than once. Playback can pick up where you left off after the app was closed, from the notification, lock screen, Bluetooth controls or widget. Bluetooth and wired reconnects are more predictable, paused sessions survive a swipe away from Recents, and unplayable tracks are skipped instead of stalling playback.

## Faster libraries

Search is faster on large libraries, rescans no longer rewrite every unchanged song, Smart Collections update only what changed, and Home previews and artwork load more efficiently.

## Smarter Home

Home Smart Collections are now configurable, and Home dashboard previews stay fast as your library grows.

## Better Now Playing

Album artwork loads more reliably and transitions more smoothly, with polish across queue controls, artist and album navigation, and motion.

## Backup & import improvements

Automatic backup now runs on a periodic schedule through Android's background scheduler (best-effort). Backups keep your history and settings consistent after restore, and BlackPlayer statistics import no longer mistakes period play counts for skips.

## Stability

Broad reliability improvements across playback, statistics, backups, imports and library scanning, plus updated support for the latest Android version.
