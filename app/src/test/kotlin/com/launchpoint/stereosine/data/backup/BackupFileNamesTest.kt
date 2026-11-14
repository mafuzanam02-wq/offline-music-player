package com.launchpoint.stereosine.data.backup

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BRAND-03: new backups use StereoSine names; legacy Wavdrop names stay recognised; selection is deterministic. */
class BackupFileNamesTest {

    private data class F(val name: String, val modified: Long)

    private fun pick(vararg f: F) = BackupFileNames.preferNewer(f.toList(), { it.name }, { it.modified })

    @Test fun `new fixed and dated names use the StereoSine prefix and ISO date`() {
        assertEquals("stereosine-backup.wdbk", BackupFileNames.fixedName())
        assertEquals("stereosine-backup-2026-10-07.wdbk", BackupFileNames.datedName(LocalDate.of(2026, 10, 7)))
    }

    @Test fun `legacy undated and dated names stay recognised`() {
        listOf("wavdrop-backup.wdbk", "wavdrop-backup-2026-10-07.wdbk", "wavdrop-backup.json", "wavdrop-backup-2026-06-01.json")
            .forEach { assertTrue(it, BackupFileNames.isBackupFileName(it)) }
    }

    @Test fun `current names are recognised`() {
        listOf("stereosine-backup.wdbk", "stereosine-backup-2026-10-07.wdbk", "stereosine-backup.json")
            .forEach { assertTrue(it, BackupFileNames.isBackupFileName(it)) }
    }

    @Test fun `generated names are always recognised and never temp-like`() {
        assertTrue(BackupFileNames.isBackupFileName(BackupFileNames.fixedName()))
        assertTrue(BackupFileNames.isBackupFileName(BackupFileNames.datedName(LocalDate.of(2027, 1, 2))))
        assertFalse(BackupFileNames.isBackupFileName(BackupFileNames.fixedName() + ".tmp"))
    }

    @Test fun `malformed or unrelated names are rejected`() {
        listOf(
            null, "", "stereosine-backup", "stereosine-backup.txt", "stereosine-backup.zip", "stereosine-backup.wdbk.tmp",
            "wavdrop-backup.wdbk.tmp", "stereosine.wdbk", "my-stereosine-backup.wdbk", "backup.wdbk", "pre-recovery-latest.json",
        ).forEach { assertFalse(it ?: "null", BackupFileNames.isBackupFileName(it)) }
    }

    @Test fun `when both brands exist the newest wins and a tie goes to StereoSine`() {
        assertEquals("wavdrop-backup.wdbk", pick(F("wavdrop-backup.wdbk", 20), F("stereosine-backup.wdbk", 10))!!.name)
        assertEquals("stereosine-backup.wdbk", pick(F("wavdrop-backup.wdbk", 10), F("stereosine-backup.wdbk", 20))!!.name)
        assertEquals("stereosine-backup.wdbk", pick(F("wavdrop-backup.wdbk", 10), F("stereosine-backup.wdbk", 10))!!.name)
        assertEquals("stereosine-backup.wdbk", pick(F("stereosine-backup.wdbk", 10), F("wavdrop-backup.wdbk", 10))!!.name)
        assertNull(pick())
    }

    @Test fun `legacy format identifiers and legacy prefix are unchanged`() {
        assertEquals("wavdrop-backup", BackupFileNames.LEGACY_PREFIX)
        assertEquals("wavdrop_backup", StereoSineBackupParser.SUPPORTED_FORMAT)
        assertEquals("wavdrop_wdbk", com.launchpoint.stereosine.data.backup.wdbk.WdbkContainerVersion.FORMAT)
        assertEquals("wavdrop_backup", com.launchpoint.stereosine.data.backup.wdbk.WdbkContainerVersion.LOGICAL_FORMAT)
    }
}
