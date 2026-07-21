package com.amneziaguard.core.tunnel

import com.amneziaguard.core.data.repo.ServerRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.netstack.socks.Socks5Client
import com.amneziaguard.core.tunnel.model.AwgConfigModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

/**
 * On-device proof-of-concept for the no-root userspace datapath: start
 * amneziawg-go as a local SOCKS5, then fetch Cloudflare's trace endpoint
 * through it and report the exit IP. If the exit IP is the AWG server (not the
 * local ISP), the obfuscated tunnel works via the SOCKS5 — de-risking the whole
 * approach before the full tun2socks engine is built.
 */
class ProxySpike @Inject constructor(
    private val proxyController: AmneziaProxyController,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
) {
    data class Outcome(val success: Boolean, val exitIp: String?)

    suspend fun run(log: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        try {
            val serverId = settingsRepository.settings.first().activeServerId
            if (serverId == null) {
                log("No active server selected — pick one on the Servers screen first.")
                return@withContext Outcome(false, null)
            }
            val confText = fullConfText(serverId)
            if (confText == null) {
                log("Could not load config for server #$serverId.")
                return@withContext Outcome(false, null)
            }

            log("Starting amneziawg-go proxy…")
            val port = proxyController.start(confText).getOrElse {
                log("Proxy failed to start: ${it.message}")
                return@withContext Outcome(false, null)
            }
            log("SOCKS5 up on 127.0.0.1:$port")

            awaitPort(port, log)
            val exitIp = fetchTrace(port, log)
            proxyController.stop()
            log("Proxy stopped.")

            if (exitIp != null) {
                log("SUCCESS — traffic exits via $exitIp (should be the VPN server, not your ISP).")
                Outcome(true, exitIp)
            } else {
                log("No exit IP returned — check the log above.")
                Outcome(false, null)
            }
        } catch (e: Exception) {
            log("Spike error: ${e.message}")
            runCatching { proxyController.stop() }
            Outcome(false, null)
        }
    }

    private suspend fun fullConfText(serverId: Long): String? {
        val body = serverRepository.confBody(serverId) ?: return null
        val model = AwgConfigModel.parse(body).getOrNull() ?: return null
        val psks = model.peers.indices.mapNotNull { i ->
            serverRepository.presharedKey(serverId, i)?.let { i to it }
        }.toMap()
        return model.withSecrets(serverRepository.privateKey(serverId), psks).serialize()
    }

    private suspend fun awaitPort(port: Int, log: (String) -> Unit) {
        repeat(20) { attempt ->
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                true
            }.getOrDefault(false)
            if (ok) return
            if (attempt == 0) log("Waiting for the SOCKS5 listener…")
            delay(250)
        }
    }

    /** SOCKS5-CONNECT to 1.1.1.1:80 and GET /cdn-cgi/trace; returns the ip= line. */
    private fun fetchTrace(port: Int, log: (String) -> Unit): String? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            log("SOCKS5 CONNECT → 1.1.1.1:80")
            Socks5Client.connect(
                input,
                output,
                destIp = byteArrayOf(1, 1, 1, 1),
                destPort = 80,
                credentials = Socks5Client.Credentials(
                    AmneziaProxyController.SOCKS_USER,
                    AmneziaProxyController.SOCKS_PASS,
                ),
            )
            log("Tunnel established; requesting /cdn-cgi/trace")
            output.write(
                ("GET /cdn-cgi/trace HTTP/1.1\r\n" +
                    "Host: 1.1.1.1\r\n" +
                    "User-Agent: AmneziaGuard-spike\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            output.flush()

            val body = input.readBytes().toString(Charsets.UTF_8)
            val ip = body.lineSequence().firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim()
            if (ip == null) log("Response head: ${body.take(200)}")
            return ip
        }
    }
}
