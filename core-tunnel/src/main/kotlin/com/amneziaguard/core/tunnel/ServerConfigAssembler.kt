package com.amneziaguard.core.tunnel

import com.amneziaguard.core.data.repo.ServerRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.tunnel.model.AwgConfigModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Reassembles a server's full .conf text (body + secrets) for the datapath. */
@Singleton
class ServerConfigAssembler @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun activeServerConf(): String? {
        val id = settingsRepository.settings.first().activeServerId ?: return null
        return confFor(id)
    }

    suspend fun confFor(serverId: Long): String? {
        val body = serverRepository.confBody(serverId) ?: return null
        val model = AwgConfigModel.parse(body).getOrNull() ?: return null
        val psks = model.peers.indices.mapNotNull { i ->
            serverRepository.presharedKey(serverId, i)?.let { i to it }
        }.toMap()
        return model.withSecrets(serverRepository.privateKey(serverId), psks).serialize()
    }
}
