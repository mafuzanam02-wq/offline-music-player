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

    // ── RELEASE-02A: disclosure corrections ──────────────────────────────────────────────────────────────────────────

    private val mainDir = File("src/main/kotlin/com/launchpoint/stereosine")
    private fun mainSources() = mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun `online searches are disclosed as user initiated browser searches`() {
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Online searches you choose to start:") }
        for (statement in listOf(
            "Search lyrics online and Search artist online",
            "These run only when you select them",
            "asks Android to open a web search in your device's browser, or another app that handles web links",
            "The song title and artist name (or the artist name alone, for the artist search) are included in that search request",
            "currently Google Search",
            "may process the request under its own terms and privacy policy",
            "StereoSine does not send the request itself, does not receive the results",
            "does not automatically send your music library or your recent searches anywhere",
        )) assertTrue("missing: $statement", s.contains(statement))
        assertFalse("must not claim the app holds INTERNET or makes direct requests", Regex("INTERNET|background request|StereoSine (sends|uploads|fetches)", RegexOption.IGNORE_CASE).containsMatchIn(s))
        assertTrue("the third-party section points at the online searches", StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Third-party services:") }.contains("the online searches described above"))
    }

    @Test fun `the online search disclosure matches the implementation`() {
        val np = File(mainDir, "ui/screen/nowplaying/NowPlayingScreen.kt").readText().replace("\r\n", "\n")
        val lyrics = np.substringAfter("private fun searchLyricsOnline").substringBefore("private fun searchArtistOnline")
        val artist = np.substringAfter("private fun searchArtistOnline").substringBefore("@Composable")
        // the query is the title (with the artist when known) for lyrics, the artist alone for the artist search
        assertTrue(lyrics.contains("song.displayArtist") && lyrics.contains("song.displayTitle") && lyrics.contains("""https://www.google.com/search?q="""))
        assertTrue(artist.contains("song.displayArtist") && !artist.contains("displayTitle") && artist.contains("""https://www.google.com/search?q="""))
        // handed to Android as a VIEW intent (external browser), never fetched by the app
        for (body in listOf(lyrics, artist)) assertTrue(body.contains("Intent(Intent.ACTION_VIEW, Uri.parse(url))") && body.contains("context.startActivity(intent)"))
        // only ever started from an explicit user selection, and only from these call sites
        val callers = Regex("""(searchLyricsOnline|searchArtistOnline)\(context, song\)""").findAll(np).count()
        assertEquals("one definition-free call per menu item or lyrics button", 3, callers)
        assertEquals(1, Regex("""onSearchOnline\s*=\s*\{ searchLyricsOnline\(context, song\) \}""").findAll(np).count())
        // no other source reaches the network, and the manifest has no INTERNET
        val searchUrls = mainSources().filter { it.readText().contains("google.com/search") }.map { it.name }
        assertEquals(listOf("NowPlayingScreen.kt"), searchUrls)
        val networking = mainSources().filter { Regex("""HttpURLConnection|HttpsURLConnection|OkHttp|java\.net\.Socket|java\.net\.URL\b|openConnection|android\.webkit\.WebView""").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("direct networking appeared: $networking", networking.isEmpty())
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    @Test fun `sharing is disclosed for audio tracks and Wrapped images and videos`() {
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Share:") }
        for (statement in listOf(
            "Sharing happens only when you choose it",
            "passes a content link for that audio file to the Android share sheet and to the app you choose",
            "When you share a Wrapped image or video, StereoSine creates the file in the app's temporary storage",
            "a Wrapped image or video can show your listening statistics",
            "StereoSine does not transmit any data itself",
            "handles the shared file under its own terms and privacy policy",
        )) assertTrue("missing: $statement", s.contains(statement))
        assertFalse("sharing must not be described as automatic", Regex("automatic", RegexOption.IGNORE_CASE).containsMatchIn(s))
    }

    @Test fun `every share intent in the app is one of the disclosed user initiated shares`() {
        val senders = mainSources().filter { it.readText().contains("Intent.ACTION_SEND)") }.map { it.name }.sorted()
        assertEquals(listOf("AudioShareHelper.kt", "WrappedShareHelper.kt", "WrappedVideoShare.kt"), senders)
        for (name in senders) {
            val text = mainSources().single { it.name == name }.readText()
            assertTrue("$name goes through the system chooser", text.contains("Intent.createChooser"))
        }
        // Wrapped files are created in the app cache and exposed only through the non-exported FileProvider
        val paths = File("src/main/res/xml/file_paths.xml").readText()
        assertTrue(paths.contains("<cache-path name=\"wrapped_share\"") && paths.contains("<cache-path name=\"wrapped_video\""))
        assertEquals("only cache paths are exposed", 2, Regex("<[a-z-]+-path ").findAll(paths).count())
        assertTrue(manifest.contains("""android:exported="false"""") && manifest.contains("android.support.FILE_PROVIDER_PATHS"))
    }

    @Test fun `the Settings library summary accounts for optional scheduled backups`() {
        val s = StereoSineAbout.LIBRARY_STAYS_YOURS
        assertFalse("must not imply data leaves only by manual export", Regex("unless you manually", RegexOption.IGNORE_CASE).containsMatchIn(s))
        for (statement in listOf(
            "stored on your device, and StereoSine does not upload them",
            "saved only to a location you choose",
            "when you save one or if you turn on optional scheduled backups",
            "managed by a cloud storage app, that app may sync the file",
            "StereoSine runs no upload service",
        )) assertTrue("missing: $statement", s.contains(statement))
        // the summary is the text actually shown on the About card
        assertTrue(about.contains("body = StereoSineAbout.LIBRARY_STAYS_YOURS"))
        // it stays consistent with the policy's three-way distinction (local/user folder, provider sync, no StereoSine upload service)
        assertTrue(policy.contains("performed by the storage provider you selected, not by StereoSine"))
    }

    @Test fun `manual backup naming is explained consistently with the real controls`() {
        val backupScreen = File(mainDir, "ui/screen/settings/SettingsBackupScreen.kt").readText()
        assertTrue("the manual control is labelled Save backup file", Regex("""title\s*=\s*"Save backup file"""").containsMatchIn(backupScreen))
        assertTrue("the screen is titled Backup & Migration", backupScreen.contains("""Text("Backup & Migration")"""))
        assertTrue("and reached from a Settings row with the same title", File(mainDir, "ui/screen/settings/SettingsScreen.kt").readText().contains("""title       = "Backup & Migration""""))
        val s = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Backup and export:") }
        assertTrue(s.contains("Settings → Backup & Migration → Save backup file"))
        assertTrue(s.contains("also calls exporting your StereoSine data"))
        assertTrue(StereoSineAbout.DATA_OWNERSHIP.single { it.startsWith("Exporting your data:") }.contains("Settings → Backup & Migration"))
        assertFalse("the policy no longer names a control that does not exist", policy.contains("The Export StereoSine Data feature"))
    }

    @Test fun `the permission wording covers every permission in the merged manifest`() {
        val intermediates = File("build/intermediates/merged_manifest")
        val merged = listOf("release/processReleaseMainManifest", "debug/processDebugMainManifest")
            .map { File(intermediates, "$it/AndroidManifest.xml") }.firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("merged manifest not built yet (run :app:processReleaseMainManifest); merged-manifest check SKIPPED", merged != null)
        val permissions = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(merged!!.readText()).map { it.groupValues[1] }.toSet()
        // the app's own signature permission for non-exported dynamic receivers is internal, not user-facing
        val userFacing = permissions.filterNot { it.endsWith("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
        assertTrue("merged manifest unexpectedly empty", userFacing.isNotEmpty())
        val section = StereoSineAbout.PRIVACY_POLICY.single { it.startsWith("Android permissions:") }
        for (p in userFacing) assertTrue("the policy must name ${p.substringAfterLast('.')}", section.contains(p.substringAfterLast('.')))
        assertFalse("no INTERNET in the merged manifest", permissions.any { it.endsWith(".INTERNET") })
        // the library-added permissions are explained as library-added, not as internet access, and not as prompts
        for (p in listOf("ACCESS_NETWORK_STATE", "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED")) assertTrue("merged manifest has $p", permissions.any { it.endsWith(p) })
        assertTrue(section.contains("added automatically by the Android libraries StereoSine uses"))
        assertTrue(section.contains("They do not give StereoSine internet access"))
        assertTrue(section.contains("Not every permission listed here is shown to you as a permission prompt"))
        assertFalse("absolute 'unnecessary permissions' claim removed", section.contains("any other unnecessary permissions"))
        // the source manifest does not declare them itself (libraries merge them in), so "added automatically by the libraries" is right
        for (p in listOf("ACCESS_NETWORK_STATE", "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED")) assertFalse(declared.any { it.endsWith(p) })
    }

    @Test fun `Terms and Disclaimer are unchanged by the disclosure corrections`() {
        fun sha(list: List<String>) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(list.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(9, StereoSineAbout.TERMS_OF_USE.size)
        assertEquals(8, StereoSineAbout.DISCLAIMER.size)
        assertEquals("f137d312d592fe940c084e91f6083d5f663970287826615401b2064eec7191a8", sha(StereoSineAbout.TERMS_OF_USE))
        assertEquals("1443eae814255ebebec90ec4e38d9f5eeba8c8910461693f6e397c918f21678e", sha(StereoSineAbout.DISCLAIMER))
    }

    @Test fun `both online legal links keep their exact destinations`() {
        assertEquals("https://launchpointdigital.co.za/stereosine/privacy", StereoSineAbout.PRIVACY_POLICY_URL)
        assertEquals("https://launchpointdigital.co.za/stereosine/terms", StereoSineAbout.TERMS_URL)
        assertEquals("View Privacy Policy Online", StereoSineAbout.PRIVACY_ONLINE_LABEL)
        assertEquals("View Terms Online", StereoSineAbout.TERMS_ONLINE_LABEL)
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
