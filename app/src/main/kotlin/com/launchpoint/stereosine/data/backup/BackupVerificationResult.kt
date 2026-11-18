package com.launchpoint.stereosine.data.backup

enum class VerificationStatus {
    /** Backup parsed cleanly and all core recoverable sections are present. */
    VERIFIED,
    /** Backup is valid but missing important non-fatal sections (see warnings). */
    PARTIAL,
    /** A backup file exists but is unreadable, corrupt, or not a StereoSine backup. */
    FAILED,
    /** No backup folder is selected or the qualified search found no candidates. */
    NO_BACKUP_FOUND,
}

enum class VerificationSearchStatus { NOT_RUN, COMPLETE, INCOMPLETE, UNAVAILABLE, FAILED }

data class BackupVerificationResult(
    val status: VerificationStatus,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val backupTimestampMillis: Long? = null,
    /** True when [backupTimestampMillis] is the file's modified time because the backup has no readable embedded export time. */
    val timestampIsFileTime: Boolean = false,
    val schemaVersion: Int? = null,
    val trackCount: Int = 0,
    val playlistCount: Int = 0,
    val favoriteCount: Int = 0,
    val statsCount: Int = 0,
    val eventCount: Int = 0,
    val lyricsCount: Int = 0,
    val hasPreferences: Boolean = false,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val integrityStatus: BackupIntegrityStatus? = null,
    val searchStatus: VerificationSearchStatus = VerificationSearchStatus.NOT_RUN,
)
