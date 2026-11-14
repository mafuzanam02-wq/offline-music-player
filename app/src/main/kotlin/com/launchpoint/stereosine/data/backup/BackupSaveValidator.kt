package com.launchpoint.stereosine.data.backup

object BackupSaveValidator {
    const val VALIDATION_FAILED_MESSAGE =
        "Backup could not be verified after saving. Try a different location."

    fun isSavedBackupValid(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        return StereoSineBackupParser.parse(content).backup != null
    }
}
