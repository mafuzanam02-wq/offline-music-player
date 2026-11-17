# StereoSine Planned Work

Product-level view of upcoming work. Nothing in this file is implemented.
When work ships, move it to RELEASE_NOTES.md and remove it here. Detailed engineering slices and
architecture backlog live in [ENGINEERING_BACKLOG_AND_DECISIONS.md](ENGINEERING_BACKLOG_AND_DECISIONS.md);
do not duplicate them here.

---

## In Progress (not user-facing)

- **Crossfade** - architecture in progress; live user-facing feature not yet enabled. There is no
  crossfade setting and nothing audible changes yet.

---

## Approved

### StereoSine rebrand - outstanding external identity and follow-ups

The in-repo rebrand (BRAND-01..04) is complete for identity, symbols, backup file names and documentation. Not done, and
each needs separate authorization:

- Signing: the original Wavdrop key was lost. A StereoSine upload key exists and was verified locally and a signed release APK was built locally (not published); still pending: Google Play App Signing enrollment, first upload, and any StereoSine release (see `docs/RELEASE_SIGNING.md`).
- Disclosure alignment (BRAND-09A review): resolved in the in-app Privacy Policy by BRAND-10A (automatic backup, cloud-backed folders, unencrypted backups, Recovery safety copy, earlier-Wavdrop import). Still open: the public page's safety-copy wording is broader than the app's Recovery-only behavior; "Export StereoSine Data" vs the "Save backup file" UI label. Google Play Data Safety and legal review remain not done.
- Done (BRAND-06/09A): the StereoSine privacy page is hosted and verified at `https://launchpointdigital.co.za/stereosine/privacy`, and
  `StereoSineAbout.PRIVACY_POLICY_URL` and the privacy guard test now use it. Still pending: the Play Console listing field. The old
  `/wavdrop/privacy` page stays published on the website.
- Done (BRAND-07A): the GitHub repository is now `mafuzanam02-wq/offline-music-player` (renamed from `wavdrop-music-player`; the old URL redirects).
- Search history disclosure (SEARCH-01): recent searches are stored locally on the device (separate store, not backed up, never uploaded). PRIVACY-SEARCH-01A added the in-app disclosure (a "Recent searches" paragraph and a local-data bullet). Still pending, in a separate website slice: add the matching public disclosure to `src/stereosinePrivacyPolicy.js` (a "Recent searches" section after "Data stored locally" and a matching data-list item) and its pins in `scripts/stereosinePrivacyTest.mjs`, then deploy. Until then the in-app and public policies differ.
- Decide backup writer metadata (`app`, `packageName`, `database`) and the Desktop `wavdrop-desktop-lab` interop plan.
- Done (BRAND-05B): signing variables are now `STEREOSINE_UPLOAD_*` with `WAVDROP_UPLOAD_*` kept as a legacy fallback. Still pending: enroll in Google Play App Signing and eventually remove the legacy variable names.

### Public Privacy Policy Page — IMPLEMENTED, PUSHED, DEPLOYED, LIVE HTTPS VALIDATED, CLOSED (WPP-1)

The in-app policy carries `StereoSineAbout.PRIVACY_POLICY_URL`, and the LaunchPoint Digital site serves the prerendered `/wavdrop/privacy` route. Hosting was deployed and `https://launchpointdigital.co.za/wavdrop/privacy` was opened successfully over HTTPS; the owner confirmed the QA_CHECKLIST "WPP-1" items A–I.

(Historical note, BRAND-09A: the app now links `https://launchpointdigital.co.za/stereosine/privacy`; the `/wavdrop/privacy` route above is the original WPP-1 deployment.)

Original requirement: publish the StereoSine Privacy Policy at `https://launchpointdigital.co.za/wavdrop/privacy`. This URL is required for the Google Play store listing Privacy Policy field before public launch. The page should publish the same text as the in-app Privacy Policy dialog. Once the page is live, add a reference line to the bottom of the in-app Privacy Policy copy pointing to the URL.

### Save queue as playlist — IMPLEMENTED (QSP-1); physical validation pending

Queue Sheet header menu -> "Save queue as playlist" saves the current logical queue (earlier + current + Up Next, in live order, repeated occurrences kept) as a new ordinary playlist. Playback is never touched. Still deferred: save only Up Next, overwrite/append/replace an existing playlist, auto-save, queue history, named queue snapshots, temporary playlists, smart-playlist conversion, playlist folders, M3U export, sync, playlist artwork, queue sharing.

---

