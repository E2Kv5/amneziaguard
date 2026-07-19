package com.amneziaguard.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amneziaguard.core.data.settings.SettingsRepository
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
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SecurityUiState> = settingsRepository.settings.map {
        SecurityUiState(
            killSwitchEnabled = it.killSwitchEnabled,
            blockWhenDisconnected = it.blockWhenDisconnected,
            dnsLeakProtection = it.dnsLeakProtection,
            rootModeEnabled = it.rootModeEnabled,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SecurityUiState(false, false, true, false),
    )

    fun setKillSwitch(enabled: Boolean) = update { settingsRepository.setKillSwitchEnabled(enabled) }
    fun setBlockWhenDisconnected(enabled: Boolean) = update { settingsRepository.setBlockWhenDisconnected(enabled) }
    fun setDnsLeakProtection(enabled: Boolean) = update { settingsRepository.setDnsLeakProtection(enabled) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
