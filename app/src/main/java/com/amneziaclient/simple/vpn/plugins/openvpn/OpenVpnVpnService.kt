package com.amneziaclient.simple.vpn.plugins.openvpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openvpn.ovpn3.ClientAPI_AppCustomControlMessageEvent
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.ClientAPI_ProvideCreds
import java.net.InetAddress
import java.net.URL

/**
 * Мост между openvpn3 ClientAPI (C++ ядро OpenVPN Inc., через SWIG director —
 * см. LICENSES-THIRD-PARTY.md) и нашим android.net.VpnService. В отличие от
 * L2TP/IKEv2, тут не нужен собственный протокол — весь handshake/шифрование
 * делает движок, нам нужно только:
 *  1) реализовать TunBuilderBase (методы tun_builder_*) поверх
 *     VpnService.Builder — движок сам их вызывает по порядку и в конце
 *     зовёт tun_builder_establish(), которому мы возвращаем реальный fd;
 *  2) socket_protect(fd) → protect(fd), чтобы служебный TCP/UDP-сокет
 *     самого движка не заворачивался в собственный туннель;
 *  3) event()/log() — колбэки состояния/логов, идут с потока connect().
 *
 * ВАЖНО (предположение, требует проверки первой сборкой): имена
 * геттеров/сеттеров сгенерированных SWIG-классов взяты по стандартному
 * поведению SWIG для public data members C++ структур — если в
 * сгенерированном коде окажется иначе, компиляция укажет на точное
 * расхождение и это будет легко поправить.
 *
 * DNS (tun_builder_set_dns_options) реализован по точному исходнику
 * openvpn/client/dns_options.hpp (DnsOptions.servers -> DnsServer.addresses
 * -> DnsAddress.address), не по догадке.
 */
class OpenVpnVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectThread: Thread? = null
    private var statsPollJob: Job? = null
    private var pingRefreshJob: Job? = null
    private var extrasJob: Job? = null
    private var phaseCollectJob: Job? = null
    private var connectedSinceMillis: Long? = null
    private var currentServerHost: String? = null
    private var trafficBaselineRx: Long? = null
    private var trafficBaselineTx: Long? = null
    private var client: Engine? = null

    companion object {
        private const val TAG = "OpenVpnVpnService"
        const val ACTION_START = "com.amneziaclient.simple.openvpn.action.START"
        const val ACTION_STOP = "com.amneziaclient.simple.openvpn.action.STOP"
        const val EXTRA_OVPN_CONTENT = "ovpn_content"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SERVER_HOST_HINT = "server_host_hint"
        const val EXTRA_SELECTED_APPS = "selected_apps"

        private const val NOTIFICATION_ID = 44
        private const val STATS_POLL_INTERVAL_MS = 2_000L
        private const val STATS_POLL_INTERVAL_BACKGROUND_MS = 15_000L

        // ВАЖНО: SWIG сам НЕ генерирует System.loadLibrary() вызов (в
        // отличие от VpnBridge.kt для L2TP, где мы явно его прописали) —
        // без этого первое же обращение к любому net.openvpn.ovpn3.*
        // классу падает с UnsatisfiedLinkError на статическом инициализаторе
        // ovpncliJNI. Библиотека называется ровно "ovpn3" (libovpn3.so,
        // см. add_library(ovpn3 ...) в CMakeLists.txt апстрима).
        init {
            System.loadLibrary("ovpn3")
        }
    }

    private inner class Engine(private val selectedApps: Set<String>) : ClientAPI_OpenVPNClient() {
        var builder: VpnService.Builder? = null
        var establishedFd: ParcelFileDescriptor? = null

        override fun tun_builder_new(): Boolean {
            builder = Builder()
            return true
        }

        override fun tun_builder_set_mtu(mtu: Int): Boolean =
            runCatching { builder?.setMtu(mtu) }.isSuccess

        override fun tun_builder_set_session_name(name: String): Boolean =
            runCatching { builder?.setSession(name) }.isSuccess

        override fun tun_builder_set_remote_address(address: String, ipv6: Boolean): Boolean = true

        override fun tun_builder_add_address(
            address: String,
            prefix_length: Int,
            gateway: String,
            ipv6: Boolean,
            net30: Boolean
        ): Boolean = runCatching { builder?.addAddress(address, prefix_length) }.isSuccess

        override fun tun_builder_add_route(
            address: String,
            prefix_length: Int,
            metric: Int,
            ipv6: Boolean
        ): Boolean = runCatching { builder?.addRoute(address, prefix_length) }.isSuccess

        override fun tun_builder_reroute_gw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean =
            runCatching {
                if (ipv4) builder?.addRoute("0.0.0.0", 0)
                if (ipv6) builder?.addRoute("::", 0)
            }.isSuccess

        /** Раньше это не было переопределено — движок расценивал дефолтный
         *  `false` как ФАТАЛЬНУЮ ошибку разбора DNS-настроек (не просто
         *  "пропустить"), и подключение падало на этапе поднятия TUN, если
         *  сервер пушил DNS (`dhcp-option DNS ...` / `--dns`). Структуру
         *  DnsOptions/DnsServer/DnsAddress сверял по реальному исходнику
         *  (openvpn/client/dns_options.hpp), а не гадал. */
        override fun tun_builder_set_dns_options(dns: net.openvpn.ovpn3.DnsOptions): Boolean =
            runCatching {
                dns.servers.values.forEach { server ->
                    server.addresses.forEach { addr ->
                        runCatching { builder?.addDnsServer(addr.address) }
                    }
                }
                dns.search_domains.forEach { domain ->
                    runCatching { builder?.addSearchDomain(domain.domain) }
                }
                true
            }.getOrDefault(false)

        override fun tun_builder_establish(): Int {
            selectedApps.forEach { pkg -> runCatching { builder?.addAllowedApplication(pkg) } }
            // См. L2tpVpnService — своё приложение всегда в списке, иначе
            // при сплит-туннеле наши же диагностические запросы (пинг,
            // публичный IP) идут мимо VPN и вводят в заблуждение.
            if (selectedApps.isNotEmpty()) {
                runCatching { builder?.addAllowedApplication(packageName) }
            }
            val fd = runCatching { builder?.establish() }.getOrNull() ?: return -1
            establishedFd = fd
            startStatsPolling()
            // detachFd(), а не .fd — иначе получаем двойное владение одним и
            // тем же файловым дескриптором: C++-ядро openvpn3 само оборачивает
            // возвращённый fd в свой ScopedFD/TunPersistTemplate и закрывает
            // его САМО при остановке (это видно в нативном крэше: "fdsan:
            // attempted to close fd ..., actually owned by unique_fd ...").
            // detachFd() снимает fd с учёта у Java-стороны ParcelFileDescriptor,
            // передавая владение целиком нативному коду.
            return fd.detachFd()
        }

        override fun tun_builder_teardown(disconnect: Boolean) {
            // НЕ закрываем сами — см. комментарий в tun_builder_establish().
            // fd уже отсоединён через detachFd(), и его закрытие — теперь
            // забота исключительно C++-ядра.
            establishedFd = null
        }

        override fun socket_protect(socket: Int, remote: String, ipv6: Boolean): Boolean =
            runCatching { protect(socket) }.getOrDefault(false)

        override fun pause_on_connection_timeout(): Boolean = true

        override fun event(ev: ClientAPI_Event) {
            VpnDebugLog.log(TAG, "event: name=${ev.name} info=${ev.info} fatal=${ev.fatal}")
            when {
                ev.name == "CONNECTED" -> {
                    connectedSinceMillis = connectedSinceMillis ?: System.currentTimeMillis()
                    OpenVpnEngineState.state.value = PluginConnectionState.CONNECTED
                    OpenVpnEngineState.lastDetail.value = null
                    phaseCollectJob?.cancel()
                    measureConnectionExtrasOnce()
                    startPingRefreshOnForeground()
                }
                ev.name == "DISCONNECTED" -> {
                    stopTunnelAndService()
                }
                ev.fatal -> {
                    OpenVpnEngineState.lastDetail.value = "${ev.name}: ${ev.info}"
                    OpenVpnEngineState.state.value = PluginConnectionState.ERROR
                    stopTunnelAndService()
                }
                else -> {
                    OpenVpnEngineState.lastDetail.value = ev.name
                }
            }
        }

        override fun acc_event(ev: ClientAPI_AppCustomControlMessageEvent) = Unit

        override fun log(info: ClientAPI_LogInfo) {
            Log.d("openvpn3", info.text)
            VpnDebugLog.log("openvpn3", info.text)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch { stopTunnelAndService() }
            ACTION_START -> {
                // Идемпотентность — см. L2tpVpnService/VlessVpnService.
                if (OpenVpnEngineState.state.value == PluginConnectionState.CONNECTED ||
                    OpenVpnEngineState.state.value == PluginConnectionState.CONNECTING
                ) {
                    return START_NOT_STICKY
                }
                val ovpnContent = intent.getStringExtra(EXTRA_OVPN_CONTENT)
                if (ovpnContent.isNullOrBlank()) {
                    OpenVpnEngineState.state.value = PluginConnectionState.ERROR
                    stopSelf()
                    return START_NOT_STICKY
                }
                currentServerHost = intent.getStringExtra(EXTRA_SERVER_HOST_HINT)
                startForeground(NOTIFICATION_ID, buildNotification())
                startTunnel(
                    ovpnContent = ovpnContent,
                    username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                    password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    selectedApps = intent.getStringArrayListExtra(EXTRA_SELECTED_APPS)?.toSet() ?: emptySet()
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(ovpnContent: String, username: String, password: String, selectedApps: Set<String>) {
        OpenVpnEngineState.state.value = PluginConnectionState.CONNECTING
        OpenVpnEngineState.stats.value = ConnectionStats()
        OpenVpnEngineState.lastDetail.value = null

        val engine = Engine(selectedApps)
        client = engine

        serviceScope.launch {
            val config = ClientAPI_Config()
            config.content = ovpnContent

            val evalConfig = runCatching { engine.eval_config(config) }.getOrNull()
            if (evalConfig == null || evalConfig.error) {
                OpenVpnEngineState.lastDetail.value = evalConfig?.message ?: "eval_config failed"
                OpenVpnEngineState.state.value = PluginConnectionState.ERROR
                stopTunnelAndService()
                return@launch
            }

            if (!evalConfig.autologin && username.isNotBlank()) {
                val creds = ClientAPI_ProvideCreds()
                creds.username = username
                creds.password = password
                val credsStatus = runCatching { engine.provide_creds(creds) }.getOrNull()
                if (credsStatus?.error == true) {
                    OpenVpnEngineState.lastDetail.value = credsStatus.message
                    OpenVpnEngineState.state.value = PluginConnectionState.ERROR
                    stopTunnelAndService()
                    return@launch
                }
            }

            // connect() блокирует поток до отключения — свой Thread, не корутина.
            connectThread = Thread({
                val status = runCatching { engine.connect() }.getOrNull()
                if (status?.error == true) {
                    OpenVpnEngineState.lastDetail.value = status.message
                }
                serviceScope.launch { stopTunnelAndService() }
            }, "openvpn3-connect").apply { start() }
        }
    }

    /** Тот же приём, что и в IKEv2/L2TP: TrafficStats по своему UID. */
    private fun startStatsPolling() {
        statsPollJob?.cancel()
        val uid = Process.myUid()
        val baseRx = android.net.TrafficStats.getUidRxBytes(uid)
        val baseTx = android.net.TrafficStats.getUidTxBytes(uid)
        if (baseRx == android.net.TrafficStats.UNSUPPORTED.toLong() ||
            baseTx == android.net.TrafficStats.UNSUPPORTED.toLong()
        ) return
        trafficBaselineRx = baseRx
        trafficBaselineTx = baseTx
        statsPollJob = serviceScope.launch {
            while (isActive) {
                val rx = android.net.TrafficStats.getUidRxBytes(uid)
                val tx = android.net.TrafficStats.getUidTxBytes(uid)
                if (rx != android.net.TrafficStats.UNSUPPORTED.toLong() &&
                    tx != android.net.TrafficStats.UNSUPPORTED.toLong()
                ) {
                    OpenVpnEngineState.stats.value = OpenVpnEngineState.stats.value.copy(
                        bytesReceived = (rx - (trafficBaselineRx ?: rx)).coerceAtLeast(0),
                        bytesSent = (tx - (trafficBaselineTx ?: tx)).coerceAtLeast(0)
                    )
                }
                delay(if (AppForegroundState.isForeground.value) STATS_POLL_INTERVAL_MS else STATS_POLL_INTERVAL_BACKGROUND_MS)
            }
        }
    }

    /** Публичный IP один раз при подключении; пинг — сразу и затем разово при
     *  каждом возврате приложения на передний план. */
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
            OpenVpnEngineState.stats.value = OpenVpnEngineState.stats.value.copy(pingMillis = pingMs, publicIp = publicIp)
        }
    }

    private suspend fun measurePingOnceMs(host: String): Long? = runCatching {
        val start = System.currentTimeMillis()
        val reachable = withContext(Dispatchers.IO) { InetAddress.getByName(host).isReachable(3000) }
        if (reachable) System.currentTimeMillis() - start else null
    }.getOrNull()

    private fun startPingRefreshOnForeground() {
        pingRefreshJob?.cancel()
        pingRefreshJob = serviceScope.launch {
            AppForegroundState.onEnterForeground.collect {
                val host = currentServerHost ?: return@collect
                val pingMs = measurePingOnceMs(host)
                OpenVpnEngineState.stats.value = OpenVpnEngineState.stats.value.copy(pingMillis = pingMs)
            }
        }
    }

    private fun stopTunnelAndService() {
        statsPollJob?.cancel()
        pingRefreshJob?.cancel()
        extrasJob?.cancel()
        phaseCollectJob?.cancel()
        runCatching { client?.stop() }
        connectThread?.let { runCatching { it.join(3_000) } }
        connectThread = null
        client = null
        connectedSinceMillis = null
        OpenVpnEngineState.state.value = PluginConnectionState.DISCONNECTED
        OpenVpnEngineState.stats.value = ConnectionStats()
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, OpenVpnVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AmneziaApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(getString(R.string.notification_vpn_connected))
            .setContentText(getString(R.string.notification_server_name, currentServerHost ?: "OpenVPN"))
            .setUsesChronometer(true)
            .setWhen(connectedSinceMillis ?: System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.notification_disconnect), stopIntent)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
