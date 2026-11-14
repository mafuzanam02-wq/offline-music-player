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

---

# BRAND-03 — Backup filenames and backup-subsystem symbols (completed)

Baseline HEAD `a7ed696f57da104faeaac522052d25f54fbe4821`. No format, marker, version, checksum, serialization or writer-metadata change.

## Filename policy (`data/backup/BackupFileNames.kt`, single source of truth)
- New backups: `stereosine-backup.wdbk` (replace-previous) and `stereosine-backup-<yyyy-MM-dd>.wdbk` (dated; `LocalDate.toString()` as before).
  Used by manual export suggestion (`SettingsBackupScreen`) and scheduled/"Back up now" (`AutoBackupRepository`).
- Recognised for discovery (Backup Verification): `stereosine-backup*` and legacy `wavdrop-backup*`, `.wdbk` or legacy `.json`, never `.tmp`.
  This is the only place a filename controls behaviour besides the writer target; restore/preview/import decide by CONTENT, not name.
- Replace-previous targets the exact `stereosine-backup.wdbk` in the chosen folder. A legacy `wavdrop-backup.wdbk` is a different file:
  never read-for-overwrite, renamed, overwritten or deleted. After migration a folder can hold both; verification picks the newest by
  modification time, ties to the StereoSine name. A stale legacy file therefore remains until the user removes it.
- Manual export keeps the user's chosen name; the app only suggests one.

## Renamed symbols (code-only)
`WavdropBackup*` → `StereoSineBackup*` (model, parser, exporters, integrity, repository, importer, document reader, stats matcher, tests),
`WavdropMergePreviewAnalyzer`, `BackupDocumentReadResult.Wavdrop`, `isLikelyWavdropBackup{FileName,Content}`,
`ImportFileValidation.WAVDROP_{WRONG_FILE,NOT_A_BACKUP}_MESSAGE` → `STEREOSINE_…`, log tags `WavdropBackup/Restore/ImportApply/OverlayPlanner/Preview`.
24 source files renamed to match.

## Retained on purpose
`DesktopWavdropBackup*` (names the external Desktop `wavdrop-desktop-lab` format), `wavdrop_backup` / `wavdrop_wdbk`, `.wdbk` markers,
writer metadata (`app:"Wavdrop"`, `packageName:"com.launchpoint.wavdrop"`, `database:"wavdrop.db"`), `ImportSourceTypes.WAVDROP_JSON`,
`SOURCE_WAVDROP_PLAYBACK`, all BRAND-02 persisted literals.

## Remaining
- Writer metadata (`app`/`packageName`/`database`) still legacy: DECISION REQUIRED, depends on Desktop consumers.
- Backup preview/restore copy says "StereoSine backup" without a legacy-format note; wording of any legacy hint is a product choice.

---

# BRAND-04 — Current documentation and release identity (completed)

Baseline HEAD `37e93ef5f5a0144abd518dd2fa3ce123c3bd3ee6`. Documentation and the directly dependent `VersionConfigTest` only; no
production code, format, schema, version (`0.1.0-beta10` / `versionCode 10`) or external system was changed.

## Classification applied
- **Current branding -> StereoSine:** `README.md`, `PROJECT_CONTEXT.md`, `ENGINEERING_BACKLOG_AND_DECISIONS.md`, `TECHNICAL_DEBT_REGISTER.md`,
  `PLANNED.md`, `QA_CHECKLIST.md`, `PLAY_STORE_*`, `docs/ARCHITECTURE.md`, `docs/BRANDING.md` (rewritten), `docs/RELEASE_SIGNING.md`,
  `docs/BACKUP_PRESERVATION_CONTRACT.md`, `docs/development/P5-*`, `docs/diagnostics/P0-*`, `docs/architecture/CROSSFADE-*`, `docs/architecture/WDBK_BENCHMARKS.md`.
  Stale code references corrected (package paths, `StereoSine*` class/tag names, `stereosine_icon_*` resources, backup file names, debug app id).
- **Historical (preserved):** `RELEASE_NOTES.md` entries, the Beta 10 `WHATS_NEW.md` summary (title kept, "now StereoSine" and a
  not-yet-released note added), `docs/qa/*`, `docs/architecture/adr/*`. Only a link target in `docs/qa/P5-F3-…` was repaired.
- **Legacy contract (preserved, note added):** `docs/WAVDROP_DATA_FORMAT_SPEC.md`, `WAVDROP_BACKUP_SCHEMA_V1.md`, `WAVDROP_IMPORT_RULES.md`
  (file names kept) and the identifiers `wavdrop_backup`, `wavdrop_wdbk`, `wavdrop-desktop-lab`, `wavdrop.db`, DataStore names, event sources.
- **External (kept, documented as outstanding):** GitHub `mafuzanam02-wq/wavdrop-music-player`; privacy URL `…/wavdrop/privacy`
  (and the external site's `wavdropPrivacyPolicy.js` / `WavdropPrivacyPage.jsx` file names).
- **Signing:** `WAVDROP_UPLOAD_*`, `keyAlias=wavdrop-upload`; docs now state the original key was lost and no StereoSine signing identity exists yet.
- **Stale/misleading corrected:** docs claimed package/ID `com.launchpoint.wavdrop`, `WavdropAbout`, `wavdrop_icon_*`, `wavdrop-backup*` as current.

## Remaining work (not done in this slice)
1. New StereoSine signing identity and rename of `WAVDROP_UPLOAD_*` / `keystore.properties.example`.
2. Host and verify a StereoSine privacy page, then change `PRIVACY_POLICY_URL` (+ `PrivacyPolicyGuardTest`) and the Play listing.
3. Rename the GitHub repository and update remotes/links.
4. Backup writer metadata decision (`app`, `packageName`, `database`) and Desktop `wavdrop-desktop-lab` plan.
5. Pre-existing doc drift unrelated to the rebrand: `docs/ARCHITECTURE.md` and `docs/architecture/CROSSFADE-PROMOTION-FEASIBILITY.md` still cite retired
   crossfade files (e.g. `CrossfadeNaturalHandoff.kt`) that no longer exist.
6. Production comments still say "Wavdrop" (`TrackIdentityEntity.kt`, `AudioEnhancementController.kt`, capture-script comment); code is out of this slice.
The rebrand is therefore **not** fully complete.
