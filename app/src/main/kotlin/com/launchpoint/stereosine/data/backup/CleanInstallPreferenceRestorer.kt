package com.launchpoint.stereosine.data.backup

import com.launchpoint.stereosine.data.model.MostPlayedDisplayLimit
import com.launchpoint.stereosine.data.model.MostPlayedPeriod
import com.launchpoint.stereosine.data.settings.AccentColor
import com.launchpoint.stereosine.data.settings.AppIconAliasManager
import com.launchpoint.stereosine.data.settings.AppIconChoice
import com.launchpoint.stereosine.data.settings.AppSettingsRepository
import com.launchpoint.stereosine.data.settings.ArtworkCornerStyle
import com.launchpoint.stereosine.data.settings.AutoBackupInterval
import com.launchpoint.stereosine.data.settings.BackupFileMode
import com.launchpoint.stereosine.data.settings.HeadphoneResumeMode
import com.launchpoint.stereosine.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.stereosine.data.settings.HomeSectionId
import com.launchpoint.stereosine.data.settings.HomeSmartCollectionSelection
import com.launchpoint.stereosine.data.settings.LibraryScanMode
import com.launchpoint.stereosine.data.settings.LibraryScanSettingsRepository
import com.launchpoint.stereosine.data.settings.NotificationControlsSetting
import com.launchpoint.stereosine.data.settings.NowPlayingBackground
import com.launchpoint.stereosine.data.settings.NowPlayingTimeDisplayMode
import com.launchpoint.stereosine.data.settings.PreviousButtonBehavior
import com.launchpoint.stereosine.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.stereosine.data.settings.SearchTapBehavior
import com.launchpoint.stereosine.data.settings.SongSortMode
import com.launchpoint.stereosine.data.settings.StartupDestination
import com.launchpoint.stereosine.data.settings.ThemeMode
import com.launchpoint.stereosine.data.settings.WrappedBackgroundIntensity
import com.launchpoint.stereosine.data.settings.WrappedFallbackTheme
import com.launchpoint.stereosine.data.settings.WrappedVisualStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class CleanInstallPreferenceRestoreResult(
    val restored: Boolean,
    val needsFolderReselection: Boolean,
    val needsAutoBackupFolderSelection: Boolean,
    val launcherIconRestored: Boolean,
)

