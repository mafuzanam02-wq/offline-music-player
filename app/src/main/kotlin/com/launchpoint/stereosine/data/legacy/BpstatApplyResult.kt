package com.launchpoint.stereosine.data.legacy

/**
 * Result returned by [com.launchpoint.stereosine.data.repository.StatsRepository.applyBpstatImport].
 * A .bpstat file carries no skip evidence, so there is deliberately no skip figure here.
 */
data class BpstatApplyResult(
    val tracksMatched: Int,
    val tracksUpdated: Int,
    val tracksSkippedNoNewStats: Int,
    val playsImported: Long,
    val unmatchedSkipped: Int,
)
