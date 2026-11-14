package com.launchpoint.stereosine.di

import com.launchpoint.stereosine.data.lyrics.LyricsExtractor
import com.launchpoint.stereosine.data.lyrics.MediaMetadataLyricsExtractor
import com.launchpoint.stereosine.data.lyrics.MediaStoreSidecarLyricsExtractor
import com.launchpoint.stereosine.data.lyrics.SidecarLyricsExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsModule {

    @Binds
    @Singleton
    abstract fun bindLyricsExtractor(impl: MediaMetadataLyricsExtractor): LyricsExtractor

    @Binds
    @Singleton
    abstract fun bindSidecarLyricsExtractor(
        impl: MediaStoreSidecarLyricsExtractor,
    ): SidecarLyricsExtractor
}
