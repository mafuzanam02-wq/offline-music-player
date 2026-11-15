package com.launchpoint.stereosine.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WPP-1: the in-app Privacy Policy is the authority for the public page at launchpointdigital.co.za/stereosine/privacy. This
 * guard keeps one public URL constant, proves the in-app policy references it, pins the substantive disclosures the public
 * page must mirror, and proves the app still declares no INTERNET permission. It is source-text based; the executable
 * link behaviour (exact URLs, external VIEW intent, no-browser safety) is covered by LegalLinksTest.
 */
class PrivacyPolicyGuardTest {

    private val url = "https://launchpointdigital.co.za/stereosine/privacy"
    private val about = File("src/main/kotlin/com/launchpoint/stereosine/ui/screen/settings/SettingsAboutScreen.kt").readText()
    private val policy = about.substringAfter("val PRIVACY_POLICY = listOf(").substringBefore("val TERMS_OF_USE")

    @Test fun `one authoritative public privacy URL exists and is exact`() {
        assertTrue(about.contains("""const val PRIVACY_POLICY_URL = "$url""""))
        assertEquals("the URL literal appears exactly once in the app sources", 1, Regex(Regex.escape(url)).findAll(about).count())
        val elsewhere = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "SettingsAboutScreen.kt" }
            .filter { it.readText().contains("/stereosine/privacy") }.map { it.name }.toList()
        assertTrue("the URL must not be scattered: $elsewhere", elsewhere.isEmpty())
    }

    @Test fun `the retired wavdrop privacy URL is no longer used by any app source`() {
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("/wavdrop/privacy") }.map { it.name }.toList()
        assertTrue("retired URL still referenced: $offenders", offenders.isEmpty())
    }

    @Test fun `the in-app privacy policy references the public URL through the constant`() {
        assertTrue(policy.contains("View the public version of this Privacy Policy: \$PRIVACY_POLICY_URL"))
    }

    @Test fun `existing privacy statements remain present`() {
        for (statement in listOf(
            "offline-first music player",
            "does not require an account",
            "does not upload your music or data to any server",
            "does not sell or share your data with advertisers or third parties",
            "Play counts, skip counts, favorite status, and total listening time per track",
            "Per-play and per-skip event records with timestamps",
            "Playlists and their track order",
            "Custom lyrics you add manually inside the app",
            "None of this data is transmitted to StereoSine, LaunchPoint Digital, or any third party.",
            "READ_EXTERNAL_STORAGE on Android 8–12 or READ_MEDIA_AUDIO on Android 13+",
            "FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "does not request internet, location, contacts, camera, microphone, advertising ID",
            "StereoSine does not upload this file. You are responsible for protecting your backup because it may contain personal listening data.",
            "passes a content link for that audio file to the Android share sheet",
            "the audio file is permanently removed from your device",
            "reads a .bpstat file from your device",
            "does not use advertising SDKs, analytics services, crash-reporting tools, or third-party tracking",
            "StereoSine itself makes no network requests.",
            "Questions about this policy: info@launchpointdigital.co.za",
        )) assertTrue("missing from the in-app policy: $statement", policy.contains(statement))
        assertFalse("backups are not encrypted; the policy must not claim it",
            Regex("""(is|are|be|been|get|gets) encrypted|encrypted (backup|file)s?""", RegexOption.IGNORE_CASE).containsMatchIn(policy))
    }

    @Test fun `What We Store no longer says backups are created only through Export`() {
        val s = StereoSineAbout.WHAT_WE_STORE.single { it.startsWith("Backup and export files:") }
        assertFalse(s.contains("only created", ignoreCase = true))
        assertFalse(s.contains("only when you explicitly use the Export", ignoreCase = true))
        assertTrue(s.contains("when you save a backup yourself"))
        assertTrue(s.contains("optional automatic backup (off by default)"))
        assertTrue(s.contains("saved to a location you choose"))
        assertTrue(s.contains("StereoSine does not upload them"))
    }

    @Test fun `recent searches are disclosed accurately`() {
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Recent searches:") }
        for (statement in listOf(
            "saves searches you complete on your device so you can use them again",
            "press Search on the keyboard, open a search result, or tap a recent search",
            "text is not saved while you type",
            "up to 30 different recent searches",
            "each for up to 90 days after you last used it",
            "You can remove individual searches or clear your whole recent search history from the search screen",
            "stays on your device, is not uploaded by StereoSine, and is not included in StereoSine backup files",
            "removed if you clear StereoSine's app data or uninstall the app",
        )) assertTrue("missing: $statement", s.contains(statement))
        assertTrue("the local-data list mentions recent searches", StereoSineAbout.PRIVACY_POLICY[1].contains("Recent searches you complete in the app"))
    }

