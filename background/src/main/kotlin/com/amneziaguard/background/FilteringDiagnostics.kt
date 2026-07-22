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
 * Shared log between [FilteringVpnService] and the diagnostics UI. Every line is
 * mirrored to logcat under the engine's tag so a single `adb logcat -s AGEngine`
 * capture explains the whole run — how far it got and why it stopped.
 */
@Singleton
class FilteringDiagnostics @Inject constructor() {
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun reset(what: String) {
        Log.i(Tun2Socks.TAG, "=== $what ===")
        _log.value = listOf("— $what —")
        _running.value = true
    }

    fun append(line: String) {
        Log.i(Tun2Socks.TAG, line)
        add(line)
    }

    /**
     * Appends without mirroring to logcat, for lines the producer already logged
     * there itself — the engine's periodic stats, which would otherwise appear
     * twice in a capture.
     */
    fun appendQuiet(line: String) = add(line)

    /**
     * The engine runs for as long as the tunnel is up and reports every couple
     * of seconds, so this list has to be bounded: unbounded it would grow without
     * limit, and appending by copy would make that quadratic.
     */
    private fun add(line: String) {
        _log.update { lines ->
            if (lines.size < MAX_LINES) lines + line
            else lines.subList(lines.size - MAX_LINES + 1, lines.size) + line
        }
    }

    fun finish() {
        Log.i(Tun2Socks.TAG, "engine stopped")
        _running.value = false
    }

    private companion object {
        const val MAX_LINES = 300
    }
}
