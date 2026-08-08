package com.amneziaclient.simple.vpn.plugins.l2tp

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amneziaclient.simple.AmneziaApp
import com.amneziaclient.simple.R
import com.amneziaclient.simple.ui.MainActivity
import com.amneziaclient.simple.vpn.AppForegroundState
import com.amneziaclient.simple.vpn.VpnDebugLog
import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import io.github.evokelektrique.tunnelforge.VpnBridge
import io.github.evokelektrique.tunnelforge.VpnTunnelEvents
import io.github.evokelektrique.tunnelforge.TunnelVpnService as EngineBridgeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL

/**
 * Реальный движок L2TP/IPsec (TunnelForge, engine.h/vpn_jni.c) обязан
 * работать внутри живого android.net.VpnService — установление (negotiate),
 * builder.establish() и protect() физических сокетов IKE/ESP должны
 * выполняться отсюда, а не из L2tpPlugin (в отличие от strongSwan/AmneziaWG,
 * у TunnelForge нет своей обёртки VpnService — see LICENSES-THIRD-PARTY.md).
 *
 * Поток:
 *  1) nativeSetSocketProtectionEnabled(true) + регистрируемся в
 *     TunnelVpnService (JNI-мост) как источник protect(fd).
 *  2) nativeNegotiate(...) на IO-потоке — IKE+L2TP+PPP до поднятия TUN.
 *  3) builder.establish() с IP/DNS, которые вернул negotiate.
 *  4) nativeStartLoop(tunFd) — БЛОКИРУЮЩИЙ вызов, поэтому в отдельном потоке.
 */
