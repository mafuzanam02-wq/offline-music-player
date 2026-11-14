package com.launchpoint.stereosine.data.model

data class FolderSummary(
    val folderKey: String,
    val displayName: String,
    val songCount: Int,
    val totalDurationMs: Long,
)
