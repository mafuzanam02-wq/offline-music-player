package com.launchpoint.stereosine.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/* SHARE-APP-01 Android glue: package metadata, FileProvider URI and the ACTION_SEND intent. */

const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Cache sub-directory owned by this feature; must match the narrow cache-path entry in res/xml/file_paths.xml. */
const val APP_SHARE_CACHE_DIR = "app_share"

fun Context.apkShareDir(): File = File(cacheDir, APP_SHARE_CACHE_DIR)

fun Context.apkInstallInfo(): ApkInstallInfo {
    val pm = packageManager
    val pkg = pm.packageInfoCompat(packageName, 0)
    val app = applicationInfo
    return ApkInstallInfo(
        packageName = packageName,
        versionName = pkg?.versionName,
        versionCode = pkg?.longVersionCodeCompat() ?: 0L,
        sourceDir = app.sourceDir,
        splitSourceDirs = app.splitSourceDirs?.toList().orEmpty(),
        debuggable = (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )
}

/** Reads package name, version and signing certificates of an APK file through PackageManager. */
class PackageManagerApkMetadataReader(private val context: Context) : ApkMetadataReader {
    override fun read(apk: File): ApkMetadata? = runCatching {
        val pm = context.packageManager
        val info = pm.packageArchiveInfoCompat(apk.absolutePath) ?: return null
        ApkMetadata(info.packageName ?: return null, info.longVersionCodeCompat(), info.signerDigests())
    }.getOrNull()
}

fun Context.runningApkMetadata(): ApkMetadata? = runCatching {
    val info = packageManager.packageInfoCompat(packageName, signingFlag()) ?: return null
    ApkMetadata(info.packageName, info.longVersionCodeCompat(), info.signerDigests())
}.getOrNull()

fun Context.apkPreparer(): AppApkPreparer =
    AppApkPreparer(apkShareDir(), PackageManagerApkMetadataReader(this), { runningApkMetadata() })

/** content:// URI for the prepared copy, from the dedicated FileProvider cache path only. */
fun Context.apkShareUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

fun buildApkShareIntent(uri: Uri, chooserTitle: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = APK_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("StereoSine", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

enum class AppShareLaunch { LAUNCHED, NO_TARGET, FAILED }

fun Context.launchApkShare(file: File, kind: AppShareKind): AppShareLaunch {
    val uri = try {
        apkShareUri(file)
    } catch (_: IllegalArgumentException) {
        return AppShareLaunch.FAILED
    }
    val title = if (kind == AppShareKind.DEVELOPMENT) "Share StereoSine (development build)" else "Share StereoSine"
    val intent = buildApkShareIntent(uri, title)
    val host = findActivity()
    if (host != null && (host.isFinishing || host.isDestroyed)) return AppShareLaunch.FAILED
    return try {
        (host ?: this).also { if (host == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.startActivity(intent)
        AppShareLaunch.LAUNCHED
    } catch (_: ActivityNotFoundException) {
        AppShareLaunch.NO_TARGET
    } catch (_: SecurityException) {
        AppShareLaunch.FAILED
    }
}

/** User-facing text for each outcome. Opening the chooser is never reported as a delivery. */
fun PrepareResult.failureMessage(): String? = when (this) {
    is PrepareResult.Ready -> null
    is PrepareResult.Unavailable -> when (eligibility) {
        AppShareEligibility.SplitInstallation ->
            "Offline sharing isn't available from this installation because it was installed in several parts."
        else -> "The installable package for this app could not be found, so it can't be shared offline."
    }
    PrepareResult.InsufficientStorage -> "Not enough free space to prepare StereoSine for sharing."
    PrepareResult.SourceUnreadable -> "The installed package couldn't be read, so it can't be shared."
    PrepareResult.CopyIncomplete -> "Preparing the package was interrupted. Please try again."
    PrepareResult.IdentityMismatch -> "The prepared package didn't match this app, so it was not shared."
    PrepareResult.Failed -> "Couldn't prepare StereoSine for sharing."
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun signingFlag(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
private fun PackageManager.packageInfoCompat(name: String, flags: Int) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        getPackageInfo(name, flags)
    }

@Suppress("DEPRECATION")
private fun PackageManager.packageArchiveInfoCompat(path: String) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(signingFlag().toLong()))
    } else {
        getPackageArchiveInfo(path, signingFlag())
    }

@Suppress("DEPRECATION")
private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun android.content.pm.PackageInfo.signerDigests(): Set<String> {
    val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners
    } else {
        signatures
    }
    return sigs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
