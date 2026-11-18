package com.launchpoint.stereosine.data.backup

import com.launchpoint.stereosine.data.settings.AutoBackupInterval

internal object AutoBackupDueRules {
    fun shouldRun(
        interval: AutoBackupInterval,
        lastBackupAtMillis: Long,
        nowMillis: Long,
    ): Boolean =
        interval != AutoBackupInterval.OFF &&
            nowMillis - lastBackupAtMillis >= interval.toMillis()

    fun successfulBackupTimestamp(
        result: AutoBackupRepository.Result,
        nowMillis: Long,
    ): Long? =
        when (result) {
            AutoBackupRepository.Result.Success, AutoBackupRepository.Result.SuccessNearCapacity -> nowMillis
            else -> null
        }
}
