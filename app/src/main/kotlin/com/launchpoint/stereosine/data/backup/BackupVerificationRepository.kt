package com.launchpoint.stereosine.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.launchpoint.stereosine.data.backup.wdbk.BackupFolderChild
import com.launchpoint.stereosine.data.settings.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Read-only search of the selected folder using the same content reader as Restore. */
@Singleton
class BackupVerificationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
) {
    suspend fun verifyLatestBackup(): BackupVerificationResult = try {
        verify()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Backup verification failed unexpectedly", e)
        BackupVerificationResult(
            status = VerificationStatus.FAILED,
            searchStatus = VerificationSearchStatus.FAILED,
            errors = listOf("The backup folder could not be checked. Try verifying again."),
        )
    }

    private suspend fun verify(): BackupVerificationResult {
        val folder = appSettingsRepository.autoBackupFolderUri.first()
            ?: return BackupVerificationResult(
                status = VerificationStatus.NO_BACKUP_FOUND,
                warnings = listOf("Choose a backup folder to check saved backups."),
            )
        return when (val result = FolderBackupDiscovery.discover(context, Uri.parse(folder))) {
            is FolderBackupDiscovery.Result.Complete -> result.toVerificationResult()
            is FolderBackupDiscovery.Result.Incomplete -> BackupVerificationResult(
                status = VerificationStatus.FAILED,
                searchStatus = VerificationSearchStatus.INCOMPLETE,
                errors = listOf(result.reason),
            )
            FolderBackupDiscovery.Result.Unavailable -> BackupVerificationResult(
                status = VerificationStatus.FAILED,
                searchStatus = VerificationSearchStatus.UNAVAILABLE,
                errors = listOf("The backup folder is unavailable. Choose the folder again to restore access."),
            )
        }
    }

    private fun FolderBackupDiscovery.Result.Complete.toVerificationResult(): BackupVerificationResult {
        val search = if (unreadableCount > 0) VerificationSearchStatus.INCOMPLETE else VerificationSearchStatus.COMPLETE
        selected?.let { return it.summary.copy(searchStatus = search, warnings = warnings + it.summary.warnings) }
        return if (candidateCount == 0) BackupVerificationResult(
            status = VerificationStatus.NO_BACKUP_FOUND,
            searchStatus = search,
            warnings = listOf("No backup file was found. Use Back Up Now to create one."),
        ) else BackupVerificationResult(
            status = VerificationStatus.FAILED,
            searchStatus = search,
            warnings = warnings,
            errors = listOfNotNull("No usable Android backup was found.", rejection),
        )
    }

    internal companion object {
        private const val TAG = "StereoSineBackup"

        internal fun isBackupFileName(name: String?): Boolean = BackupFileNames.isBackupFileName(name)
    }
}

internal fun verificationRejection(document: BackupDocumentReadResult): String = when (document) {
    is BackupDocumentReadResult.Rejected -> when (document.reason) {
        BackupRejectReason.TOO_LARGE -> BackupInputReader.TOO_LARGE_MESSAGE
        BackupRejectReason.UNREADABLE -> "A backup could not be read. Check folder access and try again."
        BackupRejectReason.EMPTY -> "A backup file is empty."
        BackupRejectReason.NOT_A_BACKUP -> "A file does not contain a StereoSine Android backup."
    }
    is BackupDocumentReadResult.DesktopJson -> "Desktop backups are available through manual Restore."
    is BackupDocumentReadResult.StereoSine -> {
        val error = document.result.error
        when {
            error?.startsWith("Unsupported backup version") == true || error == StereoSineBackupParser.NEWER_VERSION_ERROR ->
                "A backup requires a newer version of StereoSine."
            error == BackupInputReader.TOO_LARGE_MESSAGE -> BackupInputReader.TOO_LARGE_MESSAGE
            else -> "A backup is damaged or failed integrity verification."
        }
    }
}

internal fun summariseVerification(
    backup: StereoSineBackup,
    child: BackupFolderChild,
    integrity: BackupIntegrityStatus,
    readerWarnings: List<String>,
): BackupVerificationResult {
    val warnings = readerWarnings.toMutableList()
    val fileModifiedMillis = child.modifiedMillis

    // Embedded export time wins; the file's modified time is only an explicitly flagged fallback (never the export date).
    val embedded = BackupPresentation.exportTimestampMillis(backup)
    val timestamp = embedded ?: fileModifiedMillis
    if (timestamp == null) {
        warnings += "The backup has no readable date, so its age cannot be checked."
    }

    if (backup.songs.isEmpty()) {
        warnings += "The backup contains no tracks. Your library details may not be recoverable."
    }
    if (backup.trackStats.isEmpty() && backup.songs.isNotEmpty()) {
        warnings += "The backup contains no play statistics."
    }
    if (backup.listenEvents.isEmpty()) {
        warnings += "Listening history is missing. Monthly Reports and Wrapped cannot be rebuilt from this backup."
    }
    if (backup.preferences == null) {
        warnings += "App settings are not included in this backup."
    }
    // Only genuine v1 backups are "older"; v2 JSON and WDBK are integrity-verified by their own mechanisms.
    BackupPresentation.legacyFormatWarning(backup)?.let { warnings += it }

    val coreSectionsPresent =
        backup.songs.isNotEmpty() &&
            backup.trackStats.isNotEmpty() &&
            backup.listenEvents.isNotEmpty() &&
            timestamp != null

    return BackupVerificationResult(
        status                = if (coreSectionsPresent) VerificationStatus.VERIFIED else VerificationStatus.PARTIAL,
        fileName              = child.displayName,
        fileSizeBytes         = child.sizeBytes,
        backupTimestampMillis = timestamp,
        timestampIsFileTime   = embedded == null && timestamp != null,
        schemaVersion        = StereoSineBackupParser.SUPPORTED_VERSION,
        trackCount            = backup.songs.size,
        playlistCount         = backup.playlists.size,
        favoriteCount         = backup.trackStats.count { it.isFavorite },
        statsCount            = backup.trackStats.size,
        eventCount            = backup.listenEvents.size,
        lyricsCount           = backup.lyricsOverrides.size,
        hasPreferences        = backup.preferences != null,
        warnings              = warnings,
        integrityStatus       = integrity,
    )
}
