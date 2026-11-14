package com.launchpoint.stereosine.data.model

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val songCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
