package com.launchpoint.stereosine.data.backup

import com.launchpoint.stereosine.data.model.Song

data class StereoSineBackupMatchResult(
    val matchedRows: List<Pair<Song, BackupTrackStats>>,
    /** Rows not applied: truly unmatched + ambiguous + collision-dropped. */
    val unmatchedCount: Int,
    val diagnostics: StereoSineBackupMatchDiagnostics = StereoSineBackupMatchDiagnostics(),
) {
    val matchedCount: Int get() = matchedRows.size
}

/** Per-tier breakdown explaining how each backup stats row was (or wasn't) matched. */
data class StereoSineBackupMatchDiagnostics(
    val statsInBackup: Int = 0,
    val matchedByUri: Int = 0,
    val matchedByPath: Int = 0,
    val matchedByTagsDuration: Int = 0,
    val matchedByTitleArtistDuration: Int = 0,
    val matchedByTitleDuration: Int = 0,
    val matchedByTagsOnly: Int = 0,
    /** Rows where one tier produced several equally good local songs. */
    val ambiguous: Int = 0,
    /** Rows dropped because several backup rows resolved to the same local song. */
    val collisions: Int = 0,
    /** Rows with no candidate at any tier. */
    val unmatched: Int = 0,
)
