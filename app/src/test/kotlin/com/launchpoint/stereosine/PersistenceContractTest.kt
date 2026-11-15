package com.launchpoint.stereosine

import com.launchpoint.stereosine.data.backup.AutoBackupWorkScheduler
import com.launchpoint.stereosine.data.legacy.ImportSourceTypes
import com.launchpoint.stereosine.data.local.DATABASE_FILE_NAME
import com.launchpoint.stereosine.data.local.entity.TrackListenEventEntity
import com.launchpoint.stereosine.data.settings.EqPresetType
import com.launchpoint.stereosine.playback.ALBUM_ID_EXTRA
import com.launchpoint.stereosine.playback.StereoSineLibraryIds
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BRAND-02: Kotlin symbols were rebranded, but every persisted / serialized / externally observable identifier that existing
 * data depends on must still carry its original literal. These pins fail loudly if one is "tidied" for cosmetic consistency.
 */
class PersistenceContractTest {

    private val moduleDir: File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }
    private fun main(path: String) = File(moduleDir, "src/main/kotlin/com/launchpoint/stereosine/$path").readText()

    @Test fun `database and work identities keep their original literals`() {
        assertEquals("wavdrop.db", DATABASE_FILE_NAME)
        assertEquals("wavdrop_auto_backup_due_check", AutoBackupWorkScheduler.UNIQUE_WORK_NAME)
    }

    @Test fun `DataStore names and preference keys are unchanged`() {
        assertTrue(main("di/AppModule.kt").contains("name = \"wavdrop_preferences\""))
        assertTrue(main("ui/widget/WidgetAppearanceStore.kt").contains("name = \"wavdrop_widget_appearance\""))
        assertTrue(main("ui/widget/WidgetStateStore.kt").contains("name = \"wavdrop_widget_state\""))
        assertTrue(main("data/settings/AudioEnhancementsRepository.kt").contains("stringPreferencesKey(\"eq_wavdrop_preset_id\")"))
    }

    @Test fun `persisted enum entry and stored source identifiers are unchanged`() {
        assertEquals(listOf("FLAT", "WAVDROP_PRESET", "PLATFORM", "CUSTOM"), EqPresetType.entries.map { it.name })
        assertEquals("wavdrop_playback", TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK)
        assertEquals("wavdrop_desktop_playback", TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK)
        assertEquals("wavdrop_json", ImportSourceTypes.WAVDROP_JSON)
    }

    @Test fun `MediaLibrary browse ids and extras keep their literals`() {
        assertEquals("wavdrop_root", StereoSineLibraryIds.ROOT)
        assertEquals("wavdrop_recent", StereoSineLibraryIds.RECENT)
        assertEquals("wavdrop_songs", StereoSineLibraryIds.SONGS)
        assertEquals("wavdrop_recent_root", StereoSineLibraryIds.RECENT_ROOT)
        assertEquals("wavdrop_album_id", ALBUM_ID_EXTRA)
    }

    @Test fun `Room schema JSON content is byte-identical to the pre-rename export`() {
        val dir = File(moduleDir, "schemas/com.launchpoint.stereosine.data.local.StereoSineDatabase")
        val expected = mapOf(
            1 to "9d9855dbabc77b956f17939992db0cd5323d372bca455c3e575a09ed12b91600",
            2 to "736504c2c4ecd05bc4663827648aeab933c604966e54ec0bc59d73b35be41c63",
            3 to "7f2351a313c588177763b122fd0d9703e748bf8ec5b951a0cf8de38cedc6ecb9",
            4 to "a95e3e58a6df4236d3acccb1cd4c75a58b854cf0c9e1f80295b4cd19aae1736e",
            5 to "1f01a47d1041210c6b1e48206b9f743951637e2c8719ba18c68e11a45867ce18",
            6 to "4ad1b3103dbaca99145205e30b8bddda221c3e3dfc918114f93a5f6f5a8c56d3",
            7 to "c107b9a41aa00cb6f1bc13482cc14b75054f0d12e05a988a3bba5f42b18f63c8",
            8 to "b15c39c7f92078c0404526f9c36a87a9dd08887227dbaa32015f76bf03ce02a5",
            9 to "9e22423c7f1decf9d1218499cdc298366d795c110447954163274e11a14a41aa",
            10 to "a11ef4698b757a5f8fe01c858068f5bd86dfba2019660896b1c9c1e9de854c42",
            11 to "3c0ca569055cc606b4b0ce9343c8db737578d540248dc2f3d0a8651f8cd08cae",
            12 to "93fd20187dcbc45de2917839064122e9b252504cc0a58e91135743fc24c952db",
            13 to "af65489693b3f7ec501e720d8f502d48aba30565a0fc33e81adc87ab56415e1b",
        )
        assertEquals(expected.keys.map { "$it.json" }.toSet(), dir.listFiles()!!.map { it.name }.toSet())
        expected.forEach { (version, sha) ->
            // Normalise line endings so a CRLF checkout cannot produce a false failure.
            val bytes = File(dir, "$version.json").readText().replace("\r\n", "\n").toByteArray()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("schema $version.json changed", sha, actual)
        }
    }

    @Test fun `widget provider component and info resource are consistent`() {
        val manifest = File(moduleDir, "src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".ui.widget.StereoSineWidgetProvider\""))
        assertTrue(manifest.contains("@xml/stereosine_widget_info"))
        assertTrue(File(moduleDir, "src/main/kotlin/com/launchpoint/stereosine/ui/widget/StereoSineWidgetProvider.kt").exists())
        assertFalse(manifest.contains("WavdropWidgetProvider"))
    }

    @Test fun `debug-only validation flag is renamed, release stays hard-coded off, legacy alias kept`() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertTrue(gradle.contains("providers.gradleProperty(\"stereosineValidationBounded\")"))
        assertTrue("legacy -PwavdropValidationBounded alias must stay accepted", gradle.contains("providers.gradleProperty(\"wavdropValidationBounded\")"))
        assertTrue(gradle.contains("\"STEREOSINE_VALIDATION_BOUNDED\", \"false\""))
        assertTrue(gradle.substringAfter("        release {").substringBefore("proguardFiles").contains("\"STEREOSINE_VALIDATION_BOUNDED\", \"false\""))
    }

    @Test fun `signing environment variables are STEREOSINE primary with the legacy WAVDROP fallback kept`() {
        val script = File(moduleDir.absoluteFile.normalize().parentFile, "gradle/release-signing.gradle.kts").readText()
        assertTrue(script.contains("envSource(\"STEREOSINE_UPLOAD_* environment variables\", \"STEREOSINE_UPLOAD\")"))
        assertTrue("legacy WAVDROP_UPLOAD_* names must stay accepted", script.contains("\"WAVDROP_UPLOAD\")"))
        listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").forEach {
            assertTrue(it, script.contains("\"$it\""))
        }
    }
}
