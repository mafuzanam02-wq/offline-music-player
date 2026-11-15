# StereoSine Play Store Readiness Checklist

> **Signing status (see `docs/RELEASE_SIGNING.md`):** the original Wavdrop key was lost and is not used. A StereoSine upload key
> exists and was verified locally (2026-11-14); a signed release APK was built and verified locally but not published. Google
> Play App Signing enrollment is **unconfirmed**, no AAB has been recorded as built or submitted, and **no StereoSine release has
> been published**.
>
> **Outstanding external identity (rebrand, not yet done):** Google Play App Signing enrollment and first upload, and the Play
> Console listing/app record. (Done since: public privacy URL `https://launchpointdigital.co.za/stereosine/privacy` is live, the
> in-app Privacy Policy cites it as plain text, Terms has a View Terms Online action, and the GitHub repository is now
> `mafuzanam02-wq/offline-music-player`.)
>
> **Disclosure alignment (BRAND-09A review, addressed in BRAND-10A):** the in-app Privacy Policy now discloses optional,
> best-effort scheduled automatic backup, user-selected and cloud-backed backup folders, the unencrypted backup format, the
> Recovery-mode safety copy and earlier-Wavdrop backup compatibility, verified against the Android implementation and pinned by
> `PrivacyPolicyGuardTest`. This is a text alignment only: Google Play Data Safety review and legal review are **not** complete.
> Residual items (BRAND-10A): the in-app Privacy Policy and Terms still say "Export StereoSine Data" where the Settings UI says "Save backup file"; and the public page words the safety copy as before any restore, whereas the app takes it only before a Recovery-mode restore.
>
> **RELEASE-02A (in-app text only):** the in-app Privacy Policy now explains that "Save backup file" (Settings → Backup & Migration) is what the policy also calls exporting StereoSine data (the Terms are unchanged); discloses the user-started Google searches from Now Playing; covers user-initiated sharing of Wrapped images and videos as well as audio tracks; names the permissions that libraries (WorkManager, Media3) merge into the manifest; and the Settings "Your library stays yours" card accounts for optional scheduled backups. The public website still carries the previous wording and must be updated in a separate website slice; the safety-copy wording difference above is unchanged.

Tracks every item needed before submitting to Google Play. Update each row
as items are completed. Tick `[x]` when done; leave `[ ]` open.

---

## 1. Build Configuration

| Item | Status | Notes |
|---|---|---|
| `compileSdk = 36` | ✅ Set | `app/build.gradle.kts` |
| `targetSdk = 36` | ✅ Set | Android 16; re-check Google Play's current target-API requirement before each submission |
| `minSdk = 26` (Android 8.0) | ✅ Set | Covers a wide device range |
| `versionCode` set to production value | ⬜ Pending | Currently `10` (`app/build.gradle.kts`); must be incremented before every Play Console upload (each upload requires a unique, higher value) |
| `versionName` set to release value | ✅ `"0.1.0-beta10"` | Current value in `app/build.gradle.kts`; appropriate for the beta track |
| Release build signed with upload key | ⬜ Pending | Signing is wired in `app/build.gradle.kts` (see `docs/RELEASE_SIGNING.md`): one complete credential set (`keystore.properties`, `STEREOSINE_UPLOAD_*`, or legacy `WAVDROP_UPLOAD_*`) produces a signed release; a StereoSine upload key exists and a signed APK was verified locally but not published; none produces an unsigned release; a partial set fails the build. Credentials are never committed. Play Console enrollment / first upload is not recorded here and remains to be confirmed. |
| ProGuard/R8 enabled for release build | ✅ `isMinifyEnabled = true`, `isShrinkResources = true` | Confirmed in `app/build.gradle.kts` |
| Debug APK removed from release track | ⬜ Pending | Only upload a release-signed APK/AAB |
| AAB (Android App Bundle) built for upload | ⬜ Pending | Play Console prefers `.aab` over `.apk` |

---

## 2. Permissions

