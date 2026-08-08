package com.amneziaclient.simple.vpn.plugins.vless

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
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URL

/**
 * Мост между Xray-core (через XTLS/libXray — официальный враппер от самой
 * команды XTLS, MIT — см. LICENSES-THIRD-PARTY.md) и android.net.VpnService.
 *
 * ВАЖНО: этот сервис работает в ОТДЕЛЬНОМ процессе (":vless", см.
 * AndroidManifest.xml). Причина — воспроизводимый баг в самой библиотеке:
 * второй вызов runXrayFromJson в рамках одного процесса падает (SIGSEGV),
 * даже после полностью чистого stopXray() перед этим (issue готовится для
 * XTLS/libXray). Запуск в изолированном процессе, который мы сами убиваем
 * после каждой остановки, даёт два эффекта разом:
 *  1. Каждое новое подключение получает гарантированно чистый процесс —
 *     сам баг просто не успевает проявиться.
 *  2. Если нативный код всё же упадёт по любой другой причине — упадёт
 *     только этот изолированный процесс, а не всё приложение целиком.
 *
 * Расплата: VlessEngineState (обычный Kotlin-объект) больше НЕ виден из
 * основного процесса напрямую — состояние передаётся через широковещательные
 * Intent'ы (см. VlessStateBridge), а не через прямую запись в него.
 *
 * У этой библиотеки ЕСТЬ настоящий механизм socket-protect
 * (libXray.DialerController.protectFd) — движок сам вызывает его для
 * каждого своего служебного сокета, поэтому используется обычный allow-list
 * (addAllowedApplication) для настоящего сплит-туннеля, как и у остальных
 * протоколов — не нужно грубо исключать своё приложение из туннеля целиком.
 *
 * API синхронный: invoke(requestJSON) с JSON-конвертом
 * {"apiVersion":1,"method":"...","payload":{...}} и ответом
 * {"success":bool,"data":{},"error":""} — блокирует вызывающий поток до
 * реального результата, отдельный колбэк не нужен.
 *
 * Файловый дескриптор TUN передаётся через переменную окружения процесса
 * "xray.tun.fd" (см. Xray-core/proxy/tun/README.md), не параметром и не
 * полем JSON — устанавливается через android.system.Os.setenv().
 */
class VlessVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statsPollJob: Job? = null
    private var pingRefreshJob: Job? = null
    private var extrasJob: Job? = null
    private var connectedSinceMillis: Long? = null
    private var currentServerHost: String? = null
    private var establishedFd: ParcelFileDescriptor? = null
    private var rawTunFdForFallbackClose: Int? = null

    // Локальное состояние ЭТОГО процесса — используется как для внутренней
    // логики (идемпотентность в onStartCommand), так и как источник для
    // широковещательных обновлений в основной процесс (см. класс-комментарий).
    private var currentState = PluginConnectionState.DISCONNECTED
    private var currentStats = ConnectionStats()
    private var currentDetail: String? = null

    /** protectFd() дёргается движком САМ, для каждого своего служебного
     *  сокета (соединение к VLESS-серверу, DNS-запросы и т.д.) — без этого
     *  эти сокеты закольцевались бы в собственный туннель. */
    private val dialerController = object : DialerController {
        override fun protectFd(fd: Long): Boolean = runCatching { protect(fd.toInt()) }.getOrDefault(false)
    }

    companion object {
        const val ACTION_START = "com.amneziaclient.simple.vless.action.START"
        const val ACTION_STOP = "com.amneziaclient.simple.vless.action.STOP"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_SERVER_HOST_HINT = "server_host_hint"
        const val EXTRA_SELECTED_APPS = "selected_apps"

        private const val NOTIFICATION_ID = 45
        private const val TUN_LOCAL_ADDRESS = "10.10.14.1"
        private const val TUN_PREFIX_LENGTH = 30
        private const val STATS_POLL_INTERVAL_MS = 2_000L
        private const val STATS_POLL_INTERVAL_BACKGROUND_MS = 15_000L
        private const val TAG = "VlessVpnService"
    }

    private fun dlog(message: String) {
        Log.d(TAG, message)
        VpnDebugLog.log(TAG, message)
    }

    private fun dloge(message: String, t: Throwable? = null) {
        Log.e(TAG, message, t)
        VpnDebugLog.log(TAG, "$message${t?.message?.let { ": $it" } ?: ""}")
    }

    private fun setState(state: PluginConnectionState, detail: String? = currentDetail) {
        currentState = state
        currentDetail = detail
        broadcastNow()
    }

    private fun setDetail(detail: String?) {
        currentDetail = detail
        broadcastNow()
    }

    private fun updateStats(transform: (ConnectionStats) -> ConnectionStats) {
        currentStats = transform(currentStats)
        broadcastNow()
    }

    private fun broadcastNow() {
        VlessStateBridge.broadcastState(
            context = this,
            state = currentState,
            lastDetail = currentDetail,
            pingMillis = currentStats.pingMillis,
            publicIp = currentStats.publicIp,
            bytesReceived = currentStats.bytesReceived,
            bytesSent = currentStats.bytesSent
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dlog("onStartCommand: action=${intent?.action}, currentState=$currentState")
        when (intent?.action) {
            ACTION_STOP -> {
                dlog("ACTION_STOP received, dispatching stopTunnelAndService()")
                serviceScope.launch { stopTunnelAndService() }
            }
            ACTION_START -> {
                // Идемпотентность (см. остальные протоколы) — без этой
                // проверки повторный ACTION_START поднимает новую сессию
                // поверх старой, не остановив предыдущую.
                if (currentState == PluginConnectionState.CONNECTED ||
                    currentState == PluginConnectionState.CONNECTING
                ) {
                    dlog("ACTION_START ignored: already $currentState")
                    return START_NOT_STICKY
                }
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                if (configJson.isNullOrBlank()) {
                    dloge("ACTION_START: configJson is null/blank")
                    setState(PluginConnectionState.ERROR)
                    stopSelf()
                    return START_NOT_STICKY
                }
                currentServerHost = intent.getStringExtra(EXTRA_SERVER_HOST_HINT)
                val selectedApps = intent.getStringArrayListExtra(EXTRA_SELECTED_APPS)?.toSet() ?: emptySet()
                startForeground(NOTIFICATION_ID, buildNotification())
                startTunnel(configJson, selectedApps)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(configJson: String, selectedApps: Set<String>) {
        setState(PluginConnectionState.CONNECTING, detail = null)
        currentStats = ConnectionStats()

        serviceScope.launch {
            // Регистрируем протектор заново на каждое подключение — ссылка
            // должна указывать на ТЕКУЩИЙ живой экземпляр сервиса.
            runCatching { LibXray.registerDialerController(dialerController) }
            runCatching { LibXray.registerListenerController(dialerController) }
            runCatching { LibXray.setDNS(dialerController, "1.1.1.1:53") }
                .onFailure { dloge("setDNS failed (non-fatal, продолжаем)", it) }

            dlog("Building VpnService.Builder and calling establish()...")
            val fd = runCatching {
                val builder = Builder()
                    .setSession(getString(R.string.multivpn_app_name))
                    .setMtu(1500)
                    .addAddress(TUN_LOCAL_ADDRESS, TUN_PREFIX_LENGTH)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                selectedApps.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
                // Своё приложение — всегда в списке при активном
                // сплит-туннеле (см. остальные протоколы) — иначе наши же
                // диагностические запросы (пинг/IP) идут мимо VPN.
                if (selectedApps.isNotEmpty()) {
                    runCatching { builder.addAllowedApplication(packageName) }
                }
                builder.establish()
            }.onFailure { dloge("establish() threw", it) }.getOrNull()

            if (fd == null) {
                dloge("establish() returned null")
                setState(PluginConnectionState.ERROR, "VpnService.Builder.establish() failed")
                stopTunnelAndService()
                return@launch
            }
            dlog("establish() succeeded, fd=${fd.fd}")
            establishedFd = fd

            // xray.tun.fd — это НАСТОЯЩАЯ переменная окружения процесса ОС
            // (см. Xray-core/proxy/tun/README.md), а НЕ поле JSON-конфига —
            // предыдущая версия ошибочно пыталась засунуть её как вложенный
            // объект "env" в корень конфига, из-за чего Xray падал с
            // "cannot unmarshal number into Go struct field Config.env of
            // type string" (в Config такого поля вообще нет). Поскольку Go
            // слинкован в тот же процесс, что и наш Kotlin-код,
            // android.system.Os.setenv() реально видна рантайму Go через
            // os.Getenv().
            val envSet = runCatching {
                android.system.Os.setenv("xray.tun.fd", fd.fd.toString(), true)
            }
            if (envSet.isFailure) {
                dloge("Os.setenv(xray.tun.fd) failed", envSet.exceptionOrNull())
                runCatching { fd.close() }
                establishedFd = null
                setState(PluginConnectionState.ERROR, "Не удалось установить xray.tun.fd")
                stopTunnelAndService()
                return@launch
            }

            val request = JSONObject().apply {
                put("apiVersion", 1)
                put("method", "runXrayFromJson")
                put("payload", JSONObject().put("configJSON", configJson))
            }

            dlog("Calling LibXray.invoke(runXrayFromJson)...")
            val rawResponse = runCatching { LibXray.invoke(request.toString()) }
            dlog("invoke() returned: $rawResponse")

            val response = rawResponse.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
            val success = response?.optBoolean("success") == true

            if (success) {
                // Только теперь движок реально владеет дескриптором — см.
                // OpenVpnVpnService про detachFd() и двойное закрытие.
                rawTunFdForFallbackClose = fd.fd
                runCatching { fd.detachFd() }
                connectedSinceMillis = connectedSinceMillis ?: System.currentTimeMillis()
                setState(PluginConnectionState.CONNECTED)
                measureConnectionExtrasOnce()
                startPingRefreshOnForeground()
                startStatsPolling()
            } else {
                // Xray не взял fd — закрываем сами, иначе утечёт (см.
                // историю с "обрубило интернет" в предыдущей версии).
                runCatching { fd.close() }
                establishedFd = null
                val errorMessage = response?.optString("error")?.ifBlank { null }
                    ?: rawResponse.exceptionOrNull()?.message
                    ?: "runXrayFromJson failed"
                dloge("runXrayFromJson failed: $errorMessage")
                setState(PluginConnectionState.ERROR, errorMessage)
                stopTunnelAndService()
            }
        }
    }

    private fun startStatsPolling() {
        statsPollJob?.cancel()
        val uid = Process.myUid()
        val baseRx = android.net.TrafficStats.getUidRxBytes(uid)
        val baseTx = android.net.TrafficStats.getUidTxBytes(uid)
        if (baseRx == android.net.TrafficStats.UNSUPPORTED.toLong() ||
            baseTx == android.net.TrafficStats.UNSUPPORTED.toLong()
        ) return
        statsPollJob = serviceScope.launch {
            while (isActive) {
                val rx = android.net.TrafficStats.getUidRxBytes(uid)
                val tx = android.net.TrafficStats.getUidTxBytes(uid)
                if (rx != android.net.TrafficStats.UNSUPPORTED.toLong() &&
                    tx != android.net.TrafficStats.UNSUPPORTED.toLong()
                ) {
                    updateStats {
                        it.copy(
                            bytesReceived = (rx - baseRx).coerceAtLeast(0),
                            bytesSent = (tx - baseTx).coerceAtLeast(0)
                        )
                    }
                }
                delay(if (AppForegroundState.isForeground.value) STATS_POLL_INTERVAL_MS else STATS_POLL_INTERVAL_BACKGROUND_MS)
            }
        }
    }

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
            updateStats { it.copy(pingMillis = pingMs, publicIp = publicIp) }
            if (pingMs == null && publicIp == null) {
                dlog("Health-check failed: tunnel reports connected, but no response from server at all")
                setDetail("Туннель поднят, но нет ответа от сервера")
            }
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
                updateStats { it.copy(pingMillis = pingMs) }
            }
        }
    }

    private suspend fun stopTunnelAndService() {
        dlog("stopTunnelAndService() called")
        statsPollJob?.cancel()
        pingRefreshJob?.cancel()
        extrasJob?.cancel()

        val stopRequest = JSONObject().apply {
            put("apiVersion", 1)
            put("method", "stopXray")
        }
        val stopResultRaw = runCatching { LibXray.invoke(stopRequest.toString()) }
        dlog("stopXray invoke() returned: $stopResultRaw")
        val stopSuccess = stopResultRaw.getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optBoolean("success") == true

        runCatching { LibXray.resetDNS() }
        runCatching { android.system.Os.unsetenv("xray.tun.fd") }

        if (!stopSuccess) {
            // stopXray() сам не подтвердил успех — в этом случае, в отличие
            // от обычного пути, всё же закрываем fd сами как крайнюю меру
            // (риск гонки с переиспользованием номера fd тут принимаем
            // осознанно — альтернатива хуже: реально зависший, никогда не
            // закрытый VPN-интерфейс). При УСПЕШНОМ stopXray() эту
            // подстраховку не делаем вообще — именно она в прошлый раз
            // уронила AmneziaWG в следующей сессии. establishedFd к этому
            // моменту уже detachFd()-нут при успешном коннекте, поэтому
            // используем отдельно сохранённый сырой номер fd.
            dloge("stopXray did not report success — closing fd ourselves as a last resort")
            rawTunFdForFallbackClose?.let { rfd ->
                runCatching { ParcelFileDescriptor.adoptFd(rfd).close() }
            }
            runCatching { establishedFd?.close() }
        }
        rawTunFdForFallbackClose = null
        establishedFd = null

        connectedSinceMillis = null
        currentStats = ConnectionStats()
        setState(PluginConnectionState.DISCONNECTED, detail = null)
        val stopSelfResult = runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        dlog("stopTunnelAndService() finished, stopSelf result: $stopSelfResult")

        // Убиваем ВЕСЬ этот (изолированный) процесс после каждой остановки —
        // так следующее подключение гарантированно начнётся в чистом
        // процессе, минуя баг библиотеки (второй runXrayFromJson в одном
        // процессе крашится даже после честного stopXray()). Небольшая
        // задержка — чтобы широковещательный Intent с финальным статусом
        // DISCONNECTED точно успел дойти до основного процесса ДО того, как
        // этот процесс исчезнет.
        delay(300)
        dlog("Killing isolated :vless process for a clean next connect")
        Process.killProcess(Process.myPid())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, VlessVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AmneziaApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(getString(R.string.notification_vpn_connected))
            .setContentText(getString(R.string.notification_server_name, currentServerHost ?: "VLESS"))
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
