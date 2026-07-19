package com.amneziaguard.feature.firewall

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amneziaguard.core.data.model.AppMode
import com.amneziaguard.core.data.repo.RuleRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.firewall.AppInfo
import com.amneziaguard.core.firewall.InstalledAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppFilter { ALL, USER, SYSTEM }

data class FirewallRow(
    val app: AppInfo,
    val mode: AppMode,
)

data class FirewallUiState(
    val rows: List<FirewallRow>,
    val query: String,
    val filter: AppFilter,
    val defaultMode: AppMode,
    val rootModeEnabled: Boolean,
    val loading: Boolean,
) {
    val visibleRows: List<FirewallRow>
        get() = rows.filter { row ->
            (filter == AppFilter.ALL ||
                (filter == AppFilter.USER && !row.app.isSystem) ||
                (filter == AppFilter.SYSTEM && row.app.isSystem)) &&
                (query.isBlank() || row.app.label.contains(query, ignoreCase = true) ||
                    row.app.packageName.contains(query, ignoreCase = true))
        }
}

@HiltViewModel
class FirewallViewModel @Inject constructor(
    private val installedApps: InstalledAppsRepository,
    private val ruleRepository: RuleRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val apps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(AppFilter.USER)
    private val loading = MutableStateFlow(true)

    val uiState: StateFlow<FirewallUiState> = combine(
        apps,
        ruleRepository.observeRules(),
        settingsRepository.settings,
        query,
        filter,
    ) { appList, rules, settings, q, f ->
        val rows = appList.map { app ->
            FirewallRow(app, rules[app.packageName] ?: settings.defaultAppMode)
        }
        FirewallUiState(
            rows = rows,
            query = q,
            filter = f,
            defaultMode = settings.defaultAppMode,
            rootModeEnabled = settings.rootModeEnabled,
            loading = loading.value && appList.isEmpty(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FirewallUiState(emptyList(), "", AppFilter.USER, AppMode.VPN, false, true),
    )

    init {
        viewModelScope.launch {
            apps.value = installedApps.installedApps()
            loading.value = false
        }
    }

    fun icon(packageName: String): Drawable? = installedApps.icon(packageName)

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: AppFilter) { filter.value = value }

    fun setMode(packageName: String, mode: AppMode) {
        viewModelScope.launch { ruleRepository.setMode(packageName, mode) }
    }
}
