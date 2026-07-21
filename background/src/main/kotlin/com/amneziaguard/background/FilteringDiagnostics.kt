package com.amneziaguard.background

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Shared log/result between [FilteringVpnService] and the diagnostics UI. */
@Singleton
class FilteringDiagnostics @Inject constructor() {
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _exitIp = MutableStateFlow<String?>(null)
    val exitIp: StateFlow<String?> = _exitIp.asStateFlow()

    fun reset() {
        _log.value = listOf("— filtering VpnService spike —")
        _exitIp.value = null
        _running.value = true
    }

    fun append(line: String) = _log.update { it + line }

    fun finish(ip: String?) {
        _exitIp.value = ip
        _running.value = false
    }
}
