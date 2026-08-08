package com.amneziaclient.simple.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amneziaclient.simple.data.AppListRepository
import com.amneziaclient.simple.data.InstalledAppInfo
import com.amneziaclient.simple.vpn.manager.ProfileManager
import com.amneziaclient.simple.vpn.manager.VpnManager
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.registry.PluginRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppRow(val info: InstalledAppInfo, val checked: Boolean)

data class AppSelectionUiState(
    val query: String = "",
    val allApps: List<InstalledAppInfo> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    /** null, если активный протокол поддерживает сплит-туннель (или профиль
     *  не выбран вовсе) — иначе название протокола для текста предупреждения. */
    val splitTunnelUnsupportedProtocolName: String? = null
) {
    val visibleRows: List<AppRow>
        get() = allApps
            .filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
            .map { AppRow(it, selected.contains(it.packageName)) }
            // Выбранные приложения — сверху, чтобы сразу было видно, что уже отмечено.
            .sortedWith(compareByDescending<AppRow> { it.checked }.thenBy { it.info.label.lowercase() })
}

@HiltViewModel
class AppSelectionViewModel @Inject constructor(
    private val appListRepository: AppListRepository,
    private val vpnManager: VpnManager,
    private val profileManager: ProfileManager,
    private val pluginRegistry: PluginRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppSelectionUiState())
    val uiState: StateFlow<AppSelectionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val apps = appListRepository.getUserApps()
            val activeProfile = profileManager.activeProfile()
            val activePlugin = activeProfile?.let { pluginRegistry.byProtocol(it.protocol) }
            _uiState.value = _uiState.value.copy(
                allApps = apps,
                selected = appListRepository.getSelectedPackages(),
                isLoading = false,
                splitTunnelUnsupportedProtocolName = activePlugin?.let {
                    if (!it.supportsSplitTunnel) it.displayName else null
                }
            )
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun clearQuery() {
        _uiState.value = _uiState.value.copy(query = "")
    }

    fun onToggle(packageName: String) {
        val current = _uiState.value.selected.toMutableSet()
        if (!current.add(packageName)) current.remove(packageName)
        _uiState.value = _uiState.value.copy(selected = current)
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(selected = _uiState.value.allApps.map { it.packageName }.toSet())
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selected = emptySet())
    }

    /**
     * Сохраняет список и, если VPN сейчас подключён, автоматически
     * переподключает его — чтобы новый список приложений применился сразу,
     * без ручного "стоп → выйти из приложения → снова старт".
     */
    fun apply() {
        appListRepository.setSelectedPackages(_uiState.value.selected)
        viewModelScope.launch {
            if (vpnManager.connectionState.value == PluginConnectionState.CONNECTED) {
                vpnManager.disconnect()
                delay(700) // даём туннелю время корректно закрыться перед новым подключением
                vpnManager.connect()
            }
        }
    }
}
