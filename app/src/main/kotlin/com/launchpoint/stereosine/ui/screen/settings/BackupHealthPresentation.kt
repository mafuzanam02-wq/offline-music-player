package com.launchpoint.stereosine.ui.screen.settings

import com.launchpoint.stereosine.data.backup.BackupHealthRules
import com.launchpoint.stereosine.data.backup.BackupHealthRules.FailureSource
import com.launchpoint.stereosine.data.backup.BackupHealthRules.Status

/**
 * BACKUP-06A. Wording for the Backup & Migration summary, derived only from a [BackupHealthRules.Decision]. Pure and Compose-free.
 * "Backups are up to date" is reachable only from [Status.HEALTHY_CURRENT].
 */
internal object BackupHealthPresentation {

    const val UP_TO_DATE = "Backups are up to date"

    fun headline(decision: BackupHealthRules.Decision): String = when (decision.status) {
        Status.HEALTHY_CURRENT -> UP_TO_DATE
        Status.BACKUP_DUE -> "Backup due"
        Status.BACKUP_OVERDUE -> "Backup overdue"
        Status.BACKUP_FAILED -> "Last backup attempt failed"
        Status.FOLDER_UNAVAILABLE -> "Backup folder unavailable"
        Status.FOLDER_AT_CAPACITY -> "Backup folder is full"
        Status.FOLDER_NOT_SELECTED -> "No backup folder selected"
        Status.NO_BACKUP_YET -> "No backup yet"
        Status.AUTOMATIC_OFF -> "Automatic backups are off"
        Status.UNKNOWN -> "Backup status unknown"
    }

    /** Practical next step, or null when none is needed. */
    fun guidance(decision: BackupHealthRules.Decision): String? = when (decision.status) {
        Status.HEALTHY_CURRENT -> null
        Status.BACKUP_DUE -> "A scheduled backup is due and will be saved on the next automatic check. You can run Back up now."
        Status.BACKUP_OVERDUE -> "Automatic checks have not saved a backup in time. Run Back up now, then check Backup Verification."
        Status.BACKUP_FAILED -> "Run Back up now. If it fails again, choose a dedicated backup folder."
        Status.FOLDER_UNAVAILABLE, Status.FOLDER_AT_CAPACITY -> FOLDER_RECOVERY
        Status.FOLDER_NOT_SELECTED -> "Choose a backup folder below to protect your stats, playlists, and settings."
        Status.NO_BACKUP_YET ->
            if (decision.lastSuccessWasOtherFolder) "No backup has been saved to this folder yet. Use Back up now."
            else "Use Back up now below to create your first backup."
        Status.AUTOMATIC_OFF -> "Choose Daily, Weekly or Monthly below to back up automatically."
        Status.UNKNOWN -> "Run Back up now to record a fresh, reliable backup time."
    }

    const val FOLDER_RECOVERY =
        "1. Choose a dedicated, empty backup folder (not Music or Downloads).\n" +
            "2. Tap Back up now.\n" +
            "3. Open Backup Verification to confirm the new backup."

    /** True when the card should offer a direct "Choose backup folder" action. */
    fun offersFolderAction(decision: BackupHealthRules.Decision): Boolean = decision.status in setOf(
        Status.FOLDER_UNAVAILABLE, Status.FOLDER_AT_CAPACITY, Status.FOLDER_NOT_SELECTED,
    )

    /** "Automatic check, 24 Nov 2026, 09:58 — Folder unavailable" for the failure that is still unresolved; null when none. */
    fun failureLine(decision: BackupHealthRules.Decision, formatTime: (Long) -> String): String? {
        val failure = decision.unresolvedFailure ?: return null
        val who = if (failure.source == FailureSource.MANUAL) "Back up now" else "Automatic check"
        return "$who, ${formatTime(failure.atMillis)} — ${failure.result.toAutoBackupStatusText()}"
    }

    fun lastSavedValue(decision: BackupHealthRules.Decision, formatTime: (Long) -> String): String {
        val at = decision.lastKnownSuccessAtMillis ?: return "Never"
        val base = formatTime(at)
        return if (decision.lastSuccessWasOtherFolder) "$base (previous folder)" else base
    }

    const val VERIFICATION_NOTE =
        "Saved and read back when written. This is not an independent check. Open Backup Verification to check the current folder."

    /**
     * Saved is not independently verified. The Backup Verification result cannot be attributed to the latest saved backup or the
     * current folder, so the summary never reports it; it only points the user to that screen.
     */
    fun verificationNote(decision: BackupHealthRules.Decision): String? =
        if (decision.lastKnownSuccessAtMillis == null) null else VERIFICATION_NOTE
}
