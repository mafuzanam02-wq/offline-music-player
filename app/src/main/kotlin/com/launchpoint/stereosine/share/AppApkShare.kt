package com.launchpoint.stereosine.share

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/*
 * SHARE-APP-01. Offline sharing of the installed StereoSine package. This file is Android-free on purpose (java.io + coroutines only)
 * so the eligibility, copy and verification rules run in plain JVM tests. The Android glue is in AppApkShareAndroid.kt and the UI in
 * AppShareHost.kt. The user-facing action ("Share StereoSine") is deliberately separate from this mechanism: a verified official
 * download link can later be offered as an alternative without touching the copy/verify code. No such link exists today.
 */

/** What Android reports about how the running app is installed. */
data class ApkInstallInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    /** Path of the base APK as reported by ApplicationInfo.sourceDir. */
    val sourceDir: String?,
    /** ApplicationInfo.splitSourceDirs; non-empty means the installation needs more than one APK (App Bundle / split install). */
    val splitSourceDirs: List<String>,
    val debuggable: Boolean,
)

/** Package identity read from an APK file. Signers are SHA-256 digests (hex) of the signing certificates. */
data class ApkMetadata(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)

fun interface ApkMetadataReader {
    /** Parses [apk]; null when it is not a readable APK. */
    fun read(apk: File): ApkMetadata?
}

enum class AppShareKind {
    /** A release installation: shared without extra warnings. */
    RELEASE,

    /** A debug / debuggable installation: shareable only for testing, and always labelled as such. Never presented as a release. */
    DEVELOPMENT,
}

sealed interface AppShareEligibility {
    data class Eligible(val kind: AppShareKind, val source: File) : AppShareEligibility
    data object NoStandaloneApk : AppShareEligibility
    data object SplitInstallation : AppShareEligibility
    data object SourceUnreadable : AppShareEligibility
}

object AppSharePolicy {
    private const val DEBUG_SUFFIX = ".debug"

    /**
     * Standalone vs split vs debug. A split installation (anything in splitSourceDirs) can never be shared: the base APK alone is
     * not installable on its own, so it is refused rather than exported.
     */
    fun evaluate(info: ApkInstallInfo): AppShareEligibility {
        val sourcePath = info.sourceDir?.takeIf { it.isNotBlank() } ?: return AppShareEligibility.NoStandaloneApk
        if (info.splitSourceDirs.isNotEmpty()) return AppShareEligibility.SplitInstallation
        val source = File(sourcePath)
        if (!source.isFile || !source.canRead() || source.length() <= 0L) return AppShareEligibility.SourceUnreadable
        val kind = if (info.debuggable || info.packageName.endsWith(DEBUG_SUFFIX)) AppShareKind.DEVELOPMENT else AppShareKind.RELEASE
        return AppShareEligibility.Eligible(kind, source)
    }

    /** Readable, sanitized file name for the recipient. Development builds are named as such. */
    fun fileName(info: ApkInstallInfo, kind: AppShareKind): String {
        val version = (info.versionName ?: "").replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_').take(40)
            .ifEmpty { "v${info.versionCode}" }
        val suffix = if (kind == AppShareKind.DEVELOPMENT) "-development" else ""
        return "StereoSine-$version$suffix.apk"
    }
}

/** Only one preparation may run at a time; further taps while one is running are ignored. */
class SingleFlightGate {
    private val busy = AtomicBoolean(false)
    fun tryEnter(): Boolean = busy.compareAndSet(false, true)
    fun exit() = busy.set(false)
    val isBusy: Boolean get() = busy.get()
}

sealed interface PrepareResult {
    /** [file] is a complete copy of the installed APK whose identity matched the running package. Safe to offer. */
    data class Ready(val file: File, val kind: AppShareKind) : PrepareResult
    data class Unavailable(val eligibility: AppShareEligibility) : PrepareResult
    data object InsufficientStorage : PrepareResult
    data object SourceUnreadable : PrepareResult
    data object CopyIncomplete : PrepareResult
    data object IdentityMismatch : PrepareResult
    data object Failed : PrepareResult
}

object ApkIdentity {
    /** The copy must be the running app: same package, same version, and the same (non-empty) set of signing certificates. */
    fun matches(copy: ApkMetadata?, running: ApkMetadata?, expectedPackage: String): Boolean =
        copy != null && running != null &&
            copy.packageName == expectedPackage && running.packageName == expectedPackage &&
            copy.versionCode == running.versionCode &&
            copy.signerSha256.isNotEmpty() && copy.signerSha256 == running.signerSha256
}

/**
 * Retention policy for prepared APK copies (SHARE-APP-01 lifetime correction).
 *
 * Android gives no callback saying a receiving app has finished reading a shared content:// URI, and the Sharesheet hands access over
 * asynchronously (a Bluetooth or Nearby transfer can start minutes after the chooser closes). So a prepared APK is never deleted
 * because a later share started. Every preparation writes its own immutable file under a unique directory, and files are removed
 * only by age:
 *  - a file younger than [minSafeAgeMs] is never deleted by this code;
 *  - a file older than [retentionMs] is always deleted by housekeeping (also stale partial copies left by a crash);
 *  - at most [maxRetained] prepared files exist. If that cap is reached and every file is still younger than [minSafeAgeMs], the newest
 *    one is re-verified and reused instead of deleting anything.
 * Bound: at most [maxRetained] complete copies plus one in-progress copy, i.e. (maxRetained + 1) x APK size.
 * Residual risk: a transfer still reading a file older than [minSafeAgeMs] when the cap forces its eviction, or still reading after
 * [retentionMs], can fail. Android cannot tell us otherwise, and the OS or user may also clear the app cache at any time.
 */
