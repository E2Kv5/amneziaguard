package com.amneziaguard.core.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val configAssembler: ServerConfigAssembler,
) {
    data class Outcome(val success: Boolean, val exitIp: String?)

    suspend fun run(log: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        try {
            val confText = configAssembler.activeServerConf()
            if (confText == null) {
                log("No active server / config — pick one on the Servers screen first.")
                return@withContext Outcome(false, null)
            }

            log("Starting amneziawg-go proxy…")
            val port = proxyController.start(confText).getOrElse {
                log("Proxy failed to start: ${it.message}")
                log("Details are in logcat (tags AwgProxy / AmneziaWG).")
                return@withContext Outcome(false, null)
            }
            log("SOCKS5 up on 127.0.0.1:$port")

            TraceProbe.awaitPort(port)
            val exitIp = TraceProbe.fetchExitIp(port, log)
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
}
