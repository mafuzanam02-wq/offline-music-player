package com.launchpoint.stereosine.ui.screen.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.stereosine.data.backup.StereoSineBackupRepository
import com.launchpoint.stereosine.ui.scan.LibraryScanCoordinator
import com.launchpoint.stereosine.ui.scan.LibraryScanUiState
import com.launchpoint.stereosine.data.repository.SongRepository
import com.launchpoint.stereosine.data.backup.AutoBackupRepository
import com.launchpoint.stereosine.data.backup.AutoBackupWorkScheduler
import com.launchpoint.stereosine.data.settings.AccentColor
import com.launchpoint.stereosine.data.settings.AudioEnhancementsRepository
import com.launchpoint.stereosine.data.settings.EqualizerCapabilities
import com.launchpoint.stereosine.data.settings.EqPresetType
import com.launchpoint.stereosine.data.settings.ArtworkCornerStyle
import com.launchpoint.stereosine.data.settings.AutoBackupCheckResult
import com.launchpoint.stereosine.data.settings.AutoBackupInterval
import com.launchpoint.stereosine.data.settings.NowPlayingTimeDisplayMode
import com.launchpoint.stereosine.data.settings.BackupFileMode
import com.launchpoint.stereosine.data.settings.AppIconAliasManager
import com.launchpoint.stereosine.data.settings.AppIconChoice
import com.launchpoint.stereosine.data.settings.AppSettingsRepository
import com.launchpoint.stereosine.data.settings.NowPlayingBackground
import com.launchpoint.stereosine.data.settings.HeadphoneResumeMode
import com.launchpoint.stereosine.data.settings.NotificationControlsSetting
import com.launchpoint.stereosine.data.settings.PreviousButtonBehavior
import com.launchpoint.stereosine.data.settings.ThemeMode
import com.launchpoint.stereosine.data.settings.LibraryScanExclusion
import com.launchpoint.stereosine.data.settings.LibraryScanMode
import com.launchpoint.stereosine.data.settings.LibraryScanSettings
import com.launchpoint.stereosine.data.settings.LibraryScanSettingsRepository
import com.launchpoint.stereosine.data.settings.ResumeBehaviorSettings
import com.launchpoint.stereosine.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.stereosine.data.settings.SearchTapBehavior
import com.launchpoint.stereosine.data.settings.StartupDestination
import com.launchpoint.stereosine.data.settings.WrappedBackgroundIntensity
import com.launchpoint.stereosine.data.settings.WrappedFallbackTheme
import com.launchpoint.stereosine.data.settings.WrappedVisualStyle
import com.launchpoint.stereosine.playback.CrossfadeRules
import com.launchpoint.stereosine.playback.TransportFadeRules
import com.launchpoint.stereosine.playback.PlayerController
import com.launchpoint.stereosine.playback.SleepTimerOption
import com.launchpoint.stereosine.playback.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data object Exporting : ExportUiState
    data object Success : ExportUiState
    data class Error(val message: String) : ExportUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backupRepository: StereoSineBackupRepository,
    private val autoBackupRepository: AutoBackupRepository,
    private val autoBackupWorkScheduler: AutoBackupWorkScheduler,
    private val appSettingsRepository: AppSettingsRepository,
    private val appIconAliasManager: AppIconAliasManager,
    private val scanSettingsRepository: LibraryScanSettingsRepository,
    private val resumeBehaviorRepository: ResumeBehaviorSettingsRepository,
    private val audioEnhancementsRepository: AudioEnhancementsRepository,
    private val songRepository: SongRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val _exportUiState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportUiState: StateFlow<ExportUiState> = _exportUiState.asStateFlow()

    val backupFileMode: StateFlow<BackupFileMode> =
        appSettingsRepository.backupFileMode.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = BackupFileMode.DATED,
        )

    val autoBackupFolderUri: StateFlow<String?> =
        appSettingsRepository.autoBackupFolderUri.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    val autoBackupInterval: StateFlow<AutoBackupInterval> =
        appSettingsRepository.autoBackupInterval.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = AutoBackupInterval.OFF,
        )

    private val _folderBackupUiState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val folderBackupUiState: StateFlow<ExportUiState> = _folderBackupUiState.asStateFlow()

    /** Epoch-ms of the last successful folder backup (auto or Back Up Now); 0 if never. */
    val lastBackupAtMillis: StateFlow<Long> =
        appSettingsRepository.lastAutoBackupAtMillis.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0L,
        )

    val lastAutoBackupCheckAtMillis: StateFlow<Long> =
        appSettingsRepository.lastAutoBackupCheckAtMillis.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0L,
        )

    val lastAutoBackupResult: StateFlow<AutoBackupCheckResult?> =
        appSettingsRepository.lastAutoBackupResult.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /** Set by restore when auto-backup was re-enabled but no folder exists on this device. */
    val needsBackupFolderAfterRestore: StateFlow<Boolean> =
        appSettingsRepository.needsAutoBackupFolderSelectionAfterRestore.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /**
     * Saves a freshly picked backup folder. Clears the post-restore pending flag only when
     * the persistable URI permission was actually granted ([permissionGranted]).
     */
    fun onBackupFolderPicked(uri: String, permissionGranted: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setAutoBackupFolderUri(uri)
            if (permissionGranted) {
                appSettingsRepository.setNeedsAutoBackupFolderSelectionAfterRestore(false)
            }
            autoBackupWorkScheduler.reconcile()
        }
    }

    val libraryScanSettings: StateFlow<LibraryScanSettings> =
        scanSettingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryScanSettings(),
        )

    val startupDestination: StateFlow<StartupDestination> =
        appSettingsRepository.startupDestination.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StartupDestination.SONGS,
        )

    val resumeBehaviorSettings: StateFlow<ResumeBehaviorSettings> =
        resumeBehaviorRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ResumeBehaviorSettings(),
        )

    val outputProfiles = audioEnhancementsRepository.profiles.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), com.launchpoint.stereosine.data.settings.OutputProfilesState(),
    )

    fun selectOutputProfile(profile: com.launchpoint.stereosine.data.settings.OutputProfile) {
        viewModelScope.launch { audioEnhancementsRepository.selectProfile(profile) }
    }

    val eqEnabled: StateFlow<Boolean> =
        audioEnhancementsRepository.settings
            .map { it.eqEnabled }
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    val eqPresetType: StateFlow<EqPresetType> =
        audioEnhancementsRepository.settings
            .map { it.eqPresetType }
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = EqPresetType.FLAT,
            )

    val eqPlatformPresetIndex: StateFlow<Int?> =
        audioEnhancementsRepository.settings
            .map { it.eqPlatformPresetIndex }
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    val eqCustomBandLevels: StateFlow<List<Int>> =
        audioEnhancementsRepository.settings
            .map { it.eqCustomBandLevels }
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    val eqStereoSinePresetId: StateFlow<String?> =
        audioEnhancementsRepository.settings
            .map { it.eqStereoSinePresetId }
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    val eqApplicationState = audioEnhancementsRepository.applicationState

    val eqCapabilities: StateFlow<EqualizerCapabilities?> = audioEnhancementsRepository.capabilities

    val sleepTimerState: StateFlow<SleepTimerState> = playerController.sleepTimerState

    val notificationControlsSetting: StateFlow<NotificationControlsSetting> =
        appSettingsRepository.notificationControlsSetting.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = NotificationControlsSetting.STANDARD,
        )

    /** CF-2J1: the persisted crossfade duration (canonical milliseconds); the UI never reads DataStore directly. */
    val crossfadeDurationMs: StateFlow<Long> =
        appSettingsRepository.crossfadeDurationMs.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = CrossfadeRules.OFF_MS,
        )

    val transportFadeEnabled: StateFlow<Boolean> =
        appSettingsRepository.transportFadeEnabled.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransportFadeRules.DEFAULT_ENABLED,
        )

    val transportFadeDurationMs: StateFlow<Long> =
        appSettingsRepository.transportFadeDurationMs.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransportFadeRules.DEFAULT_DURATION_MS,
        )

    val previousButtonBehavior: StateFlow<PreviousButtonBehavior> =
        appSettingsRepository.previousButtonBehavior.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = PreviousButtonBehavior.DEFAULT,
        )

    val searchTapBehavior: StateFlow<SearchTapBehavior> =
        appSettingsRepository.searchTapBehavior.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = SearchTapBehavior.DEFAULT,
        )

    val appIconChoice: StateFlow<AppIconChoice> =
        appSettingsRepository.appIconChoice.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppIconChoice.DEFAULT,
        )

    val themeMode: StateFlow<ThemeMode> =
        appSettingsRepository.themeMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.SYSTEM,
        )

    val accentColor: StateFlow<AccentColor> =
        appSettingsRepository.accentColor.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AccentColor.MIDNIGHT_VIOLET,
        )

    val compactMode: StateFlow<Boolean> =
        appSettingsRepository.compactMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    val artworkCornerStyle: StateFlow<ArtworkCornerStyle> =
        appSettingsRepository.artworkCornerStyle.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ArtworkCornerStyle.ROUNDED,
        )

    val showSongThumbnails: StateFlow<Boolean> =
        appSettingsRepository.showSongThumbnails.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    val showAlbumInSongRows: StateFlow<Boolean> =
        appSettingsRepository.showAlbumInSongRows.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    val nowPlayingBackground: StateFlow<NowPlayingBackground> =
        appSettingsRepository.nowPlayingBackground.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NowPlayingBackground.ARTWORK,
        )

    val showQueueCount: StateFlow<Boolean> =
        appSettingsRepository.showQueueCount.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    val nowPlayingTimeDisplayMode: StateFlow<NowPlayingTimeDisplayMode> =
        appSettingsRepository.nowPlayingTimeDisplayMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NowPlayingTimeDisplayMode.DURATION,
        )

    val showMilestoneCelebrations: StateFlow<Boolean> =
        appSettingsRepository.showMilestoneCelebrations.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    val wrappedUseArtworkBackgrounds: StateFlow<Boolean> =
        appSettingsRepository.wrappedUseArtworkBackgrounds.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    val wrappedBackgroundIntensity: StateFlow<WrappedBackgroundIntensity> =
        appSettingsRepository.wrappedBackgroundIntensity.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WrappedBackgroundIntensity.MEDIUM,
        )

    val wrappedFallbackTheme: StateFlow<WrappedFallbackTheme> =
        appSettingsRepository.wrappedFallbackTheme.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WrappedFallbackTheme.AUTO,
        )

    val wrappedVisualStyle: StateFlow<WrappedVisualStyle> =
        appSettingsRepository.wrappedVisualStyle.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WrappedVisualStyle.DEFAULT,
        )

    private val libraryScan = LibraryScanCoordinator()
    val libraryScanUiState: StateFlow<LibraryScanUiState> = libraryScan.state

    private val _iconChangeEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val iconChangeEvent: SharedFlow<String> = _iconChangeEvent.asSharedFlow()

    fun setBackupFileMode(mode: BackupFileMode) {
        viewModelScope.launch { appSettingsRepository.setBackupFileMode(mode) }
    }

    fun setAutoBackupFolderUri(uri: String?) {
        viewModelScope.launch {
            appSettingsRepository.setAutoBackupFolderUri(uri)
            autoBackupWorkScheduler.reconcile()
        }
    }

    fun setAutoBackupInterval(interval: AutoBackupInterval) {
        viewModelScope.launch {
            appSettingsRepository.setAutoBackupInterval(interval)
            autoBackupWorkScheduler.reconcile()
        }
    }

    fun backupNowToFolder() {
        if (_folderBackupUiState.value == ExportUiState.Exporting) return
        _folderBackupUiState.value = ExportUiState.Exporting
        viewModelScope.launch {
            _folderBackupUiState.value = when (val result = autoBackupRepository.runNow()) {
                is AutoBackupRepository.Result.Success          -> ExportUiState.Success
                is AutoBackupRepository.Result.NoFolderSelected -> ExportUiState.Error("No backup folder selected.")
                is AutoBackupRepository.Result.FolderUnavailable -> ExportUiState.Error(result.message)
                is AutoBackupRepository.Result.Failure          -> ExportUiState.Error(result.message)
                else                                            -> ExportUiState.Idle
            }
        }
    }

    fun exportTo(uri: Uri) {
        _exportUiState.value = ExportUiState.Exporting
        viewModelScope.launch {
            _exportUiState.value = runCatching {
                backupRepository.exportToUri(uri)
            }.fold(
                onSuccess = { ExportUiState.Success },
                onFailure = { e ->
                    ExportUiState.Error(e.message ?: "Export failed. Please try again.")
                },
            )
        }
    }

    fun setScanMode(scanMode: LibraryScanMode) {
        viewModelScope.launch {
            scanSettingsRepository.setScanMode(scanMode)
        }
    }

    fun setMinimumTrackDurationSeconds(seconds: Int) {
        viewModelScope.launch {
            scanSettingsRepository.setMinimumTrackDurationSeconds(seconds)
        }
    }

    fun setIncludeWhatsAppVoiceNotes(enabled: Boolean) {
        viewModelScope.launch {
            scanSettingsRepository.setIncludeWhatsAppVoiceNotes(enabled)
        }
    }

    /** SE-1: enables or disables one preset scan exclusion. Applies at the next rescan; the path rules live in the data layer. */
    fun setPresetScanExclusion(exclusion: LibraryScanExclusion, excluded: Boolean) {
        viewModelScope.launch {
            scanSettingsRepository.setPresetExclusion(exclusion, excluded)
        }
    }

    fun addSelectedFolderUri(folderUri: String) {
        viewModelScope.launch {
            scanSettingsRepository.addSelectedFolderUri(folderUri)
        }
    }

    fun removeSelectedFolderUri(folderUri: String) {
        viewModelScope.launch {
            scanSettingsRepository.removeSelectedFolderUri(folderUri)
        }
    }

    /** CFE-1: removes one custom folder exclusion (settings only; takes effect at the next rescan, no automatic rescan). */
    fun removeCustomFolderExclusion(folderPath: String) {
        viewModelScope.launch {
            scanSettingsRepository.removeCustomFolderExclusion(folderPath)
        }
    }

    fun setStartupDestination(destination: StartupDestination) {
        viewModelScope.launch {
            appSettingsRepository.setStartupDestination(destination)
        }
    }

    fun setPauseOnAudioDisconnect(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setPauseOnAudioDisconnect(enabled) }
    }

    fun setRememberLastTrack(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setRememberLastTrack(enabled) }
    }

    fun setRememberPosition(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setRememberPosition(enabled) }
    }

    fun setRestoreQueue(enabled: Boolean) {
        viewModelScope.launch { resumeBehaviorRepository.setRestoreQueue(enabled) }
    }

    fun setEqualizerEnabled(enabled: Boolean, expected: com.launchpoint.stereosine.data.settings.OutputProfile? = null) {
        viewModelScope.launch { audioEnhancementsRepository.setEqEnabled(enabled, expected) }
    }

    fun setEqFlat(expected: com.launchpoint.stereosine.data.settings.OutputProfile? = null) {
        viewModelScope.launch { audioEnhancementsRepository.setEqFlatPreset(expected) }
    }

    fun setEqCustomPreset(bandCount: Int, expected: com.launchpoint.stereosine.data.settings.OutputProfile? = null, frequencies: List<Int> = emptyList()) {
        viewModelScope.launch { audioEnhancementsRepository.setEqCustomPreset(bandCount, expected, frequencies) }
    }

    fun resetCustomBandsForDevice(expected: com.launchpoint.stereosine.data.settings.OutputProfile, capabilities: EqualizerCapabilities) {
        viewModelScope.launch { audioEnhancementsRepository.resetCustomBandsForDevice(expected, capabilities) }
    }

    fun setEqPlatformPreset(index: Int, expected: com.launchpoint.stereosine.data.settings.OutputProfile? = null) {
        viewModelScope.launch { audioEnhancementsRepository.setEqPlatformPreset(index, expected) }
    }

    fun setEqCustomBandLevels(levels: List<Int>, expected: com.launchpoint.stereosine.data.settings.OutputProfile? = null, frequencies: List<Int> = emptyList()) {
        viewModelScope.launch { audioEnhancementsRepository.setEqCustomBandLevels(levels, expected, frequencies) }
    }

    fun setEqStereoSinePreset(id: String, expected: com.launchpoint.stereosine.data.settings.OutputProfile? = null) {
        viewModelScope.launch { audioEnhancementsRepository.setEqStereoSinePreset(id, expected) }
    }

    fun setSleepTimer(option: SleepTimerOption, finishCurrentTrack: Boolean = false) {
        playerController.setSleepTimer(option, finishCurrentTrack)
    }

    fun setCustomSleepTimer(durationMs: Long, finishCurrentTrack: Boolean = false) {
        playerController.setCustomSleepTimer(durationMs, finishCurrentTrack)
    }

    fun setNotificationControlsSetting(setting: NotificationControlsSetting) {
        viewModelScope.launch { appSettingsRepository.setNotificationControlsSetting(setting) }
    }

    fun setTransportFadeEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setTransportFadeEnabled(enabled) }
    }

    fun setTransportFadeDurationMs(durationMs: Long) {
        viewModelScope.launch { appSettingsRepository.setTransportFadeDurationMs(durationMs) }
    }

    fun setCrossfadeDurationMs(durationMs: Long) {
        viewModelScope.launch { appSettingsRepository.setCrossfadeDurationMs(durationMs) }
    }

    fun setPreviousButtonBehavior(behavior: PreviousButtonBehavior) {
        viewModelScope.launch { appSettingsRepository.setPreviousButtonBehavior(behavior) }
    }

    fun setSearchTapBehavior(behavior: SearchTapBehavior) {
        viewModelScope.launch { appSettingsRepository.setSearchTapBehavior(behavior) }
    }

    fun setBluetoothResumeMode(mode: HeadphoneResumeMode) {
        viewModelScope.launch { resumeBehaviorRepository.setBluetoothResumeMode(mode) }
    }

    fun setWiredResumeMode(mode: HeadphoneResumeMode) {
        viewModelScope.launch { resumeBehaviorRepository.setWiredResumeMode(mode) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettingsRepository.setThemeMode(mode) }
    }

    fun setAccentColor(color: AccentColor) {
        viewModelScope.launch { appSettingsRepository.setAccentColor(color) }
    }

    fun setCompactMode(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setCompactMode(enabled) }
    }

    fun setArtworkCornerStyle(style: ArtworkCornerStyle) {
        viewModelScope.launch { appSettingsRepository.setArtworkCornerStyle(style) }
    }

    fun setShowSongThumbnails(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setShowSongThumbnails(enabled) }
    }

    fun setShowAlbumInSongRows(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setShowAlbumInSongRows(enabled) }
    }

    fun setNowPlayingBackground(bg: NowPlayingBackground) {
        viewModelScope.launch { appSettingsRepository.setNowPlayingBackground(bg) }
    }

    fun setShowQueueCount(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setShowQueueCount(enabled) }
    }

    fun setNowPlayingTimeDisplayMode(mode: NowPlayingTimeDisplayMode) {
        viewModelScope.launch { appSettingsRepository.setNowPlayingTimeDisplayMode(mode) }
    }

    fun setShowMilestoneCelebrations(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setShowMilestoneCelebrations(enabled) }
    }

    fun setWrappedUseArtworkBackgrounds(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setWrappedUseArtworkBackgrounds(enabled) }
    }

    fun setWrappedBackgroundIntensity(intensity: WrappedBackgroundIntensity) {
        viewModelScope.launch { appSettingsRepository.setWrappedBackgroundIntensity(intensity) }
    }

    fun setWrappedFallbackTheme(theme: WrappedFallbackTheme) {
        viewModelScope.launch { appSettingsRepository.setWrappedFallbackTheme(theme) }
    }

    fun setWrappedVisualStyle(style: WrappedVisualStyle) {
        viewModelScope.launch { appSettingsRepository.setWrappedVisualStyle(style) }
    }

    fun setAppIcon(choice: AppIconChoice) {
        viewModelScope.launch {
            appSettingsRepository.setAppIconChoice(choice)
            runCatching { appIconAliasManager.apply(choice) }
                .onSuccess { result ->
                    val message = if (result.confirmed) {
                        "Icon preference saved. Your phone's launcher controls when the icon updates."
                    } else {
                        "Icon preference saved, but the launcher icon could not be confirmed."
                    }
                    _iconChangeEvent.tryEmit(message)
                }
                .onFailure { e ->
                    Log.e(TAG, "Icon switch failed for ${choice.aliasClassName}", e)
                    _iconChangeEvent.tryEmit(
                        "Icon preference saved, but the launcher icon could not be confirmed."
                    )
                }
        }
    }

    private companion object {
        const val TAG = "StereoSine-IconSwitch"
    }

    fun rescanLibrary() {
        libraryScan.start(viewModelScope) { songRepository.sync() }
    }
}
