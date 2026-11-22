package com.launchpoint.stereosine.share

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Returns the click handler for the Settings "Share StereoSine" action. Taps while a copy is in progress are ignored. If the screen
 * leaves composition mid-copy the coroutine is cancelled and the partial file is deleted by [AppApkPreparer].
 * This is the offline-package path only; a verified official link, when one exists, is a separate option to add here later.
 */
@Composable
fun rememberShareAppAction(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = remember { SingleFlightGate() }
    return remember(context) {
        {
            if (gate.tryEnter()) {
                scope.launch {
                    try {
                        val result = context.apkPreparer().prepare(context.apkInstallInfo())
                        if (result is PrepareResult.Ready) {
                            when (context.launchApkShare(result.file, result.kind)) {
                                AppShareLaunch.LAUNCHED -> Unit
                                AppShareLaunch.NO_TARGET -> toast(context, "No app is available to share with.")
                                AppShareLaunch.FAILED -> toast(context, "Couldn't open sharing for StereoSine.")
                            }
                        } else {
                            result.failureMessage()?.let { toast(context, it) }
                        }
                    } finally {
                        gate.exit()
                    }
                }
            }
        }
    }
}

private fun toast(context: android.content.Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