@Singleton
class CleanInstallPreferenceRestorer @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val homeSettings: HomeLayoutSettingsRepository,
    private val scanSettings: LibraryScanSettingsRepository,
    private val resumeSettings: ResumeBehaviorSettingsRepository,
    private val iconManager: AppIconAliasManager,
) {
    /** What happens to the device-specific music-folder configuration (SAF grants / scan mode). */
    enum class FolderPolicy {
        /** Clean-install recovery: old grants cannot exist, so folders are cleared and re-selection is requested. */
        CLEAN_INSTALL_RESET,

        /** Recovery on a device that already has a working library: folder grants are device state and are left untouched. */
        KEEP_DEVICE_FOLDERS,
    }

    suspend fun restore(
        preferences: BackupPreferences?,
        folderPolicy: FolderPolicy = FolderPolicy.CLEAN_INSTALL_RESET,
    ): CleanInstallPreferenceRestoreResult {
        if (preferences == null) {
            return CleanInstallPreferenceRestoreResult(false, false, false, false)
        }

        preferences.startupDestination.enumValue<StartupDestination>()?.let {
            appSettings.setStartupDestination(it)
        }
        preferences.mostPlayedPeriod.enumValue<MostPlayedPeriod>()?.let {
            appSettings.setMostPlayedPeriod(it)
        }
        preferences.mostPlayedLimit.enumValue<MostPlayedDisplayLimit>()?.let {
            appSettings.setMostPlayedDisplayLimit(it)
        }
        preferences.songSortMode.enumValue<SongSortMode>()?.let { appSettings.setSongSortMode(it) }
        preferences.searchTapBehavior.enumValue<SearchTapBehavior>()?.let { appSettings.setSearchTapBehavior(it) }
        preferences.homeVisibleSections
            ?.mapNotNull { it.enumValue<HomeSectionId>() }
            ?.toSet()
            ?.let { homeSettings.setVisibleSections(it) }

        // P5-AUDIT-08. Null means the backup predates the field: leave the device value alone. Everything present is normalized by the
        // same rules the settings screens use, and an unrecognized value is ignored rather than replaced by a default.
        preferences.homeSmartCollections
            ?.let { homeSettings.setHomeSmartCollections(HomeSmartCollectionSelection.sanitizeIds(it)) }
        preferences.crossfadeDurationMs?.let { appSettings.setCrossfadeDurationMs(it.toLong()) }
        preferences.transportFadeEnabled?.let { appSettings.setTransportFadeEnabled(it) }
        preferences.transportFadeDurationMs?.let { appSettings.setTransportFadeDurationMs(it.toLong()) }
        PreviousButtonBehavior.fromStoredName(preferences.previousButtonBehavior)
            ?.let { appSettings.setPreviousButtonBehavior(it) }

        preferences.minimumTrackDurationSeconds?.let { scanSettings.setMinimumTrackDurationSeconds(it) }
        preferences.includeWhatsAppVoiceNotes?.let { scanSettings.setIncludeWhatsAppVoiceNotes(it) }

        val needsFolders = folderPolicy == FolderPolicy.CLEAN_INSTALL_RESET &&
            CleanInstallRecoveryPolicy.requiresFolderReselection(preferences)
        if (folderPolicy == FolderPolicy.CLEAN_INSTALL_RESET) {
            scanSettings.setSelectedFolderUris(emptyList())
            scanSettings.setScanMode(
                if (needsFolders) LibraryScanMode.SELECTED_FOLDERS else LibraryScanMode.WHOLE_DEVICE,
            )
            appSettings.setNeedsFolderReselectionAfterRestore(needsFolders)
        }

        preferences.themeMode.enumValue<ThemeMode>()?.let { appSettings.setThemeMode(it) }
        preferences.accentColor.enumValue<AccentColor>()?.let { appSettings.setAccentColor(it) }
        preferences.compactMode?.let { appSettings.setCompactMode(it) }
        preferences.backupFileMode.enumValue<BackupFileMode>()?.let { appSettings.setBackupFileMode(it) }
        preferences.artworkCornerStyle.enumValue<ArtworkCornerStyle>()?.let { appSettings.setArtworkCornerStyle(it) }
        preferences.showSongThumbnails?.let { appSettings.setShowSongThumbnails(it) }
        preferences.showAlbumInSongRows?.let { appSettings.setShowAlbumInSongRows(it) }
        preferences.nowPlayingBackground.enumValue<NowPlayingBackground>()
            ?.let { appSettings.setNowPlayingBackground(it) }
        preferences.showQueueCount?.let { appSettings.setShowQueueCount(it) }
        preferences.nowPlayingTimeDisplayMode.enumValue<NowPlayingTimeDisplayMode>()
            ?.let { appSettings.setNowPlayingTimeDisplayMode(it) }
        preferences.notificationControls.enumValue<NotificationControlsSetting>()
            ?.let { appSettings.setNotificationControlsSetting(it) }
        preferences.showMilestoneCelebrations?.let { appSettings.setShowMilestoneCelebrations(it) }
        preferences.wrappedUseArtworkBackgrounds?.let { appSettings.setWrappedUseArtworkBackgrounds(it) }
        preferences.wrappedBackgroundIntensity.enumValue<WrappedBackgroundIntensity>()
            ?.let { appSettings.setWrappedBackgroundIntensity(it) }
        preferences.wrappedFallbackTheme.enumValue<WrappedFallbackTheme>()
            ?.let { appSettings.setWrappedFallbackTheme(it) }
        preferences.wrappedVisualStyle.enumValue<WrappedVisualStyle>()
            ?.let { appSettings.setWrappedVisualStyle(it) }

        preferences.pauseOnAudioDisconnect?.let { resumeSettings.setPauseOnAudioDisconnect(it) }
        preferences.rememberLastTrack?.let { resumeSettings.setRememberLastTrack(it) }
        preferences.rememberPosition?.let { resumeSettings.setRememberPosition(it) }
        preferences.restoreQueue?.let { resumeSettings.setRestoreQueue(it) }
        preferences.bluetoothResumeMode.enumValue<HeadphoneResumeMode>()
            ?.let { resumeSettings.setBluetoothResumeMode(it) }
        preferences.wiredResumeMode.enumValue<HeadphoneResumeMode>()
            ?.let { resumeSettings.setWiredResumeMode(it) }

        val interval = preferences.autoBackupInterval.enumValue<AutoBackupInterval>()
        if (interval != null) appSettings.setAutoBackupInterval(interval)
        val needsBackupFolder =
            interval != null &&
                interval != AutoBackupInterval.OFF &&
                appSettings.autoBackupFolderUri.first().isNullOrBlank()
        appSettings.setNeedsAutoBackupFolderSelectionAfterRestore(needsBackupFolder)

        val icon = preferences.launcherIcon.enumValue<AppIconChoice>()
        val iconRestored = if (icon != null) {
            appSettings.setAppIconChoice(icon)
            runCatching { iconManager.apply(icon) }.isSuccess
        } else {
            false
        }

        return CleanInstallPreferenceRestoreResult(
            restored = true,
            needsFolderReselection = needsFolders,
            needsAutoBackupFolderSelection = needsBackupFolder,
            launcherIconRestored = iconRestored,
        )
    }
}

private inline fun <reified T : Enum<T>> String?.enumValue(): T? =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
