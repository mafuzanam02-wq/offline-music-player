package com.launchpoint.stereosine.data.backup

import com.launchpoint.stereosine.data.settings.AutoBackupCheckResult
import com.launchpoint.stereosine.data.settings.AutoBackupInterval

/**
 * BACKUP-06A. A pure, deterministic decision about backup health. It takes the current time as a parameter and reads nothing else.
 *
 * Persisted facts and what they actually prove:
 *  - [Input.lastSuccessAtMillis] (`last_auto_backup_at_millis`): a backup document was published to the selected folder and read back
 *    by the saver at that moment (automatic OR manual). It is NOT the Backup Verification screen's independent check.
 *  - [Input.lastAutoCheckAtMillis]/[Input.lastAutoResult]: the most recent automatic worker check only (overwritten each run).
 *  - [Input.lastManualAtMillis]/[Input.lastManualResult]: the most recent "Back up now" attempt only, kept separate from the above.
 *  - [Input.folderChangedAtMillis]: when the destination folder last changed; 0 when unknown.
 *
 * BACKUP-06A-R1: the Backup Verification screen's result is deliberately NOT an input. It identifies a backup only by display name,
 * size and an embedded export time (no folder or document identity), can be VERIFIED while its folder search is INCOMPLETE, and is
 * not refreshed when a manual backup or a folder change happens on the Backup & Migration screen. Attributing it to the latest
 * saved backup could therefore be false.
 */
internal object BackupHealthRules {

    enum class Status {
        /** A backup is on record, within its interval, with no unresolved later failure. */
        HEALTHY_CURRENT,
        /** The interval has elapsed but a scheduled check is still within its normal delay window. */
        BACKUP_DUE,
        /** The interval plus the scheduling allowance has elapsed without a newer success. */
        BACKUP_OVERDUE,
        /** The latest relevant attempt failed for a reason other than folder access or capacity. */
        BACKUP_FAILED,
        /** The latest relevant attempt could not access or reliably list the selected folder. */
        FOLDER_UNAVAILABLE,
        /** The latest relevant attempt was refused because the folder is full for StereoSine's purposes. */
        FOLDER_AT_CAPACITY,
        /** Automatic backups are on but no folder is chosen. */
        FOLDER_NOT_SELECTED,
        /** No backup is on record for the current folder. */
        NO_BACKUP_YET,
        AUTOMATIC_OFF,
        /** The records cannot be trusted (clock anomaly). */
        UNKNOWN,
    }

    enum class FailureSource { AUTOMATIC, MANUAL }

    data class Failure(val source: FailureSource, val result: AutoBackupCheckResult, val atMillis: Long)

    data class Input(
        val interval: AutoBackupInterval,
        val folderSelected: Boolean,
        /** False when a stored folder URI no longer has a persisted permission. null = not known. */
        val folderPermissionValid: Boolean? = null,
        val lastSuccessAtMillis: Long,
        val lastAutoCheckAtMillis: Long,
        val lastAutoResult: AutoBackupCheckResult?,
        val lastManualAtMillis: Long = 0L,
        val lastManualResult: AutoBackupCheckResult? = null,
        val folderChangedAtMillis: Long = 0L,
    )

    data class Decision(
        val status: Status,
        /** Last saved backup, shown even when it is no longer current. null if none or its timestamp is invalid. */
        val lastKnownSuccessAtMillis: Long?,
        /** True when [lastKnownSuccessAtMillis] predates the current folder, i.e. it was saved somewhere else. */
        val lastSuccessWasOtherFolder: Boolean,
        /** The latest failure that no later success or folder change has resolved. */
        val unresolvedFailure: Failure?,
    ) {
        /** Only a current backup with no known problem may claim "up to date". */
        val isHealthy: Boolean get() = status == Status.HEALTHY_CURRENT
    }

    /** Tolerance for clocks that differ slightly (e.g. a manual time change). Anything further ahead is untrustworthy. */
    const val CLOCK_SKEW_TOLERANCE_MS: Long = 5L * 60 * 1000

    private const val HOUR_MS = 60L * 60 * 1000
    private const val DAY_MS = 24L * HOUR_MS

