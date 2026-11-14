package com.launchpoint.stereosine.di

import com.launchpoint.stereosine.data.backup.RecoveryDatabaseApplier
import com.launchpoint.stereosine.data.backup.RecoveryPreferenceApplier
import com.launchpoint.stereosine.data.backup.RecoveryPreferenceApplierImpl
import com.launchpoint.stereosine.data.backup.RecoveryRestoreRepository
import com.launchpoint.stereosine.data.backup.RecoverySafetySnapshotService
import com.launchpoint.stereosine.data.backup.SafetySnapshotProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface RecoveryRestoreModule {
    @Binds fun bindSafetySnapshotProvider(impl: RecoverySafetySnapshotService): SafetySnapshotProvider
    @Binds fun bindRecoveryDatabaseApplier(impl: RecoveryRestoreRepository): RecoveryDatabaseApplier
    @Binds fun bindRecoveryPreferenceApplier(impl: RecoveryPreferenceApplierImpl): RecoveryPreferenceApplier
}
