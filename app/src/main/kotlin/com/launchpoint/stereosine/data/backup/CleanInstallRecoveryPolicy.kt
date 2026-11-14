package com.launchpoint.stereosine.data.backup

import com.launchpoint.stereosine.data.settings.LibraryScanMode

object CleanInstallRecoveryPolicy {
    fun requiresFolderReselection(preferences: BackupPreferences?): Boolean =
        preferences?.scanMode == LibraryScanMode.SELECTED_FOLDERS.name ||
            preferences?.selectedFolderUris?.any { it.isNotBlank() } == true
}
