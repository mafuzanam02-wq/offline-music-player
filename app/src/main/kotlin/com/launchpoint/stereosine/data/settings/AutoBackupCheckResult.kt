package com.launchpoint.stereosine.data.settings

enum class AutoBackupCheckResult {
    OFF,
    NO_FOLDER_SELECTED,
    NOT_DUE,
    SUCCESS,
    FOLDER_UNAVAILABLE,
    FAILURE,
    SUCCESS_NEAR_CAPACITY,
    FOLDER_AT_CAPACITY,
}
