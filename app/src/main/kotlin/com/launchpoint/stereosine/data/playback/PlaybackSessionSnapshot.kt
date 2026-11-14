package com.launchpoint.stereosine.data.playback

import com.launchpoint.stereosine.playback.RepeatMode

data class PlaybackSessionSnapshot(
    val queueSongIds: List<Long>,
    val playbackOrder: List<Int>? = null,
    val currentSongId: Long?,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val updatedAtMs: Long,
)
