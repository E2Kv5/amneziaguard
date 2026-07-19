package com.amneziaguard.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amneziaguard.core.data.repo.ServerRepository
import com.amneziaguard.core.tunnel.model.AwgConfigModel
import com.amneziaguard.core.tunnel.model.AwgInterface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ObfuscationForm(
    val jc: String = "",
    val jmin: String = "",
    val jmax: String = "",
    val s1: String = "",
    val s2: String = "",
    val h1: String = "",
    val h2: String = "",
    val h3: String = "",
    val h4: String = "",
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
)

data class ServerEditUiState(
    val name: String = "",
    val obfuscation: ObfuscationForm = ObfuscationForm(),
    val loaded: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

/**
 * Edits a saved server's name and AWG 2.0 obfuscation parameters. The private
 * key and peer secrets stay untouched in the SecretStore; only the secret-free
 * conf body is rewritten.
 */
@HiltViewModel
class ServerEditViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val serverId: Long = savedStateHandle.get<String>("id")?.toLongOrNull() ?: -1L

    private val _state = MutableStateFlow(ServerEditUiState())
    val state: StateFlow<ServerEditUiState> = _state.asStateFlow()

    private var baseModel: AwgConfigModel? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val body = serverRepository.confBody(serverId)
        val name = serverRepository.serverName(serverId) ?: ""
        val model = body?.let { AwgConfigModel.parse(it).getOrNull() }
        baseModel = model
        val i = model?.iface
        _state.value = ServerEditUiState(
            name = name,
            obfuscation = ObfuscationForm(
                jc = i?.jc?.toString().orEmpty(),
                jmin = i?.jmin?.toString().orEmpty(),
                jmax = i?.jmax?.toString().orEmpty(),
                s1 = i?.s1?.toString().orEmpty(),
                s2 = i?.s2?.toString().orEmpty(),
                h1 = i?.h1.orEmpty(),
                h2 = i?.h2.orEmpty(),
                h3 = i?.h3.orEmpty(),
                h4 = i?.h4.orEmpty(),
                i1 = i?.i1.orEmpty(),
                i2 = i?.i2.orEmpty(),
                i3 = i?.i3.orEmpty(),
                i4 = i?.i4.orEmpty(),
                i5 = i?.i5.orEmpty(),
            ),
            loaded = model != null,
            error = if (model == null) "Could not load this server" else null,
        )
    }

    fun updateName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun updateObfuscation(transform: (ObfuscationForm) -> ObfuscationForm) {
        _state.value = _state.value.copy(obfuscation = transform(_state.value.obfuscation))
    }

    fun save() {
        val model = baseModel ?: return
        val form = _state.value.obfuscation
        val updated = model.copy(
            iface = model.iface.applyObfuscation(form),
        )
        viewModelScope.launch {
            serverRepository.updateBody(
                id = serverId,
                name = _state.value.name.ifBlank { "Server" },
                confBody = updated.serialize(),
                endpoint = updated.endpointSummary,
            )
            _state.value = _state.value.copy(saved = true)
        }
    }

    private fun AwgInterface.applyObfuscation(form: ObfuscationForm): AwgInterface = copy(
        jc = form.jc.toIntOrNull(),
        jmin = form.jmin.toIntOrNull(),
        jmax = form.jmax.toIntOrNull(),
        s1 = form.s1.toIntOrNull(),
        s2 = form.s2.toIntOrNull(),
        h1 = form.h1.ifBlank { null },
        h2 = form.h2.ifBlank { null },
        h3 = form.h3.ifBlank { null },
        h4 = form.h4.ifBlank { null },
        i1 = form.i1.ifBlank { null },
        i2 = form.i2.ifBlank { null },
        i3 = form.i3.ifBlank { null },
        i4 = form.i4.ifBlank { null },
        i5 = form.i5.ifBlank { null },
    )
}