| Permission | Declared | Appropriate | Notes |
|---|---|---|---|
| `READ_EXTERNAL_STORAGE` | ✅ | ✅ | `maxSdkVersion="32"` — correct scope for API 26–32 |
| `READ_MEDIA_AUDIO` | ✅ | ✅ | API 33+ granular audio permission |
| `FOREGROUND_SERVICE` | ✅ | ✅ | Required for background media playback |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | ✅ | ✅ | Matches `foregroundServiceType=mediaPlayback` in service |
| `BLUETOOTH` | ✅ | ✅ | `maxSdkVersion="30"`; used only to receive profile connection broadcasts for auto-resume |
| `BLUETOOTH_CONNECT` | ✅ | ✅ | API 31+; reconnect broadcasts and output-route detection. No scan/discovery is performed; no location permission |
| `ACCESS_NETWORK_STATE` | ➕ Merged | ✅ | Merged in by `work-runtime` 2.9.1, `media3-exoplayer`/`media3-common` 1.11.1 (manifest-merger report); not declared in the source manifest. Network-state checks inside those libraries; StereoSine's own backup schedule has no network constraint. Not a runtime prompt. Named in the in-app policy (RELEASE-02A) |
| `WAKE_LOCK` | ➕ Merged | ✅ | Merged in by `work-runtime` and `media3-exoplayer`; lets those libraries hold the device awake while work runs. Not a runtime prompt. Named in the in-app policy |
| `RECEIVE_BOOT_COMPLETED` | ➕ Merged | ✅ | Merged in by `work-runtime` and `media3-session`; lets scheduled work be restored after a restart. Not a runtime prompt. Named in the in-app policy |
| `INTERNET` | ✅ Absent | ✅ | StereoSine itself makes no network requests (also absent from the merged release manifest); the browser search action hands a URL to the user's browser |
| Location | ✅ Absent | ✅ | Not used |
| Camera | ✅ Absent | ✅ | Not used |
| Microphone | ✅ Absent | ✅ | Not used |
| Contacts | ✅ Absent | ✅ | Not used |
| Advertising ID | ✅ Absent | ✅ | No ads or tracking |

---

## 3. Privacy / Data Safety

Google Play Data Safety form — all answers are derived from the code audit.
StereoSine itself makes no network requests and runs no upload service. Data is handled outside the app only after a user action, in three ways: (1) backup/export files saved to a destination the user selects, including destinations whose storage provider may synchronize the file to cloud storage; (2) audio tracks and Wrapped images/videos shared through the Android share sheet to an app the user picks; (3) the Now Playing online lyrics and artist searches, which open an external browser or link-handling app and can send the song title or artist name to Google Search. In each case another app or provider handles the data under its own terms. How these should be answered in the Data safety form has not been decided or verified.

