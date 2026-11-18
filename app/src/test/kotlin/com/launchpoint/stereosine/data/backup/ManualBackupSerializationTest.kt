package com.launchpoint.stereosine.data.backup

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import com.launchpoint.stereosine.data.backup.wdbk.BackupFileHandle
import com.launchpoint.stereosine.data.backup.wdbk.WdbkReader
import com.launchpoint.stereosine.data.local.StereoSineDatabase
import com.launchpoint.stereosine.data.local.dao.SongDao
import com.launchpoint.stereosine.data.local.entity.PlaylistEntity
import com.launchpoint.stereosine.data.local.entity.SongEntity
import com.launchpoint.stereosine.data.settings.AppSettingsRepository
import com.launchpoint.stereosine.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.stereosine.data.settings.LibraryScanSettingsRepository
import com.launchpoint.stereosine.data.settings.ResumeBehaviorSettingsRepository
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualBackupSerializationTest {
    @get:Rule val temp = TemporaryFolder()

    private class MemoryDocument(
        private val failWrite: Boolean = false,
        private val beforeRead: () -> Unit = {},
    ) : BackupFileHandle {
        private lateinit var written: ByteArrayOutputStream

        override fun openOutput(mode: String): OutputStream {
            if (failWrite) return object : OutputStream() {
                override fun write(b: Int) { throw IOException("fixture write failure") }
            }
            return ByteArrayOutputStream().also { written = it }
        }

        override fun openInput(): InputStream = object : FilterInputStream(bytes().inputStream()) {
            private var started = false

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!started) {
                    started = true
                    beforeRead()
                }
                return super.read(b, off, len)
            }
        }

        override fun delete(): Boolean = false

        fun backup(): StereoSineBackup {
            val decoded = WdbkReader().read(bytes().inputStream())
            assertEquals(decoded.error, BackupIntegrityStatus.VERIFIED, decoded.integrityStatus)
            return requireNotNull(decoded.backup)
        }

        private fun bytes() = written.toByteArray()
    }

    private inner class Fixture(scope: CoroutineScope, val db: StereoSineDatabase) {
        private val context = RuntimeEnvironment.getApplication()
        private val prefs = temp.root.resolve("fixture.preferences_pb")
        private val store = PreferenceDataStoreFactory.create(scope = scope) { prefs }
        private val appSettings = AppSettingsRepository(store)
        private val serializer = BackupExecutionSerializer()
        val captures = AtomicInteger()
        val snapshots = AtomicInteger()
        var beforeCapture: suspend () -> Unit = {}

        val manual = StereoSineBackupRepository(
            context, store, object : SongDao by db.songDao() {
                override suspend fun getAllSongsSnapshot(): List<SongEntity> {
                    captures.incrementAndGet()
                    beforeCapture()
                    return db.songDao().getAllSongsSnapshot()
                }
            },
            db.trackStatsDao(), db.importBaselineDao(), db.lyricsOverrideDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingBackupExtensionDao(), db.pendingTrackDao(),
            appSettings, HomeLayoutSettingsRepository(store), LibraryScanSettingsRepository(store),
            ResumeBehaviorSettingsRepository(store), serializer,
        )

        val automatic = AutoBackupRepository(context, appSettings, manual, serializer)

        fun recovery(
            applyPreferences: suspend () -> RecoveryPreferenceResult,
        ) = RecoveryRestoreOrchestrator(
            snapshots = object : SafetySnapshotProvider {
                override suspend fun createVerifiedSnapshot() = serializer.withSerializedBackup {
                    snapshots.incrementAndGet()
                    SafetySnapshotResult.Verified(
                        VerifiedSafetySnapshot(
                            path = "fixture", createdAtMs = 1, sizeBytes = 1, backupId = "fixture",
                            integrityFingerprint = "fixture", verification = BackupIntegrityStatus.VERIFIED,
                            songCount = 0, statsCount = 0, eventCount = 0, playlistCount = 0,
                        ),
                    )
                }
            },
            database = object : RecoveryDatabaseApplier {
                override suspend fun applyRecovery(backup: StereoSineBackup): StereoSineBackupImportApplyResult {
                    db.withTransaction {
                        db.playlistDao().insertPlaylist(PlaylistEntity(name = "Recovered", createdAt = 1, updatedAt = 1))
                    }
                    return StereoSineBackupImportApplyResult(
                        matchedTracks = 0, unmatchedTracks = 0, statsUpdated = 0,
                        restoreMode = BackupRestoreMode.RECOVERY,
                    )
                }
            },
            preferences = object : RecoveryPreferenceApplier {
                override suspend fun applyRecoveryPreferences(preferences: BackupPreferences?) = applyPreferences()
            },
            restoreLock = RestoreOperationLock(),
            backupExecutionSerializer = serializer,
        )

        suspend fun applySettings(): RecoveryPreferenceResult {
            appSettings.setCrossfadeDurationMs(6000L)
            return RecoveryPreferenceResult.Applied(false, false)
        }
    }

    // The export/auto entry points use this same dispatcher, so an UNDISPATCHED contender runs to its
    // first suspension before the assertions. Timeouts bound failures without timing the ordering.
    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StereoSineDatabase::class.java).build()
        val storeJob = Job()
        try {
            val fixture = Fixture(CoroutineScope(Dispatchers.IO + storeJob), db)
            withTimeout(30_000) { test(fixture) }
        } finally {
            storeJob.cancelAndJoin()
            db.close()
        }
    }

    private fun recoveryFirst(failPreferences: Boolean) = scenario { f ->
        val afterCommit = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restore = async {
            f.recovery {
                afterCommit.complete(Unit)
                release.await()
                if (failPreferences) RecoveryPreferenceResult.Failed("fixture preference failure")
                else f.applySettings()
            }.restore(RecoveryTestFixtures.v2Json())
        }
        afterCommit.await()
        val document = temp.newFile("manual.wdbk")
        val export = async(start = CoroutineStart.UNDISPATCHED) { f.manual.exportToUri(Uri.fromFile(document)) }
        assertEquals(0, f.captures.get())
        assertFalse(export.isCompleted)
        release.complete(Unit)
        val outcome = restore.await()
        if (failPreferences) assertTrue(outcome is RecoveryRestoreOutcome.PartialRecovery)
        else assertTrue(outcome is RecoveryRestoreOutcome.Success)
        export.await()
        val decoded = document.inputStream().use { WdbkReader().read(it) }
        assertEquals(decoded.error, BackupIntegrityStatus.VERIFIED, decoded.integrityStatus)
        val saved = requireNotNull(decoded.backup)
        assertEquals(listOf("Recovered"), saved.playlists.map { it.name })
        assertEquals(if (failPreferences) 0 else 6000, saved.preferences?.crossfadeDurationMs)
    }

    @Test fun recoveryFinishesBeforeManualExportCaptures() = recoveryFirst(failPreferences = false)

    @Test fun manualExportCapturesResolvedStateAfterPartialRecovery() = recoveryFirst(failPreferences = true)

    @Test fun recoveryAutomaticBackupAndDuplicateExportWaitForReadback() = scenario { f ->
        val verifying = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val document = MemoryDocument(beforeRead = {
            verifying.complete(Unit)
            check(release.await(15, TimeUnit.SECONDS)) { "Timed out at WDBK readback gate" }
        })
        val first = async { f.manual.exportToHandle(document) }
        verifying.await()
        val recovery = async(start = CoroutineStart.UNDISPATCHED) {
            f.recovery { f.applySettings() }.restore(RecoveryTestFixtures.v2Json())
        }
        val auto = async(start = CoroutineStart.UNDISPATCHED) { f.automatic.runIfDue() }
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { f.manual.exportToHandle(MemoryDocument()) }
        try {
            assertEquals(1, f.captures.get())
            assertEquals(0, f.snapshots.get())
            assertFalse(recovery.isCompleted)
            assertFalse(auto.isCompleted)
            assertFalse(duplicate.isCompleted)
            assertTrue(f.db.playlistDao().getAllPlaylistsSnapshot().isEmpty())
        } finally {
            release.countDown()
        }
        first.await()
        assertTrue(document.backup().playlists.isEmpty())
        assertTrue(recovery.await() is RecoveryRestoreOutcome.Success)
        assertEquals(AutoBackupRepository.Result.Skipped, auto.await())
        duplicate.await()
        assertEquals(2, f.captures.get())
    }

    @Test fun cancelledWaiterAndCancelledOwnerLeaveSerializerUsable() = scenario { f ->
        val capturing = CompletableDeferred<Unit>()
        f.beforeCapture = {
            capturing.complete(Unit)
            awaitCancellation()
        }
        val owner = async { f.manual.exportToHandle(MemoryDocument()) }
        capturing.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { f.manual.exportToHandle(MemoryDocument()) }
        assertEquals(1, f.captures.get())
        waiter.cancelAndJoin()
        owner.cancelAndJoin()
        assertTrue(waiter.isCancelled)
        assertTrue(owner.isCancelled)
        assertEquals(1, f.captures.get())
        f.beforeCapture = {}
        f.manual.exportToHandle(MemoryDocument())
        assertTrue(f.recovery { f.applySettings() }.restore(RecoveryTestFixtures.v2Json()) is RecoveryRestoreOutcome.Success)
    }

    @Test fun snapshotExceptionReleasesSerializer() = scenario { f ->
        f.beforeCapture = { throw IOException("fixture snapshot failure") }
        expectIoFailure { f.manual.exportToHandle(MemoryDocument()) }
        f.beforeCapture = {}
        f.manual.exportToHandle(MemoryDocument())
    }

    @Test fun writeExceptionReleasesSerializer() = scenario { f ->
        expectIoFailure { f.manual.exportToHandle(MemoryDocument(failWrite = true)) }
        f.manual.exportToHandle(MemoryDocument())
    }

    @Test fun verificationExceptionReleasesSerializer() = scenario { f ->
        expectIoFailure {
            f.manual.exportToHandle(MemoryDocument(beforeRead = { throw IOException("fixture read failure") }))
        }
        f.manual.exportToHandle(MemoryDocument())
    }

    private suspend fun expectIoFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected an IOException")
        } catch (_: IOException) {
            // The subsequent export demonstrates that the failed operation released ownership.
        }
    }
}
