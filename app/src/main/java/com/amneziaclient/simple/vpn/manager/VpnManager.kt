package com.amneziaclient.simple.vpn.manager

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.amneziaclient.simple.data.AppListRepository
import com.amneziaclient.simple.service.AmneziaVpnService
import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import com.amneziaclient.simple.vpn.registry.PluginRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единая точка входа для UI: "подключить/отключить активный профиль",
 * независимо от протокола. UI и ViewModel работают ТОЛЬКО через VpnManager
 * и никогда не обращаются к конкретным плагинам/сервисам напрямую — это и
 * есть смысл Plugin Architecture.
 *
 * Два принципиально разных способа поднятия VPN:
 *  - AmneziaWG/WireGuard: движок (GoBackend) должен работать ВНУТРИ живого
 *    android.net.VpnService — поэтому здесь мы стартуем свой foreground
 *    AmneziaVpnService, а он уже сам вызывает plugin.connect().
 *  - IKEv2 (strongSwan) и остальные: плагин поднимает и обслуживает VPN
 *    полностью сам (через VpnProfileControlActivity/CharonVpnService) — нам
 *    не нужен собственный foreground-сервис, просто вызываем plugin.connect().
 */
@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
    private val pluginRegistry: PluginRegistry,
    private val appListRepository: AppListRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    val connectionState: StateFlow<PluginConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow(ConnectionStats())
    val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()

    private var followJob: Job? = null
    private var statsFollowJob: Job? = null

    init {
        // Следим за активным профилем и переключаем "источник" connectionState
        // на state того плагина, который сейчас активен.
        scope.launch {
            profileManager.activeProfileFlow.collectLatest { profile ->
                followJob?.cancel()
                statsFollowJob?.cancel()
                val plugin = profile?.let { pluginRegistry.byProtocol(it.protocol) }
                if (plugin == null) {
                    _connectionState.value = PluginConnectionState.DISCONNECTED
                    _stats.value = ConnectionStats()
                    return@collectLatest
                }
                followJob = scope.launch {
                    // Некоторые движки (в т.ч. AmneziaWG) во время реального
                    // согласования соединения могут кратковременно репортить
                    // несколько подряд переключений UP/DOWN — это нормальное
                    // поведение движка, а не наш баг. debounce схлопывает
                    // такие быстрые скачки, оставляя в UI только финальное
                    // устоявшееся состояние, сохраняя при этом отзывчивость
                    // на реальные, "одиночные" изменения (например, стоп).
                    plugin.connectionState.debounce(400).collectLatest { _connectionState.value = it }
                }
                statsFollowJob = scope.launch {
                    plugin.stats.collectLatest { _stats.value = it }
                }
            }
        }
    }

    /** true, если протокол требует, чтобы МЫ сами держали foreground VpnService
     *  (движок работает внутри него) — иначе плагин обслуживает VPN сам. */
    private fun needsOwnForegroundService(protocol: VpnProtocolType): Boolean =
        protocol == VpnProtocolType.AMNEZIAWG || protocol == VpnProtocolType.WIREGUARD

    suspend fun connect() {
        val profile = profileManager.activeProfile() ?: return
        val plugin = pluginRegistry.byProtocol(profile.protocol) ?: return
        if (!plugin.isAvailable) return

        if (needsOwnForegroundService(profile.protocol)) {
            val intent = Intent(context, AmneziaVpnService::class.java).setAction(AmneziaVpnService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        } else {
            plugin.connect(profile.configBlob, appListRepository.getSelectedPackages())
        }
    }

    suspend fun disconnect() {
        val profile = profileManager.activeProfile() ?: return
        val plugin = pluginRegistry.byProtocol(profile.protocol) ?: return

        if (needsOwnForegroundService(profile.protocol)) {
            context.startService(Intent(context, AmneziaVpnService::class.java).setAction(AmneziaVpnService.ACTION_STOP))
        } else {
            plugin.disconnect()
        }
    }
}