    /**
     * WorkManager runs periodic work with a constraint-dependent delay, not at an exact time, and the worker itself only checks every
     * 24 h. This is how long past the interval a backup may be before it is called overdue rather than merely due.
     * DAILY 6 h, WEEKLY 42 h, MONTHLY 3 days (a quarter of the interval, bounded to 6 h..3 days).
     */
    fun schedulingAllowanceMs(interval: AutoBackupInterval): Long =
        if (interval == AutoBackupInterval.OFF) 0L else (interval.toMillis() / 4).coerceIn(6 * HOUR_MS, 3 * DAY_MS)

    private val failureResults = setOf(
        AutoBackupCheckResult.FAILURE,
        AutoBackupCheckResult.FOLDER_UNAVAILABLE,
        AutoBackupCheckResult.FOLDER_AT_CAPACITY,
        AutoBackupCheckResult.NO_FOLDER_SELECTED,
    )

    fun decide(input: Input, nowMillis: Long): Decision {
        val ceiling = nowMillis + CLOCK_SKEW_TOLERANCE_MS
        fun valid(t: Long) = t > 0L
        fun future(t: Long) = valid(t) && t > ceiling

        // A timestamp from the future cannot be ordered against anything; never infer health from it.
        val clockAnomaly = future(input.lastSuccessAtMillis) || future(input.lastAutoCheckAtMillis) ||
            future(input.lastManualAtMillis) || future(input.folderChangedAtMillis)

        val success = input.lastSuccessAtMillis.takeIf { valid(it) && !future(it) }
        val folderChangedAt = input.folderChangedAtMillis.takeIf { valid(it) && !future(it) } ?: 0L
        val successIsCurrentFolder = success != null && success > folderChangedAt
        val currentSuccess = success.takeIf { successIsCurrentFolder }
        val otherFolderSuccess = success != null && !successIsCurrentFolder

        // A failure is unresolved only when nothing newer settled it: a later success, or the user choosing a different folder.
        val resolvedBefore = maxOf(currentSuccess ?: 0L, folderChangedAt)
        val candidates = buildList {
            if (valid(input.lastAutoCheckAtMillis) && input.lastAutoResult in failureResults) {
                add(Failure(FailureSource.AUTOMATIC, input.lastAutoResult!!, input.lastAutoCheckAtMillis))
            }
            if (valid(input.lastManualAtMillis) && input.lastManualResult in failureResults) {
                add(Failure(FailureSource.MANUAL, input.lastManualResult!!, input.lastManualAtMillis))
            }
        }.filter { it.atMillis > resolvedBefore }
            // "No folder selected" is moot once a folder exists; it cannot be a current problem for a selected folder.
            .filterNot { it.result == AutoBackupCheckResult.NO_FOLDER_SELECTED && input.folderSelected }
        val failure = candidates.maxByOrNull { it.atMillis }

        fun decision(status: Status) = Decision(
            status = status,
            lastKnownSuccessAtMillis = success,
            lastSuccessWasOtherFolder = otherFolderSuccess,
            unresolvedFailure = failure,
        )

        if (clockAnomaly) return decision(Status.UNKNOWN)
        if (input.interval == AutoBackupInterval.OFF) return decision(Status.AUTOMATIC_OFF)
        if (!input.folderSelected) return decision(Status.FOLDER_NOT_SELECTED)
        if (input.folderPermissionValid == false) return decision(Status.FOLDER_UNAVAILABLE)

        if (failure != null) {
            return decision(
                when (failure.result) {
                    AutoBackupCheckResult.FOLDER_UNAVAILABLE -> Status.FOLDER_UNAVAILABLE
                    AutoBackupCheckResult.FOLDER_AT_CAPACITY -> Status.FOLDER_AT_CAPACITY
                    else -> Status.BACKUP_FAILED
                },
            )
        }

        if (currentSuccess == null) return decision(Status.NO_BACKUP_YET)

        val age = (nowMillis - currentSuccess).coerceAtLeast(0L)
        val interval = input.interval.toMillis()
        return decision(
            when {
                age < interval -> Status.HEALTHY_CURRENT
                age < interval + schedulingAllowanceMs(input.interval) -> Status.BACKUP_DUE
                else -> Status.BACKUP_OVERDUE
            },
        )
    }
}