data class ApkRetentionPolicy(
    val minSafeAgeMs: Long = 60L * 60 * 1000,
    val retentionMs: Long = 24L * 60 * 60 * 1000,
    val maxRetained: Int = 4,
)

/**
 * Copies the installed APK into its own directory under [shareDir] ("<epochMillis>-<random>/<StereoSine-x.apk>"), checks it is
 * complete and is the running package, and only then returns it. The copy is written to a ".part" file and renamed on success, so a
 * partial or interrupted copy is never visible under the final name, and a failed or cancelled preparation deletes only its own
 * directory. Completed files are immutable and never overwritten; see [ApkRetentionPolicy] for when they are removed.
 */
class AppApkPreparer(
    private val shareDir: File,
    private val metadataReader: ApkMetadataReader,
    private val runningMetadata: () -> ApkMetadata?,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val openSource: (File) -> InputStream = { FileInputStream(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val policy: ApkRetentionPolicy = ApkRetentionPolicy(),
    private val random: () -> String = { java.util.UUID.randomUUID().toString().take(8) },
) {
    suspend fun prepare(info: ApkInstallInfo): PrepareResult = withContext(Dispatchers.IO) {
        val eligible = when (val e = AppSharePolicy.evaluate(info)) {
            is AppShareEligibility.Eligible -> e
            else -> return@withContext PrepareResult.Unavailable(e)
        }
        val source = eligible.source
        if (!shareDir.isDirectory && !shareDir.mkdirs()) return@withContext PrepareResult.Failed
        val now = clock()
        housekeep(now)

        val expectedSize = source.length()
        val name = AppSharePolicy.fileName(info, eligible.kind)

        // Cap reached and nothing is old enough to evict safely: reuse the newest verified copy rather than delete a live one.
        if (completedEntries(now).size >= policy.maxRetained) {
            return@withContext reuseNewest(info, eligible, expectedSize, now) ?: PrepareResult.Failed
        }

        if (freeSpace(shareDir) < expectedSize + MIN_FREE_MARGIN_BYTES) return@withContext PrepareResult.InsufficientStorage

        val dir = File(shareDir, "$now-${random()}")
        if (!dir.mkdirs()) return@withContext PrepareResult.Failed
        val part = File(dir, "$name.part")
        val target = File(dir, name)
        var done = false
        try {
            try {
                openSource(source).use { input ->
                    FileOutputStream(part).use { out ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            coroutineContext.ensureActive()
                        }
                        out.flush()
                        out.fd.sync()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                return@withContext if (part.exists()) classify(e) else PrepareResult.SourceUnreadable
            } catch (e: SecurityException) {
                return@withContext PrepareResult.SourceUnreadable
            } catch (e: IOException) {
                return@withContext classify(e)
            }

            // Complete means: exactly the bytes of the source as it was before copying, and the source did not change underneath us.
            if (part.length() != expectedSize || source.length() != expectedSize) return@withContext PrepareResult.CopyIncomplete

            // Verification happens on the copy that would be handed to the recipient, before it gets its final name.
            val copyMetadata = metadataReader.read(part)
            if (!ApkIdentity.matches(copyMetadata, runningMetadata(), info.packageName)) return@withContext PrepareResult.IdentityMismatch

            if (!part.renameTo(target)) return@withContext PrepareResult.Failed
            done = true
            PrepareResult.Ready(target, eligible.kind)
        } finally {
            if (!done) dir.deleteRecursively() // only this preparation's own directory
        }
    }

    private fun reuseNewest(info: ApkInstallInfo, eligible: AppShareEligibility.Eligible, expectedSize: Long, now: Long): PrepareResult? {
        val newest = completedEntries(now).minByOrNull { it.ageMs } ?: return null
        if (newest.apk.length() != expectedSize) return null
        if (!ApkIdentity.matches(metadataReader.read(newest.apk), runningMetadata(), info.packageName)) return null
        return PrepareResult.Ready(newest.apk, eligible.kind)
    }

    private class Completed(val dir: File, val apk: File, val ageMs: Long)

    private fun ageOf(entry: File, now: Long): Long {
        val stamp = entry.name.substringBefore('-', "").toLongOrNull() ?: entry.lastModified()
        return now - stamp
    }

    private fun completedEntries(now: Long): List<Completed> =
        shareDir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d ->
            val apk = d.listFiles().orEmpty().firstOrNull { it.isFile && it.name.endsWith(".apk") }
            apk?.let { Completed(d, it, ageOf(d, now)) }
        }

    /** Age-based cleanup only. Anything younger than the safe age is always kept, even above the cap (the cap is then met by reuse). */
    private fun housekeep(now: Long) {
        // Past retention: finished copies, stale partial directories left by a crash, and loose files from older builds.
        shareDir.listFiles().orEmpty().forEach { f ->
            if (ageOf(f, now) > policy.retentionMs) f.deleteRecursively()
        }
        // Keep room for the new copy: evict the oldest copies, but only those already past the safe age.
        var excess = completedEntries(now).size - (policy.maxRetained - 1)
        for (c in completedEntries(now).sortedByDescending { it.ageMs }) {
            if (excess <= 0) break
            if (c.ageMs >= policy.minSafeAgeMs) { c.dir.deleteRecursively(); excess-- }
        }
    }

    private fun classify(e: IOException): PrepareResult =
        if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space left", ignoreCase = true) == true) {
            PrepareResult.InsufficientStorage
        } else {
            PrepareResult.CopyIncomplete
        }

    /** Deletes everything. For tests and explicit maintenance only: never called while sharing, because it would break live URIs. */
    fun clearShareDir() {
        shareDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val MIN_FREE_MARGIN_BYTES = 8L * 1024 * 1024
    }
}
