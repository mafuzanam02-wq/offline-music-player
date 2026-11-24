package com.launchpoint.stereosine.ui.screen.settings

import com.launchpoint.stereosine.data.backup.BackupVerificationResult
import com.launchpoint.stereosine.data.backup.VerificationSearchStatus
import com.launchpoint.stereosine.data.backup.VerificationStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BACKUP-06B. Deterministic: Unconfined dispatcher plus controllable deferreds; no sleeps or wall-clock time.
 * Each verification suspends in a NON-cancellable section so "finishes after it was cancelled" is exercised for real.
 */
class BackupVerificationCoordinatorTest {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After fun tearDown() { job.cancel() }

    private fun verified() = BackupVerificationResult(VerificationStatus.VERIFIED, fileName = "b.wdbk")
    private fun failed() = BackupVerificationResult(VerificationStatus.FAILED, searchStatus = VerificationSearchStatus.FAILED)

    private class Gate(val folder: String?, val result: BackupVerificationResult) {
        val release = CompletableDeferred<Unit>()
    }

    private class Harness(scope: CoroutineScope) {
        val gates = mutableListOf<Gate>()
        var folder: String? = "A"
        var next: BackupVerificationResult? = null
        val coordinator = BackupVerificationCoordinator(scope) {
            val gate = Gate(folder, requireNotNull(next))
            gates += gate
            withContext(NonCancellable) { gate.release.await() }
            BackupVerificationUiState.Ready(gate.result, gate.folder)
        }
        val state get() = coordinator.state.value
        fun release(i: Int) = gates[i].release.complete(Unit)
    }

    private fun harness() = Harness(scope)

