package com.launchpoint.stereosine.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.launchpoint.stereosine.data.local.MIGRATION_1_2
import com.launchpoint.stereosine.data.local.MIGRATION_2_3
import com.launchpoint.stereosine.data.local.MIGRATION_3_4
import com.launchpoint.stereosine.data.local.MIGRATION_4_5
import com.launchpoint.stereosine.data.local.MIGRATION_5_6
import com.launchpoint.stereosine.data.local.MIGRATION_6_7
import com.launchpoint.stereosine.data.local.MIGRATION_7_8
import com.launchpoint.stereosine.data.local.MIGRATION_8_9
import com.launchpoint.stereosine.data.local.MIGRATION_9_10
import com.launchpoint.stereosine.data.local.MIGRATION_10_11
import com.launchpoint.stereosine.data.local.MIGRATION_11_12
import com.launchpoint.stereosine.data.local.MIGRATION_12_13
import com.launchpoint.stereosine.data.local.DATABASE_FILE_NAME
import com.launchpoint.stereosine.data.local.StereoSineDatabase
import com.launchpoint.stereosine.data.local.dao.ImportBaselineDao
import com.launchpoint.stereosine.data.local.dao.LyricsOverrideDao
import com.launchpoint.stereosine.data.local.dao.PendingBackupExtensionDao
import com.launchpoint.stereosine.data.local.dao.PendingTrackDao
import com.launchpoint.stereosine.data.local.dao.PlaylistDao
import com.launchpoint.stereosine.data.local.dao.SongDao
import com.launchpoint.stereosine.data.local.dao.TrackIdentityDao
import com.launchpoint.stereosine.data.local.dao.TrackListenEventDao
import com.launchpoint.stereosine.data.local.dao.TrackStatsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.stereoSinePreferencesDataStore by preferencesDataStore(
    name = "wavdrop_preferences",
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StereoSineDatabase =
        Room.databaseBuilder(context, StereoSineDatabase::class.java, DATABASE_FILE_NAME)
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
            )
            .build()

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.stereoSinePreferencesDataStore

    @Provides
    fun provideSongDao(db: StereoSineDatabase): SongDao = db.songDao()

    @Provides
    fun provideTrackStatsDao(db: StereoSineDatabase): TrackStatsDao = db.trackStatsDao()

    @Provides
    fun provideImportBaselineDao(db: StereoSineDatabase): ImportBaselineDao = db.importBaselineDao()

    @Provides
    fun providePlaylistDao(db: StereoSineDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideTrackListenEventDao(db: StereoSineDatabase): TrackListenEventDao = db.trackListenEventDao()

    @Provides
    fun provideLyricsOverrideDao(db: StereoSineDatabase): LyricsOverrideDao = db.lyricsOverrideDao()

    @Provides
    fun providePendingTrackDao(db: StereoSineDatabase): PendingTrackDao = db.pendingTrackDao()

    @Provides
    fun provideTrackIdentityDao(db: StereoSineDatabase): TrackIdentityDao = db.trackIdentityDao()

    @Provides
    fun providePendingBackupExtensionDao(db: StereoSineDatabase): PendingBackupExtensionDao =
        db.pendingBackupExtensionDao()
}