| Question | Answer | Evidence |
|---|---|---|
| Does the app collect user data and send it to your servers? | **No** | No `INTERNET` permission; no networking libraries. (Now Playing "search online" actions open a search URL in the user's own browser via `ACTION_VIEW`; StereoSine sends nothing itself.) |
| Does the app share data with third parties? | **No** | Share sheet is a user-initiated OS feature for audio tracks and for Wrapped images/videos (created in the app cache, exposed through the non-exported FileProvider); the browser search actions are user-initiated ACTION_VIEW intents. No data pipeline. Whether these user-initiated actions need a Data safety declaration is an owner/legal determination that has not been made |
| Is data encrypted in transit? | **Not decided** | StereoSine itself transmits nothing over the network, so it has no transit encryption to declare. Transfers after a user action (browser search, share target, storage-provider sync) are performed by other apps and are outside StereoSine's control; whether they affect this answer is an owner/legal determination |
| Does the app use advertising ID? | **No** | No ad SDK; `INTERNET` absent |
| Does the app use crash reporting? | **No** | No Crashlytics, Firebase, or equivalent |
| Can users request data deletion? | **Yes** | Uninstalling the app deletes all local DB and DataStore data |
| Privacy Policy URL added to Play Console listing | ⬜ Pending | Use `https://launchpointdigital.co.za/stereosine/privacy` (live; Play Console entry not done) |
| Privacy Policy URL added to app store listing | ⬜ Pending | Same URL in the listing Privacy Policy field |

**Data Safety — detailed data type declarations:**

Select "No" for all data type collection in the Data Safety section. Specific
types to explicitly confirm as not collected:

- Location — not collected
- Personal info (name, email) — not collected
- Financial info — not collected
- Health and fitness — not collected
- Messages — not collected
- Photos and videos — not collected (audio file metadata is read locally, not collected)
- Audio files — accessed locally via MediaStore; not collected/uploaded
- Files and docs — backup files created by user via SAF; StereoSine does not upload them
- Contacts — not collected
- App activity — not collected
- App info and performance — not collected (no crash reporting)
- Device or other IDs — not collected

---

## 4. In-App Legal / Privacy Wording

| Item | Status | Location |
|---|---|---|
| Privacy Policy dialog present | ✅ Done | Settings → About → Legal → Privacy Policy |
| Privacy Policy covers local storage, permissions, backup, share, delete, import | ✅ Done (text) | `StereoSineAbout.PRIVACY_POLICY` (BRAND-10A) covers manual export, optional automatic backup, cloud-backed folders, unencrypted backups, the Recovery safety copy, BlackPlayer and earlier-Wavdrop import; Data Safety and legal review are not complete |
| No cloud/future-sync promise in Privacy Policy | ✅ Done | Removed in legal polish pass |
| Terms of Use dialog present | ✅ Done | Settings → About → Legal → Terms of Use |
| Disclaimer covers delete permanence, share, music rights | ✅ Done | `StereoSineAbout.DISCLAIMER` — 8 items |
| Open Source Licenses: no placeholder text | ✅ Done | Apache 2.0 attribution for all 8 major dependencies |
| Backup export subtitle accurate | ✅ Done | `SettingsBackupScreen.kt` |
| Policy discloses user-started online lyric/artist searches, Wrapped sharing, merged permissions, optional scheduled backup in the Settings summary | ✅ Done (in-app text, RELEASE-02A) | `StereoSineAbout.PRIVACY_POLICY`, `StereoSineAbout.LIBRARY_STAYS_YOURS`; pinned by `PrivacyPolicyGuardTest`. Website not yet updated |
| Backup import subtitle accurate (restore is live) | ✅ Done | `SettingsBackupScreen.kt` |
| Permission request wording clear | ✅ Done | `AudioPermissionGate.kt` — "audio files on this device" |
| Onboarding privacy promise present | ✅ Done | Page 2: "Your music stays yours" |

---

## 5. Manifest / Configuration

| Item | Status | Notes |
|---|---|---|
| `android:label` set | ✅ `@string/app_name` = "StereoSine" | |
| Launcher icon references valid mipmap-nodpi assets | ✅ | All 6 aliases point to `mipmap-nodpi/stereosine_icon_*.png` |
| Round icon references valid | ✅ | `stereosine_icon_*_round.png` present for all 6 variants |
| `ACTION_VIEW` audio MIME types declared | ✅ | `audio/*`, `audio/mpeg`, `audio/mp4`, `audio/aac`, `audio/flac`, `audio/ogg`, `audio/wav`, `audio/opus`, `audio/amr`, `audio/midi` |
| `android:allowBackup="false"` | ✅ | Prevents ADB backup of app data |
| `PlaybackService` exported with media session action | ✅ | Required for system media UI on API 33+ |
| `foregroundServiceType=mediaPlayback` declared | ✅ | Matches `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission |
| `android:supportsRtl="true"` | ✅ | Required for international distribution |
| 6 launcher aliases declared | ✅ | `ObsidianBlack` is the enabled default; Midnight Violet, Clean Purple, Deep Teal, Ocean Blue, and Sunset Orange are disabled until the user selects them |
| Media / system integration declared | ✅ | `PlaybackService` is a `MediaLibraryService` (also `MediaBrowserService` / `MediaSessionService` actions); `MediaButtonReceiver`; exported only where required |
| Non-exported internal receivers | ✅ | `AudioOutputReconnectReceiver` and `WidgetActionReceiver` are `exported="false"`; the widget provider is exported for the launcher |
| Background work | ✅ | WorkManager periodic automatic-backup check (default initializer replaced by a Hilt worker factory) |

---

## 6. Store Listing Assets

| Asset | Status | Spec |
|---|---|---|
| App icon (512 × 512 px, PNG, no alpha) | ⬜ Pending | Required for listing. Use the Obsidian Black icon (the default launcher icon) as base — see `docs/BRANDING.md` |
| Feature graphic (1024 × 500 px, JPG or PNG, no alpha) | ⬜ Pending | Required — appears at the top of listing on large screens |
| Phone screenshots (minimum 2, portrait) | ⬜ Pending | See Section 7 |
| Tablet screenshots | ⬜ Optional | Recommended if targeting tablets |
| Short description (≤ 80 chars) | ⬜ Pending | See `PLAY_STORE_LISTING_DRAFT.md` |
| Full description (≤ 4000 chars) | ⬜ Pending | See `PLAY_STORE_LISTING_DRAFT.md` |
| Privacy Policy URL | ⬜ Pending | `https://launchpointdigital.co.za/stereosine/privacy` (live; Play Console entry not done) |
| Contact email | ⬜ Pending | Confirm final support email address before listing |
| Developer name | ⬜ Pending | Must match Play Console account name (LaunchPoint Digital) |

---

## 7. Screenshot Checklist

Capture on a clean device with a representative music library loaded.
Minimum 2 phone screenshots required; 6–8 recommended.

| Screen | Priority | Notes |
|---|---|---|
| Home dashboard | High | Show continue listening + sections |
| Now Playing | High | With album art, controls, track info visible |
| Songs list (All Songs) | High | Shows library scan working |
| Albums or Artists | Medium | Shows browse capability |
| Statistics Dashboard or Listening Report | Medium | Shows Wrapped/reports value |
| Playlists or Smart Collections | Medium | Shows organisation features |
| Settings → About (Legal section visible) | Low | Demonstrates transparency |
| Monthly Reports or Wrapped | Low | Shows unique analytics value |

---

## 8. Closed Testing / Internal Testing

| Item | Status | Notes |
|---|---|---|
| Internal test track created in Play Console | ⬜ Pending | Use for initial upload before promotion |
| APK/AAB uploaded to internal/closed track | ⬜ Pending | |
| Minimum 1 tester confirmed install and launch | ⬜ Pending | |
| QA smoke pass completed | ⬜ Pending | See `QA_CHECKLIST.md` |
| No P0/P1 crashes in closed track | ⬜ Pending | |
| Test devices: Samsung (One UI) | ⬜ Pending | Launcher icon caching; share sheet behavior |
| Test devices: Pixel / stock Android | ⬜ Pending | Reference behavior |
| Test devices: Android 8.x / 9 (minSdk) | ⬜ Pending | Confirm `READ_EXTERNAL_STORAGE` path |
| Test devices: Android 13+ | ⬜ Pending | Confirm `READ_MEDIA_AUDIO` path |

---

## 9. Known Device / QA Risk Areas

| Area | Risk | Mitigation |
|---|---|---|
| Launcher icon switching | Launcher caching delays vary by OEM | Documented in `PLANNED.md`; explain delay in-app if needed |
| Share action on Samsung / Xiaomi | OEM share sheets vary | Test `audio/*` MIME type across major OEMs |
| Delete from device on Android 11–12 vs 13+ | `MediaStore.createDeleteRequest` behavior differs | Test on each API level; confirm system dialog appears |
| Bluetooth auto-resume | OEM audio routing behavior varies | Real-device testing required; documented in QA checklist |
| WhatsApp voice note exclusion | Folder path matching depends on OEM folder structure | Test on WhatsApp-installed device |
| Large library performance (5,000+ songs) | LazyColumn re-composition cost at scale | Stress test before production release |
| Backup restore with very large event history | Memory and time to parse large JSON | Tested manually; no known issue |

---

## 10. Release Blockers

Items that must be resolved before any public release track.

| Blocker | Owner | Notes |
|---|---|---|
| Public Privacy Policy URL | LaunchPoint Digital web team | `https://launchpointdigital.co.za/stereosine/privacy` — live (BRAND-06D); Terms online copy at `https://launchpointdigital.co.za/stereosine/terms` |
| Production keystore generated and stored securely | Dev | Upload key created and locally verified; encrypted off-repository backups reported in `docs/RELEASE_SIGNING.md`. Do not lose; Play App Signing enrollment is unconfirmed |
| Release-signed AAB built and tested | Dev | A signed release APK was built and verified locally; an AAB (`bundleRelease`) build/test and any upload are not recorded |
| In-app and public Privacy Policy disclosure alignment | Dev + LaunchPoint web | In-app text aligned in BRAND-10A (see header). Still open: the public safety-copy sentence says "before StereoSine restores data" whereas the app takes the copy only before a Recovery-mode restore (website change needed, owner decision); "Export StereoSine Data" wording vs the "Save backup file" UI label |
| Store listing assets complete (icon, feature graphic, screenshots) | Design | |
| Data Safety form completed in Play Console | Dev | All "No" for data collection; see Section 3 |
| Privacy Policy URL entered in Play Console listing | Dev | |
| App reviewed against Play Store policies for audio players | Dev | Review current play.google.com/about/developer-content-policy |

---

## 11. Post-Launch Items (Not Blockers)

| Item | Notes |
|---|---|
| Migrate support email to domain address | Done — in-app contact and privacy policy now use `info@launchpointdigital.co.za`. Update Play Store developer contact field to match before submission. |
| Add Privacy Policy URL reference to in-app Privacy Policy dialog | Once web page is live, add a "Full policy at [URL]" line to `StereoSineAbout.PRIVACY_POLICY` |
| Review OSS licenses via Gradle OSS Licenses Plugin | Current manual Apache 2.0 list is sufficient for launch; plugin automates future dependency additions |
| Tablet layout review | Compose stretches gracefully but a tablet-specific layout pass would improve quality |
