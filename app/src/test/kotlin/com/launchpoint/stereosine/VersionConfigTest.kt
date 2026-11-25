package com.launchpoint.stereosine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionConfigTest {

    private val buildText: String
        get() = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).first { it.exists() }.readText()

    @Test
    fun `beta 11 release candidate has correct version code and name`() {
        val text = buildText

        assertTrue("versionCode must be 11 for Beta 11", Regex("""versionCode\s*=\s*11\b""").containsMatchIn(text))
        assertEquals(
            "0.1.0-beta11",
            Regex("versionName\\s*=\\s*\"([^\"]+)\"")
                .find(text)
                ?.groupValues
                ?.get(1),
        )
    }

    // RELEASE-11A: the version bump must not change the package identity (permanent once published to Play).
    @Test
    fun `beta 11 keeps the StereoSine application id`() {
        val text = buildText
        assertEquals(
            "com.launchpoint.stereosine",
            Regex("applicationId\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1),
        )
        assertTrue("debug suffix unchanged", """applicationIdSuffix = ".debug"""" in text)
    }

    private fun doc(name: String): String =
        listOf(File("../$name"), File(name)).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun `beta 11 release documents agree with the build version`() {
        // The newest StereoSine entry is a release CANDIDATE: no document may present it as distributed or published.
        val whatsNew = doc("WHATS_NEW.md")
        assertTrue(whatsNew.startsWith("# What's New — StereoSine Beta 11 (release candidate; not yet distributed)"))
        assertTrue("has not been distributed to testers or published" in whatsNew)
        assertTrue("`0.1.0-beta11`" in whatsNew)
        // The Beta 10 summary is kept below as history with its original Wavdrop title.
        assertTrue("# What's New — Wavdrop Beta 10.0 (now StereoSine)" in whatsNew)
        assertTrue(whatsNew.indexOf("StereoSine Beta 11") < whatsNew.indexOf("Wavdrop Beta 10.0"))
        assertTrue("renamed **StereoSine**" in whatsNew)

        val notes = doc("RELEASE_NOTES.md")
        assertTrue("No StereoSine-branded release has been published yet" in notes)
        assertTrue("## 0.1.0-beta11 (release candidate; NOT yet distributed)" in notes)
        assertTrue("## 0.1.0-beta10" in notes)
        // Newest first: Beta 11 above Beta 10 above Beta 9; prior history kept.
        assertTrue(notes.indexOf("## 0.1.0-beta11") < notes.indexOf("## 0.1.0-beta10"))
        assertTrue(notes.indexOf("## 0.1.0-beta10") < notes.indexOf("## 0.1.0-beta9"))
        assertTrue("Unreleased / Engineering since beta9" !in notes)
        assertTrue("## 0.1.0-beta7.9" in notes && "## 0.1.0-beta7.5" in notes)

        assertTrue("`0.1.0-beta11`, versionCode 11" in doc("PROJECT_CONTEXT.md"))
        assertTrue("`com.launchpoint.stereosine`" in doc("PROJECT_CONTEXT.md"))
        assertTrue("`com.launchpoint.stereosine`" in doc("README.md"))
        assertTrue("version `0.1.0-beta11` (versionCode 11)" in doc("README.md"))
        // The lost original signing key must never be described as recovered/configured.
        assertTrue("original Wavdrop release key was lost" in doc("docs/RELEASE_SIGNING.md"))
        assertTrue("""`versionCode = 11`, `versionName = "0.1.0-beta11"`""" in doc("docs/RELEASE_SIGNING.md"))
        assertTrue("0.1.0-beta11 (versionCode 11)" in doc("ENGINEERING_BACKLOG_AND_DECISIONS.md"))
        assertTrue("0.1.0-beta10 (versionCode 10)" in doc("ENGINEERING_BACKLOG_AND_DECISIONS.md")) // history kept
        val play = doc("PLAY_STORE_READINESS_CHECKLIST.md")
        assertTrue("""`"0.1.0-beta11"`""" in play && "Currently `11`" in play)
    }

    // The Beta 11 section must distinguish built / installed / tested / distributed / published and never claim a release.
    @Test
    fun `beta 11 notes do not claim distribution publication or Google Play`() {
        val notes = doc("RELEASE_NOTES.md")
        val beta11 = notes.substringAfter("## 0.1.0-beta11").substringBefore("## 0.1.0-beta10")
        for (stage in listOf(
            "Final signed release APK assembled | **No**",
            "Distributed to external testers | **No**",
            "Published publicly | **No**",
            "Play App Signing enrolled | **No** / unconfirmed",
        )) assertTrue("missing release-status row: $stage", stage in beta11)
        assertTrue("QA evidence for the Beta 10 build only" in beta11)
        val lower = beta11.lowercase()
        for (claim in listOf("now available on google play", "has shipped", "is released", "published to google play")) {
            assertTrue("Beta 11 notes must not claim '$claim'", claim !in lower)
        }
        assertTrue("with the receiving phone online" in beta11)
    }

    // RELEASE-11A-R1: wdbk validation must be stated precisely (create+verify seen on a device; restore / cross-device / schedule not).
    @Test
    fun `beta 11 notes state exactly what was physically validated for wdbk backups`() {
        val beta11 = doc("RELEASE_NOTES.md").substringAfter("## 0.1.0-beta11").substringBefore("## 0.1.0-beta10")
        assertTrue("**`.wdbk` backup: partly validated on a device.**" in beta11)
        assertTrue("independently read it and reported verified integrity with a complete folder search" in beta11)
        for (notValidated in listOf(
            "a full restore from a `.wdbk` backup", "recovery of a `.wdbk` backup on a different device",
            "folder-change behaviour", "a scheduled automatic backup run",
        )) assertTrue("missing not-validated item: $notValidated", notValidated in beta11)
        // The blanket 'wdbk physical validation pending' wording is gone from the pending list.
        val pending = beta11.substringAfter("**Implemented but physical validation pending**").substringBefore("- A cosmetic defect")
        assertTrue("`.wdbk`" !in pending)
        val whatsNew = doc("WHATS_NEW.md").substringBefore("# What's New — Wavdrop Beta 10.0")
        assertTrue("confirmed on one phone" in whatsNew && "restoring from a `.wdbk` backup" in whatsNew)
    }

    @Test
    fun `wave c is closed with every WC item resolved and crossfade enabled`() {
        val backlog = doc("ENGINEERING_BACKLOG_AND_DECISIONS.md")
        assertTrue("| WC-10 | **Resolved (post-Beta 10)**" in backlog)
        assertTrue("| WC-09 | **Resolved (post-Beta 10)**" in backlog)
        for (id in (1..11).map { "WC-%02d".format(it) }) {
            assertTrue("$id must be resolved", Regex("""\| $id \| \*\*Resolved""").containsMatchIn(backlog))
        }
        assertTrue("| Open (unchanged) |" !in backlog)
        val gate = File("src/main/kotlin/com/launchpoint/stereosine/playback/CrossfadeRolloutPolicy.kt").readText()
        assertTrue("const val RUNTIME_ENABLED = true" in gate)
    }

    @Test
    fun `beta 10 notes never claim recovery from a user force-stop`() {
        val notes = doc("RELEASE_NOTES.md")
        val beta10 = notes.substringAfter("## 0.1.0-beta10").substringBefore("## 0.1.0-beta9")
        val lower = beta10.lowercase()
        assertTrue("stopped by the system or the user" !in lower)
        // Android force-stop is respected; the notes must not promise restart/resume/resurrection after it.
        val claims = Regex("(force[- ]?stop|resurrect|restart itself|relaunch itself)").findAll(lower).map { it.value }.toList()
        assertTrue("Beta 10 notes mention force-stop/resurrection: $claims", claims.isEmpty())
    }
}
