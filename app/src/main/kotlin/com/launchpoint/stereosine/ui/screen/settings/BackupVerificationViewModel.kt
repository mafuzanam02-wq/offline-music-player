package com.launchpoint.stereosine.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.stereosine.data.backup.AutoBackupRepository
import com.launchpoint.stereosine.data.backup.BackupVerificationRepository
import com.launchpoint.stereosine.data.backup.BackupVerificationResult
import com.launchpoint.stereosine.data.settings.AutoBackupCheckResult
import com.launchpoint.stereosine.data.settings.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface BackupVerificationUiState {
    data object Loading : BackupVerificationUiState
    /** [folderUri] is the folder this result was produced for; the UI shows it only while that is still the selected folder. */
    data class Ready(val result: BackupVerificationResult, val folderUri: String? = null) : BackupVerificationUiState
}

@HiltViewModel
class BackupVerificationViewModel @Inject constructor(
    private val verificationRepository: BackupVerificationRepository,
    private val autoBackupRepository: AutoBackupRepository,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val coordinator = BackupVerificationCoordinator(viewModelScope) {
        // The folder is read before verifying so the result is labelled with the folder it was requested for.
        val folder = appSettingsRepository.autoBackupFolderUri.first()
        BackupVerificationUiState.Ready(verificationRepository.verifyLatestBackup(), folder)
    }
    val uiState: StateFlow<BackupVerificationUiState> = coordinator.state

    init {
        // A different folder, or any newly saved backup (including a background automatic one while the screen is open),
        // makes the displayed result obsolete.
        coordinator.requestOnChange(appSettingsRepository.autoBackupFolderUri)
        coordinator.requestOnChange(appSettingsRepository.lastAutoBackupAtMillis)
    }

    private val _backupNowState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val backupNowState: StateFlow<ExportUiState> = _backupNowState.asStateFlow()


    /** Runs the first verification when the screen opens; safe across recompositions. */
    fun verifyOnEntry() {
        coordinator.requestOnce()
    }

    fun verifyAgain() {
        coordinator.request()
    }

    /** BACKUP-06B: hides any current result now and verifies again once [operation] has finished, success or failure. */
    fun verifyAfter(operation: Job) {
        coordinator.requestAfter(operation)
    }

    /** Creates a backup via the existing backup path, then re-verifies. */
    fun createBackupNow() {
        if (_backupNowState.value == ExportUiState.Exporting) return
        _backupNowState.value = ExportUiState.Exporting
        coordinator.invalidate()
        viewModelScope.launch {
            _backupNowState.value = when (val result = autoBackupRepository.runNow()) {
                is AutoBackupRepository.Result.Success           -> ExportUiState.Success
                AutoBackupRepository.Result.SuccessNearCapacity ->
                    ExportUiState.Advisory(AutoBackupCheckResult.SUCCESS_NEAR_CAPACITY.toAutoBackupStatusText())
                AutoBackupRepository.Result.FolderAtCapacity ->
                    ExportUiState.Error(AutoBackupCheckResult.FOLDER_AT_CAPACITY.toAutoBackupStatusText())
                is AutoBackupRepository.Result.NoFolderSelected  -> ExportUiState.Error("No backup folder selected.")
                is AutoBackupRepository.Result.FolderUnavailable -> ExportUiState.Error(result.message)
                is AutoBackupRepository.Result.Failure           -> ExportUiState.Error(result.message)
                else                                             -> ExportUiState.Idle
            }
            verifyAgain()
        }
    }

    /** Saves a freshly picked backup folder (same semantics as Backup & Migration), then re-verifies. */
    fun onBackupFolderPicked(uri: String, permissionGranted: Boolean) {
        coordinator.invalidate()
        viewModelScope.launch {
            appSettingsRepository.setAutoBackupFolderUri(uri)
            if (permissionGranted) {
                appSettingsRepository.setNeedsAutoBackupFolderSelectionAfterRestore(false)
            }
            verifyAgain()
        }
    }
}