    // 1. Initial verification on screen entry.
    @Test fun entryShowsCheckingThenTheResultOfTheCurrentRequest() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce()
        assertSame(BackupVerificationUiState.Loading, h.state)
        h.release(0)
        assertEquals(BackupVerificationUiState.Ready(verified(), "A"), h.state)
    }

    // 2. Recomposition or re-entry does not repeat the check.
    @Test fun repeatedEntryCallsStartExactlyOneVerification() {
        val h = harness().apply { next = verified() }
        repeat(5) { h.coordinator.requestOnce() }
        assertEquals(1, h.gates.size)
        h.release(0)
        h.coordinator.requestOnce()
        assertEquals(1, h.gates.size)
    }

    // 3. Manual backup invalidates the old VERIFIED result immediately.
    @Test fun aBackupHidesTheOldVerifiedResultImmediatelyAndDuringTheOperation() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce(); h.release(0)
        assertTrue(h.state is BackupVerificationUiState.Ready)
        val backup = Job()
        h.coordinator.requestAfter(backup)
        assertSame(BackupVerificationUiState.Loading, h.state)
        assertEquals("no verification starts while the backup is still running", 1, h.gates.size)
    }

    // 4. Successful backup triggers fresh verification.
    @Test fun afterASuccessfulBackupFinishesAFreshVerificationRuns() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce(); h.release(0)
        val backup = Job()
        h.coordinator.requestAfter(backup)
        backup.complete()
        assertEquals(2, h.gates.size)
        assertSame(BackupVerificationUiState.Loading, h.state)
        h.release(1)
        assertTrue(h.state is BackupVerificationUiState.Ready)
    }

    // 5. A failed backup does not leave the old VERIFIED result on screen.
    @Test fun aFailedBackupNeverRestoresTheOldResultAndStillReverifies() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce(); h.release(0)
        val backup = Job()
        h.coordinator.requestAfter(backup)
        h.next = failed()
        backup.completeExceptionally(IllegalStateException("backup failed"))
        assertEquals(2, h.gates.size)
        assertSame(BackupVerificationUiState.Loading, h.state)
        h.release(1)
        assertEquals(VerificationStatus.FAILED, (h.state as BackupVerificationUiState.Ready).result.status)
    }

    // 6. Folder A verified, then folder B selected: B is never shown with A's result.
    @Test fun aResultIsLabelledWithItsFolderAndOnlyShownForThatFolder() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce(); h.release(0)
        val ready = h.state as BackupVerificationUiState.Ready
        assertEquals("A", ready.folderUri)
        assertEquals("Verified backup", verificationRowSubtitle(ready, "A", true))
        assertEquals(VERIFICATION_CHECKING, verificationRowSubtitle(ready, "B", true))
        assertEquals(VERIFICATION_CHECKING, verificationRowSubtitle(ready, null, true))
        assertEquals(VERIFICATION_CHECKING, verificationRowSubtitle(BackupVerificationUiState.Loading, "A", true))
        val picked = Job()
        h.folder = "B"
        h.coordinator.requestAfter(picked)
        assertSame(BackupVerificationUiState.Loading, h.state)
        picked.complete()
        h.release(1)
        assertEquals("B", (h.state as BackupVerificationUiState.Ready).folderUri)
    }

    // 7. New folder has no valid backup.
    @Test fun aNewEmptyFolderReportsNoBackupNotTheOldVerified() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce(); h.release(0)
        h.folder = "B"
        h.next = BackupVerificationResult(VerificationStatus.NO_BACKUP_FOUND, searchStatus = VerificationSearchStatus.COMPLETE)
        h.coordinator.request(); h.release(1)
        assertEquals("No verified backup found", verificationRowSubtitle(h.state, "B", true))
    }

    // 8. New folder whose permission is unavailable is never recorded as verified.
    @Test fun anUnavailableFolderIsNeverVerified() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce(); h.release(0)
        h.folder = "B"
        h.next = BackupVerificationResult(VerificationStatus.FAILED, searchStatus = VerificationSearchStatus.UNAVAILABLE)
        h.coordinator.request(); h.release(1)
        val s = verificationRowSubtitle(h.state, "B", folderPermissionValid = false)
        assertFalse(s, s.contains("Verified backup"))
    }

    // 9. Same folder chosen again: the old result is still dropped and a new verification runs.
    @Test fun reselectingTheSameFolderStillInvalidatesAndReverifies() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce(); h.release(0)
        val picked = Job()
        h.coordinator.requestAfter(picked)
        assertSame(BackupVerificationUiState.Loading, h.state)
        picked.complete()
        assertEquals(2, h.gates.size)
        h.release(1)
        assertEquals("A", (h.state as BackupVerificationUiState.Ready).folderUri)
    }

    // 10. Verification A completes after verification B.
    @Test fun anOlderVerificationFinishingLaterCannotReplaceTheNewerResult() {
        val h = harness().apply { next = failed() }
        h.coordinator.request()
        h.next = verified()
        h.coordinator.request()
        h.release(1)
        val shown = h.state
        assertEquals(VerificationStatus.VERIFIED, (shown as BackupVerificationUiState.Ready).result.status)
        h.release(0)
        assertSame(shown, h.state)
    }

    // 11. An obsolete verification fails after a newer one succeeded.
    @Test fun anObsoleteFailureDoesNotOverwriteANewerSuccess() {
        val h = harness().apply { next = failed() }
        h.coordinator.request()
        h.next = verified()
        h.coordinator.request()
        h.release(1); h.release(0)
        assertEquals(VerificationStatus.VERIFIED, (h.state as BackupVerificationUiState.Ready).result.status)
    }

    // 12. A backup begins while an earlier verification is still running.
    @Test fun aVerificationRunningWhenABackupStartsNeverPublishes() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce()
        val backup = Job()
        h.coordinator.requestAfter(backup)
        h.release(0)
        assertSame(BackupVerificationUiState.Loading, h.state)
        backup.complete()
        assertEquals(2, h.gates.size)
        h.release(1)
        assertTrue(h.state is BackupVerificationUiState.Ready)
    }

    @Test fun aNewerRequestMakesAPendingAfterOperationStepStandDown() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce(); h.release(0)
        val backup = Job()
        h.coordinator.requestAfter(backup)
        h.coordinator.request()
        backup.complete()
        assertEquals("only the newer request verifies", 2, h.gates.size)
    }

    // 13. Dedicated screen: Verify again always starts a new verification, independent of entry.
    @Test fun verifyAgainAlwaysStartsANewVerification() {
        val h = harness().apply { next = verified() }
        h.coordinator.requestOnce(); h.release(0)
        h.coordinator.request(); h.release(1)
        h.coordinator.request(); h.release(2)
        assertEquals(3, h.gates.size)
        assertTrue(h.state is BackupVerificationUiState.Ready)
    }

    // 15. A newly saved backup (e.g. a background automatic one) or a folder change while the screen is open invalidates the result.
    @Test fun aChangedBackupTimestampOrFolderInvalidatesAndReverifies() {
        val h = harness().apply { next = verified() }
        val lastBackupAt = MutableStateFlow(100L)
        val folder = MutableStateFlow<String?>("A")
        h.coordinator.requestOnChange(lastBackupAt)
        h.coordinator.requestOnChange(folder)
        lastBackupAt.value = 200L
        assertEquals("not entered yet: no verification", 0, h.gates.size)
        h.coordinator.requestOnce(); h.release(0)
        lastBackupAt.value = 300L
        assertSame(BackupVerificationUiState.Loading, h.state)
        assertEquals(2, h.gates.size)
        h.release(1)
        folder.value = "B"
        assertSame(BackupVerificationUiState.Loading, h.state)
        assertEquals(3, h.gates.size)
        lastBackupAt.value = 300L
        assertEquals("an unchanged value adds no verification", 3, h.gates.size)
    }
}
