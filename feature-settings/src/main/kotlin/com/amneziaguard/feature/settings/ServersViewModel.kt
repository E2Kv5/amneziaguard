package com.amneziaguard.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amneziaguard.core.data.model.Server
import com.amneziaguard.core.data.repo.ServerRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.tunnel.model.AwgConfigModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServersUiState(
    val servers: List<Server>,
    val activeServerId: Long?,
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<ServersUiState> = combine(
        serverRepository.observeServers(),
        settingsRepository.settings,
    ) { servers, settings ->
        ServersUiState(servers, settings.activeServerId)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ServersUiState(emptyList(), null),
    )

    fun selectServer(id: Long) {
        viewModelScope.launch { settingsRepository.setActiveServerId(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { serverRepository.delete(id) }
    }

    /**
     * Parses a pasted .conf, splits secrets out and stores the server.
     * Returns the new server id, or an error message.
     */
    suspend fun import(name: String, confText: String): Result<Long> {
        val parsed = AwgConfigModel.parse(confText).getOrElse {
            return Result.failure(it)
        }
        val split = parsed.splitSecrets()
        val serverName = name.ifBlank { defaultName(parsed) }
        val id = serverRepository.save(
            name = serverName,
            confBody = split.config.serialize(),
            endpoint = parsed.endpointSummary,
            privateKey = split.privateKey,
            presharedKeys = split.presharedKeys,
        )
        settingsRepository.setActiveServerId(id)
        return Result.success(id)
    }

    private fun defaultName(model: AwgConfigModel): String =
        model.endpointSummary.substringBefore(':').ifBlank { "Server" }
}
