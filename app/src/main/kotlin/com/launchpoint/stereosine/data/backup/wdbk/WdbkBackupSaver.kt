package com.launchpoint.stereosine.data.backup.wdbk

import com.launchpoint.stereosine.data.backup.BackupIntegrityStatus
import com.launchpoint.stereosine.data.backup.BackupSaveValidator
import com.launchpoint.stereosine.data.backup.StereoSineBackupIntegrityV2
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A writable/readable backup destination. The Android implementations wrap a SAF Uri or a [DocumentFile];
 * keeping the save/verify logic behind this seam lets it be tested on the JVM, including every failure mode.
 */
interface BackupFileHandle {
    /** Opens the destination for writing in [mode] ("wt" truncating, "w" fallback); null when it cannot be opened. */
    fun openOutput(mode: String): OutputStream?

    /** Opens the destination for reading; null when it cannot be opened. */
    fun openInput(): InputStream?

    fun delete(): Boolean
}

/**
 * Writes a WDBK container and proves it: success is reported only after the bytes on disk have been re-opened and
 * decoded by the SAME [WdbkReader] real imports use, with the physical (per-entry SHA-256) and semantic
 * (StereoSineBackupIntegrityV2 fingerprint) checks passing AND matching what the writer recorded. `ZipOutputStream`
 * closing without error is never treated as success.
 */
class WdbkBackupSaver(
    private val writer: WdbkWriter = WdbkWriter(),
    private val reader: WdbkReader = WdbkReader(),
) {

    /** Manual export: write [backup] to [target], re-read it, verify it. Throws [IOException] on any failure. */
    suspend fun saveAndVerify(target: BackupFileHandle, snapshot: WdbkExportSnapshot): WdbkWriteReceipt {
        val receipt = writeStreaming(target, snapshot)
        verify(target, receipt)
        return receipt
    }

    /** Folder publication has already qualified a distinct, empty document. */
    internal suspend fun saveFreshAndVerify(target: BackupFileHandle, snapshot: WdbkExportSnapshot) {
        val stream = target.openOutput("w") ?: throw IOException("Could not open the new backup for writing.")
        val receipt = stream.use {
            currentCoroutineContext().ensureActive()
            writer.write(snapshot, it)
        }
        currentCoroutineContext().ensureActive()
        verify(target, receipt)
    }

    private suspend fun writeStreaming(target: BackupFileHandle, snapshot: WdbkExportSnapshot): WdbkWriteReceipt {
        // "wt" truncates; the default "w" does not on every provider (a larger old file would leave trailing
        // garbage). "w" is only a fallback for providers that reject "wt".
        for (mode in WRITE_MODES) {
            val stream = target.openOutput(mode) ?: continue
            try {
                // A retry re-streams the SAME captured snapshot (the event source is re-opened up to the same boundary).
                return stream.use { writer.write(snapshot, it) }
            } catch (_: IOException) {
                continue
            }
        }
        throw IOException("Could not save the backup file. Try a different location.")
    }

    /** Re-opens [target], decodes it with the production reader and requires it to equal what was written. */
    fun verify(target: BackupFileHandle, receipt: WdbkWriteReceipt) {
        val input = try {
            target.openInput()
        } catch (_: IOException) {
            null
        } ?: throw IOException(BackupSaveValidator.VALIDATION_FAILED_MESSAGE)

        val result = try {
            input.use { reader.read(it) }
        } catch (_: IOException) {
            throw IOException(BackupSaveValidator.VALIDATION_FAILED_MESSAGE)
        }
        val backup = result.backup
        if (backup == null ||
            result.integrityStatus != BackupIntegrityStatus.VERIFIED ||
            backup.backupId != receipt.backupId ||
            StereoSineBackupIntegrityV2.fingerprint(backup) != receipt.fingerprint ||
            !receipt.manifest.counts.matchesContentOf(backup)
        ) {
            throw IOException(BackupSaveValidator.VALIDATION_FAILED_MESSAGE)
        }
    }

    private companion object {
        val WRITE_MODES = listOf("wt", "w")
    }
}
