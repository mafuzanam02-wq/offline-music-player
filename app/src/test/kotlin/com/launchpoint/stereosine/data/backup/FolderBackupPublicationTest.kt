package com.launchpoint.stereosine.data.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.work.ListenableWorker
import com.launchpoint.stereosine.data.backup.wdbk.WdbkTestSupport
import com.launchpoint.stereosine.data.local.StereoSineDatabase
import com.launchpoint.stereosine.data.local.dao.SongDao
import com.launchpoint.stereosine.data.local.entity.SongEntity
import com.launchpoint.stereosine.data.settings.AppSettingsRepository
import com.launchpoint.stereosine.data.settings.AutoBackupInterval
import com.launchpoint.stereosine.data.settings.BackupFileMode
import com.launchpoint.stereosine.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.stereosine.data.settings.LibraryScanSettingsRepository
import com.launchpoint.stereosine.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderBackupPublicationTest {
    @get:Rule val temp = TemporaryFolder()

    private enum class Fault { NONE, CREATE, ALIAS, REWRITE, LIST, NO_SPACE, READ, WRONG_BYTES, HIDE_AFTER_READ }

    // Implements the DocumentsContract create/query/open protocol over test-only files.
    private class FixtureProvider(private val files: File) : ContentProvider() {
        data class Child(val name: String, val file: File)
        val children = linkedMapOf<String, Child>()
        val outputIds = mutableListOf<String>()
        val inputIds = mutableListOf<String>()
        val mtimes = mutableMapOf<String, Long>()
        var listingQueries = 0
        var listingLoading = false
        var listingUnavailable = false
        var onList = {}
        val deleteIds = mutableListOf<String>()
        var fault = Fault.NONE
        var creates = 0
        var onOutput = {}
        var onFinalListing: (Int) -> Unit = {}
        var hidden: String? = null
        var unreadable: String? = null
        private var newest: String? = null
        private var newestReads = 0
        val tree: Uri = DocumentsContract.buildTreeDocumentUri("backup.fixture", "root")

        fun put(id: String, name: String, bytes: ByteArray) {
            children[id] = Child(name, File(files, id).apply { writeBytes(bytes) })
        }

        override fun onCreate() = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:deleteDocument") {
                deleteIds += "deleteDocument"
                error("Unexpected deletion")
            }
            check(method == "android:createDocument")
            val arguments = requireNotNull(extras)
            val parent = requireNotNull(arguments.getParcelable("uri", Uri::class.java))
            assertEquals("root", DocumentsContract.getDocumentId(parent))
            assertEquals("application/octet-stream", arguments.getString(Document.COLUMN_MIME_TYPE))
            val requested = requireNotNull(arguments.getString(Document.COLUMN_DISPLAY_NAME))
            creates++
            if (fault == Fault.CREATE) return null
            val id = if (fault == Fault.ALIAS) children.keys.first() else "created-$creates"
            if (fault != Fault.ALIAS) {
                put(id, if (fault == Fault.REWRITE) "provider-renamed-$creates" else requested, byteArrayOf())
            }
            newest = id
            newestReads = 0
            return Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): MatrixCursor? {
            val columns = requireNotNull(projection)
            val isListing = uri.lastPathSegment == "children"
            if (isListing) {
                listingQueries++
                onList()
                if (listingUnavailable) return null
            }
            if (isListing && creates > 0 && fault == Fault.LIST) return null
            if (isListing && newestReads == 2) {
                newestReads++
                onFinalListing(creates)
            }
            val rows = if (isListing) children.filterKeys { it != hidden }
                else children.filterKeys { it == DocumentsContract.getDocumentId(uri) }
            return MatrixCursor(columns).apply {
                if (isListing && listingLoading) {
                    extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
                }
                rows.forEach { (id, child) ->
                    addRow(columns.map {
                        when (it) {
                            Document.COLUMN_DOCUMENT_ID -> id
                            Document.COLUMN_DISPLAY_NAME -> child.name
                            Document.COLUMN_MIME_TYPE -> "application/octet-stream"
                            Document.COLUMN_SIZE -> child.file.length()
                            Document.COLUMN_LAST_MODIFIED -> mtimes[id]
                            else -> null
                        }
                    })
                }
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = DocumentsContract.getDocumentId(uri)
            val child = children[id] ?: throw FileNotFoundException(id)
            if (mode != "r") {
                outputIds += id
                onOutput()
                if (fault == Fault.NO_SPACE) throw FileNotFoundException("fixture ENOSPC")
                return ParcelFileDescriptor.open(child.file, ParcelFileDescriptor.MODE_WRITE_ONLY)
            }
            inputIds += id
            if (id == unreadable) throw FileNotFoundException("fixture temporarily unreadable")
            if (id == newest && ++newestReads == 2) {
                when (fault) {
                    Fault.READ -> throw FileNotFoundException("fixture readback denied")
                    Fault.WRONG_BYTES -> return ParcelFileDescriptor.open(
                        children.getValue("old").file, ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                    Fault.HIDE_AFTER_READ -> hidden = id
                    else -> Unit
                }
            }
            return ParcelFileDescriptor.open(child.file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected insert")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Unexpected update")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deleteIds += DocumentsContract.getDocumentId(uri)
            error("Unexpected deletion")
        }
    }

    private inner class Fixture(scope: CoroutineScope, db: StereoSineDatabase) {
        val provider = FixtureProvider(temp.root)
        val oldBytes = WdbkTestSupport.write(RecoveryTestFixtures.v2Backup()).first
        private val context = RuntimeEnvironment.getApplication()
        private val parentJob = scope.coroutineContext[Job]
        private var storeJob = Job(parentJob)
        private var backingStore = newStore()
        var failReservationCreate = false
        var failReservationClear = false
        private val store = object : DataStore<Preferences> {
            override val data = flow { emitAll(backingStore.data) }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                backingStore.updateData { previous ->
                    val next = transform(previous)
                    val hadReservation = previous.hasReservation()
                    val hasReservation = next.hasReservation()
                    if ((!hadReservation && hasReservation && failReservationCreate) ||
                        (hadReservation && !hasReservation && failReservationClear)
                    ) throw IOException("fixture reservation persistence failure")
                    next
                }
        }

        private fun Preferences.hasReservation() =
            asMap().keys.any { it.name.startsWith("backup_create_reservation_") }

        private fun newStore() = PreferenceDataStoreFactory.create(scope = CoroutineScope(storeJob + Dispatchers.IO)) {
            temp.root.resolve("backup.preferences_pb")
        }

        suspend fun reloadStore() {
            storeJob.cancelAndJoin()
            storeJob = Job(parentJob)
            backingStore = newStore()
        }
        val settings = AppSettingsRepository(store)
        var captures = 0
        var beforeCapture: suspend () -> Unit = {}
        private val serializer = BackupExecutionSerializer()
        private val manual = StereoSineBackupRepository(
            context, store, object : SongDao by db.songDao() {
                override suspend fun getAllSongsSnapshot(): List<SongEntity> {
                    captures++
                    beforeCapture()
                    return db.songDao().getAllSongsSnapshot()
                }
            }, db.trackStatsDao(), db.importBaselineDao(), db.lyricsOverrideDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingBackupExtensionDao(), db.pendingTrackDao(),
            settings, HomeLayoutSettingsRepository(store), LibraryScanSettingsRepository(store),
            ResumeBehaviorSettingsRepository(store), serializer,
        )

        init {
            provider.attachInfo(context, ProviderInfo().apply {
                authority = "backup.fixture"
                exported = true
                grantUriPermissions = true
                applicationInfo = context.applicationInfo
                packageName = context.packageName
                name = FixtureProvider::class.java.name
            })
            ShadowContentResolver.registerProviderInternal("backup.fixture", provider)
        }

        fun repository() = AutoBackupRepository(context, settings, manual, serializer)
        fun verifier() = BackupVerificationRepository(context, settings)

        fun old() = provider.put("old", "wavdrop-backup.wdbk", oldBytes)

        fun assertOldUnchanged() {
            assertArrayEquals(oldBytes, provider.children.getValue("old").file.readBytes())
            assertFalse("old" in provider.outputIds)
            assertTrue(provider.deleteIds.isEmpty())
        }

        suspend fun managedFixture(
            id: String, date: LocalDate?, ordinal: Long, valid: Boolean = true, foreign: Boolean = false,
            misleading: Boolean = false, exportedAtMs: Long = System.currentTimeMillis(),
        ) {
            val localInstallation = manual.getOrCreateInstallationId()
            val payloadInstallation = if (foreign || misleading) "00000000-0000-0000-0000-0000000000b2" else localInstallation
            val owner = ManagedBackupGeneration.ownerFor(UUID.fromString(if (foreign) payloadInstallation else localInstallation))
            val backupId = UUID.nameUUIDFromBytes(id.encodeToByteArray())
            val name = ManagedBackupGeneration(ManagedBackupGeneration.Lineage(owner, date), ordinal, backupId).fileName
            val bytes = if (valid) WdbkTestSupport.write(
                RecoveryTestFixtures.v2Backup(backupId = backupId.toString()).copy(
                    sourceInstallationId = payloadInstallation, exportedAtMs = exportedAtMs,
                )
            ).first else byteArrayOf(0x50, 0x4b)
            provider.put(id, name, bytes)
        }
    }

    private fun scenario(timeoutMillis: Long = 30_000, test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StereoSineDatabase::class.java).build()
        val job = Job()
        try {
            val fixture = Fixture(CoroutineScope(job + Dispatchers.IO), db)
            fixture.settings.setAutoBackupFolderUri(fixture.provider.tree.toString())
            withTimeout(timeoutMillis) { test(fixture) }
        } finally {
            job.cancelAndJoin()
            db.close()
        }
    }

    @Test fun firstManualRunPublishesOneVerifiedDocumentAndUpdatesTimestamp() = scenario { f ->
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        assertEquals(1, f.captures)
        assertEquals(1, f.provider.creates)
        assertEquals(listOf("created-1"), f.provider.outputIds)
        val child = f.provider.children.getValue("created-1")
        val generation = requireNotNull(ManagedBackupGeneration.parse(child.name))
        assertEquals(LocalDate.now(), generation.lineage.date)
        val decoded = WdbkTestSupport.read(child.file.readBytes())
        assertEquals(decoded.error, BackupIntegrityStatus.VERIFIED, decoded.integrityStatus)
        assertTrue(generation.matchesPayload(requireNotNull(decoded.backup)))
        assertTrue(f.settings.lastAutoBackupAtMillis.first() > 0)
        assertTrue(f.provider.deleteIds.isEmpty())
    }

    @Test fun createFailureAndAliasedResultNeverOpenAnExistingBackup() = scenario { f ->
        f.old()
        for (fault in listOf(Fault.CREATE, Fault.ALIAS)) {
            f.provider.fault = fault
            assertTrue(f.repository().runNow() is AutoBackupRepository.Result.Failure)
            assertTrue(f.provider.outputIds.isEmpty())
            assertEquals(0L, f.settings.lastAutoBackupAtMillis.first())
            f.assertOldUnchanged()
        }
    }

    @Test fun rewrittenCreationStaysBlockedAcrossRepositoryRecreation() = scenario { f ->
        f.old()
        f.provider.fault = Fault.REWRITE
        assertTrue(f.repository().runNow() is AutoBackupRepository.Result.Failure)
        f.provider.fault = Fault.NONE
        val blocked = f.repository().runNow()
        assertTrue(blocked is AutoBackupRepository.Result.FolderUnavailable)
        assertTrue(blocked.toWorkerResult() is ListenableWorker.Result.Success)
        assertEquals(1, f.provider.creates)
        assertTrue(f.provider.outputIds.isEmpty())
        f.assertOldUnchanged()
        // Simulate the user moving the unexpected document out of the folder.
        f.provider.children.remove("created-1")
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        f.assertOldUnchanged()
    }

    @Test fun inaccessiblePostCreateListingPreventsAnyWriteAndCanonicalArtifactCanBeReconciled() = scenario { f ->
        f.old()
        f.provider.fault = Fault.LIST
        assertTrue(f.repository().runNow() is AutoBackupRepository.Result.FolderUnavailable)
        assertTrue(f.provider.outputIds.isEmpty())
        assertEquals(0L, f.settings.lastAutoBackupAtMillis.first())
        f.provider.fault = Fault.NONE
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        assertEquals(listOf("created-2"), f.provider.outputIds)
        f.assertOldUnchanged()
    }

    @Test fun noSpaceAndFailedReadbackConsumeTheBudgetWithoutRetryingTheOutput() = scenario { f ->
        f.old()
        f.provider.fault = Fault.NO_SPACE
        assertTrue(f.repository().runNow() is AutoBackupRepository.Result.Failure)
        f.provider.fault = Fault.READ
        assertTrue(f.repository().runNow() is AutoBackupRepository.Result.Failure)
        // Even a complete payload with a failed readback stays in the inventory.
        f.provider.children.getValue("created-2").file.writeBytes(byteArrayOf(0x50, 0x4b))
        f.provider.fault = Fault.NONE
        f.reloadStore()
        val blocked = f.repository().runNow() as AutoBackupRepository.Result.FolderUnavailable
        assertTrue(blocked.message.contains("Two local backup attempts"))
        assertEquals(2, f.provider.creates)
        assertEquals(listOf("created-1", "created-2"), f.provider.outputIds)
        assertEquals(0L, f.settings.lastAutoBackupAtMillis.first())
        f.assertOldUnchanged()
    }

    @Test fun readableWrongReceiptAndMissingFinalListingCannotReportSuccess() = scenario { f ->
        f.old()
        f.provider.fault = Fault.WRONG_BYTES
        assertTrue(f.repository().runNow() is AutoBackupRepository.Result.Failure)
        f.provider.fault = Fault.HIDE_AFTER_READ
        assertTrue(f.repository().runNow() is AutoBackupRepository.Result.Failure)
        assertEquals(0L, f.settings.lastAutoBackupAtMillis.first())
        assertEquals(listOf("created-1", "created-2"), f.provider.outputIds)
        f.assertOldUnchanged()
    }

    @Test fun datedAndReplacementScopesRetainLegacyForeignAndHealthyDifferentDates() = scenario { f ->
        f.old()
        f.managedFixture("yesterday", LocalDate.now().minusDays(1), 1)
        f.managedFixture("today", LocalDate.now(), 7)
        f.managedFixture("foreign", LocalDate.now(), 90, foreign = true)
        val saved = f.provider.children.mapValues { (_, child) -> child.file.readBytes() }

        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        val secondToday = ManagedBackupGeneration.parse(f.provider.children.getValue("created-1").name)
        assertEquals(8L, secondToday?.ordinal)
        f.settings.setBackupFileMode(BackupFileMode.REPLACE_PREVIOUS)
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        val replacement = ManagedBackupGeneration.parse(f.provider.children.getValue("created-3").name)
        assertNotNull(replacement)
        assertEquals(null, replacement?.lineage?.date)
        assertEquals(2L, replacement?.ordinal)
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        assertEquals(4, f.provider.creates)
        saved.forEach { (id, bytes) -> assertArrayEquals(bytes, f.provider.children.getValue(id).file.readBytes()) }
        assertEquals(listOf("created-1", "created-2", "created-3", "created-4"), f.provider.outputIds)
        f.assertOldUnchanged()
    }

    @Test fun copiedMisleadingAndInterruptedArtifactsArePreservedAndCounted() = scenario { f ->
        f.old()
        f.managedFixture("copied", null, 1, misleading = true)
        f.managedFixture("partial", LocalDate.now(), 1, valid = false)
        val saved = f.provider.children.mapValues { (_, child) -> child.file.readBytes() }
        assertTrue(f.repository().runNow() is AutoBackupRepository.Result.FolderUnavailable)
        assertEquals(0, f.provider.creates)
        saved.forEach { (id, bytes) -> assertArrayEquals(bytes, f.provider.children.getValue(id).file.readBytes()) }
        assertTrue(f.provider.outputIds.isEmpty())
        f.assertOldUnchanged()
    }

    @Test fun cancellationPropagatesDuringCaptureOutputAndFinalListingWithoutUpdatingSuccess() = scenario { f ->
        f.old()
        val capturing = CompletableDeferred<Unit>()
        f.beforeCapture = { capturing.complete(Unit); awaitCancellation() }
        val capture = async { f.repository().runNow() }
        capturing.await()
        capture.cancelAndJoin()
        assertEquals(0, f.provider.creates)
        f.beforeCapture = {}

        for (atFinalListing in listOf(false, true)) {
            val reached = CompletableDeferred<Unit>()
            val resume = java.util.concurrent.CountDownLatch(1)
            val pause = {
                reached.complete(Unit)
                check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            val attempt = f.provider.creates + 1
            if (atFinalListing) {
                f.provider.onFinalListing = { if (it == attempt) pause() }
            } else f.provider.onOutput = pause
            val exporting = async { f.repository().runNow() }
            reached.await()
            exporting.cancel()
            resume.countDown()
            exporting.join()
            assertTrue(exporting.isCancelled)
            assertEquals(0L, f.settings.lastAutoBackupAtMillis.first())
            f.provider.onOutput = {}
            f.provider.onFinalListing = {}
        }
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        f.assertOldUnchanged()
    }

    @Test fun automaticDueFailureRetriesAndOnlyVerifiedSuccessAdvancesTimestamp() = scenario { f ->
        f.old()
        f.settings.setAutoBackupInterval(AutoBackupInterval.DAILY)
        f.provider.fault = Fault.NO_SPACE
        assertTrue(f.repository().runIfDue().toWorkerResult() is ListenableWorker.Result.Retry)
        assertEquals(0L, f.settings.lastAutoBackupAtMillis.first())
        f.provider.fault = Fault.NONE
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runIfDue())
        assertEquals(AutoBackupRepository.Result.NotDue, f.repository().runIfDue())
        f.assertOldUnchanged()
    }

    @Test fun tenHealthyUndatedGenerationsRetainTheirBytes() = publishTen(BackupFileMode.REPLACE_PREVIOUS)

    @Test fun tenHealthySameDayGenerationsRetainTheirBytes() = publishTen(BackupFileMode.DATED)

    private fun publishTen(mode: BackupFileMode) = scenario(90_000) { f ->
        f.old()
        f.settings.setBackupFileMode(mode)
        repeat(10) { index ->
            publishAndCheckPriorBytes(f, index + 1)
            val child = f.provider.children.getValue("created-${index + 1}")
            val generation = requireNotNull(ManagedBackupGeneration.parse(child.name))
            assertEquals(index + 1L, generation.ordinal)
            assertEquals(if (mode == BackupFileMode.DATED) LocalDate.now() else null, generation.lineage.date)
        }
        assertEquals(mode, f.settings.backupFileMode.first())
        f.assertOldUnchanged()
    }

    @Test fun thirtyActualPublicationsAcrossModesAndDatedScopesPassFormerPreflightLimit() = scenario(120_000) { f ->
        f.old()
        f.managedFixture("earlier-date", LocalDate.now().minusDays(1), 1)
        f.managedFixture("later-clock", LocalDate.now(), 7, exportedAtMs = System.currentTimeMillis() + 60_000)
        f.provider.put("fixed", "stereosine-backup.wdbk", f.oldBytes)
        f.provider.put("user-copy", "my personal copy.wdbk", f.oldBytes)
        f.managedFixture("foreign", null, 1, foreign = true, valid = false)
        repeat(30) { index ->
            f.settings.setBackupFileMode(if (index % 2 == 0) BackupFileMode.DATED else BackupFileMode.REPLACE_PREVIOUS)
            publishAndCheckPriorBytes(f, index + 1)
        }
        assertEquals(8L, ManagedBackupGeneration.parse(f.provider.children.getValue("created-1").name)?.ordinal)
        assertEquals(22L, ManagedBackupGeneration.parse(f.provider.children.getValue("created-29").name)?.ordinal)
        assertEquals(15L, ManagedBackupGeneration.parse(f.provider.children.getValue("created-30").name)?.ordinal)
        assertEquals(List(30) { "created-${it + 1}" }, f.provider.outputIds)
        f.assertOldUnchanged()
    }

    @Test fun transientUnreadableAndCorruptLocalAttemptsBlockUntilOneRecovers() = scenario { f ->
        f.old()
        f.managedFixture("read-later", null, 1)
        f.managedFixture("corrupt", null, 2, valid = false)
        f.provider.unreadable = "read-later"
        val blocked = f.repository().runNow()
        assertTrue(blocked is AutoBackupRepository.Result.FolderUnavailable)
        assertEquals(0, f.provider.creates)
        f.reloadStore()
        f.provider.unreadable = null
        publishAndCheckPriorBytes(f, 1)
        f.assertOldUnchanged()
    }

    @Test fun interruptedVerifiedDocumentCanReconcileAfterReservationClearFails() = scenario { f ->
        f.old()
        f.failReservationCreate = true
        assertTrue(f.repository().runNow() is AutoBackupRepository.Result.Failure)
        assertEquals(0, f.provider.creates)
        assertEquals(0L, f.settings.lastAutoBackupAtMillis.first())
        f.failReservationCreate = false

        f.failReservationClear = true
        assertTrue(f.repository().runNow() is AutoBackupRepository.Result.Failure)
        assertEquals(0L, f.settings.lastAutoBackupAtMillis.first())
        assertEquals(BackupIntegrityStatus.VERIFIED,
            WdbkTestSupport.read(f.provider.children.getValue("created-1").file.readBytes()).integrityStatus)
        f.failReservationClear = false
        f.reloadStore()
        publishAndCheckPriorBytes(f, 2)
        assertTrue(f.settings.lastAutoBackupAtMillis.first() > 0)
        f.assertOldUnchanged()
    }

    @Test fun qualifiedInventoryWarnsBeforeCapacityAndRefusesAtCapacityWithoutCreating() = scenario { f ->
        f.old()
        repeat(FolderBackupPublisher.CAPACITY_WARNING_CHILDREN - 2) {
            f.provider.put("unrelated-$it", "unrelated-$it.txt", byteArrayOf())
        }
        f.settings.setAutoBackupInterval(AutoBackupInterval.DAILY)
        assertEquals(AutoBackupRepository.Result.SuccessNearCapacity, f.repository().runIfDue())
        assertEquals(com.launchpoint.stereosine.data.settings.AutoBackupCheckResult.SUCCESS_NEAR_CAPACITY,
            f.settings.lastAutoBackupResult.first())
        val lastSuccess = f.settings.lastAutoBackupAtMillis.first()
        assertTrue(lastSuccess > 0)
        val remaining = FolderBackupPublisher.MAX_PUBLICATION_CHILDREN - f.provider.children.size
        repeat(remaining) {
            f.provider.put("capacity-$it", "capacity-$it.txt", byteArrayOf())
        }
        assertEquals(AutoBackupRepository.Result.FolderAtCapacity, f.repository().runNow())
        assertEquals(1, f.provider.creates)
        assertEquals(listOf("created-1"), f.provider.outputIds)
        assertEquals(lastSuccess, f.settings.lastAutoBackupAtMillis.first())
        f.assertOldUnchanged()
    }

    private suspend fun publishAndCheckPriorBytes(f: Fixture, attempt: Int) {
        val saved = f.provider.children.mapValues { (_, child) -> child.file.readBytes() }
        val outputsBefore = f.provider.outputIds.size
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        assertEquals(listOf("created-$attempt"), f.provider.outputIds.drop(outputsBefore))
        val child = f.provider.children.getValue("created-$attempt")
        val decoded = WdbkTestSupport.read(child.file.readBytes())
        assertEquals(decoded.error, BackupIntegrityStatus.VERIFIED, decoded.integrityStatus)
        assertTrue(requireNotNull(ManagedBackupGeneration.parse(child.name)).matchesPayload(requireNotNull(decoded.backup)))
        saved.forEach { (id, bytes) -> assertArrayEquals(bytes, f.provider.children.getValue(id).file.readBytes()) }
        assertTrue(f.provider.deleteIds.isEmpty())
    }

    @Test fun verificationFallsBackPastCorruptEmptyUnreadableAndUnsupportedNewestFilesWithoutReopening() = scenario { f ->
        val olderBytes = WdbkTestSupport.write(WdbkTestSupport.fullBackup()).first
        val unsupported = WdbkTestSupport.rezip(olderBytes) {
            WdbkTestSupport.mutateManifest(it) { manifest -> manifest.put("containerMajor", 9) }
        }
        f.provider.put("older-valid", "wavdrop-backup.wdbk", olderBytes)
        f.provider.mtimes["older-valid"] = 100
        for ((name, bytes) in listOf(
            "corrupt" to olderBytes.copyOf(olderBytes.size / 2),
            "empty" to byteArrayOf(),
            "unreadable" to olderBytes,
            "unsupported" to unsupported,
        )) {
            f.provider.put("newer", "stereosine-backup.wdbk", bytes)
            f.provider.mtimes["newer"] = 200
            f.provider.unreadable = if (name == "unreadable") "newer" else null
            f.provider.inputIds.clear()
            f.provider.listingQueries = 0
            val result = f.verifier().verifyLatestBackup()
            assertEquals(name, "wavdrop-backup.wdbk", result.fileName)
            assertEquals(BackupIntegrityStatus.VERIFIED, result.integrityStatus)
            assertEquals(VerificationStatus.VERIFIED, result.status)
            assertEquals(olderBytes.size.toLong(), result.fileSizeBytes)
            assertTrue(result.trackCount > 0 && result.eventCount > 0 && result.hasPreferences)
            assertTrue(result.warnings.any { "invalid" in it })
            val search = if (name == "unreadable") VerificationSearchStatus.INCOMPLETE else VerificationSearchStatus.COMPLETE
            assertEquals(search, result.searchStatus)
            assertEquals(listOf("older-valid", "newer"), f.provider.inputIds)
            assertEquals(1, f.provider.listingQueries)
        }
        assertTrue(f.provider.outputIds.isEmpty() && f.provider.deleteIds.isEmpty())
    }

    @Test fun verificationDistinguishesCoverageLegacyEmptyInvalidAndUnavailableListings() = scenario { f ->
        val legacy = org.json.JSONObject(RecoveryTestFixtures.v1Json()).apply { remove("payloadSha256") }
            .toString().encodeToByteArray()
        assertEquals(VerificationStatus.NO_BACKUP_FOUND, f.verifier().verifyLatestBackup().status)
        f.provider.put("legacy", "wavdrop-backup.json", legacy)
        val fallback = f.verifier().verifyLatestBackup()
        assertEquals(BackupIntegrityStatus.UNVERIFIED_LEGACY, fallback.integrityStatus)
        assertTrue(fallback.warnings.any { "unverified" in it })

        f.old() // Verified WDBK with partial data coverage, including no listening events.
        val partial = f.verifier().verifyLatestBackup()
        assertEquals(BackupIntegrityStatus.VERIFIED, partial.integrityStatus)
        assertEquals(VerificationStatus.PARTIAL, partial.status)
        assertEquals("wavdrop-backup.wdbk", partial.fileName)
        assertTrue(partial.warnings.any { "Listening history is missing" in it })

        f.provider.children.clear()
        f.provider.put("invalid", "stereosine-backup.wdbk", byteArrayOf())
        val invalid = f.verifier().verifyLatestBackup()
        assertEquals(VerificationStatus.FAILED, invalid.status)
        assertEquals(VerificationSearchStatus.COMPLETE, invalid.searchStatus)
        assertTrue(invalid.errors.any { "empty" in it })
        f.provider.listingUnavailable = true
        assertEquals(VerificationSearchStatus.UNAVAILABLE, f.verifier().verifyLatestBackup().searchStatus)
        f.provider.listingUnavailable = false
        f.provider.listingLoading = true
        val incomplete = f.verifier().verifyLatestBackup()
        assertEquals(VerificationSearchStatus.INCOMPLETE, incomplete.searchStatus)
        assertEquals(VerificationStatus.FAILED, incomplete.status)
    }

    @Test fun verificationScansSeventeenAndSixtyFourCandidatesOnceAndRejectsAnOverflowListing() = scenario(60_000) { f ->
        for (count in listOf(17, 64)) {
            repeat(count) { index -> f.managedFixture("ordinal-$index", null, index + 1L) }
            f.provider.inputIds.clear()
            f.provider.listingQueries = 0
            val start = System.nanoTime()
            val result = f.verifier().verifyLatestBackup()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertEquals(f.provider.children.getValue("ordinal-${count - 1}").name, result.fileName)
            assertEquals(VerificationSearchStatus.COMPLETE, result.searchStatus)
            assertEquals(List(count) { "ordinal-$it" }, f.provider.inputIds)
            assertEquals(1, f.provider.listingQueries)
            println("VERIFY_IO candidates=$count opens=${f.provider.inputIds.size} bytes=${f.provider.children.values.sumOf { it.file.length() }} elapsedMs=$elapsedMs")
        }
        f.provider.inputIds.clear()
        f.provider.put("unrelated", "photo.jpg", byteArrayOf(1))
        val overflow = f.verifier().verifyLatestBackup()
        assertEquals(VerificationSearchStatus.INCOMPLETE, overflow.searchStatus)
        assertTrue(f.provider.inputIds.isEmpty())
        assertTrue(f.provider.outputIds.isEmpty() && f.provider.deleteIds.isEmpty())
    }

    @Test fun verificationUsesOrdinalAcrossClockRollbackAndExportTimeAcrossLineages() = scenario { f ->
        val now = System.currentTimeMillis()
        f.managedFixture("first", null, 1, exportedAtMs = now)
        f.managedFixture("rolled-back", null, 2, exportedAtMs = now - 2_000)
        f.provider.mtimes["first"] = Long.MAX_VALUE
        assertEquals(f.provider.children.getValue("rolled-back").name, f.verifier().verifyLatestBackup().fileName)
        f.managedFixture("dated", LocalDate.now(), 1, exportedAtMs = now - 1_000)
        val result = f.verifier().verifyLatestBackup()
        assertEquals(f.provider.children.getValue("dated").name, result.fileName)
        assertEquals(now - 1_000, result.backupTimestampMillis)
        assertFalse(result.timestampIsFileTime)
    }

    @Test fun backUpNowThenVerificationUsesItsActualPublishedName() = scenario { f ->
        f.old()
        assertEquals(AutoBackupRepository.Result.Success, f.repository().runNow())
        val saved = f.provider.children.getValue("created-1")
        f.provider.inputIds.clear()
        assertEquals(saved.name, f.verifier().verifyLatestBackup().fileName)
        assertEquals(listOf("old", "created-1"), f.provider.inputIds)
        f.assertOldUnchanged()
    }

    @Test fun verificationCancelsDuringListingAndInterruptibleStreamRead() = scenario { f ->
        f.old()
        val enteredListing = CompletableDeferred<Unit>()
        f.provider.onList = {
            enteredListing.complete(Unit)
            Thread.sleep(10_000)
        }
        val listing = async { f.verifier().verifyLatestBackup() }
        enteredListing.await()
        listing.cancelAndJoin()
        assertTrue(listing.isCancelled)
        assertTrue(f.provider.inputIds.isEmpty())
        f.provider.onList = {}

        val enteredRead = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val uri = DocumentsContract.buildDocumentUriUsingTree(f.provider.tree, "old")
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().contentResolver)
            .registerInputStreamSupplier(uri) {
                object : java.io.InputStream() {
                    override fun read(): Int {
                        enteredRead.complete(Unit)
                        Thread.sleep(10_000)
                        return -1
                    }
                    override fun close() { closed.complete(Unit) }
                }
            }
        val reading = async { f.verifier().verifyLatestBackup() }
        enteredRead.await()
        reading.cancelAndJoin()
        assertTrue(reading.isCancelled)
        assertTrue(closed.isCompleted)
        assertTrue(f.provider.outputIds.isEmpty() && f.provider.deleteIds.isEmpty())
    }

    @Test fun pacedVerificationMeasuresBytesAndKeepsOneOpenPerDocument() = scenario { f ->
        val random = ByteArray(512 * 1024).also { java.util.Random(4).nextBytes(it) }
        val text = java.util.Base64.getEncoder().encodeToString(random)
        val model = WdbkTestSupport.fullBackup().copy(
            lyricsOverrides = listOf(BackupLyricsOverride(1L, "content://media/1", text, 1L)),
        )
        val bytes = WdbkTestSupport.write(model).first
        val inputs = mutableListOf<String>()
        var bytesRead = 0
        repeat(4) { index ->
            val id = "slow-$index"
            f.provider.put(id, "stereosine-backup-$index.wdbk", bytes)
            val uri = DocumentsContract.buildDocumentUriUsingTree(f.provider.tree, id)
            org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().contentResolver)
                .registerInputStreamSupplier(uri) {
                    inputs += id
                    Thread.sleep(20) // Controlled simulated provider open delay.
                    object : java.io.FilterInputStream(bytes.inputStream()) {
                        private val started = System.nanoTime()
                        private var consumed = 0L
                        private fun pace(count: Int) {
                            if (count <= 0) return
                            bytesRead += count
                            consumed += count
                            val delay = consumed * 1_000_000_000L / (2 * 1024 * 1024) - (System.nanoTime() - started)
                            if (delay > 0) java.util.concurrent.locks.LockSupport.parkNanos(delay)
                        }
                        override fun read(): Int = super.read().also { if (it >= 0) pace(1) }
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            super.read(b, off, len).also(::pace)
                    }
                }
        }
        val start = System.nanoTime()
        val result = f.verifier().verifyLatestBackup()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(BackupIntegrityStatus.VERIFIED, result.integrityStatus)
        assertEquals(List(4) { "slow-$it" }, inputs)
        assertEquals(bytes.size * 4, bytesRead)
        println("VERIFY_IO candidates=4 opens=${inputs.size} bytes=$bytesRead elapsedMs=$elapsedMs simulatedOpenMs=20 simulatedBytesPerSecond=2097152")
    }
}
