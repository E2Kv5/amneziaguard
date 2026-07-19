package com.amneziaguard.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.amneziaguard.background.TunnelController
import com.amneziaguard.core.data.repo.GroupSeeder
import com.amneziaguard.core.data.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.AbstractBackend

@HiltAndroidApp
class AmneziaGuardApp : Application(), Configuration.Provider {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StartupEntryPoint {
        fun workerFactory(): HiltWorkerFactory
        fun settingsRepository(): SettingsRepository
        fun tunnelController(): TunnelController
        fun groupSeeder(): GroupSeeder
    }

    // Resolved from the Hilt component rather than @Inject lateinit fields:
    // workManagerConfiguration can be read by a ContentProvider-triggered
    // WorkManager init *before* super.onCreate() would have populated an
    // injected field, which would crash. EntryPointAccessors builds the
    // component on demand, so there is no field-injection race.
    private val startup: StartupEntryPoint by lazy {
        EntryPointAccessors.fromApplication(this, StartupEntryPoint::class.java)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(startup.workerFactory())
            .build()

    override fun onCreate() {
        super.onCreate()
        // When the system starts our VPN via Always-on, bring the last server up.
        AbstractBackend.setAlwaysOnCallback {
            appScope.launch {
                startup.settingsRepository().settings.first().activeServerId?.let {
                    startup.tunnelController().connect(it)
                }
            }
        }
        appScope.launch { startup.groupSeeder().seedIfNeeded() }
    }
}
