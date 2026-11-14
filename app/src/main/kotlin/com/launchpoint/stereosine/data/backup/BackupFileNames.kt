package com.launchpoint.stereosine.data.backup

import java.time.LocalDate

/**
 * Single source of truth for backup FILE NAMES (BRAND-03).
 *
 * New backups are written with the StereoSine prefix. Backups written before the rebrand carry the legacy `wavdrop-backup`
 * prefix; they stay recognised for discovery/verification and are never renamed, overwritten or deleted by this app.
 * The file name only selects CANDIDATES: restore/verification decide by CONTENT ([StereoSineBackupDocumentReader]), and the
 * container/format identifiers (`wavdrop_wdbk`, `wavdrop_backup`) inside the file are unchanged.
 *
 * Replace-previous writes the exact [fixedName] in the chosen folder. A legacy `wavdrop-backup.wdbk` in the same folder is a
 * different file and is left untouched; when both exist, [preferNewer] picks the most recently modified one (ties go to the
 * StereoSine name) so verification is deterministic.
 */
internal object BackupFileNames {
    const val CURRENT_PREFIX = "stereosine-backup"
    /** Pre-rebrand prefix. Recognition only; never used to create a file. */
    const val LEGACY_PREFIX = "wavdrop-backup"
    const val EXTENSION = ".wdbk"

    /** Fixed (replace-previous) name for new backups. */
    fun fixedName(): String = CURRENT_PREFIX + EXTENSION

    /** Dated name for new backups; the date uses [LocalDate.toString] (ISO `yyyy-MM-dd`), as before the rebrand. */
    fun datedName(date: LocalDate): String = "$CURRENT_PREFIX-$date$EXTENSION"

    fun isCurrentBrand(name: String?): Boolean = name?.lowercase()?.startsWith(CURRENT_PREFIX) == true

    /**
     * True for backup candidates of either brand: `.wdbk` or legacy `.json`, never a `.tmp` leftover
     * (auto-backup writes `<name>.tmp` beside the real file).
     */
    fun isBackupFileName(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return (lower.startsWith(CURRENT_PREFIX) || lower.startsWith(LEGACY_PREFIX)) &&
            (lower.endsWith(".wdbk") || lower.endsWith(".json")) &&
            !lower.contains(".tmp")
    }

    /** Deterministic pick between two candidates: newer modification time wins; on a tie the StereoSine name wins. */
    fun <T> preferNewer(items: List<T>, name: (T) -> String?, modified: (T) -> Long): T? =
        items.maxWithOrNull(
            compareBy<T> { modified(it) }.thenBy { if (isCurrentBrand(name(it))) 1 else 0 },
        )
}
