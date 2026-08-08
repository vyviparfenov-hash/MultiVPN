package com.amneziaclient.simple.vpn.plugins.amneziawg

import android.content.Context
import android.util.Log
import com.amneziaclient.simple.vpn.AppForegroundState
import com.amneziaclient.simple.vpn.VpnDebugLog
import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.ValidationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import java.io.StringReader
import java.net.InetAddress
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Обёртка над официальным backend'ом AmneziaWG (com.zaneschepke:amneziawg-android,
 * Maven Central). API проверен реальным reflection-дампом на CI (см. историю
 * чата/коммитов) — GoBackend(Context, TunnelActionHandler), Backend.setState(...),
 * Config.parse(BufferedReader), Tunnel.State {UP, DOWN}.
 *
 * Один и тот же движок обслуживает и AmneziaWG, и обычный WireGuard —
 * протокол WireGuard является подмножеством формата AmneziaWG (без полей
 * обфускации Jc/Jmin/Jmax/S1/S2/H1-H4, которые в Config опциональны), поэтому
 * отдельная библиотека для WireGuard не нужна: AmneziaWgPlugin и WireGuardPlugin
 * используют один и тот же AmneziaWgAdapter.
 *
 * Единственный tunnel active за раз — это нормально для VPN-приложения
 * (одновременно активен только один профиль, как в любом VPN-клиенте).
 *
 * ВАЖНО про backend.getStatistics(tunnel): точные имена методов класса
 * Statistics НЕ подтверждены reflection-дампом (мы дампили только GoBackend/
 * Tunnel/Backend/BackendException/Config), поэтому вызов обёрнут в
 * runCatching — если имена окажутся другими, трафик просто останется 0
 * вместо падения всего подключения. Реальные пинг и публичный IP не зависят
 * от этого API и гарантированно рабочие (обычные сетевые запросы).
 */
