# StereoSine Branding

Concise current branding facts, verified against `AndroidManifest.xml`, `AppIconChoice`, and the
launcher resources.

## Names

- Product name: **StereoSine** (`app_name`); launcher label: **StereoSine** (`launcher_label`).
- Naming history: `Lyra` -> `EchoVault` -> Wavdrop -> **StereoSine** (current). Wavdrop is the historical name of the
  published beta builds; it is kept only where it names a legacy artifact (see below).
- Application ID / namespace: `com.launchpoint.stereosine`; debug builds: `com.launchpoint.stereosine.debug`.
  Root Gradle project: `StereoSine`. The previous `com.launchpoint.wavdrop[.debug]` apps are separate installs to
  Android and are not upgraded in place.
- Release status: no StereoSine-branded release has been published, and the new signing identity is still pending
  (see [RELEASE_SIGNING.md](RELEASE_SIGNING.md)).

## Legacy Wavdrop identifiers that are intentionally retained

These are compatibility contracts or external identifiers, not branding leftovers:

- Backup format ids `wavdrop_backup` / `wavdrop_wdbk`, backup writer metadata (`app`, `packageName`, `database`),
  Desktop `wavdrop-desktop-lab`, the `.wdbk` markers and the `docs/WAVDROP_*.md` specifications.
- Persisted identifiers: Room file `wavdrop.db`, DataStore names, stored event sources and media-browse ids
  (see `PersistenceContractTest`).
- Signing variables `WAVDROP_UPLOAD_*`.
- Outstanding external identity (not yet migrated): the GitHub repository `mafuzanam02-wq/wavdrop-music-player` and
  the privacy URL `https://launchpointdigital.co.za/wavdrop/privacy`.

## Backup file names

New backups are written as `stereosine-backup.wdbk` (fixed-name mode) or `stereosine-backup-YYYY-MM-DD.wdbk`
(dated mode). Existing `wavdrop-backup*.wdbk` / `.json` files stay recognised and restorable; the app never renames,
overwrites or deletes them.

## Launcher icons

Launcher icon switching uses Android `activity-alias` entries targeting `MainActivity`; the app icon
and round icon on `<application>` are the Obsidian Black variants. Exactly one alias is enabled at a time.

| Icon | Alias | Default |
|---|---|---|
| Obsidian Black | `MainActivityAliasObsidianBlack` | **Yes** (enabled at install; `AppIconChoice.DEFAULT`) |
| Midnight Violet | `MainActivityAliasMidnightViolet` | no |
| Clean Purple | `MainActivityAliasCleanPurple` | no |
| Deep Teal | `MainActivityAliasDeepTeal` | no |
| Ocean Blue | `MainActivityAliasOceanBlue` | no |
| Sunset Orange | `MainActivityAliasSunsetOrange` | no |

Users choose an icon in Settings -> Appearance -> App Icon. The choice is also part of backups
(`launcherIcon` in `preferences.android`). Launcher caching can delay the visible change on some launchers.

Resources: `app/src/main/res/mipmap-nodpi/stereosine_icon_<variant>.png` and `..._round.png` for all six
variants. Every alias also declares the legacy `MUSIC_PLAYER` filter so Samsung's "Play music" shortcut
resolves StereoSine (see the comment in the manifest).

Source artwork kept for reference lives in `app/src/main/assets/branding/` (Midnight Violet, Clean Purple,
and Deep Teal PNGs). It is not the shipped launcher resource set.

## Brand notes

- The mark is a wave-and-play symbol; all variants share it.
- Midnight Violet was the original primary identity; **Obsidian Black has been the default since
  Beta 3**. Do not describe Midnight Violet as the default.
- For the Play Store icon asset use the Obsidian Black variant (see `PLAY_STORE_READINESS_CHECKLIST.md`).
