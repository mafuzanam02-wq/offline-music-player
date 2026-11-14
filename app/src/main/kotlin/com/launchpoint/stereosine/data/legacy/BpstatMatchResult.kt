package com.launchpoint.stereosine.data.legacy

import com.launchpoint.stereosine.data.model.Song

/**
 * Result of matching a [BlackPlayerImportResult] against the current StereoSine song library.
 *
 * @property matchedCount   Valid import rows that map to a known StereoSine song.
 * @property unmatchedCount Valid import rows with no matching StereoSine song.
 * @property unmatchedSample First 10 unmatched rows, for display in the preview screen.
 * @property matchedRows    Every (Song, import-row) pair, consumed by the apply step.
 */
data class BpstatMatchResult(
    val matchedCount: Int,
    val unmatchedCount: Int,
    val unmatchedSample: List<BlackPlayerStatImportRow>,
    val matchedRows: List<Pair<Song, BlackPlayerStatImportRow>>,
)
