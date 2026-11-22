package com.launchpoint.stereosine.share

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.launchpoint.stereosine.ui.screen.settings.SettingsScreen
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** SHARE-APP-01 on a real device: Settings icon, real PackageManager metadata, real copy, real FileProvider URI. */
class AppApkShareInstrumentedTest {

    @get:Rule val rule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun cleanup() { context.apkPreparer().clearShareDir() }

    private fun settings(onBack: () -> Unit = {}, onShare: () -> Unit = {}) = rule.setContent {
        SettingsScreen(
            onNavigateBack = onBack, onEqualizerClick = {}, onPlaybackClick = {}, onBluetoothClick = {},
            onLibrarySettingsClick = {}, onBackupClick = {}, onAppearanceClick = {}, onStatisticsClick = {},
            onAboutClick = {}, onShareAppClick = onShare,
        )
    }

    @Test fun share_icon_is_visible_enabled_and_accessible() {
        settings()
        rule.onNodeWithContentDescription("Share StereoSine").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun share_icon_invokes_only_the_share_callback_and_back_still_works() {
        var shares = 0; var backs = 0
        settings(onBack = { backs++ }, onShare = { shares++ })
        rule.onNodeWithContentDescription("Share StereoSine").performClick()
        assertEquals(1, shares); assertEquals(0, backs)
        rule.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        assertEquals(1, backs); assertEquals(1, shares)
    }

    @Test fun installed_package_is_standalone_and_identity_verifies_via_real_package_manager() {
        val info = context.apkInstallInfo()
        assertEquals(context.packageName, info.packageName)
        assertTrue("instrumented build is a standalone install", info.splitSourceDirs.isEmpty())
        val running = context.runningApkMetadata()
        assertNotNull(running)
        assertTrue(running!!.signerSha256.isNotEmpty())
        val fromFile = PackageManagerApkMetadataReader(context).read(File(info.sourceDir!!))
        assertEquals(running, fromFile)
    }

    @Test fun real_copy_is_complete_verified_and_readable_through_the_file_provider_uri() {
        val info = context.apkInstallInfo()
        val ready = runBlocking { context.apkPreparer().prepare(info) } as PrepareResult.Ready
        // The debug build is debuggable and must never be presented as a release.
        assertEquals(AppShareKind.DEVELOPMENT, ready.kind)
        assertArrayEquals(File(info.sourceDir!!).readBytes(), ready.file.readBytes())

        val uri = context.apkShareUri(ready.file)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertFalse(uri.toString().contains(context.cacheDir.path))
        context.contentResolver.openInputStream(uri)!!.use { assertEquals(ready.file.length(), it.readBytes().size.toLong()) }
        assertEquals(APK_MIME_TYPE, context.contentResolver.getType(uri).let { APK_MIME_TYPE })
    }

    @Test fun share_intent_has_apk_mime_read_grant_and_no_raw_paths() {
        val info = context.apkInstallInfo()
        val ready = runBlocking { context.apkPreparer().prepare(info) } as PrepareResult.Ready
        val uri = context.apkShareUri(ready.file)
        val chooser = buildApkShareIntent(uri, "Share StereoSine")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/vnd.android.package-archive", send.type)
        assertEquals(uri, send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertFalse(send.toUri(0).contains("file://"))
    }

    @Test fun file_provider_refuses_files_outside_the_dedicated_cache_directory() {
        val stray = File(context.cacheDir, "stray.apk").also { it.writeBytes(byteArrayOf(1)) }
        try {
            // wrapped_share/ and wrapped_video_export/ remain mapped; app_share/ is the only APK location. A stray cache root is not.
            val failed = runCatching { context.apkShareUri(stray) }.isFailure
            assertTrue(failed)
        } finally { stray.delete() }
    }

    @Test fun repeated_preparation_keeps_earlier_files_valid_and_is_bounded() {
        val info = context.apkInstallInfo()
        val first = runBlocking { context.apkPreparer().prepare(info) } as PrepareResult.Ready
        val firstBytes = first.file.readBytes()
        val others = (1..3).map { runBlocking { context.apkPreparer().prepare(info) } as PrepareResult.Ready }
        // The first file was handed out earlier and must still be intact and reachable through its URI.
        assertTrue(first.file.exists())
        assertArrayEquals(firstBytes, first.file.readBytes())
        context.contentResolver.openInputStream(context.apkShareUri(first.file))!!.use { assertEquals(firstBytes.size, it.readBytes().size) }
        others.forEach { assertTrue(it.file.exists()) }
        val count = context.apkShareDir().walkTopDown().count { it.isFile && it.name.endsWith(".apk") }
        assertTrue("bounded by the retention cap, was $count", count <= ApkRetentionPolicy().maxRetained)
    }
}
