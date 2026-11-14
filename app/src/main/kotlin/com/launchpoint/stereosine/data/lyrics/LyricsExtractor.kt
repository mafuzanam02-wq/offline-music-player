package com.launchpoint.stereosine.data.lyrics

interface LyricsExtractor {
    fun extract(uri: String): LyricsResult
}
