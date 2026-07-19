package com.amneziaguard.core.data.repo

import com.amneziaguard.core.data.db.ServerDao
import com.amneziaguard.core.data.db.ServerEntity
import com.amneziaguard.core.data.model.Server
import com.amneziaguard.core.data.secret.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores server configs with secrets split out: the .conf body (without
 * PrivateKey / PresharedKey lines) goes to Room, the keys go to [SecretStore].
 * Reassembly of the full config text happens in :core-tunnel.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val secretStore: SecretStore,
) {
    fun observeServers(): Flow<List<Server>> =
        serverDao.observeAll().map { list ->
            list.map { Server(it.id, it.name, it.endpoint, it.createdAt) }
        }

    suspend fun confBody(id: Long): String? = serverDao.byId(id)?.confBody

    suspend fun serverName(id: Long): String? = serverDao.byId(id)?.name

    fun privateKey(id: Long): String? = secretStore.getPrivateKey(id)

    fun presharedKey(id: Long, peerIndex: Int): String? =
        secretStore.getPresharedKey(id, peerIndex)

    suspend fun save(
        name: String,
        confBody: String,
        endpoint: String,
        privateKey: String?,
        presharedKeys: Map<Int, String>,
    ): Long {
        val id = serverDao.insert(
            ServerEntity(
                name = name,
                confBody = confBody,
                endpoint = endpoint,
                createdAt = System.currentTimeMillis(),
            )
        )
        privateKey?.let { secretStore.putPrivateKey(id, it) }
        presharedKeys.forEach { (peerIndex, psk) ->
            secretStore.putPresharedKey(id, peerIndex, psk)
        }
        return id
    }

    suspend fun rename(id: Long, name: String) {
        val entity = serverDao.byId(id) ?: return
        serverDao.update(entity.copy(name = name))
    }

    /** Replaces the (secret-free) conf body and name, keeping stored keys. */
    suspend fun updateBody(id: Long, name: String, confBody: String, endpoint: String) {
        val entity = serverDao.byId(id) ?: return
        serverDao.update(entity.copy(name = name, confBody = confBody, endpoint = endpoint))
    }

    suspend fun updateConf(
        id: Long,
        confBody: String,
        endpoint: String,
        privateKey: String?,
        presharedKeys: Map<Int, String>,
    ) {
        val entity = serverDao.byId(id) ?: return
        serverDao.update(entity.copy(confBody = confBody, endpoint = endpoint))
        secretStore.deleteFor(id)
        privateKey?.let { secretStore.putPrivateKey(id, it) }
        presharedKeys.forEach { (peerIndex, psk) ->
            secretStore.putPresharedKey(id, peerIndex, psk)
        }
    }

    suspend fun delete(id: Long) {
        serverDao.delete(id)
        secretStore.deleteFor(id)
    }
}
