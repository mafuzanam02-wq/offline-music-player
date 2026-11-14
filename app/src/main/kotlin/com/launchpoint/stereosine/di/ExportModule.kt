package com.launchpoint.stereosine.di

import com.launchpoint.stereosine.data.export.MediaPathSource
import com.launchpoint.stereosine.data.export.MediaStorePathResolver
import com.launchpoint.stereosine.data.export.PlaylistExportRepository
import com.launchpoint.stereosine.data.export.PlaylistExporter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {
    @Binds abstract fun bindPathSource(impl: MediaStorePathResolver): MediaPathSource
    @Binds abstract fun bindPlaylistExporter(impl: PlaylistExportRepository): PlaylistExporter
}
