package com.launchpoint.stereosine

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.launchpoint.stereosine.data.backup.AutoBackupRepository
import com.launchpoint.stereosine.data.backup.AutoBackupWorkScheduler
import com.launchpoint.stereosine.ui.widget.WavdropWidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class StereoSineApp : Application(), Configuration.Provider {

    @Inject lateinit var widgetUpdater: WavdropWidgetUpdater
    @Inject lateinit var autoBackupRepository: AutoBackupRepository
    @Inject lateinit var autoBackupWorkScheduler: AutoBackupWorkScheduler
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (ENABLE_WIDGET) {
            widgetUpdater.start()
        }
        applicationScope.launch {
            autoBackupWorkScheduler.reconcile()
            autoBackupRepository.runIfDue()
        }
    }

    companion object {
        // Widget V1 — AppWidgetProvider (WavdropWidgetProvider) is registered in
        // AndroidManifest.xml. Flip this flag to false to disable widget update calls.
        const val ENABLE_WIDGET = true
    }
}
