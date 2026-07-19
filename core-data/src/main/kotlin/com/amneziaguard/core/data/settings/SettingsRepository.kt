package com.amneziaguard.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amneziaguard.core.data.model.AppMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val activeServerId: Long?,
    val defaultAppMode: AppMode,
    val killSwitchEnabled: Boolean,
    val blockWhenDisconnected: Boolean,
    val dnsLeakProtection: Boolean,
    val dnsFallback: String,
    val rootModeEnabled: Boolean,
    val connectOnBoot: Boolean,
    val leakCheckIntervalHours: Int,
    val lastLeakCheckOk: Boolean?,
    val lastLeakCheckAt: Long?,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val activeServerId = longPreferencesKey("active_server_id")
        val defaultAppMode = intPreferencesKey("default_app_mode")
        val killSwitchEnabled = booleanPreferencesKey("kill_switch_enabled")
        val blockWhenDisconnected = booleanPreferencesKey("block_when_disconnected")
        val dnsLeakProtection = booleanPreferencesKey("dns_leak_protection")
        val dnsFallback = stringPreferencesKey("dns_fallback")
        val rootModeEnabled = booleanPreferencesKey("root_mode_enabled")
        val connectOnBoot = booleanPreferencesKey("connect_on_boot")
        val leakCheckIntervalHours = intPreferencesKey("leak_check_interval_hours")
        val lastLeakCheckOk = booleanPreferencesKey("last_leak_check_ok")
        val lastLeakCheckAt = longPreferencesKey("last_leak_check_at")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            activeServerId = p[Keys.activeServerId],
            defaultAppMode = AppMode.fromId(p[Keys.defaultAppMode] ?: AppMode.VPN.id),
            killSwitchEnabled = p[Keys.killSwitchEnabled] ?: false,
            blockWhenDisconnected = p[Keys.blockWhenDisconnected] ?: false,
            dnsLeakProtection = p[Keys.dnsLeakProtection] ?: true,
            dnsFallback = p[Keys.dnsFallback] ?: DEFAULT_DNS_FALLBACK,
            rootModeEnabled = p[Keys.rootModeEnabled] ?: false,
            connectOnBoot = p[Keys.connectOnBoot] ?: false,
            leakCheckIntervalHours = p[Keys.leakCheckIntervalHours] ?: 6,
            lastLeakCheckOk = p[Keys.lastLeakCheckOk],
            lastLeakCheckAt = p[Keys.lastLeakCheckAt],
        )
    }

    suspend fun setActiveServerId(id: Long?) = dataStore.edit { p ->
        if (id == null) p.remove(Keys.activeServerId) else p[Keys.activeServerId] = id
    }

    suspend fun setDefaultAppMode(mode: AppMode) = dataStore.edit { it[Keys.defaultAppMode] = mode.id }
    suspend fun setKillSwitchEnabled(enabled: Boolean) = dataStore.edit { it[Keys.killSwitchEnabled] = enabled }
    suspend fun setBlockWhenDisconnected(enabled: Boolean) = dataStore.edit { it[Keys.blockWhenDisconnected] = enabled }
    suspend fun setDnsLeakProtection(enabled: Boolean) = dataStore.edit { it[Keys.dnsLeakProtection] = enabled }
    suspend fun setDnsFallback(dns: String) = dataStore.edit { it[Keys.dnsFallback] = dns }
    suspend fun setRootModeEnabled(enabled: Boolean) = dataStore.edit { it[Keys.rootModeEnabled] = enabled }
    suspend fun setConnectOnBoot(enabled: Boolean) = dataStore.edit { it[Keys.connectOnBoot] = enabled }

    suspend fun setLastLeakCheck(ok: Boolean, atEpochMs: Long) = dataStore.edit { p ->
        p[Keys.lastLeakCheckOk] = ok
        p[Keys.lastLeakCheckAt] = atEpochMs
    }

    companion object {
        const val DEFAULT_DNS_FALLBACK = "1.1.1.1"
    }
}
