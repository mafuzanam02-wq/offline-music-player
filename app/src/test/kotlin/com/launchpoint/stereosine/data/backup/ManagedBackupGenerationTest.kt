package com.launchpoint.stereosine.data.backup

import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedBackupGenerationTest {
    private val installation = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    private val owner = ManagedBackupGeneration.ownerFor(installation)
    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val nextId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    private val replace = ManagedBackupGeneration.Lineage(owner)
    private val dated = replace.copy(date = LocalDate.of(2028, 2, 29))

    @Test fun canonicalNamesRoundTripForBothModes() {
        assertEquals(owner, ManagedBackupGeneration.ownerFor(installation))
        assertEquals(32, owner.length)
        for (lineage in listOf(replace, dated)) {
            val generation = ManagedBackupGeneration(lineage, Long.MAX_VALUE, id)
            assertEquals(generation, ManagedBackupGeneration.parse(generation.fileName))
            assertTrue(BackupFileNames.isBackupFileName(generation.fileName))
            assertFalse(generation.fileName.contains(installation.toString()))
        }
    }

    @Test fun malformedAndNonCanonicalNamesAreRejected() {
        val name = ManagedBackupGeneration(dated, 7, id).fileName
        for (invalid in listOf(
            name.replace("-g1-", "-g2-"), name.replace(owner, "0"), name.replace(owner, "z".repeat(32)),
            name.replace("-d-", "-r-"), name.replace("-d-", "-x-"),
            name.replace("2028-02-29", "2027-02-29"), name.replace("2028-02-29", "0000-02-29"),
            name.replace("2028-02-29", "2028-2-29"), name.replace("-7-", "-0-"),
            name.replace("-7-", "-07-"), name.replace("-7-", "--7-"),
            name.replace("-7-", "-9223372036854775808-"),
            name.replace(id.toString(), "1-2-3-4-a1"), name.uppercase(), "$name.tmp",
        )) assertNull(invalid, ManagedBackupGeneration.parse(invalid))
    }

    @Test fun allocationUsesVisibleOrdinalsAndRejectsCollisionsAndOverflow() {
        val old = ManagedBackupGeneration(replace, 20, id)
        val otherDate = ManagedBackupGeneration(dated, 99, UUID.fromString("00000000-0000-0000-0000-0000000000a3"))
        assertEquals(21L, ManagedBackupGeneration.next(replace, nextId, listOf(old.fileName, otherDate.fileName))?.ordinal)
        assertNull(ManagedBackupGeneration.next(replace, id, listOf(old.fileName)))
        assertNull(ManagedBackupGeneration.next(replace, nextId, listOf(old.fileName, old.fileName)))
        assertNull(ManagedBackupGeneration.next(replace, nextId, listOf(old.fileName, old.copy(ordinal = 21).fileName)))
        assertNull(ManagedBackupGeneration.next(replace, nextId, listOf(old.copy(ordinal = Long.MAX_VALUE).fileName)))
        assertEquals(1L, ManagedBackupGeneration.next(dated, nextId, listOf(old.fileName))?.ordinal)
    }

    @Test fun historicalAndManualNamesAreRecognizedWithoutBeingManaged() {
        listOf("stereosine-backup.wdbk", "stereosine-backup-2026-11-18.wdbk", "wavdrop-backup.json").forEach {
            assertTrue(BackupFileNames.isBackupFileName(it))
            assertNull(ManagedBackupGeneration.parse(it))
        }
        assertFalse(BackupFileNames.isBackupFileName("my music.wdbk"))
        assertFalse(BackupFileNames.isBackupFileName("stereosine-backup.wdbk.tmp"))
    }
}