    @Test fun `the 90 day retention is not promised as instant or continuous deletion`() {
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Recent searches:") }
        assertTrue(s.contains("Expired searches stop appearing when you next open search or when the recent-search list updates."))
        assertTrue(s.contains("Expired entries are removed from local storage when the history is next changed."))
        assertFalse(Regex("(automatically|immediately|instantly|exactly|continuously)[^.]*(deleted|removed|cleared)", RegexOption.IGNORE_CASE).containsMatchIn(s))
    }

    @Test fun `the recent searches disclosure matches the implemented limits`() {
        val rules = File("src/main/kotlin/com/launchpoint/stereosine/data/search/SearchHistoryRules.kt").readText()
        assertTrue(rules.contains("const val MAX_ENTRIES = 30"))
        assertTrue(rules.contains("const val RETENTION_DAYS = 90"))
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Recent searches:") }
        assertFalse("must not claim the history is uploaded or synced", Regex("(is|are|will be) (uploaded|sent|shared|synced) to", RegexOption.IGNORE_CASE).containsMatchIn(s))
        // the claim "not included in backup files" is true only while the backup code never reads the history store
        val backup = File("src/main/kotlin/com/launchpoint/stereosine/data/backup").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("SearchHistory") }.map { it.name }.toList()
        assertTrue("backup code must not touch search history: $backup", backup.isEmpty())
    }

    @Test fun `backup files are described as unencrypted`() {
        assertTrue(policy.contains("StereoSine does not encrypt backup files, so anyone who can open the file can read its contents."))
    }

    @Test fun `automatic backup disclosures are present and accurate`() {
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Automatic backup:") }
        for (statement in listOf(
            "Automatic backup is optional and is off unless you turn it on.",
            "daily, weekly or monthly interval and select a backup folder",
            "You can also use Back up now to save one immediately.",
            "best-effort basis through Android's background scheduler",
            "a backup may run later than the interval you chose or not run",
            "device storage is low or the selected folder is no longer available",
            "saved only to the folder you select. StereoSine does not send them anywhere else.",
            "file named with that day's date or replaces the previous StereoSine backup file",
        )) assertTrue("missing: $statement", s.contains(statement))
    }

    @Test fun `cloud backed destination disclosure is present`() {
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Cloud-backed backup destinations:") }
        assertTrue(s.contains("cloud storage app or another storage provider, that provider may synchronize or upload the backup file under its own terms and privacy policy"))
        assertTrue(s.contains("performed by the storage provider you selected, not by StereoSine"))
        assertTrue(s.contains("not through any service operated by StereoSine or LaunchPoint Digital"))
    }

    @Test fun `safety copy disclosure matches Recovery restore behaviour`() {
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Safety copy before Recovery restore:") }
        for (statement in listOf(
            "using Recovery mode",
            "verified safety copy of your current StereoSine data in the app's private storage on your device",
            "Recovery does not start if that copy cannot be verified",
            "Only the latest safety copy is kept; a newer one replaces it",
            "stays on your device and is not uploaded",
        )) assertTrue("missing: $statement", s.contains(statement))
    }

    @Test fun `earlier Wavdrop backup compatibility is disclosed`() {
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Earlier Wavdrop backups:") }
        assertTrue(s.contains("StereoSine can still read backup files created by earlier Wavdrop versions"))
        assertTrue(s.contains("reads the file you select on your device and sends nothing anywhere"))
        assertTrue(StereoSineAbout.PRIVACY_POLICY.any { it.startsWith("BlackPlayer import:") })
    }

    @Test fun `disclosures are backed by the real behaviour they describe`() {
        val main = File("src/main/kotlin/com/launchpoint/stereosine")
        // optional and off by default
        assertTrue(File(main, "data/settings/AppSettingsRepository.kt").readText().contains("?: AutoBackupInterval.OFF"))
        // best effort: WorkManager periodic work, storage-not-low constraint, no network constraint
        val scheduler = File(main, "data/backup/AutoBackupWorkScheduler.kt").readText()
        assertTrue(scheduler.contains("PeriodicWorkRequestBuilder") && scheduler.contains("setRequiresStorageNotLow(true)"))
        // the safety copy lives in app-private storage and is only taken for Recovery
        val snapshot = File(main, "data/backup/RecoverySafetySnapshot.kt").readText()
        assertTrue(snapshot.contains("recovery-safety") && snapshot.contains("app-private"))
        assertTrue(File(main, "data/backup/BackupRestoreMode.kt").readText().contains("can only run after a VERIFIED pre-restore safety snapshot"))
        // no encryption anywhere in the backup code, and Android platform auto-backup is off
        val encryption = File(main, "data/backup").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("Cipher|SecretKey|AES|encrypt", RegexOption.IGNORE_CASE).containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue("backup code unexpectedly mentions encryption: $encryption", encryption.isEmpty())
        assertTrue(manifest.contains("""android:allowBackup="false""""))
    }

    /**
     * Cross-repository consistency with the published website policy. The website repository is optional in ordinary
     * development environments: when it is absent this test is SKIPPED (visible in the report), never passed. Override the
     * location with -Dstereosine.websitePolicy=<path to stereosinePrivacyPolicy.js>. It pins the shared backup, cloud,
     * safety-copy and legacy disclosures; it does not prove the two documents are otherwise identical.
     */
    @Test fun `shared disclosures are also present in the published website policy source`() {
        val candidates = listOfNotNull(
            System.getProperty("stereosine.websitePolicy"),
            "../../launchpoint-digital/src/stereosinePrivacyPolicy.js",
        ).map { File(it) }
        val site = candidates.firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("website policy source not available (looked in $candidates); cross-repo check SKIPPED", site != null)
        val web = site!!.readText().replace("\\\"", "\"").replace(Regex("""\s+"""), " ")
        val shared = listOf(
            "Automatic backup is optional and is off unless you turn it on.",
            "daily, weekly or monthly interval and select a backup folder",
            "You can also use Back up now to save one immediately.",
            "Automatic backups are scheduled on a best-effort basis through Android's background scheduler.",
            "a backup may run later than the interval you chose or not run, for example when device storage is low or the selected folder is no longer available.",
            "Automatic backups are saved only to the folder you select. StereoSine does not send them anywhere else.",
            "that provider may synchronize or upload the backup file under its own terms and privacy policy.",
            "That transfer is performed by the storage provider you selected, not by StereoSine, and not through any service operated by StereoSine or LaunchPoint Digital.",
            "StereoSine does not encrypt backup files, so anyone who can open the file can read its contents.",
            "This copy stays on your device and is not uploaded.",
        )
        val app = StereoSineAbout.PRIVACY_POLICY.joinToString(" ").replace(Regex("""\s+"""), " ")
        for (sentence in shared) {
            assertTrue("missing from the website source: $sentence", web.contains(sentence))
            assertTrue("missing from the in-app policy: $sentence", app.contains(sentence))
        }
    }

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()

    @Test fun `the app still declares no INTERNET permission`() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    @Test fun `the Bluetooth disclosure is tied to the permissions the manifest declares`() {
        assertTrue("manifest declares BLUETOOTH", "android.permission.BLUETOOTH" in declared)
        assertTrue("manifest declares BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_CONNECT" in declared)
        assertTrue("BLUETOOTH is limited to Android 11 and earlier", Regex("""android:name="android.permission.BLUETOOTH"\s+android:maxSdkVersion="30"""").containsMatchIn(manifest))
        assertFalse("no scan permission", declared.any { it.contains("BLUETOOTH_SCAN") })
        assertFalse("no advertise/admin permission", declared.any { it.contains("BLUETOOTH_ADVERTISE") || it.contains("BLUETOOTH_ADMIN") })
        // every declared Bluetooth permission is named in the policy
        for (permission in declared.filter { it.startsWith("android.permission.BLUETOOTH") }) {
            assertTrue("policy must name ${permission.substringAfterLast('.')}", policy.contains(permission.substringAfterLast('.') + " on Android") || policy.contains(" or " + permission.substringAfterLast('.') + " on Android"))
        }
        assertTrue(policy.contains("BLUETOOTH on Android 11 and earlier or BLUETOOTH_CONNECT on Android 12+"))
        assertTrue("purpose: audio connection-state handling and reconnect resume", policy.contains("Bluetooth audio connection-state handling and optional playback resume when an audio device reconnects"))
        assertTrue("no scan/discovery", policy.contains("StereoSine does not scan for or discover nearby Bluetooth devices."))
        assertFalse("the policy must not imply pairing or tracking", Regex("pair|bluetooth identifier|bluetooth location", RegexOption.IGNORE_CASE).containsMatchIn(policy))
        assertTrue("storage/foreground disclosures remain and no-internet statement is kept",
            policy.contains("FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK") &&
                policy.contains("does not request internet, location, contacts, camera, microphone, advertising ID"))
    }
}
