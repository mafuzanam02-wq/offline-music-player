# StereoSine Release Signing

## Status

| Item | State |
|------|-------|
| Signing wiring in the build | **Complete.** One signing identity from one source; none supplied → unsigned release. |
| StereoSine upload key | **Created and locally verified** (2026-11-14). Certificate SHA-256 below. The private key and its password are held by the owner outside the repository. |
| Encrypted backups of the key | Two encrypted copies exist off-repository and were restore-verified against the original keystore hash and certificate fingerprint. Their location is intentionally not recorded here. |
| Signed release artifact | A release APK signed with the upload key has been **built and verified locally** (`apksigner verify`, certificate fingerprint matches). It has **not been published, uploaded or distributed**. |
| Google Play App Signing enrollment | **Not confirmed.** Play Console enrollment and the first upload are not recorded in the repository. |
| StereoSine release | **None.** No StereoSine-branded release has been published. |

Upload-key public certificate (`CN=StereoSine, O=LaunchPoint Digital`, RSA 4096, valid to 2054-04-01):

```
SHA-256: 68:80:E5:57:66:B3:13:5D:55:51:50:86:C8:70:4D:B0:6C:84:0C:5C:83:5F:20:91:F8:E0:9B:62:1D:77:58:22
```

> The original Wavdrop release key was lost and is not recovered or used anywhere. The StereoSine upload key is a new
> identity; Android treats StereoSine (`com.launchpoint.stereosine`) as a different app from legacy Wavdrop.

## Intended architecture

- **Google Play App Signing owns the final app-signing key.** Google holds and manages the key that end-user devices
  verify. (Enrollment is still to be done and confirmed.)
- **Developer/CI holds a separate _upload_ key.** We sign uploads with the upload key; Play re-signs with the app key on
  distribution. If the upload key is ever lost it can be reset via Play Console without breaking installed users.
- **Distribution channel:** Google Play **internal testing → closed testing** for Beta (see
  `PLAY_STORE_READINESS_CHECKLIST.md` section 8). Play Console prefers an **AAB** (`bundleRelease`) over an APK for
  upload. Direct debug-signed APK sideloading is legacy and should not be used for the Play track.

## How signing is resolved

The rules live in [`gradle/release-signing.gradle.kts`](../gradle/release-signing.gradle.kts), applied by
`app/build.gradle.kts`. A signing identity is **one complete set of four values from one source**. Sources, in order of
precedence:

| # | Source | storeFile | storePassword | keyAlias | keyPassword |
|---|--------|-----------|---------------|----------|-------------|
| 1 | `keystore.properties` at the repo root (gitignored) | `storeFile` | `storePassword` | `keyAlias` | `keyPassword` |
| 2 | `STEREOSINE_UPLOAD_*` environment variables (CI encrypted secrets) | `STEREOSINE_UPLOAD_STORE_FILE` | `STEREOSINE_UPLOAD_STORE_PASSWORD` | `STEREOSINE_UPLOAD_KEY_ALIAS` | `STEREOSINE_UPLOAD_KEY_PASSWORD` |
| 3 | `WAVDROP_UPLOAD_*` environment variables (**legacy compatibility fallback**) | `WAVDROP_UPLOAD_STORE_FILE` | `WAVDROP_UPLOAD_STORE_PASSWORD` | `WAVDROP_UPLOAD_KEY_ALIAS` | `WAVDROP_UPLOAD_KEY_PASSWORD` |

The `WAVDROP_UPLOAD_*` names are retained temporarily so existing CI or owner setups keep working; new setups should use
`STEREOSINE_UPLOAD_*`. A later slice may remove the legacy names.

Values are **never merged across sources**. (Before BRAND-05B each value was looked up independently as
`file ?: environment`, so a keystore path from `keystore.properties` could silently be paired with a password from an
environment variable.) Rules, evaluated at Gradle configuration time:

| Situation | Result |
|-----------|--------|
| No source supplies any value | Release build stays **unsigned** (`app-release-unsigned.apk`). Debug builds and unit tests are unaffected. |
| Exactly one source is configured and complete | Signed release using that source. |
| A source supplies one to three of its four values | **Configuration fails**: `Incomplete release signing configuration in <source>: missing <names>`. This applies even if another source is complete. |
| Several sources are complete but differ in any value | **Configuration fails**: `Conflicting release signing configuration … differ in: <names>`. |
| Several sources are complete and identical | Accepted; the highest-precedence source is used. |
| Keystore file missing, and a release signing task is scheduled | **Build fails**: `Release keystore file not found: '<file name>'`. Not raised for debug builds or unit tests. |

Error messages name keys, variables and sources only. They never echo passwords, aliases or full paths (only the
keystore file **name**). Passwords are not validated at configuration time; a wrong password surfaces in the signing task.
There is **no fallback to the debug key** for release in any state.

> **Design note — conflicts are rejected rather than resolved by precedence.** If `keystore.properties` and an environment
> variable set both define complete but different identities, the build fails instead of silently preferring the file.
> A stale environment variable on a developer machine therefore cannot go unnoticed, and a release is never signed with
> a key the operator did not expect. Precedence decides the reported source only when the sources agree. If you prefer
> "higher source silently wins", that is a one-line policy change and needs an explicit decision.

Optional override: `-Pstereosine.keystorePropertiesFile=<path>` points the build at a different properties file (or at a
non-existent path to simulate "no `keystore.properties`"). It is for CI and verification runs; it is not needed normally.

Template: [`keystore.properties.example`](../keystore.properties.example) (placeholders only).

## Secret hygiene

`.gitignore` blocks `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, `*.pepk`. Never place passwords in Gradle
files, source, docs, logs or shell history. Keep the keystore backed up securely and off Git, with the keystore password
and the backup passphrase retained separately. Until Play App Signing is enrolled, losing the upload key means no
future updates under it.

## Setup (outside the repository; needs explicit authorization)

1. Generate an upload keystore (`keytool -genkeypair … -keyalg RSA -keysize 4096`) and store it securely outside the
   repo. *(Done for StereoSine; do not regenerate.)*
2. Copy `keystore.properties.example` → `keystore.properties` and fill in real values, or supply the four
   `STEREOSINE_UPLOAD_*` variables.
3. Build the upload artifact: `./gradlew.bat bundleRelease` (AAB) or `assembleRelease` (APK).
4. Create the app in Play Console, **enroll in Play App Signing**, and upload to the internal testing track. *(Not done.)*
5. For CI: store the keystore and the four `STEREOSINE_UPLOAD_*` values as encrypted secrets.

## Verification

```bash
# No credentials supplied (unsigned output):
apksigner verify --verbose app/build/outputs/apk/release/app-release-unsigned.apk
#   -> DOES NOT VERIFY / Missing META-INF/MANIFEST.MF

# Credentials supplied (signed with the upload key); compare the signer certificate SHA-256 with the value above:
apksigner verify --verbose --print-certs --min-sdk-version 26 app/build/outputs/apk/release/app-release.apk
```

AGP signs with APK Signature Scheme v2 for this configuration (minSdk 26, so v1 is not required).

Automated coverage: `ReleaseSigningConfigurationTest` runs the real `release-signing.gradle.kts` in throw-away fixture
builds with synthetic credentials (none, local file, new env, legacy env, partial, mixed, conflicting, missing keystore,
release-only validation, debug without credentials, no debug-key fallback).

## versionCode / package notes

- Current values in `app/build.gradle.kts`: `versionCode = 10`, `versionName = "0.1.0-beta10"`,
  `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`. Each Play upload needs a unique, monotonically increasing
  `versionCode` — bump before every new upload.
- `applicationId = com.launchpoint.stereosine` (debug builds add `.debug`) — this is **permanent** once first
  uploaded to Play and can never change for this app listing. The pre-rebrand `com.launchpoint.wavdrop` is not
  carried forward: Android treats StereoSine as a different app, so it installs beside legacy Wavdrop builds and
  starts with empty data (use backup/restore to move data).