## Under Evaluation

- **Support domain email**: ~~Migrated~~ — `StereoSineAbout.CONTACT_EMAIL` and the privacy policy contact line in `SettingsAboutScreen.kt` now use `info@launchpointdigital.co.za`. Update the Play Store developer contact field to the same address before submission.
- **Additional Delete entry points**: after Track Details Phase 1 is stable and validated,
  evaluate adding "Delete from device" to song-row overflow menus (Songs, Home, Album, Artist,
  Folder, Smart Collection screens). Requires assessment of accidental-deletion risk in
  list-view contexts.
- **Drag auto-scroll/reorder library**: evaluate replacing the current custom drag-to-reorder
  implementation with a stable third-party library if real-device edge cases surface in
  testing. The current implementation is functional but the auto-scroll and
  virtualization-interrupt paths are non-trivial to maintain.
- **Broader folder exclusion beyond CFE-1**: CFE-1 (implemented, physical validation pending) lets a user exclude a
  concrete folder already known to StereoSine (and its descendants) from Folder Details, managed in Settings -> Library &
  Scanning -> Custom excluded folders. Still deferred: arbitrary free-text paths, wildcards, regex, a general allow-list,
  per-song exclusion, metadata-based filtering, automatic exclusion suggestions, a filesystem browser for exclusions, and
  exporting exclusions in backups.

---

## Deferred

- Delete from song-row overflow menus (Phase 2, after Phase 1 is stable).
- Delete from Queue Sheet.
- Delete from Playlist Details inline row actions.
- Bulk delete (multi-select delete from device).
- Undo / recycle-bin behavior for deleted files (not feasible: Android provides no recycle bin
  for shared media storage).
- Scrobbling / last.fm integration.
- Android Auto support.
- Lock-screen widgets / additional widget surfaces (a home-screen widget already ships).
- Equalizer expansion beyond the shipped device Equalizer (portable/custom curves; automatic profile selection,
  headphone calibration/automatic detection and Loudness Protection remain deferred; manual Output Profiles
  and user-named Headphones presets are implemented).
- Metadata / ID3 tag editing.
- In-app reset / clear-data (if ever added it must require an export-before-reset prompt).

---

## Rejected

- Silent deletion (no confirmation before removing a file from device).
- Skipping the StereoSine pre-confirmation dialog before `MediaStore.createDeleteRequest`.
- Deleting externally opened audio files (opened via `ACTION_VIEW`; not part of the StereoSine library).
- Wiping `track_stats` or `track_listen_events` when the user deletes a track from device.
- Streaming features.
- Cloud-first music playback.
- User accounts / social / shared listening features.
- AI recommendation or playlist-generation systems.

---

## Known Issues / Open Questions

- **Queue/playlist drag reorder — virtualization-interrupt commit**: the commit fix lands the
  dragged item at the last tracked ghost position when the source row leaves the composition
  window, but this path needs broader real-device validation across screen sizes and Android
  versions before it can be considered fully stable.
- **Native Share action**: needs validation across WhatsApp, Gmail, Google Drive, Bluetooth,
  Nearby Share / Quick Share, and OEM-customized share sheets (Samsung OneUI, Xiaomi MIUI,
  etc.) to confirm the `audio/*` MIME type and `FLAG_GRANT_READ_URI_PERMISSION` combination
  behaves correctly across apps and Android versions.
- **Delete from device Phase 1**: implemented on Track Details for Android 11+. Needs real-device
  QA: delete non-playing track, delete currently playing track, cancel at each confirmation stage,
  verify playlist/lyrics cleanup, verify stats are retained.
- **Bluetooth / wired headphone resume**: auto-resume on device connect needs real-device
  validation across a broader range of headphone models, Bluetooth speakers, and car audio
  systems. Behavior depends on Android version and OEM audio-focus handling.
- **Launcher icon switching**: live icon switching via activity-alias works correctly on tested
  devices but launcher caching delays still vary. Some launchers (Nova, Action Launcher, etc.)
  may cache the old icon for minutes to hours after the switch.
- **Notification shuffle/repeat controls**: media notification action button visibility and
  behaviour is determined by Android's media session UI and the OEM notification shade — not
  directly controllable by the app. Exact appearance varies by device and Android version.
- **Backup/restore regression check**: the Backup & Restore flow (manual export, verification,
  merge restore, automatic WorkManager backup, Desktop import, quarantine behaviour) should be
  retested end to end on a real device before the next distribution; see `QA_CHECKLIST.md`.
