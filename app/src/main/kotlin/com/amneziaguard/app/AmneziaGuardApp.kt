package com.amneziaguard.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.amneziaguard.background.TunnelController
import com.amneziaguard.core.data.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.AbstractBackend
import javax.inject.Inject

@HiltAndroidApp
class AmneziaGuardApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var tunnelController: TunnelController
    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // When the system starts our VPN via Always-on, bring the last server up.
        AbstractBackend.setAlwaysOnCallback {
            appScope.launch {
                settingsRepository.settings.first().activeServerId?.let {
                    tunnelController.connect(it)
                }
            }
        }
    }
}
