package com.launchpoint.stereosine.ui.screen.settings

import com.launchpoint.stereosine.data.backup.BackupHealthRules
import com.launchpoint.stereosine.data.backup.BackupHealthRules.Status
import com.launchpoint.stereosine.data.settings.AutoBackupCheckResult
import com.launchpoint.stereosine.data.settings.AutoBackupInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BACKUP-06A. The reported scenario must not produce "Backups are up to date". */
class BackupHealthPresentationTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_000_000 * hour
    private val fmt: (Long) -> String = { "T${(now - it) / hour}h" }

    private fun decision(
        interval: AutoBackupInterval = AutoBackupInterval.DAILY,
        success: Long = 0L,
        autoAt: Long = 0L,
        auto: AutoBackupCheckResult? = null,
        folderSelected: Boolean = true,
    ) = BackupHealthRules.decide(
        BackupHealthRules.Input(
            interval = interval, folderSelected = folderSelected, lastSuccessAtMillis = success,
            lastAutoCheckAtMillis = autoAt, lastAutoResult = auto,
        ),
        now,
    )

    private val reported = decision(success = now - 5 * day, autoAt = now - hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE)

    @Test fun theReportedCombinationNeverSaysUpToDate() {
        assertEquals("Backup folder unavailable", BackupHealthPresentation.headline(reported))
        assertFalse(BackupHealthPresentation.headline(reported).contains("up to date"))
    }

    @Test fun theReportedCombinationShowsTheFailureAndKeepsTheLastSuccess() {
        assertEquals("Automatic check, T1h — Folder unavailable", BackupHealthPresentation.failureLine(reported, fmt))
        assertEquals("T120h", BackupHealthPresentation.lastSavedValue(reported, fmt))
    }

    @Test fun folderFailuresGiveTheThreeStepRecoveryAndAFolderAction() {
        val g = BackupHealthPresentation.guidance(reported)!!
        assertTrue(g.indexOf("dedicated") < g.indexOf("Back up now"))
        assertTrue(g.indexOf("Back up now") < g.indexOf("Backup Verification"))
        assertTrue(BackupHealthPresentation.offersFolderAction(reported))
        val capacity = decision(success = now - day, autoAt = now - hour, auto = AutoBackupCheckResult.FOLDER_AT_CAPACITY)
        assertEquals(g, BackupHealthPresentation.guidance(capacity))
    }

    @Test fun onlyAHealthyDecisionSaysUpToDate() {
        val all = listOf(
            decision(success = now - hour),
            decision(success = now - 26 * hour), decision(success = now - 40 * hour), reported, decision(),
            decision(AutoBackupInterval.OFF), decision(folderSelected = false),
            decision(success = now + 5 * day),
        )
        val upToDate = all.filter { BackupHealthPresentation.headline(it) == BackupHealthPresentation.UP_TO_DATE }
        assertEquals(1, upToDate.size)
        assertEquals(Status.HEALTHY_CURRENT, upToDate.single().status)
        assertNull(BackupHealthPresentation.guidance(upToDate.single()))
    }

    @Test fun everyNonHealthyStatusHasAHeadlineAndGuidance() {
        val seen = mutableSetOf<Status>()
        listOf(
            decision(success = now - 26 * hour), decision(success = now - 40 * hour), reported,
            decision(success = now - day, autoAt = now - hour, auto = AutoBackupCheckResult.FOLDER_AT_CAPACITY),
            decision(success = now - day, autoAt = now - hour, auto = AutoBackupCheckResult.FAILURE),
            decision(), decision(AutoBackupInterval.OFF), decision(folderSelected = false), decision(success = now + day),
        ).forEach { d ->
            seen += d.status
            assertTrue(BackupHealthPresentation.headline(d).isNotBlank())
            assertNotNull(BackupHealthPresentation.guidance(d))
        }
        assertEquals(Status.entries.toSet() - Status.HEALTHY_CURRENT, seen)
    }

    private val claims = listOf("Independently verified", "verified by", "Verified backup")

    private fun assertNoVerificationClaim(d: BackupHealthRules.Decision) {
        val note = BackupHealthPresentation.verificationNote(d)!!
        claims.forEach { assertFalse("'$note' must not contain '$it'", note.contains(it, ignoreCase = true)) }
        assertEquals(BackupHealthPresentation.VERIFICATION_NOTE, note)
        assertTrue(note.contains("not an independent check"))
        assertTrue(note.contains("current folder"))
    }

    // A valid current backup with no independent verification.
    @Test fun aValidCurrentBackupIsUpToDateButNeverClaimedVerified() {
        val d = decision(success = now - hour)
        assertEquals(BackupHealthPresentation.UP_TO_DATE, BackupHealthPresentation.headline(d))
        assertNoVerificationClaim(d)
        assertNull(BackupHealthPresentation.verificationNote(decision()))
    }

    // Previously verified backup A followed by manual backup B: the summary has no per-backup verification, so B gets no claim.
    @Test fun aNewerBackupNeverInheritsAnEarlierVerificationResult() {
        val afterManualB = BackupHealthRules.decide(
            BackupHealthRules.Input(
                AutoBackupInterval.DAILY, true, true, lastSuccessAtMillis = now - hour,
                lastAutoCheckAtMillis = now - 3 * day, lastAutoResult = AutoBackupCheckResult.SUCCESS,
                lastManualAtMillis = now - hour, lastManualResult = AutoBackupCheckResult.SUCCESS,
            ),
            now,
        )
        assertEquals(BackupHealthPresentation.UP_TO_DATE, BackupHealthPresentation.headline(afterManualB))
        assertNoVerificationClaim(afterManualB)
        assertEquals("T1h", BackupHealthPresentation.lastSavedValue(afterManualB, fmt))
    }

    // Verified result from a previous folder after switching folders.
    @Test fun afterSwitchingFoldersNothingIsAttributedToTheNewFolder() {
        val d = BackupHealthRules.decide(
            BackupHealthRules.Input(
                AutoBackupInterval.DAILY, true, true, now - 2 * day, 0L, null, folderChangedAtMillis = now - hour,
            ),
            now,
        )
        assertEquals("No backup yet", BackupHealthPresentation.headline(d))
        assertEquals("T48h (previous folder)", BackupHealthPresentation.lastSavedValue(d, fmt))
        assertNoVerificationClaim(d)
    }

    // Incomplete/unavailable search, loading, or a failed candidate cannot change the summary: it does not consume them.
    @Test fun summaryIsIndependentOfVerificationSearchOrLoadingState() {
        val d = decision(success = now - hour)
        // The only verification wording is the constant pointer; identical whatever the Verification screen is doing.
        repeat(4) { assertEquals(BackupHealthPresentation.VERIFICATION_NOTE, BackupHealthPresentation.verificationNote(d)) }
        assertNoVerificationClaim(d)
        assertEquals(BackupHealthPresentation.UP_TO_DATE, BackupHealthPresentation.headline(d))
    }

    // A manual success after an earlier automatic failure.
    @Test fun manualSuccessAfterAutomaticFailureIsCurrentWithoutAVerificationClaim() {
        val d = BackupHealthRules.decide(
            BackupHealthRules.Input(
                AutoBackupInterval.DAILY, true, true, lastSuccessAtMillis = now - hour,
                lastAutoCheckAtMillis = now - 5 * hour, lastAutoResult = AutoBackupCheckResult.FOLDER_UNAVAILABLE,
                lastManualAtMillis = now - hour, lastManualResult = AutoBackupCheckResult.SUCCESS,
            ),
            now,
        )
        assertEquals(BackupHealthPresentation.UP_TO_DATE, BackupHealthPresentation.headline(d))
        assertNull(BackupHealthPresentation.failureLine(d, fmt))
        assertNoVerificationClaim(d)
    }

    // The original 19-24 November scenario: headline not healthy, failure shown, last success kept, wording not a verification claim.
    @Test fun theOriginalReportedScenarioIsNotHealthyAndMakesNoVerificationClaim() {
        assertNotEquals(BackupHealthPresentation.UP_TO_DATE, BackupHealthPresentation.headline(reported))
        assertEquals("Backup folder unavailable", BackupHealthPresentation.headline(reported))
        assertEquals("Automatic check, T1h — Folder unavailable", BackupHealthPresentation.failureLine(reported, fmt))
        assertEquals("T120h", BackupHealthPresentation.lastSavedValue(reported, fmt))
        assertNoVerificationClaim(reported)
    }

    @Test fun anOtherFolderSuccessIsLabelledAsSuch() {
        val d = BackupHealthRules.decide(
            BackupHealthRules.Input(
                AutoBackupInterval.DAILY, true, true, now - 5 * day, 0L, null, folderChangedAtMillis = now - hour,
            ),
            now,
        )
        assertEquals("T120h (previous folder)", BackupHealthPresentation.lastSavedValue(d, fmt))
        assertEquals("No backup has been saved to this folder yet. Use Back up now.", BackupHealthPresentation.guidance(d))
    }
}
