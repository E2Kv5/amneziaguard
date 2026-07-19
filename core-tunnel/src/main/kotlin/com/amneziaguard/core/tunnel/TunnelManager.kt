package com.amneziaguard.core.tunnel

import com.amneziaguard.core.data.repo.RuleRepository
import com.amneziaguard.core.data.repo.ServerRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.firewall.FirewallPolicyCompiler
import com.amneziaguard.core.tunnel.model.AwgConfigModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Statistics
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single active AmneziaWG tunnel and exposes its state. Config
 * assembly is text-level: the stored (secret-free) .conf body is recombined
 * with the private key, the per-app firewall policy and a DNS-guard line, then
 * handed to the library's own [Config.parse].
 */
@Singleton
class TunnelManager @Inject constructor(
    private val backendProvider: BackendProvider,
    private val serverRepository: ServerRepository,
    private val ruleRepository: RuleRepository,
    private val settingsRepository: SettingsRepository,
    private val policyCompiler: FirewallPolicyCompiler,
) {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Down)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var activeTunnel: AwgTunnel? = null
    private var activeConfig: Config? = null
    private var activeServerId: Long? = null

    /** Loads server config + rules, assembles the final config and brings the tunnel up. */
    suspend fun connect(serverId: Long): Result<Unit> = mutex.withLock {
        _state.value = TunnelState.Connecting
        runCatching {
            val config = buildConfig(serverId)
            val tunnel = AwgTunnel(serverId) { onBackendState(it) }
            withContext(Dispatchers.IO) {
                backendProvider.backend.setState(tunnel, Tunnel.State.UP, config)
            }
            activeTunnel = tunnel
            activeConfig = config
            activeServerId = serverId
            _state.value = TunnelState.Up(serverId, System.currentTimeMillis())
        }.onFailure { e ->
            activeTunnel = null
            activeConfig = null
            activeServerId = null
            _state.value = TunnelState.Error(e.message ?: "connection failed")
        }
    }

    suspend fun disconnect(): Result<Unit> = mutex.withLock {
        val tunnel = activeTunnel ?: return@withLock Result.success(Unit)
        runCatching {
            withContext(Dispatchers.IO) {
                backendProvider.backend.setState(tunnel, Tunnel.State.DOWN, null)
            }
            Unit
        }.also {
            activeTunnel = null
            activeConfig = null
            activeServerId = null
            _state.value = TunnelState.Down
        }
    }

    suspend fun statistics(): Statistics? {
        val tunnel = activeTunnel ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { backendProvider.backend.getStatistics(tunnel) }.getOrNull()
        }
    }

    /** Re-resolves DDNS peer endpoints after a network change (roaming). */
    suspend fun onNetworkChanged() {
        val config = activeConfig ?: return
        withContext(Dispatchers.IO) {
            runCatching { backendProvider.backend.resolveDDNS(config, true) }
        }
    }

    private fun onBackendState(backendState: Tunnel.State) {
        // The library reports an unexpected DOWN (e.g. revoked permission or a
        // dropped handshake) even though we did not request a disconnect.
        if (backendState == Tunnel.State.DOWN && activeTunnel != null) {
            _state.value = TunnelState.Down
        }
    }

    private suspend fun buildConfig(serverId: Long): Config {
        val confBody = serverRepository.confBody(serverId)
            ?: throw IllegalStateException("server $serverId not found")
        val base = AwgConfigModel.parseOrThrow(confBody)

        val privateKey = serverRepository.privateKey(serverId)
        val presharedKeys = base.peers.indices.mapNotNull { i ->
            serverRepository.presharedKey(serverId, i)?.let { i to it }
        }.toMap()
        val withSecrets = base.withSecrets(privateKey, presharedKeys)

        val settings = settingsRepository.settings.first()
        val rules = ruleRepository.rules()
        val policy = policyCompiler.compile(rules, settings.defaultAppMode)

        val dns = if (settings.dnsLeakProtection && withSecrets.iface.dns.isEmpty()) {
            listOf(settings.dnsFallback)
        } else {
            withSecrets.iface.dns
        }

        val finalModel = withSecrets.copy(
            iface = withSecrets.iface.copy(
                dns = dns,
                includedApplications = policy.included.toList(),
                excludedApplications = policy.excluded.toList(),
            ),
        )
        return Config.parse(finalModel.serialize().byteInputStream())
    }
}
