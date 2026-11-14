# BRAND-01 — Wavdrop → StereoSine migration inventory

Baseline HEAD `a1f11ae98362c34ee5fbacd4ccce12b6a25e8b9a`. Classes: **A** rebrand, **B** compatibility (keep),
**C** historical, **D** generated/build output, **E** external identifier (separate authorization).

Before slice 1: 935 tracked files / 6,220 case-insensitive matches (479 main, 409 test, 15 androidTest,
17 docs, 1 script, build files) plus 13 Room schema JSONs and ~50 resource/asset/Kotlin filenames.
After slice 1: 280 files / 2,378 matches (see residual table). `docs/` and root `*.md` are untouched.

## Dependency map
1. Package/namespace (`com.launchpoint.wavdrop` → `.stereosine`) drives: Gradle `namespace`/`applicationId`,
   manifest relative names, 6 launcher-alias class strings (`AppIconChoice`), intent actions / session
   commands, widget `android:configure`, androidTest receiver, Room schema directory name (Room exports to
   `schemas/<FQCN>`; schema dir moved with `git mv` — migration tests read it), source-reading JVM tests
   (paths `src/main/kotlin/com/launchpoint/...`).
2. `FileProvider` authority is `${applicationId}.fileprovider` and URIs use `packageName + ".fileprovider"`: follows
   the application ID automatically.
3. Resource names (`wavdrop_icon_*`, `wavdrop_widget_{idle,short,normal,tall,expanded,info,loading_background}`,
   `ic_stat_wavdrop`, `Theme.Wavdrop`) are referenced from manifest, Kotlin (`R.*`) and widget XML.
4. Kotlin type names (`Wavdrop*`) are referenced by ~250 files; they are independent of Android identity and can be
   renamed in a later slice with IDE-safe refactoring.

## Classification of what remains after slice 1
| Class | Items | Reason / plan |
|---|---|---|
| B | `wavdrop_backup`, `wavdrop_wdbk` format ids; backup JSON `app:"Wavdrop"`, `packageName:"com.launchpoint.wavdrop"`, `database:"wavdrop.db"`; `wavdrop-desktop-lab`; `.wdbk` magic/manifest | Existing backups and Desktop exports must stay readable. Writer metadata unchanged in slice 1 (DECISION REQUIRED, see report). |
| B | Room DB file `wavdrop.db`, DataStore names `wavdrop_preferences`, `wavdrop_widget_appearance`, `wavdrop_widget_state`, WorkManager `wavdrop_auto_backup_due_check` | Per-install storage; a new app ID has fresh storage, but names must not change until in-place import/migration policy is approved. |
| B | DB row/value ids: `wavdrop_playback`, `wavdrop_desktop_playback`, `wavdrop_json`, `EqPresetType.WAVDROP_PRESET`, `wavdrop_preset_id`/`wavdropPresetId` fields, `wavdrop_album_id` | Persisted in Room/DataStore/backups. |
| B/A? | Backup file names `wavdrop-backup[-date].wdbk` (+ `isBackupFileName` recognition) | User-visible but used to find/replace previous backups; needs a decision (write StereoSine, recognise both). |
| A (later slice) | Kotlin type/symbol names: `WavdropDatabase`, `WavdropBackup*`, `WavdropTheme`, `WavdropMotion`, `WavdropNavGraph`, `WavdropWidget*`, `WavdropMediaLibrary` (+ browse ids `wavdrop_root`…), `WavdropPreset*`, test class names | Internal only; rename with refactor tooling. Backup-related names stay with the legacy-format code if kept. |
| A (later slice) | Log tags (`Wavdrop-Startup`, `WavdropBackup`, …), `WAVDROP_VALIDATION_BOUNDED` BuildConfig, `-PwavdropValidationBounded`, `wavdrop.bench`, `Capture-WavdropValidation.ps1` | Tooling; coupled to tests and the capture script. |
| B (signing) | `WAVDROP_UPLOAD_*` env vars, `keyAlias=wavdrop-upload` in `keystore.properties.example` | Signing migration is out of scope; local secrets keep working. |
| C | `docs/WAVDROP_*.md` format specs, `RELEASE_NOTES.md`, `WHATS_NEW.md`, QA/readiness docs, `ENGINEERING_BACKLOG…`, `PROJECT_CONTEXT.md`, `README.md` | Docs slice: current-product docs rebranded, release history labelled legacy. `VersionConfigTest` asserts `WHATS_NEW.md` title `Wavdrop Beta 10.0`; change both together. |
| E | GitHub repo URL `mafuzanam02-wq/wavdrop-music-player`; privacy URL `https://launchpointdigital.co.za/wavdrop/privacy` | Needs separate authorization / new hosted page. |
| D | `app/build/`, `build/`, `release-builds/`, `releases/`, old APKs, `.gradle/`, stray `C:...build.log` | Untracked/ignored; not in patch. |

## Slice 1 (this patch) scope
Gradle root project, namespace, applicationId (debug suffix preserved ⇒ `.stereosine.debug`), 940+ file package
move (`git mv`-equivalent renames), `StereoSineApp`, manifest + alias + intent-action strings, launcher/app labels
(`StereoSine`), user-visible prose, theme/resource/asset renames, Wrapped export file prefixes, Room schema dir,
`.idea/.name`, capture-script default package (so it never targets the old app), tests updated, plus
`BrandIdentityTest`.