@Singleton
class AmneziaWgAdapter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val noOpTunnelActionHandler = object : TunnelActionHandler {
        override fun runPreUp(commands: Collection<String>) = Unit
        override fun runPostUp(commands: Collection<String>) = Unit
        override fun runPreDown(commands: Collection<String>) = Unit
        override fun runPostDown(commands: Collection<String>) = Unit
    }

    private val backend: Backend by lazy { GoBackend(context, noOpTunnelActionHandler) }

    private val _state = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    val state: StateFlow<PluginConnectionState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(ConnectionStats())
    val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()

    private var connectedSince: Long? = null
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statsPollJob: Job? = null
    private var extrasJob: Job? = null
    private var pingRefreshJob: Job? = null
    private var currentServerHost: String? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "Tunnel state changed: $newState")
            VpnDebugLog.log(TAG, "Tunnel state changed: $newState")
            _state.value = when (newState) {
                Tunnel.State.UP -> {
                    if (connectedSince == null) connectedSince = System.currentTimeMillis()
                    PluginConnectionState.CONNECTED
                }
                Tunnel.State.DOWN -> {
                    connectedSince = null
                    stopBackgroundWork()
                    PluginConnectionState.DISCONNECTED
                }
            }
        }
        override fun isIpv4ResolutionPreferred(): Boolean = false
        override fun isMetered(): Boolean = false
    }

    companion object {
        private const val TAG = "AmneziaWgAdapter"
        const val TUNNEL_NAME = "amnezia_simple_tunnel"
        private const val STATS_POLL_INTERVAL_MS = 2000L
        private const val STATS_POLL_INTERVAL_BACKGROUND_MS = 15_000L
    }

    fun parse(rawText: String): Config = Config.parse(StringReader(rawText.trim()).buffered())

    fun validate(rawText: String): ValidationResult = runCatching { parse(rawText) }
        .fold(
            onSuccess = { ValidationResult.Valid },
            onFailure = { ValidationResult.Invalid(it.message ?: "Некорректная конфигурация") }
        )

    /** Вставляет "IncludedApplications = pkg1,pkg2" в секцию [Interface] перед
     *  парсингом — так библиотека сама применяет сплит-туннель через
     *  addAllowedApplication(), нам не нужно вызывать VpnService.Builder руками. */
    private fun injectIncludedApplications(rawText: String, packages: Set<String>): String {
        // Своё приложение — всегда в списке (см. L2tpVpnService/
        // OpenVpnVpnService): иначе при активном сплит-туннеле наш же
        // measureConnectionExtrasOnce() (пинг/публичный IP) идёт мимо VPN
        // и показывает реальный домашний IP вместо адреса сервера.
        val allPackages = packages + context.packageName
        val line = "IncludedApplications = " + allPackages.joinToString(",")
        val lines = rawText.lines().toMutableList()
        val interfaceIndex = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (interfaceIndex >= 0) {
            lines.add(interfaceIndex + 1, line)
        } else {
            lines.add(0, "[Interface]")
            lines.add(1, line)
        }
        return lines.joinToString("\n")
    }

    @Synchronized
    @Throws(BackendException::class)
    fun connect(rawText: String, selectedApps: Set<String>) {
        // Идемпотентность: повторный connect() пока уже CONNECTED/CONNECTING не
        // должен пере-поднимать туннель (это и было причиной "мигания" VPN).
        if (_state.value == PluginConnectionState.CONNECTED ||
            _state.value == PluginConnectionState.CONNECTING
        ) {
            return
        }

        val finalText = if (selectedApps.isNotEmpty()) {
            injectIncludedApplications(rawText, selectedApps)
        } else {
            rawText
        }
        val config = parse(finalText)

        _state.value = PluginConnectionState.CONNECTING
        _stats.value = ConnectionStats()
        backend.setState(tunnel, Tunnel.State.UP, config)

        // Пинг и публичный IP: разово сразу после подключения, и затем ещё
        // раз при каждом возврате приложения на передний план — та же
        // логика, что и у IKEv2/L2TP/OpenVPN (см. IKEv2Plugin).
        currentServerHost = serverHost(rawText)
        measureConnectionExtrasOnce(currentServerHost!!)
        startPingRefreshOnForeground()
        startStatsPolling()
    }

    @Throws(BackendException::class)
    @Synchronized
    fun disconnect() {
        if (_state.value == PluginConnectionState.DISCONNECTED) return
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        stopBackgroundWork()
        _stats.value = ConnectionStats()
    }

    private fun stopBackgroundWork() {
        statsPollJob?.cancel()
        extrasJob?.cancel()
        pingRefreshJob?.cancel()
    }

    /** Периодически опрашивает счётчики трафика, пока туннель поднят —
     *  реже, пока приложение свёрнуто (см. AppForegroundState). */
    private fun startStatsPolling() {
        statsPollJob?.cancel()
        statsPollJob = adapterScope.launch {
            while (isActive) {
                refreshTrafficStatsOnce()
                delay(if (AppForegroundState.isForeground.value) STATS_POLL_INTERVAL_MS else STATS_POLL_INTERVAL_BACKGROUND_MS)
            }
        }
    }

    private fun refreshTrafficStatsOnce() {
        val statistics = runCatching { backend.getStatistics(tunnel) }.getOrNull() ?: return
        val rx = runCatching { statistics.totalRx() }.getOrNull() ?: 0L
        val tx = runCatching { statistics.totalTx() }.getOrNull() ?: 0L
        _stats.value = _stats.value.copy(bytesReceived = rx, bytesSent = tx)
    }

    /** Реальное, разовое измерение (не имитация): пинг до сервера через
     *  InetAddress.isReachable(), публичный IP — реальный запрос к внешнему
     *  сервису после того, как трафик уже идёт через туннель. */
    private fun measureConnectionExtrasOnce(serverHost: String) {
        extrasJob?.cancel()
        extrasJob = adapterScope.launch {
            // Даём туннелю немного времени реально подняться перед замером.
            delay(1500)

            val pingMs = measurePingOnceMs(serverHost)

            val publicIp = runCatching {
                withContext(Dispatchers.IO) {
                    URL("https://api.ipify.org").openStream().bufferedReader().use { it.readText().trim() }
                }
            }.getOrNull()

            _stats.value = _stats.value.copy(pingMillis = pingMs, publicIp = publicIp)
        }
    }

    private suspend fun measurePingOnceMs(host: String): Long? = runCatching {
        val start = System.currentTimeMillis()
        val reachable = withContext(Dispatchers.IO) { InetAddress.getByName(host).isReachable(3000) }
        if (reachable) System.currentTimeMillis() - start else null
    }.getOrNull()

    /** Пинг НЕ опрашивается непрерывно — только разово при подключении и
     *  разово при каждом возврате приложения на передний план (см.
     *  IKEv2Plugin для полного обоснования этого решения). */
    private fun startPingRefreshOnForeground() {
        pingRefreshJob?.cancel()
        pingRefreshJob = adapterScope.launch {
            AppForegroundState.onEnterForeground.collect {
                val host = currentServerHost ?: return@collect
                val pingMs = measurePingOnceMs(host)
                _stats.value = _stats.value.copy(pingMillis = pingMs)
            }
        }
    }

    fun serverHost(rawText: String): String =
        runCatching { parse(rawText).peers.firstOrNull()?.endpoint?.orElse(null)?.host }
            .getOrNull() ?: "VPN"
}

