package com.launchpoint.stereosine.data.backup

/**
 * Source format version of a parsed backup.
 *
 * [V1] — original StereoSine backup format; [lastListenedAt][BackupTrackStats.lastListenedAt]
 * is absent and represented as null in the internal model.
 *
 * [V2] — format version 2; [lastListenedAt][BackupTrackStats.lastListenedAt] is a required,
 * non-negative integer; opaque IDs are JSON strings; integrity verification is mandatory.
 */
enum class BackupFormatVersion { V1, V2 }
