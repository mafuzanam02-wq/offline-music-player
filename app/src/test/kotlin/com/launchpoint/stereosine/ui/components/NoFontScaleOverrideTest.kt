package com.launchpoint.stereosine.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-READABILITY-03A-B regression guard: the readability fixes must never come back as a global or subtree-level font-scale override
 * or as automatic shrinking of the user's text. The screens below must lay out around the requested scale, not reduce it.
 */
class NoFontScaleOverrideTest {
    private val root = listOf("src/main/kotlin", "app/src/main/kotlin").map(::File).first { it.exists() }
    private val guarded = listOf(
        "com/launchpoint/stereosine/ui/components/PrimaryNavigationBar.kt",
        "com/launchpoint/stereosine/ui/screen/statistics/StatisticsScreen.kt",
        "com/launchpoint/stereosine/ui/screen/settings/SettingsComponents.kt",
        "com/launchpoint/stereosine/ui/screen/trackdetails/TrackDetailsScreen.kt",
    )

    private fun code(path: String) = File(root, path).readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }

    @Test fun guarded_files_do_not_override_density_or_font_scale() {
        val forbidden = listOf(Regex("""LocalDensity\s+provides"""), Regex("""[^a-zA-Z]Density\("""), Regex("""LocalFontScale"""))
        guarded.forEach { f ->
            code(f).forEachIndexed { i, line ->
                forbidden.forEach { r -> assertTrue("$f:${i + 1} overrides the font scale: $line", !r.containsMatchIn(line)) }
            }
        }
    }

    @Test fun guarded_files_do_not_shrink_text_automatically() {
        val forbidden = listOf(Regex("""fontSize\s*=.*\*"""), Regex("""\.scaled\("""), Regex("""fitScale"""), Regex("""labelFontScale"""))
        guarded.forEach { f ->
            code(f).forEachIndexed { i, line ->
                forbidden.forEach { r -> assertTrue("$f:${i + 1} shrinks text: $line", !r.containsMatchIn(line)) }
            }
        }
    }
}
