package com.launchpoint.stereosine

import com.launchpoint.stereosine.data.backup.WavdropBackupParser
import com.launchpoint.stereosine.data.backup.wdbk.WdbkContainerVersion
import com.launchpoint.stereosine.data.local.DATABASE_FILE_NAME
import com.launchpoint.stereosine.data.settings.AppIconChoice
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BRAND-01: the StereoSine identity is the only active package identity, every manifest component
 * resolves to a real class, and the legacy-data identifiers that existing users' files depend on are
 * deliberately unchanged.
 */
class BrandIdentityTest {

    private val moduleDir: File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }
    private val mainKotlin = File(moduleDir, "src/main/kotlin/com/launchpoint/stereosine")

    private fun manifestNodes(tag: String): List<org.w3c.dom.Node> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(moduleDir, "src/main/AndroidManifest.xml"))
        val list = doc.getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) }
    }

    private fun attr(node: org.w3c.dom.Node, name: String) = node.attributes.getNamedItem(name)?.nodeValue

    @Test fun `every manifest component class exists under the StereoSine package`() {
        val tags = listOf("application", "activity", "activity-alias", "service", "receiver")
        val names = tags.flatMap { manifestNodes(it) }.mapNotNull { attr(it, "android:name") }
        assertTrue("manifest lists components", names.size >= 10)
        names.filter { it.startsWith(".") }.forEach { rel ->
            val path = rel.removePrefix(".").replace('.', '/')
            // Activity aliases have no class of their own; they are validated against AppIconChoice below.
            if (!path.startsWith("MainActivityAlias")) {
                assertTrue("component $rel has no source file", File(mainKotlin, "$path.kt").exists())
            }
        }
        assertEquals(".StereoSineApp", attr(manifestNodes("application").single(), "android:name"))
    }

    @Test fun `launcher aliases match AppIconChoice and use the new package`() {
        val aliases = manifestNodes("activity-alias").mapNotNull { attr(it, "android:name") }.toSet()
        val expected = AppIconChoice.entries.map { it.aliasClassName }
        expected.forEach { assertTrue("$it must start with the new package", it.startsWith("com.launchpoint.stereosine.")) }
        assertEquals(expected.map { "." + it.removePrefix("com.launchpoint.stereosine.") }.toSet(), aliases)
    }

    @Test fun `widget and FileProvider references are authority-relative or use the new package`() {
        val provider = manifestNodes("provider").first { attr(it, "android:name") == "androidx.core.content.FileProvider" }
        assertEquals("\${applicationId}.fileprovider", attr(provider, "android:authorities"))
        val info = File(moduleDir, "src/main/res/xml/stereosine_widget_info.xml").readText()
        assertTrue(info.contains("android:configure=\"com.launchpoint.stereosine.ui.widget.WidgetConfigActivity\""))
    }

    @Test fun `gradle declares the StereoSine application id and namespace`() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertTrue(gradle.contains("namespace = \"com.launchpoint.stereosine\""))
        assertTrue(gradle.contains("applicationId = \"com.launchpoint.stereosine\""))
        assertTrue(gradle.contains("applicationIdSuffix = \".debug\""))
    }

    @Test fun `no source file or resource keeps the old package namespace except the legacy backup metadata literal`() {
        val allowed = setOf("WavdropBackupExporter.kt", "WavdropBackupParserTest.kt", "BrandIdentityTest.kt")
        val roots = listOf("src/main", "src/test", "src/androidTest").map { File(moduleDir, it) }
        val offenders = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList() }
            .filter { it.name !in allowed && it.readText().contains("com.launchpoint.wavdrop") }
            .map { it.path }
        assertTrue("old package still referenced: $offenders", offenders.isEmpty())
        assertTrue("legacy packageName literal is kept in the exporter",
            File(mainKotlin, "data/backup/WavdropBackupExporter.kt").readText().contains("put(\"packageName\", \"com.launchpoint.wavdrop\")"))
    }

    @Test fun `legacy data identifiers are unchanged (compatibility, not branding)`() {
        // .wdbk / JSON backups written by Wavdrop must stay readable: these literals are format identifiers.
        assertEquals("wavdrop_backup", WavdropBackupParser.SUPPORTED_FORMAT)
        assertEquals("wavdrop_wdbk", WdbkContainerVersion.FORMAT)
        assertEquals("wavdrop_backup", WdbkContainerVersion.LOGICAL_FORMAT)
        // Persisted-store identifiers: renaming these would silently reset data in a future in-place migration.
        assertEquals("wavdrop.db", DATABASE_FILE_NAME)
    }

    @Test fun `user-visible launcher and app names are StereoSine`() {
        val strings = File(moduleDir, "src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"app_name\">StereoSine</string>"))
        assertTrue(strings.contains("<string name=\"launcher_label\">StereoSine</string>"))
    }
}
