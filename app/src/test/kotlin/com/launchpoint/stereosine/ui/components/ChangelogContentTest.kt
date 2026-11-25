package com.launchpoint.stereosine.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RELEASE-11A. The changelog lists are private Compose-file data, so these checks read the source text (the same approach as
 * VersionConfigTest for documents). They guard: Beta 11 is the default and the newest history entry, older history is intact,
 * and the user-facing Beta 11 wording stays plain and honest.
 */
class ChangelogContentTest {

    private val source: String = listOf(
        File("src/main/kotlin/com/launchpoint/stereosine/ui/components/ChangelogDialog.kt"),
        File("app/src/main/kotlin/com/launchpoint/stereosine/ui/components/ChangelogDialog.kt"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    private fun block(name: String): String =
        source.substringAfter("private val $name: List<ChangelogEntry> = listOf(").substringBefore("\n)\n")

    private val beta11 get() = block("BETA_11_ENTRIES")

    @Test
    fun `default dialog is Beta 11 and no longer Beta 10`() {
        val dialog = source.substringAfter("fun ChangelogDialog(").substringBefore("@Composable")
        assertTrue("title = \"What's new in Beta 11\"" in dialog)
        assertTrue("entries = BETA_11_ENTRIES" in dialog)
        assertFalse("Beta 10" in dialog)
        assertFalse("BETA_10_ENTRIES" in dialog)
    }

    @Test
    fun `full history lists Beta 11 first and keeps every older entry`() {
        val history = source.substringAfter("fun FullChangelogDialog(").substringBefore("@Composable")
        val order = listOf("Beta 11 — ", "Beta 10 — ", "Beta 9 — ", "Beta 7.9 — ", "Beta 7 — ", "Beta 5 — ", "\"Beta 4\"", "\"Beta 3.1\"")
        val positions = order.map { history.indexOf(it) }
        assertTrue("every release heading must remain: $positions", positions.all { it >= 0 })
        assertEquals("newest first", positions.sorted(), positions)
        for (list in listOf("BETA_11_ENTRIES", "BETA_10_ENTRIES", "BETA_9_ENTRIES", "BETA_7_9_ENTRIES", "BETA_7_ENTRIES", "BETA_5_ENTRIES", "BETA_4_ENTRIES", "BETA_3_1_ENTRIES")) {
            assertTrue("$list must exist", "private val $list:" in source)
        }
        // Historical Beta 10 wording is preserved verbatim.
        assertTrue("Smooth transitions between songs are now available in Settings > Playback" in block("BETA_10_ENTRIES"))
    }

    @Test
    fun `Beta 11 covers the planned sections`() {
        for (section in listOf(
            "Share StereoSine", "Wrapped Videos", "More Sound Control", "Safer Backups", "Search and Navigation", "Reliability and Polish",
        )) assertTrue("missing section $section", "ChangelogEntry.Section(\"$section\")" in beta11)
    }

    @Test
    fun `Beta 11 text is plain user language without internal ids or hashes`() {
        assertFalse(Regex("""\b[0-9a-f]{7,40}\b""").containsMatchIn(beta11))
        for (internal in listOf("RELEASE-", "P5-", "BACKUP-", "SHARE-APP", "WDBK", "slice", "commit", "WorkManager")) {
            assertFalse("internal term '$internal' in user-facing Beta 11 text", internal.lowercase() in beta11.lowercase())
        }
    }

    @Test
    fun `Beta 11 makes no unsupported reliability or offline claims`() {
        val lower = beta11.lowercase()
        for (claim in listOf(
            "independently verified", "works on all devices", "works on every device", "works everywhere", "infallible", "guaranteed",
            "both devices offline", "fully offline", "has shipped", "now available on google play",
        )) assertFalse("unsupported claim '$claim'", claim in lower)
        // Required honesty qualifiers.
        assertTrue("depends on your device" in lower)
        assertTrue("best-effort" in lower)
        assertTrue("saved backup isn't the same as a verified one" in lower)
        assertTrue("on your device only" in lower)
    }
}
