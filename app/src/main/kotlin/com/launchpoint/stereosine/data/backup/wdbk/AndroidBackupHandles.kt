package com.launchpoint.stereosine.data.backup.wdbk

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/** [BackupFileHandle] over a SAF/content Uri (manual export destination). */
class UriBackupHandle(private val context: Context, private val uri: Uri) : BackupFileHandle {
    override fun openOutput(mode: String): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(uri, mode) }.getOrNull()

    override fun openInput(): InputStream? =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    override fun delete(): Boolean = false // the user's chosen destination is never deleted by StereoSine
}
