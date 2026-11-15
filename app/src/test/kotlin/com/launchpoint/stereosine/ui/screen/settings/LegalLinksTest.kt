package com.launchpoint.stereosine.ui.screen.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * BRAND-09A: the public legal links (Privacy Policy and Terms) and the supplementary "View Terms Online" action.
 * Executable behaviour is tested through [openExternalLink] and the internal [StereoSineAbout] constants; the dialog's
 * Compose behaviour is covered by LegalInfoDialogInstrumentedTest (androidTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegalLinksTest {

    /** Records the intents the app asks Android to start, optionally refusing them like a device with no browser. */
    private class RecordingContext(base: Context, private val failWith: RuntimeException? = null) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            started += intent
            failWith?.let { throw it }
        }
    }

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Test fun `the approved Privacy Policy URL is exact`() {
        assertEquals("https://launchpointdigital.co.za/stereosine/privacy", StereoSineAbout.PRIVACY_POLICY_URL)
    }

    @Test fun `the approved Terms URL is exact`() {
        assertEquals("https://launchpointdigital.co.za/stereosine/terms", StereoSineAbout.TERMS_URL)
    }

    @Test fun `the in-app privacy policy still points readers at the public page through the constant`() {
        assertEquals(
            "Public policy:\nView the public version of this Privacy Policy: https://launchpointdigital.co.za/stereosine/privacy",
            StereoSineAbout.PRIVACY_POLICY.last(),
        )
    }

    @Test fun `the offline Terms keep exactly the nine approved in-app paragraphs`() {
        assertEquals(PINNED_TERMS, StereoSineAbout.TERMS_OF_USE)
    }

    @Test fun `the online Privacy Policy action label is exactly View Privacy Policy Online`() {
        assertEquals("View Privacy Policy Online", StereoSineAbout.PRIVACY_ONLINE_LABEL)
    }

    @Test fun `opening the Privacy Policy link starts one external ACTION_VIEW for the privacy URL`() {
        val ctx = RecordingContext(app)
        assertTrue(ctx.openExternalLink(StereoSineAbout.PRIVACY_POLICY_URL))
        assertEquals(1, ctx.started.size)
        val intent = ctx.started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://launchpointdigital.co.za/stereosine/privacy", intent.data.toString())
    }

    @Test fun `no browser or a refused launch for the Privacy Policy link is handled without a crash`() {
        val missing = RecordingContext(app, ActivityNotFoundException("no browser"))
        assertFalse(missing.openExternalLink(StereoSineAbout.PRIVACY_POLICY_URL))
        assertEquals(1, missing.started.size)
        assertFalse(RecordingContext(app, SecurityException("blocked")).openExternalLink(StereoSineAbout.PRIVACY_POLICY_URL))
    }

    @Test fun `the Privacy Policy link is requested from exactly one UI control using the single URL constant`() {
        val about = File("src/main/kotlin/com/launchpoint/stereosine/ui/screen/settings/SettingsAboutScreen.kt").readText()
        assertEquals(1, Regex("""openExternalLink\(StereoSineAbout\.PRIVACY_POLICY_URL\)""").findAll(about).count())
        assertEquals(1, Regex("""onlineLabel\s*=\s*StereoSineAbout\.PRIVACY_ONLINE_LABEL""").findAll(about).count())
        assertEquals("the privacy URL literal exists once", 1, Regex(Regex.escape("https://launchpointdigital.co.za/stereosine/privacy")).findAll(about).count())
    }

    @Test fun `the online Terms action label is exactly View Terms Online`() {
        assertEquals("View Terms Online", StereoSineAbout.TERMS_ONLINE_LABEL)
    }

    @Test fun `opening the Terms link starts one external ACTION_VIEW for the Terms URL`() {
        val ctx = RecordingContext(app)
        assertTrue(ctx.openExternalLink(StereoSineAbout.TERMS_URL))
        assertEquals(1, ctx.started.size)
        val intent = ctx.started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://launchpointdigital.co.za/stereosine/terms", intent.data.toString())
    }

    @Test fun `the existing website link still opens the unchanged website URL`() {
        val ctx = RecordingContext(app)
        assertTrue(ctx.openExternalLink(StereoSineAbout.WEBSITE_URL))
        assertEquals("https://launchpointdigital.co.za", ctx.started.single().data.toString())
    }

    @Test fun `no browser available is handled without a crash`() {
        val ctx = RecordingContext(app, ActivityNotFoundException("no browser"))
        assertFalse(ctx.openExternalLink(StereoSineAbout.TERMS_URL))
        assertEquals("the launch was attempted exactly once", 1, ctx.started.size)
    }

    @Test fun `a refused launch of any kind is handled without a crash`() {
        assertFalse(RecordingContext(app, SecurityException("blocked")).openExternalLink(StereoSineAbout.TERMS_URL))
    }

    @Test fun `the app still declares no INTERNET permission`() {
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("android.permission.INTERNET"))
    }

    @Test fun `no embedded WebView exists in app sources or layouts`() {
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { Regex("""android\.webkit\.WebView|\bWebView\s*\(|<WebView""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("WebView found in: $offenders", offenders.isEmpty())
    }

    @Test fun `the Terms link is requested from exactly one UI control`() {
        val about = File("src/main/kotlin/com/launchpoint/stereosine/ui/screen/settings/SettingsAboutScreen.kt").readText()
        assertEquals(1, Regex("""openExternalLink\(StereoSineAbout\.TERMS_URL\)""").findAll(about).count())
        assertEquals(1, Regex("""onlineLabel\s*=\s*StereoSineAbout\.TERMS_ONLINE_LABEL""").findAll(about).count())
    }

    @Test fun `legacy compatibility identifiers and the application identity are unchanged`() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("""applicationId = "com.launchpoint.stereosine""""))
        assertTrue(gradle.contains("""namespace = "com.launchpoint.stereosine""""))
        assertTrue(gradle.contains("versionCode = 10"))
        assertTrue(gradle.contains("""versionName = "0.1.0-beta10""""))
        // legacy Wavdrop identifier that must keep working (WorkManager unique-work name)
        assertTrue(File("src/main/kotlin/com/launchpoint/stereosine/data/backup/AutoBackupWorkScheduler.kt").readText()
            .contains("wavdrop_auto_backup_due_check"))
    }

    private companion object {
        val PINNED_TERMS = listOf(
            "By using StereoSine, you agree to the following terms. If you do not agree, please uninstall the app.",
            "Use of the app:\nStereoSine is designed for managing and playing audio files stored on your device.",
            "Your music files and rights:\nYou are responsible for ensuring you have the right to play, back up, and share any audio files you use with StereoSine. StereoSine does not verify music ownership or licensing.",
            "Delete from device:\nDelete from device permanently removes audio files from your device. This action cannot be undone. Android does not provide a recycle bin for shared media storage. Use this feature with care.",
            "Backup files:\nBackup files you export may contain your listening statistics, playlist names, custom lyrics, app preferences, and other personal library data in an unencrypted file. Store them securely and do not share them with people you do not trust.",
            "Sharing:\nWhen you share a track, you are responsible for what you share and with whom. StereoSine opens the Android share sheet; it is not responsible for how the receiving app handles the shared file.",
            "Format support:\nAudio format support depends partly on your Android device and its codecs. StereoSine does not guarantee playback of all audio file types on all devices.",
            "BlackPlayer import:\nOnly import .bpstat files you exported from your own BlackPlayer installation. Do not attempt to import files you did not personally create.",
            "Changes to these terms:\nThese terms may be updated as new features are added. Continued use of StereoSine after an update means you accept the revised terms.",
        )
    }
}
