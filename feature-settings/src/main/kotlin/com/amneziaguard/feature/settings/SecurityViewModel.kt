package com.amneziaguard.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.firewall.RootFirewallController
import com.amneziaguard.core.firewall.leak.LeakCheckScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecurityUiState(
    val killSwitchEnabled: Boolean,
    val blockWhenDisconnected: Boolean,
    val dnsLeakProtection: Boolean,
    val rootModeEnabled: Boolean,
    val lastLeakCheckOk: Boolean?,
    val lastLeakCheckAt: Long?,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val rootController: RootFirewallController,
    private val leakCheckScheduler: LeakCheckScheduler,
) : ViewModel() {

    val uiState: StateFlow<SecurityUiState> = settingsRepository.settings.map {
        SecurityUiState(
            killSwitchEnabled = it.killSwitchEnabled,
            blockWhenDisconnected = it.blockWhenDisconnected,
            dnsLeakProtection = it.dnsLeakProtection,
            rootModeEnabled = it.rootModeEnabled,
            lastLeakCheckOk = it.lastLeakCheckOk,
            lastLeakCheckAt = it.lastLeakCheckAt,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SecurityUiState(false, false, true, false, null, null),
    )

    fun setKillSwitch(enabled: Boolean) = update { settingsRepository.setKillSwitchEnabled(enabled) }
    fun setBlockWhenDisconnected(enabled: Boolean) = update { settingsRepository.setBlockWhenDisconnected(enabled) }
    fun setDnsLeakProtection(enabled: Boolean) = update { settingsRepository.setDnsLeakProtection(enabled) }

    /** Enabling root mode is gated on an actual root check; it silently stays off otherwise. */
    fun setRootMode(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val available = rootController.isRootAvailable()
                settingsRepository.setRootModeEnabled(available)
            } else {
                settingsRepository.setRootModeEnabled(false)
            }
        }
    }

    fun checkForLeaks() {
        leakCheckScheduler.runOnce()
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