class L2tpVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopThread: Thread? = null
    private var statsPollJob: Job? = null
    private var phaseCollectJob: Job? = null
    private var extrasJob: Job? = null
    private var pingRefreshJob: Job? = null
    private var tunFd: android.os.ParcelFileDescriptor? = null
    private var connectedSinceMillis: Long? = null
    private var currentServerHost: String? = null

    companion object {
        private const val TAG = "L2tpVpnService"
        const val ACTION_START = "com.amneziaclient.simple.l2tp.action.START"
        const val ACTION_STOP = "com.amneziaclient.simple.l2tp.action.STOP"

        const val EXTRA_SERVER = "server"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PSK = "psk"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_SELECTED_APPS = "selected_apps"

        private const val NOTIFICATION_ID = 43
        private const val DEFAULT_MTU = 1400
        private const val STATS_POLL_INTERVAL_MS = 2_000L
        private const val STATS_POLL_INTERVAL_BACKGROUND_MS = 15_000L
    }

    private fun dlog(message: String) {
        Log.d(TAG, message)
        VpnDebugLog.log(TAG, message)
    }

    override fun onCreate() {
        super.onCreate()
        EngineBridgeTarget.register(
            protect = { fd -> runCatching { protect(fd) }.getOrDefault(false) },
            onReady = { detail ->
                dlog("Connected: $detail")
                L2tpEngineState.state.value = PluginConnectionState.CONNECTED
                L2tpEngineState.lastDetail.value = detail
                phaseCollectJob?.cancel()
                measureConnectionExtrasOnce()
                startPingRefreshOnForeground()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch { stopTunnelAndService() }
            }
            ACTION_START -> {
                // Идемпотентность (см. AmneziaWgAdapter.connect() /
                // VlessVpnService) — без этой проверки повторный
                // ACTION_START, пока уже идёт подключение/уже подключено,
                // поднимает новую сессию поверх старой, не остановив её.
                if (L2tpEngineState.state.value == PluginConnectionState.CONNECTED ||
                    L2tpEngineState.state.value == PluginConnectionState.CONNECTING
                ) {
                    return START_NOT_STICKY
                }
                val server = intent.getStringExtra(EXTRA_SERVER)
                if (server.isNullOrBlank()) {
                    L2tpEngineState.state.value = PluginConnectionState.ERROR
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (connectedSinceMillis == null) connectedSinceMillis = System.currentTimeMillis()
                startForeground(NOTIFICATION_ID, buildNotification(server))
                startTunnel(
                    server = server,
                    username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                    password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    psk = intent.getStringExtra(EXTRA_PSK).orEmpty(),
                    mtu = intent.getIntExtra(EXTRA_MTU, DEFAULT_MTU),
                    selectedApps = intent.getStringArrayListExtra(EXTRA_SELECTED_APPS)?.toSet() ?: emptySet()
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(
        server: String,
        username: String,
        password: String,
        psk: String,
        mtu: Int,
        selectedApps: Set<String>
    ) {
        dlog("Connecting to $server...")
        L2tpEngineState.state.value = PluginConnectionState.CONNECTING
        L2tpEngineState.stats.value = ConnectionStats()
        currentServerHost = server
        VpnTunnelEvents.resetPhase()
        L2tpEngineState.lastDetail.value = null
        phaseCollectJob?.cancel()
        phaseCollectJob = serviceScope.launch {
            VpnTunnelEvents.phase.collect { p ->
                if (p != null) L2tpEngineState.lastDetail.value = p
            }
        }

        serviceScope.launch {
            VpnBridge.nativeSetSocketProtectionEnabled(true)

            val clientIpv4 = IntArray(4)
            val primaryDns = IntArray(4)
            val secondaryDns = IntArray(4)

            val exitCode = withContext(Dispatchers.IO) {
                runCatching {
                    VpnBridge.nativeNegotiate(
                        server, username, password, psk, mtu,
                        clientIpv4, primaryDns, secondaryDns
                    )
                }.getOrDefault(-1)
            }

            if (exitCode != 0) {
                dlog("Error: L2TP/IPsec negotiate failed, code=$exitCode")
                L2tpEngineState.state.value = PluginConnectionState.ERROR
                L2tpEngineState.lastDetail.value = "L2TP/IPsec negotiate failed, code=$exitCode"
                stopTunnelAndService()
                return@launch
            }

            val establishedFd = runCatching {
                buildTunnel(clientIpv4, primaryDns, secondaryDns, mtu, selectedApps)
            }.getOrNull()

            if (establishedFd == null) {
                dlog("Error: VpnService.Builder.establish() returned null")
                L2tpEngineState.state.value = PluginConnectionState.ERROR
                L2tpEngineState.lastDetail.value = "VpnService.Builder.establish() returned null"
                stopTunnelAndService()
                return@launch
            }

            tunFd = establishedFd
            startStatsPolling()

            // nativeStartLoop блокирует поток до остановки туннеля — свой
            // Thread, а не корутина на разделяемом диспетчере.
            loopThread = Thread({
                val loopExit = runCatching { VpnBridge.nativeStartLoop(establishedFd.fd) }.getOrDefault(-1)
                serviceScope.launch {
                    if (L2tpEngineState.state.value != PluginConnectionState.DISCONNECTED) {
                        L2tpEngineState.lastDetail.value = "Tunnel loop exited, code=$loopExit"
                    }
                    stopTunnelAndService()
                }
            }, "l2tp-tunnel-loop").apply { start() }
        }
    }

    private fun buildTunnel(
        clientIpv4: IntArray,
        primaryDns: IntArray,
        secondaryDns: IntArray,
        mtu: Int,
        selectedApps: Set<String>
    ): android.os.ParcelFileDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.multivpn_app_name))
            .setMtu(mtu)
            .addAddress(clientIpv4.joinToString(".") { it.toString() }, 32)
            .addRoute("0.0.0.0", 0)

        if (primaryDns.any { it != 0 }) builder.addDnsServer(primaryDns.joinToString(".") { it.toString() })
        if (secondaryDns.any { it != 0 }) builder.addDnsServer(secondaryDns.joinToString(".") { it.toString() })

        selectedApps.forEach { pkg ->
            runCatching { builder.addAllowedApplication(pkg) }
        }
        // Своё приложение — всегда в списке, независимо от выбора
        // пользователя. Иначе при активном сплит-туннеле наши же запросы
        // (пинг/публичный IP в статистике) идут мимо VPN и показывают
        // реальный домашний IP вместо адреса сервера — выглядит как баг,
        // хотя маршрутизация на самом деле работает правильно.
        if (selectedApps.isNotEmpty()) {
            runCatching { builder.addAllowedApplication(packageName) }
        }

        return builder.establish() ?: throw IllegalStateException("establish() returned null")
    }

    /** Тот же приём, что и для IKEv2: TrafficStats по своему UID — общий
     *  Android API, а не протокол-специфичный (см. IKEv2Plugin для деталей
     *  обоснования). */
    private fun startStatsPolling() {
        statsPollJob?.cancel()
        val uid = Process.myUid()
        val baseRx = android.net.TrafficStats.getUidRxBytes(uid)
        val baseTx = android.net.TrafficStats.getUidTxBytes(uid)
        if (baseRx == android.net.TrafficStats.UNSUPPORTED.toLong() ||
            baseTx == android.net.TrafficStats.UNSUPPORTED.toLong()
        ) {
            return
        }
        statsPollJob = serviceScope.launch {
            while (isActive) {
                val rx = android.net.TrafficStats.getUidRxBytes(uid)
                val tx = android.net.TrafficStats.getUidTxBytes(uid)
                if (rx != android.net.TrafficStats.UNSUPPORTED.toLong() &&
                    tx != android.net.TrafficStats.UNSUPPORTED.toLong()
                ) {
                    L2tpEngineState.stats.value = L2tpEngineState.stats.value.copy(
                        bytesReceived = (rx - baseRx).coerceAtLeast(0),
                        bytesSent = (tx - baseTx).coerceAtLeast(0)
                    )
                }
                kotlinx.coroutines.delay(
                    if (com.amneziaclient.simple.vpn.AppForegroundState.isForeground.value) STATS_POLL_INTERVAL_MS
                    else STATS_POLL_INTERVAL_BACKGROUND_MS
                )
            }
        }
    }

    /** Публичный IP меряется один раз при подключении. Пинг — тоже сразу
     *  после коннекта, а затем ещё раз при каждом возврате приложения на
     *  передний план, см. [startPingRefreshOnForeground] — НЕ постоянно и
     *  НЕ в фоне. */
    private fun measureConnectionExtrasOnce() {
        val host = currentServerHost ?: return
        extrasJob?.cancel()
        extrasJob = serviceScope.launch {
            delay(1_500)
            val pingMs = measurePingOnceMs(host)

            val publicIp = runCatching {
                withContext(Dispatchers.IO) {
                    URL("https://api.ipify.org").openStream().bufferedReader().use { it.readText().trim() }
                }
            }.getOrNull()

            L2tpEngineState.stats.value = L2tpEngineState.stats.value.copy(pingMillis = pingMs, publicIp = publicIp)
        }
    }

    private suspend fun measurePingOnceMs(host: String): Long? = runCatching {
        val start = System.currentTimeMillis()
        val reachable = withContext(Dispatchers.IO) {
            InetAddress.getByName(host).isReachable(3000)
        }
        if (reachable) System.currentTimeMillis() - start else null
    }.getOrNull()

    /** Пинг НЕ опрашивается непрерывно — только разово при подключении и
     *  разово при каждом возврате приложения на передний план, пока
     *  туннель жив. В фоне не трогаем — на экране остаётся последнее
     *  зафиксированное значение. */
    private fun startPingRefreshOnForeground() {
        pingRefreshJob?.cancel()
        pingRefreshJob = serviceScope.launch {
            AppForegroundState.onEnterForeground.collect {
                val host = currentServerHost ?: return@collect
                val pingMs = measurePingOnceMs(host)
                L2tpEngineState.stats.value = L2tpEngineState.stats.value.copy(pingMillis = pingMs)
            }
        }
    }

    private fun stopTunnelAndService() {
        statsPollJob?.cancel()
        phaseCollectJob?.cancel()
        extrasJob?.cancel()
        pingRefreshJob?.cancel()
        runCatching { VpnBridge.nativeStopTunnel() }
        loopThread?.let { thread ->
            runCatching { thread.join(3_000) }
        }
        loopThread = null
        runCatching { tunFd?.close() }
        tunFd = null
        connectedSinceMillis = null
        dlog("Disconnected")
        L2tpEngineState.state.value = PluginConnectionState.DISCONNECTED
        L2tpEngineState.stats.value = ConnectionStats()
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(serverLabel: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, L2tpVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AmneziaApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(getString(R.string.notification_vpn_connected))
            .setContentText(getString(R.string.notification_server_name, serverLabel))
            .setUsesChronometer(true)
            .setWhen(connectedSinceMillis ?: System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.notification_disconnect), stopIntent)
            .build()
    }

    override fun onDestroy() {
        EngineBridgeTarget.unregister()
        serviceScope.cancel()
        super.onDestroy()
    }
}