---

# BRAND-02 — Active Kotlin symbols and internal tooling (completed)

Baseline HEAD `bd70dbe67dd1477d5767ff4cb54955e421cf5484`. Behaviour is unchanged; no persisted identifier, schema,
application ID, backup metadata or signing value was touched.

## Renamed (code-only symbols and source files)
- Types/objects: `WavdropDatabase`→`StereoSineDatabase`, `WavdropTheme`/`WavdropTypography`/`WavdropMotion`/`WavdropHaptics`/
  `WavdropFadeThrough`, `WavdropNavGraph`/`WavdropNavViewModel`, `WavdropWidgetProvider`/`WavdropWidgetUpdater`/
  `WavdropWidgetEntryPoint`, `WavdropMediaLibrary`/`WavdropLibraryIds`, `WavdropPreset`/`WavdropPresetCatalog`/
  `WavdropPresetResolver`, `WavdropAbout`, `WavdropSessionCallback` (all → `StereoSine…`).
- Functions/properties: `parseWavdropPresetId`, `resolveWavdropBandLevels`, `resolveWavdropInitialLevels`, `setEqWavdropPreset`,
  `onSelectWavdropPreset`, `wavdropPresetsAvailable`, `stateAfterWavdrop`, `withWavdropCommands`,
  `clearWavdropPlaylistsForRecovery`, `eqWavdropPresetId`, `wavdropPresetId`, `wavdropId`/`wavdropName` (debug summary text),
  `EQ_WAVDROP_PRESET_ID`, `wavdropPreferencesDataStore`, `wavdrop{FadeThrough,ItemPlacement,PressFeedback,PressScale}`.
- Constants: `WAVDROP_DATABASE_NAME`→`DATABASE_FILE_NAME`, `WAVDROP_ALBUM_ID_EXTRA`→`ALBUM_ID_EXTRA` (values unchanged).
- 18 Kotlin source files (11 main + 7 test) plus the capture script renamed to match; schema directory
  `schemas/…WavdropDatabase` → `schemas/…StereoSineDatabase` (Room derives it from the class name; JSON content byte-identical).
- Manifest: only `.ui.widget.WavdropWidgetProvider` → `.ui.widget.StereoSineWidgetProvider` (exported status unchanged).
- Log tags (non-backup): `Wavdrop{Validation,StartTrace,QueuePerf,SessionPerf,SessionProgress,Crossfade,NextSlot,Resume,Gapless,
  AudioSession,AudioEffects,Artwork,SearchPlayback,Widget}` and `Wavdrop-{Startup,Sync,IconSwitch}` → `StereoSine…`.
- Tooling: `WAVDROP_VALIDATION_BOUNDED`→`STEREOSINE_VALIDATION_BOUNDED` (BuildConfig, debug-only; release stays hard-coded false);
  Gradle `-PstereosineValidationBounded` (legacy `-PwavdropValidationBounded` kept as a documented alias);
  `stereosine.bench` / `STEREOSINE_BENCH[_SIZES]` (legacy `wavdrop.bench` / `WAVDROP_BENCH[_SIZES]` still accepted);
  `stereosine.expectValidationBounded`; `scripts/Capture-WavdropValidation.ps1`→`Capture-StereoSineValidation.ps1`
  (default output folder is now `%USERPROFILE%\stereosine-validation-logs`).

## Deliberately preserved (compatibility)
`wavdrop_backup`, `wavdrop_wdbk`, `.wdbk` markers, backup writer metadata (`app`, `packageName`, `database`) and filenames
(`wavdrop-backup[-date].wdbk`), `wavdrop.db`, DataStore names (`wavdrop_preferences`, `wavdrop_widget_appearance`,
`wavdrop_widget_state`), key `eq_wavdrop_preset_id`, WorkManager `wavdrop_auto_backup_due_check`, `EqPresetType.WAVDROP_PRESET`,
stored event sources (`wavdrop_playback`, `wavdrop_desktop_playback`, `wavdrop_json`) and the constants `SOURCE_WAVDROP_PLAYBACK`,
`ImportSourceTypes.WAVDROP_JSON` that carry them, MediaLibrary ids (`wavdrop_root|recent|recent_root|songs`), extra key
`wavdrop_album_id`, `WAVDROP_UPLOAD_*` signing variables. Pinned by `PersistenceContractTest`.

## Retained active symbols — backup/restore subsystem (next slice)
`WavdropBackup*`, `DesktopWavdropBackup*`, `WavdropMergePreviewAnalyzer`, `BackupDocumentReadResult.Wavdrop`,
`ImportFileValidation.WAVDROP_*_MESSAGE`, `isLikelyWavdropBackup*`, backup log tags (`WavdropBackup`, `WavdropRestore`,
`WavdropImportApply`, `WavdropOverlayPlanner`, `WavdropPreview`) and their test classes. They name the legacy format and move
together with the backup writer-metadata/filename decision.

## Remaining risks
- Renaming the widget provider class changes the component name: a widget already placed from a pre-BRAND-02 StereoSine build
  would be dropped on update. Not a risk for installs that never shipped the BRAND-01 identity.
- Docs under `docs/` and root `*.md` still carry the old name (historical/QA); `VersionConfigTest` ties `WHATS_NEW.md`'s title to it.
- Residual user-visible old text: "Always saves as wavdrop-backup.wdbk" in Backup settings (filename decision pending).
