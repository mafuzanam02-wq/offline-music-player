package com.launchpoint.stereosine.data.backup

import com.launchpoint.stereosine.data.backup.BackupHealthRules.FailureSource
import com.launchpoint.stereosine.data.backup.BackupHealthRules.Input
import com.launchpoint.stereosine.data.backup.BackupHealthRules.Status
import com.launchpoint.stereosine.data.settings.AutoBackupCheckResult
import com.launchpoint.stereosine.data.settings.AutoBackupInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BACKUP-06A. All time is injected; nothing reads the clock. */
class BackupHealthRulesTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_000_000 * hour // arbitrary fixed "current" time

    private fun input(
        interval: AutoBackupInterval = AutoBackupInterval.DAILY,
        folderSelected: Boolean = true,
        permission: Boolean? = true,
        success: Long = 0L,
        autoAt: Long = 0L,
        auto: AutoBackupCheckResult? = null,
        manualAt: Long = 0L,
        manual: AutoBackupCheckResult? = null,
        folderChangedAt: Long = 0L,
    ) = Input(interval, folderSelected, permission, success, autoAt, auto, manualAt, manual, folderChangedAt)

    private fun decide(i: Input) = BackupHealthRules.decide(i, now)
    private fun status(i: Input) = decide(i).status

    // A. Daily current with a successful check.
    @Test fun dailyCurrentWithSuccessfulCheckIsHealthy() {
        val d = decide(input(success = now - 2 * hour, autoAt = now - 2 * hour, auto = AutoBackupCheckResult.SUCCESS))
        assertEquals(Status.HEALTHY_CURRENT, d.status)
        assertTrue(d.isHealthy)
        assertNull(d.unresolvedFailure)
    }

    @Test fun notDueCheckAfterASuccessKeepsItHealthy() {
        assertEquals(
            Status.HEALTHY_CURRENT,
            status(input(success = now - 10 * hour, autoAt = now - hour, auto = AutoBackupCheckResult.NOT_DUE)),
        )
    }

    // B. Daily overdue: due inside the 6 h scheduling allowance, overdue beyond it.
    @Test fun dailyBecomesDueThenOverdue() {
        assertEquals(Status.HEALTHY_CURRENT, status(input(success = now - 24 * hour + 1)))
        assertEquals(Status.BACKUP_DUE, status(input(success = now - 24 * hour)))
        assertEquals(Status.BACKUP_DUE, status(input(success = now - 26 * hour)))
        assertEquals(Status.BACKUP_OVERDUE, status(input(success = now - 31 * hour)))
    }

    // C. Weekly within and beyond.
    @Test fun weeklyWithinAndBeyondItsInterval() {
        val w = AutoBackupInterval.WEEKLY
        assertEquals(Status.HEALTHY_CURRENT, status(input(w, success = now - 6 * day)))
        assertEquals(Status.BACKUP_DUE, status(input(w, success = now - 8 * day)))
        assertEquals(Status.BACKUP_OVERDUE, status(input(w, success = now - 9 * day)))
    }

    // D. Monthly within and beyond.
    @Test fun monthlyWithinAndBeyondItsInterval() {
        val m = AutoBackupInterval.MONTHLY
        // The old fixed eight-day rule called 20 days stale for a monthly schedule.
        assertEquals(Status.HEALTHY_CURRENT, status(input(m, success = now - 20 * day)))
        assertEquals(Status.BACKUP_DUE, status(input(m, success = now - 31 * day)))
        assertEquals(Status.BACKUP_OVERDUE, status(input(m, success = now - 34 * day)))
    }

    @Test fun allowanceIsBoundedAndOffHasNone() {
        assertEquals(6 * hour, BackupHealthRules.schedulingAllowanceMs(AutoBackupInterval.DAILY))
        assertEquals(42 * hour, BackupHealthRules.schedulingAllowanceMs(AutoBackupInterval.WEEKLY))
        assertEquals(3 * day, BackupHealthRules.schedulingAllowanceMs(AutoBackupInterval.MONTHLY))
        assertEquals(0L, BackupHealthRules.schedulingAllowanceMs(AutoBackupInterval.OFF))
    }

    // E. The reported scenario: old success, then Folder unavailable. Never healthy.
    @Test fun oldSuccessFollowedByFolderUnavailableIsNotHealthyAndKeepsTheLastSuccess() {
        val success = now - 5 * day
        val d = decide(input(success = success, autoAt = now - 3 * hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE))
        assertEquals(Status.FOLDER_UNAVAILABLE, d.status)
        assertFalse(d.isHealthy)
        assertEquals(success, d.lastKnownSuccessAtMillis)
        assertEquals(FailureSource.AUTOMATIC, d.unresolvedFailure?.source)
    }

    @Test fun aRecentSuccessDoesNotHideALaterFolderFailure() {
        val d = decide(
            input(AutoBackupInterval.WEEKLY, success = now - day, autoAt = now - hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE),
        )
        assertEquals(Status.FOLDER_UNAVAILABLE, d.status)
    }

    @Test fun genericFailureIsBackupFailed() {
        assertEquals(
            Status.BACKUP_FAILED,
            status(input(success = now - 2 * day, autoAt = now - hour, auto = AutoBackupCheckResult.FAILURE)),
        )
    }

    // F. Folder at capacity.
    @Test fun folderAtCapacity() {
        assertEquals(
            Status.FOLDER_AT_CAPACITY,
            status(input(success = now - 2 * day, autoAt = now - hour, auto = AutoBackupCheckResult.FOLDER_AT_CAPACITY)),
        )
        // Near capacity is a success with a warning, not a failure.
        assertEquals(
            Status.HEALTHY_CURRENT,
            status(input(success = now - hour, autoAt = now - hour, auto = AutoBackupCheckResult.SUCCESS_NEAR_CAPACITY)),
        )
    }

    // G. No selected folder.
    @Test fun noSelectedFolder() {
        val d = decide(input(folderSelected = false, permission = null, success = now - hour))
        assertEquals(Status.FOLDER_NOT_SELECTED, d.status)
        assertEquals(now - hour, d.lastKnownSuccessAtMillis)
    }

    @Test fun lostFolderPermissionIsFolderUnavailableEvenWithoutAFailureRecord() {
        assertEquals(Status.FOLDER_UNAVAILABLE, status(input(permission = false, success = now - hour)))
    }

    // H. Automatic backups off.
    @Test fun automaticBackupsOffIsNeverHealthy() {
        val d = decide(input(AutoBackupInterval.OFF, success = now - hour))
        assertEquals(Status.AUTOMATIC_OFF, d.status)
        assertFalse(d.isHealthy)
    }

    // I. Failure followed by a successful manual backup.
    @Test fun manualSuccessResolvesAnEarlierAutomaticFailure() {
        // A manual success updates the shared success timestamp; the automatic-check record is left as it was.
        val d = decide(
            input(
                success = now - hour, autoAt = now - 5 * hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE,
                manualAt = now - hour, manual = AutoBackupCheckResult.SUCCESS,
            ),
        )
        assertEquals(Status.HEALTHY_CURRENT, d.status)
        assertNull(d.unresolvedFailure)
    }

    // J. Failure followed by a failed manual attempt: the latest relevant failure is reported.
    @Test fun failedManualAfterAutomaticFailureReportsTheManualOne() {
        val d = decide(
            input(
                success = now - 5 * day, autoAt = now - 5 * hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE,
                manualAt = now - hour, manual = AutoBackupCheckResult.FOLDER_AT_CAPACITY,
            ),
        )
        assertEquals(Status.FOLDER_AT_CAPACITY, d.status)
        assertEquals(FailureSource.MANUAL, d.unresolvedFailure?.source)
    }

    @Test fun manualSuccessThenLaterAutomaticFailureIsAFailure() {
        val d = decide(
            input(
                success = now - 4 * hour, autoAt = now - hour, auto = AutoBackupCheckResult.FAILURE,
                manualAt = now - 4 * hour, manual = AutoBackupCheckResult.SUCCESS,
            ),
        )
        assertEquals(Status.BACKUP_FAILED, d.status)
        assertEquals(FailureSource.AUTOMATIC, d.unresolvedFailure?.source)
    }

    @Test fun manualFailureThenLaterAutomaticSuccessIsHealthy() {
        assertEquals(
            Status.HEALTHY_CURRENT,
            status(
                input(
                    success = now - hour, autoAt = now - hour, auto = AutoBackupCheckResult.SUCCESS,
                    manualAt = now - 3 * hour, manual = AutoBackupCheckResult.FAILURE,
                ),
            ),
        )
    }

    // K. Folder changed after an earlier failure.
    @Test fun changingTheFolderMakesEarlierFailuresStaleAndAnEarlierSuccessForeign() {
        val d = decide(
            input(
                success = now - 5 * day, autoAt = now - 3 * hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE,
                manualAt = now - 2 * hour, manual = AutoBackupCheckResult.FOLDER_UNAVAILABLE, folderChangedAt = now - hour,
            ),
        )
        assertEquals(Status.NO_BACKUP_YET, d.status)
        assertNull(d.unresolvedFailure)
        assertTrue(d.lastSuccessWasOtherFolder)
        assertEquals(now - 5 * day, d.lastKnownSuccessAtMillis)
    }

    @Test fun successToTheNewFolderAfterTheChangeIsCurrent() {
        assertEquals(
            Status.HEALTHY_CURRENT,
            status(
                input(
                    success = now - hour, folderChangedAt = now - 2 * hour,
                    autoAt = now - 5 * hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE,
                ),
            ),
        )
    }

    @Test fun aFailureAfterTheFolderChangeStillCounts() {
        assertEquals(
            Status.FOLDER_UNAVAILABLE,
            status(
                input(
                    success = now - 5 * day, folderChangedAt = now - 3 * hour,
                    autoAt = now - hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE,
                ),
            ),
        )
    }

    @Test fun unknownFolderChangeTimeAssumesTheSuccessAppliesToTheCurrentFolder() {
        val d = decide(input(success = now - hour, folderChangedAt = 0L))
        assertEquals(Status.HEALTHY_CURRENT, d.status)
        assertFalse(d.lastSuccessWasOtherFolder)
    }

    // L. Success and automatic-check timestamps disagree.
    @Test fun aNewerFailureWinsAndAnOlderFailureDoesNot() {
        assertEquals(
            Status.FOLDER_UNAVAILABLE,
            status(input(success = now - 2 * day, autoAt = now - hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE)),
        )
        assertEquals(
            Status.HEALTHY_CURRENT,
            status(input(success = now - hour, autoAt = now - 2 * day, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE)),
        )
    }

    @Test fun anOldNoFolderSelectedResultIsMootOnceAFolderIsChosen() {
        assertEquals(
            Status.HEALTHY_CURRENT,
            status(input(success = now - 2 * hour, autoAt = now - hour, auto = AutoBackupCheckResult.NO_FOLDER_SELECTED)),
        )
    }

    // M. No historical success.
    @Test fun noHistoricalSuccess() {
        val d = decide(input())
        assertEquals(Status.NO_BACKUP_YET, d.status)
        assertNull(d.lastKnownSuccessAtMillis)
    }

    @Test fun noSuccessButAFailureReportsTheFailure() {
        assertEquals(Status.FOLDER_UNAVAILABLE, status(input(autoAt = now - hour, auto = AutoBackupCheckResult.FOLDER_UNAVAILABLE)))
    }

    // N. Clock anomalies, missing records, invalid timestamps.
    @Test fun futureSuccessTimestampIsUnknownNotHealthy() {
        val d = decide(input(success = now + day))
        assertEquals(Status.UNKNOWN, d.status)
        assertNull(d.lastKnownSuccessAtMillis)
    }

    @Test fun futureCheckManualOrFolderChangeTimestampIsUnknown() {
        assertEquals(Status.UNKNOWN, status(input(success = now - hour, autoAt = now + day, auto = AutoBackupCheckResult.SUCCESS)))
        assertEquals(Status.UNKNOWN, status(input(success = now - hour, manualAt = now + day, manual = AutoBackupCheckResult.FAILURE)))
        assertEquals(Status.UNKNOWN, status(input(success = now - hour, folderChangedAt = now + day)))
    }

    @Test fun smallClockSkewIsTolerated() {
        assertEquals(Status.HEALTHY_CURRENT, status(input(success = now + 60_000)))
    }

    @Test fun negativeOrZeroTimestampsAreMissing() {
        assertEquals(Status.NO_BACKUP_YET, status(input(success = -5L, autoAt = -1L, auto = AutoBackupCheckResult.FAILURE)))
    }

    @Test fun aResultWithNoTimestampOrATimestampWithNoResultIsIgnored() {
        assertEquals(Status.HEALTHY_CURRENT, status(input(success = now - hour, autoAt = 0L, auto = AutoBackupCheckResult.FAILURE)))
        assertEquals(Status.HEALTHY_CURRENT, status(input(success = now - hour, autoAt = now, auto = null)))
    }

    // O. Saved but not independently verified: verification is not an input, so it can never be attributed.
    @Test fun independentVerificationIsNotAnInputToTheDecision() {
        val inputFields = Input::class.java.declaredFields.map { it.name }
        val decisionFields = BackupHealthRules.Decision::class.java.declaredFields.map { it.name }
        assertTrue((inputFields + decisionFields).none { it.contains("erification", ignoreCase = true) })
        assertEquals(Status.HEALTHY_CURRENT, status(input(success = now - hour)))
    }

    @Test fun otherSituationsAreNeverHealthy() {
        listOf(
            input(success = now - 31 * hour),
            input(autoAt = now, auto = AutoBackupCheckResult.FAILURE),
            input(folderSelected = false),
            input(AutoBackupInterval.OFF),
            input(),
        ).forEach { assertFalse(decide(it).isHealthy) }
    }
}
