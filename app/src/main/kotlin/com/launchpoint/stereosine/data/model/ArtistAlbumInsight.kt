package com.launchpoint.stereosine.data.model

data class ArtistAlbumInsight(
    val albumKey: String,
    val songCount: Int,
    val playCount: Int,
    val skipCount: Int,
    val totalListeningTimeMs: Long,
    val lastPlayedAt: Long?,
)
