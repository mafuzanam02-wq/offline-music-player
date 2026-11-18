package com.launchpoint.stereosine.data.backup

import com.launchpoint.stereosine.data.backup.wdbk.BackupDocumentIdentity
import com.launchpoint.stereosine.data.backup.wdbk.BackupFolderChild
import com.launchpoint.stereosine.data.backup.wdbk.BackupFolderListing
import com.launchpoint.stereosine.data.backup.wdbk.WdbkTestSupport
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderBackupDiscoveryTest {
    private val backup = RecoveryTestFixtures.v2Backup()
    private val owner = ManagedBackupGeneration.ownerFor(UUID.fromString(backup.sourceInstallationId))
    private val lineage = ManagedBackupGeneration.Lineage(owner)
    private val backupId = UUID.fromString(backup.backupId)
    private val newerId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    private val stamp = RecoveryTestFixtures.NOW
    private val good = child("good", "wavdrop-backup-2026-06-23.json", stamp)
    private val rejected = child("rejected", "stereosine-backup.wdbk", stamp + 1)

    @Test fun rejectedNewerCandidatesCannotHideAnIntegrityVerifiedBackup() = runBlocking {
        val valid = legacyJson(backup)
        val bytes = WdbkTestSupport.write(backup).first
        val unsupported = WdbkTestSupport.rezip(bytes) {
            WdbkTestSupport.mutateManifest(it) { manifest -> manifest.put("containerMajor", 9) }
        }
        for (newer in listOf(
            read(bytes.copyOf(bytes.size / 2)), read(unsupported), read("not backup".encodeToByteArray()),
            BackupDocumentReadResult.Rejected(BackupRejectReason.UNREADABLE),
        )) {
            val result = select(rejected to newer, good to valid)
            assertEquals(good, result.selected?.child)
            assertEquals(BackupIntegrityStatus.VERIFIED, result.selected?.integrity)
            assertTrue(result.warnings.isNotEmpty())
        }
    }

    @Test fun integrityWinsEvenWhenCoreCoverageIsPartialAndLegacyIsNewer() = runBlocking {
        val legacy = child("legacy", "wavdrop-backup.json", Long.MAX_VALUE)
        val unsignedLegacy = JSONObject(RecoveryTestFixtures.v1Json()).apply {
            remove("payloadSha256")
            put("exportedAt", Instant.ofEpochMilli(stamp + 1).toString())
        }
        val parsed = read(unsignedLegacy.toString().encodeToByteArray()) as BackupDocumentReadResult.StereoSine
        assertEquals(BackupIntegrityStatus.UNVERIFIED_LEGACY, parsed.result.integrityStatus)
        val partial = read(WdbkTestSupport.write(backup.copy(trackStats = emptyList(), listenEvents = emptyList())).first)
            as BackupDocumentReadResult.StereoSine
        assertTrue(partial.result.backup!!.trackStats.isEmpty())
        assertTrue(partial.result.backup!!.listenEvents.isEmpty())
        val selected = select(legacy to parsed, rejected to partial)
        assertEquals(rejected, selected.selected?.child)
        assertEquals(BackupIntegrityStatus.VERIFIED, selected.selected?.integrity)
        val fallback = select(legacy to parsed)
        assertEquals(BackupIntegrityStatus.UNVERIFIED_LEGACY, fallback.selected?.integrity)
        assertTrue(fallback.warnings.any { "unverified" in it })
    }

    @Test fun verifiedLineageOrderSurvivesClockRollback() = runBlocking {
        val (first, firstRead) = managed("first", 1, backupId, stamp)
        val (second, secondRead) = managed("second", 2, newerId, stamp - 600_000)
        assertEquals(second, select(first to firstRead, second to secondRead).selected?.child)
        val unrelated = good.copy(modifiedMillis = null)
        val result = select(first to firstRead, second to secondRead, unrelated to legacyJson(backup))
        assertEquals(unrelated, result.selected?.child)
    }

    @Test fun collisionsAndPayloadMismatchCannotClaimManagedOrder() = runBlocking {
        val (first, firstRead) = managed("first", 5, backupId, stamp)
        val (second, secondRead) = managed("second", 5, newerId, stamp - 600_000)
        val collided = select(first to firstRead, second to secondRead)
        assertEquals(first, collided.selected?.child)
        assertTrue(collided.warnings.any { "generation names" in it })
        val mismatched = second.copy(displayName = ManagedBackupGeneration(lineage, 9, backupId).fileName)
        val mismatch = select(first to firstRead, mismatched to secondRead)
        assertEquals(first, mismatch.selected?.child)
        assertTrue(mismatch.warnings.any { "generation names" in it })
        val wrongOwner = second.copy(displayName = ManagedBackupGeneration(lineage.copy(owner = "f".repeat(32)), 9, newerId).fileName)
        assertTrue(select(wrongOwner to secondRead).warnings.any { "generation names" in it })
    }

    @Test fun embeddedDateBeatsMisleadingFileTimesAndMissingDatesHaveStableFallback() = runBlocking {
        val old = rejected.copy(modifiedMillis = Long.MAX_VALUE)
        val newer = good.copy(modifiedMillis = null)
        assertEquals(
            newer, select(old to legacyJson(backup.copy(exportedAtMs = stamp - 1)), newer to legacyJson(backup)).selected?.child,
        )

        val parsed = legacyJson(backup) as BackupDocumentReadResult.StereoSine
        val noDate = parsed.copy(result = parsed.result.copy(backup = backup.copy(exportedAtMs = null)))
        assertEquals(old, select(newer to noDate, old to noDate).selected?.child)
        assertTrue(select(newer to noDate).warnings.any { "embedded export date" in it })
        val currentWithNoTimes = rejected.copy(modifiedMillis = null)
        assertEquals(currentWithNoTimes, select(newer to noDate, currentWithNoTimes to noDate).selected?.child)
    }

    @Test fun unrelatedDocumentsTmpAndDirectoriesAreSkipped() = runBlocking {
        val ignored = listOf(
            child("user", "private.wdbk", null), child("temp", "stereosine-backup.wdbk.tmp", null),
            child("dir", "stereosine-backup-2026-06-23.wdbk", null)
                .copy(mimeType = "vnd.android.document/directory"),
        )
        val result = FolderBackupDiscovery.select(BackupFolderListing.Safe(ignored)) {
            error("Unexpected read of ${it.displayName}")
        } as FolderBackupDiscovery.Result.Complete
        assertEquals(0, result.candidateCount)
        assertNull(result.selected)
    }

    @Test fun desktopAndInvalidCandidatesLeaveNoSelection() = runBlocking {
        val result = select(
            good to BackupDocumentReadResult.DesktopJson("{}"),
            rejected to read(byteArrayOf()),
        )
        assertEquals(2, result.candidateCount)
        assertNull(result.selected)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test fun overLimitAmbiguousAndUnavailableInventoriesDoNotOpenFiles() = runBlocking {
        val children = List(FolderBackupDiscovery.MAX_CANDIDATES + 1) {
            child("file-$it", "stereosine-backup-$it.wdbk", null)
        }
        assertTrue(FolderBackupDiscovery.select(BackupFolderListing.Safe(children)) { error("unexpected open") }
            is FolderBackupDiscovery.Result.Incomplete)
        assertEquals(
            FolderBackupDiscovery.Result.Incomplete("partial listing"),
            FolderBackupDiscovery.select(BackupFolderListing.Ambiguous("partial listing")) { error("unexpected open") },
        )
        assertEquals(
            FolderBackupDiscovery.Result.Unavailable,
            FolderBackupDiscovery.select(BackupFolderListing.Unavailable) { error("unexpected open") },
        )
        val valid = legacyJson(backup)
        val atLimit = FolderBackupDiscovery.select(BackupFolderListing.Safe(children.dropLast(1))) { valid }
            as FolderBackupDiscovery.Result.Complete
        assertNotNull(atLimit.selected)
        assertEquals(FolderBackupDiscovery.MAX_CANDIDATES, atLimit.candidateCount)
    }

    private fun managed(documentId: String, ordinal: Long, id: UUID, time: Long): Pair<BackupFolderChild, BackupDocumentReadResult> {
        val generation = ManagedBackupGeneration(lineage, ordinal, id)
        return child(documentId, generation.fileName, time) to
            read(WdbkTestSupport.write(backup.copy(backupId = id.toString(), exportedAtMs = time)).first)
    }

    private fun child(id: String, name: String, modified: Long?) =
        BackupFolderChild(BackupDocumentIdentity("test.provider", id), name, "application/octet-stream", modified)

    private fun read(bytes: ByteArray) = StereoSineBackupDocumentReader.read(bytes.inputStream(), nowMs = stamp + 1_000_000)
    private fun legacyJson(model: StereoSineBackup) = read(RecoveryTestFixtures.v2Json(model).encodeToByteArray())

    private suspend fun select(vararg files: Pair<BackupFolderChild, BackupDocumentReadResult>): FolderBackupDiscovery.Result.Complete {
        val byChild = files.toMap()
        return FolderBackupDiscovery.select(BackupFolderListing.Safe(byChild.keys.toList()), byChild::getValue)
            as FolderBackupDiscovery.Result.Complete
    }
}
