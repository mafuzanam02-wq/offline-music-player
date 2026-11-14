package com.launchpoint.stereosine.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * File-selection gating for restore/import flows.
 *
 * SAF pickers cannot reliably filter by extension (and providers often mislabel
 * MIME types), so each flow validates the selected file AFTER picking:
 *  - StereoSine restore decides by CONTENT (see [StereoSineBackupDocumentReader]): a `.wdbk` container or a
 *    legacy `.json` backup is recognised by its bytes, never by file name, extension or MIME type.
 *  - BlackPlayer import accepts only `.bpstat` names.
 */
object ImportFileValidation {

    const val STEREOSINE_WRONG_FILE_MESSAGE   = "Choose a StereoSine backup file."
    const val STEREOSINE_NOT_A_BACKUP_MESSAGE = "This does not look like a StereoSine backup file."
    const val BPSTAT_WRONG_FILE_MESSAGE    = "Choose a BlackPlayer .bpstat file."

    /** Resolves the user-visible file name via OpenableColumns, falling back to the URI path. */
    fun displayName(context: Context, uri: Uri): String? {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0 && cursor.moveToFirst() && !cursor.isNull(col)) {
                        cursor.getString(col)
                    } else {
                        null
                    }
                }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** Name hint only (UI/diagnostics). It never decides how a document is parsed. */
    fun isLikelyStereoSineBackupFileName(name: String?): Boolean {
        val lower = name?.trim()?.lowercase() ?: return false
        return lower.endsWith(".wdbk") || lower.endsWith(".json")
    }

    fun isLikelyBlackPlayerStatsFileName(name: String?): Boolean =
        name?.trim()?.lowercase()?.endsWith(".bpstat") == true

    /**
     * Cheap structural sniff used when the file name is unavailable or not `.json`:
     * a StereoSine backup is a JSON object that declares the Android wavdrop_backup
     * format or the Desktop appName.
     * This avoids running the full parser on arbitrary binary/text files.
     */
    fun isLikelyStereoSineBackupContent(content: String): Boolean {
        val head = content.trimStart()
        return head.startsWith("{") &&
            (
                content.contains("\"${StereoSineBackupParser.SUPPORTED_FORMAT}\"") ||
                    DesktopWavdropBackupParser.isDesktopBackupContent(content)
                )
    }
}
