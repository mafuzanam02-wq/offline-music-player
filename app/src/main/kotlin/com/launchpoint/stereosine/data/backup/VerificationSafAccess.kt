package com.launchpoint.stereosine.data.backup

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.FilterInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible

/** Keep ownership on the worker until the blocking operation and its cleanup finish. */
@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> withVerificationSignal(operation: (CancellationSignal) -> T): T {
    val job = currentCoroutineContext().job
    job.ensureActive()
    val signal = CancellationSignal()
    // Completion alone is too late when the job is waiting for a synchronous Binder call.
    val registration = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) signal.cancel()
    }
    try {
        return runInterruptible {
            signal.throwIfCanceled()
            val result = operation(signal)
            job.ensureActive()
            signal.throwIfCanceled()
            result
        }
    } catch (e: OperationCanceledException) {
        throw CancellationException("Backup verification was cancelled.", e)
    } finally {
        registration.dispose()
    }
}

internal fun readVerificationDocument(
    context: Context,
    uri: Uri,
    signal: CancellationSignal,
): BackupDocumentReadResult {
    if (BackupInputReader.declaredSizeExceedsLimit(BackupInputReader.declaredSize(context, uri, signal))) {
        return BackupDocumentReadResult.Rejected(BackupRejectReason.TOO_LARGE)
    }
    signal.throwIfCanceled()
    val stream = try {
        val asset = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
            ?: return BackupDocumentReadResult.Rejected(BackupRejectReason.UNREADABLE)
        try {
            signal.throwIfCanceled()
            asset.createInputStream()
        } catch (failure: Throwable) {
            asset.use { throw failure }
        }
    } catch (e: OperationCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return BackupDocumentReadResult.Rejected(BackupRejectReason.UNREADABLE)
    }
    return try {
        stream.use { source ->
            // The decoder may close its ZIP stream. The acquired stream is owned by this use block.
            val input = object : FilterInputStream(source) {
                private inline fun <T> checkedRead(read: () -> T): T {
                    if (signal.isCanceled) throw CancellationException("Backup verification was cancelled.")
                    return try {
                        read()
                    } catch (e: OperationCanceledException) {
                        throw CancellationException("Backup verification was cancelled.", e)
                    }
                }

                override fun read(): Int = checkedRead { source.read() }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    checkedRead { source.read(bytes, offset, length) }

                override fun close() = Unit
            }
            StereoSineBackupDocumentReader.read(input)
        }
    } catch (_: IOException) {
        BackupDocumentReadResult.Rejected(BackupRejectReason.UNREADABLE)
    }
}
