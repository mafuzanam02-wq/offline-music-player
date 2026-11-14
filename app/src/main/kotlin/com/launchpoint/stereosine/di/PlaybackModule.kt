package com.launchpoint.stereosine.di

import com.launchpoint.stereosine.data.repository.PlayEventWriter
import com.launchpoint.stereosine.data.repository.StatsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    @Binds
    @Singleton
    abstract fun bindPlayEventWriter(impl: StatsRepository): PlayEventWriter
}
