package com.launchpoint.stereosine.di

import com.launchpoint.stereosine.data.backup.AutoBackupWorkManagerGateway
import com.launchpoint.stereosine.data.backup.WorkManagerAutoBackupGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface BackupWorkModule {
    @Binds
    @Singleton
    fun bindAutoBackupWorkManagerGateway(
        gateway: WorkManagerAutoBackupGateway,
    ): AutoBackupWorkManagerGateway
}
