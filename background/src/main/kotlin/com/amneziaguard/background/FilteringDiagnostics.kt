package com.amneziaguard.background

import android.util.Log
import com.amneziaguard.core.netstack.Tun2Socks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared log/result between [FilteringVpnService] and the diagnostics UI.
 * Every line is mirrored to logcat under the engine's tag so a single
 * `adb logcat -s AGEngine` capture explains the whole run — which probe was
 * started, how far it got and why it stopped.
 */
@Singleton
class FilteringDiagnostics @Inject constructor() {
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _exitIp = MutableStateFlow<String?>(null)
    val exitIp: StateFlow<String?> = _exitIp.asStateFlow()

    fun reset(what: String) {
        Log.i(Tun2Socks.TAG, "=== $what ===")
        _log.value = listOf("— $what —")
        _exitIp.value = null
        _running.value = true
    }

    fun append(line: String) {
        Log.i(Tun2Socks.TAG, line)
        _log.update { it + line }
    }

    fun finish(ip: String?) {
        Log.i(Tun2Socks.TAG, "finished, exitIp=$ip")
        _exitIp.value = ip
        _running.value = false
    }
}
