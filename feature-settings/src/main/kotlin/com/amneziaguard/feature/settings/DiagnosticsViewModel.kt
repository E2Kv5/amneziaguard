package com.amneziaguard.feature.settings

import androidx.lifecycle.ViewModel
import com.amneziaguard.background.FilteringController
import com.amneziaguard.background.FilteringDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val filteringController: FilteringController,
    diagnostics: FilteringDiagnostics,
) : ViewModel() {

    // The engine runs inside FilteringVpnService and reports through the shared
    // diagnostics holder, so the screen just observes it.
    val log: StateFlow<List<String>> = diagnostics.log
    val running: StateFlow<Boolean> = diagnostics.running

    /** Called by the UI once VPN consent is granted. */
    fun startEngine() = filteringController.startFirewall()

    fun stopEngine() = filteringController.stop()
}
