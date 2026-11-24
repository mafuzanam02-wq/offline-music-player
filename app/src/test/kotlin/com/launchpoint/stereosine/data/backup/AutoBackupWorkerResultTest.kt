package com.launchpoint.stereosine.data.backup

import androidx.work.ListenableWorker
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupWorkerResultTest {
    @Test
    fun `permanent configuration and not-due states complete successfully`() {
        val permanentResults = listOf(
            AutoBackupRepository.Result.Skipped,
            AutoBackupRepository.Result.NoFolderSelected,
            AutoBackupRepository.Result.NotDue,
            AutoBackupRepository.Result.Success,
            AutoBackupRepository.Result.FolderUnavailable("folder unavailable"),
        )

        permanentResults.forEach { result ->
            assertTrue(result.toWorkerResult() is ListenableWorker.Result.Success)
        }
    }

    // BACKUP-06A: permanent errors complete without a WorkManager retry storm because runIfDue has already persisted
    // the outcome (lastAutoBackupResult/CheckAt), which is what the status card reads.
    @Test
    fun `capacity is permanent and completes without retry`() {
        assertTrue(AutoBackupRepository.Result.FolderAtCapacity.toWorkerResult() is ListenableWorker.Result.Success)
        assertTrue(AutoBackupRepository.Result.SuccessNearCapacity.toWorkerResult() is ListenableWorker.Result.Success)
    }

    @Test
    fun `generic backup failures are retryable`() {
        assertTrue(
            AutoBackupRepository.Result.Failure("write failed").toWorkerResult()
                is ListenableWorker.Result.Retry,
        )
    }
}
