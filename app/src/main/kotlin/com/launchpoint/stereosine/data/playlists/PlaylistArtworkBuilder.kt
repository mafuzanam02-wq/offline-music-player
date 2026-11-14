package com.launchpoint.stereosine.data.playlists

import com.launchpoint.stereosine.data.artwork.ArtworkResolver
import com.launchpoint.stereosine.data.model.Song

object PlaylistArtworkBuilder {

    fun buildArtworkUris(songs: List<Song>): List<String> {
        val uris = linkedSetOf<String>()
        for (song in songs) {
            val uri = ArtworkResolver.albumArtworkUri(song.albumId) ?: continue
            uris.add(uri)
            if (uris.size == 4) break
        }
        return uris.toList()
    }
}
