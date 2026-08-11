package com.amneziaclient.simple.vpn.plugins.sstp

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import com.amneziaclient.simple.sstpbridge.SstpBridge
import com.amneziaclient.simple.vpn.AppForegroundState
import com.amneziaclient.simple.vpn.VpnDebugLog
import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.ImportResult
import com.amneziaclient.simple.vpn.plugin.ImportSource
import com.amneziaclient.simple.vpn.plugin.ImportedProfileDraft
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.ValidationResult
import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetAddress
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSTP через вендоренный kittoku/Open-SSTP-Client (MIT — см.
 * LICENSES-THIRD-PARTY.md). Реальная работа — в модуле :sstp
 * (SstpBridge + вендоренный движок), этот класс — обычная обвязка под
 * наш общий интерфейс VpnPlugin, как и у остальных протоколов.
 *
 * Важный нюанс архитектуры (см. комментарий в SstpBridge): у движка нет
 * честного сигнала "PPP-туннель реально поднят" — только "сервис
 * запущен". Поэтому после того, как движок сообщает о запуске, мы сами
 * проверяем реальную связь (пинг + публичный IP) и только по её
 * результату показываем "Подключено" — тот же приём, что и для VLESS.
 */
@Singleton
class SstpPlugin @Inject constructor(
    @ApplicationContext private val context: Context
) : VpnPlugin {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var healthCheckJob: Job? = null
    private var statsPollJob: Job? = null
    private var trafficBaselineRx: Long? = null
    private var trafficBaselineTx: Long? = null
    private var currentServerHost: String? = null
    private var currentDnsServer: String? = null

    private fun dlog(message: String) {
        Log.d(TAG, message)
        VpnDebugLog.log(TAG, message)
    }

    companion object {
        private const val TAG = "SstpPlugin"
        private const val STATS_POLL_INTERVAL_MS = 2_000L
        private const val STATS_POLL_INTERVAL_BACKGROUND_MS = 15_000L
        private val UNSUPPORTED = TrafficStats.UNSUPPORTED.toLong()
    }

    override val id: String = "sstp"
    override val protocol: VpnProtocolType = VpnProtocolType.SSTP
    override val displayName: String = "SSTP"
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    override val supportsSplitTunnel: Boolean = true
    override val supportsQrImport: Boolean = false
    override val supportedFileExtensions: List<String> = listOf("sstp")

    override val connectionState: StateFlow<PluginConnectionState> = SstpEngineState.state
    override val stats: StateFlow<ConnectionStats> = SstpEngineState.stats

    init {
        SstpBridge.ensureObserving(context)
        scope.launch {
            SstpBridge.rawState.collect { raw ->
                when (raw) {
                    SstpBridge.RawState.DISCONNECTED -> {
                        healthCheckJob?.cancel()
                        statsPollJob?.cancel()
                        trafficBaselineRx = null
                        trafficBaselineTx = null
                        currentServerHost = null
                        currentDnsServer = null
                        SstpEngineState.state.value = PluginConnectionState.DISCONNECTED
                        SstpEngineState.stats.value = ConnectionStats()
                        SstpEngineState.lastDetail.value = null
                    }
                    SstpBridge.RawState.CONNECTING -> {
                        // Уже могли быть тут (см. init{} при пересоздании
                        // плагина не бывает, но на всякий случай не дублируем).
                        if (SstpEngineState.state.value != PluginConnectionState.CONNECTED) {
                            SstpEngineState.state.value = PluginConnectionState.CONNECTING
                            startHealthCheck()
                        }
                    }
                }
            }
        }
    }

    override suspend fun importProfile(source: ImportSource): ImportResult {
        // Re-импорт файла, который сами же экспортировали — родного
        // текстового формата у SSTP-профиля нет, разбираем как наш JSON.
        if (source is ImportSource.FileText) {
            return runCatching {
                val json = JSONObject(source.rawText)
                val server = json.optString("server")
                if (server.isBlank()) return ImportResult.Error("Не указан адрес сервера.")
                ImportResult.Success(
                    ImportedProfileDraft(
                        suggestedName = source.fileName.substringBeforeLast(".").ifBlank { server },
                        protocol = VpnProtocolType.SSTP,
                        configBlob = source.rawText
                    )
                )
            }.getOrElse { ImportResult.Error("Не удалось разобрать файл профиля SSTP: ${it.message}") }
        }

        val fields = when (source) {
            is ImportSource.ManualFields -> source.fields
            else -> return ImportResult.Error("SSTP: поддерживается ручной ввод (сервер/логин/пароль) или файл, ранее экспортированный этим же приложением.")
        }
        val server = fields["server"]?.trim().orEmpty()
        if (server.isBlank()) return ImportResult.Error("Не указан адрес сервера.")
        val port = fields["port"]?.trim()?.toIntOrNull() ?: 443
        val username = fields["username"]?.trim().orEmpty()
        val password = fields["password"].orEmpty()
        val insecure = fields["insecure"]?.trim()?.lowercase() in setOf("yes", "true", "1", "да")
        val cert = fields["cert"]?.trim().orEmpty()
        val dns = fields["dns"]?.trim().orEmpty()

        val configBlob = JSONObject().apply {
            put("server", server)
            put("port", port)
            put("username", username)
            put("password", password)
            put("insecure", insecure)
            if (cert.isNotBlank()) put("cert", cert)
            if (dns.isNotBlank()) put("dns", dns)
        }.toString()

        return ImportResult.Success(
            ImportedProfileDraft(
                suggestedName = fields["name"]?.ifBlank { null } ?: server,
                protocol = VpnProtocolType.SSTP,
                configBlob = configBlob
            )
        )
    }

    override suspend fun exportProfile(configBlob: String): String = configBlob

    override suspend fun validate(configBlob: String): ValidationResult = try {
        val json = JSONObject(configBlob)
        if (json.optString("server").isBlank()) {
            ValidationResult.Invalid("Не указан адрес сервера.")
        } else {
            ValidationResult.Valid
        }
    } catch (e: Exception) {
        ValidationResult.Invalid("Некорректный формат профиля SSTP: ${e.message}")
    }

    override suspend fun connect(configBlob: String, selectedApps: Set<String>) {
        val json = JSONObject(configBlob)
        val server = json.optString("server")
        if (server.isBlank()) {
            SstpEngineState.state.value = PluginConnectionState.ERROR
            return
        }
        currentServerHost = server
        currentDnsServer = json.optString("dns").ifBlank { null }
        SstpEngineState.state.value = PluginConnectionState.CONNECTING
        SstpEngineState.stats.value = ConnectionStats()
        SstpEngineState.lastDetail.value = null

        val error = SstpBridge.connect(
            context = context,
            host = server,
            port = json.optInt("port", 443),
            username = json.optString("username"),
            password = json.optString("password"),
            insecure = json.optBoolean("insecure", false),
            certPem = json.optString("cert").ifBlank { null },
            dns = json.optString("dns").ifBlank { null },
            selectedApps = selectedApps
        )
        if (error != null) {
            SstpEngineState.lastDetail.value = error
            SstpEngineState.state.value = PluginConnectionState.ERROR
        }
    }

    override suspend fun disconnect() {
        SstpBridge.disconnect(context)
        withTimeoutOrNull(5_000) {
            SstpEngineState.state.first { it == PluginConnectionState.DISCONNECTED || it == PluginConnectionState.ERROR }
        }
    }

    /** Движок сообщает только "сервис запущен", не "PPP реально поднят" —
     *  подтверждаем связь сами (пинг + публичный IP), как и для VLESS. */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        val host = currentServerHost ?: return
        healthCheckJob = scope.launch {
            dlog("startHealthCheck: waiting 3s before first ping attempt to $host")
            delay(3_000) // дать время на PPP/SSTP-согласование
            // DIAGNOSTIC: в реальном логе между "waiting 3s" и первым успешным
            // "attempt 1/5" проходило 26-66 секунд вместо ожидаемых ~3 — при
            // этом сам pingMs (время именно getByName+isReachable) оказывался
            // быстрым (39-73 мс), т.е. проблема НЕ в самой сетевой проверке.
            // Это сообщение покажет, сколько РЕАЛЬНО прошло именно на delay(3000)
            // — если тут тоже будет ~60 секунд вместо 3, значит корутина не
            // выполняется вовремя (Dispatchers.Default чем-то занят/голодает),
            // а не физическая сеть тормозит.
            dlog("startHealthCheck: delay(3000) elapsed, entering ping loop")
            // DIAGNOSTIC/FIX: подтверждено логом — hasVpnTransport=false для
            // "активной по умолчанию" сети процесса этого приложения (см.
            // logActiveNetworkInfo ниже), то есть ВЕСЬ трафик health-check'а
            // (включая fetchPublicIp и rawTcpConnect) до сих пор шёл в обход
            // туннеля, по wlan0/мобильной сети — отсюда и "показывает домашний
            // IP", и все прошлые выводы про "файрвол на сервере блокирует TCP"
            // были построены на тесте, который туннель вообще не использовал.
            // В отличие от IKEv2 (где strongSwan сам явно исключает своё
            // приложение из тоннеля ради CRL-фетчера, и явная привязка к VPN-
            // сети падает с EPERM) — у kittoku такого самоисключения в коде
            // нет, так что явная привязка к найденной VPN-сети здесь должна
            // сработать без EPERM.
            logActiveNetworkInfo("before health-check ping loop")
            val vpnNetwork = findVpnNetwork()
            dlog("findVpnNetwork() result: $vpnNetwork")
            repeat(5) { attempt ->
                // DIAGNOSTIC: withTimeoutOrNull(8_000) в fetchPublicIp() уже стоит,
                // но реальная задержка в логе всё равно ~60+ секунд вместо
                // ожидаемых ~8.5 — значит либо она физически не там, где мы
                // думали, либо есть что-то неочевидное в поведении корутин
                // именно здесь. Меряем КАЖДЫЙ вызов explicit-таймстампами по
                // миллисекундам, а не полагаемся на интервалы между строками
                // лога — это снимает все вопросы разом.
                val t0 = System.currentTimeMillis()
                val pingMs = measurePingOnceMs(host)
                val t1 = System.currentTimeMillis()
                // ВАЖНО: теперь явно привязан к VPN-сети — это единственный
                // способ узнать РЕАЛЬНЫЙ публичный IP тоннеля, а не той сети,
                // что Android выбрал бы "по умолчанию" для этого процесса.
                val publicIp = if (pingMs != null) fetchPublicIp(vpnNetwork) else null
                val t2 = System.currentTimeMillis()
                val externalPingMs = measurePingOnceMs("1.1.1.1")
                val t3 = System.currentTimeMillis()
                // DIAGNOSTIC: отдельно проверяем, реально ли ДОСТИЖИМ сам DNS-
                // сервер через туннель (не только "настроен ли" — настроен он,
                // мы это уже подтвердили по IPTerminal-логу) — это разделяет
                // "DNS-сервер физически недоступен" от "маршрутизация вообще не
                // работает" от "работает всё, кроме крупных пакетов (MTU)".
                val dnsServerPingMs = currentDnsServer?.let { measurePingOnceMs(it) }
                val t4 = System.currentTimeMillis()
                // ВАЖНО: тоже теперь через vpnNetwork — раньше этот тест (как и
                // всё остальное) фактически проверял обычную сеть телефона, а
                // не туннель, из-за чего прошлый вывод "файрвол сервера
                // блокирует TCP" мог быть в корне неверным.
                val rawTcpConnectMs = runCatching {
                    val tcpStart = System.currentTimeMillis()
                    val socket = vpnNetwork?.socketFactory?.createSocket() ?: java.net.Socket()
                    socket.use {
                        it.connect(java.net.InetSocketAddress("1.1.1.1", 443), 5_000)
                    }
                    System.currentTimeMillis() - tcpStart
                }.getOrNull()
                val t5 = System.currentTimeMillis()
                dlog("health-check attempt ${attempt + 1}/5: pingMs=$pingMs publicIp=$publicIp externalIpPingMs=$externalPingMs " +
                    "dnsServerPingMs=$dnsServerPingMs rawTcpConnectMs=$rawTcpConnectMs " +
                    "[timing: measurePingOnceMs=${t1 - t0}ms fetchPublicIp=${t2 - t1}ms externalPing=${t3 - t2}ms " +
                    "dnsServerPing=${t4 - t3}ms rawTcpConnect=${t5 - t4}ms]")
                // ВАЖНО: measurePingOnceMs() внутри делает InetAddress.isReachable() —
                // это БЛОКИРУЮЩИЙ вызов, отмену корутины (healthCheckJob.cancel(),
                // см. RawState.DISCONNECTED выше) он не прерывает, отменённость
                // проверяется только в следующей точке приостановки. Если
                // пользователь нажал "Отключить" ПОКА этот вызов уже шёл, к
                // моменту его завершения состояние уже могло быть корректно
                // переведено в DISCONNECTED — без этой проверки мы бы затёрли его
                // обратно на CONNECTED (ровно так и ловилось: "Стоп" не
                // нажимается, профиль висит "Подключено" уже после реального
                // отключения). ensureActive() бросает CancellationException и
                // останавливает корутину здесь же, не давая ей записать состояние.
                ensureActive()
                if (pingMs != null || publicIp != null) {
                    SstpEngineState.stats.value = SstpEngineState.stats.value.copy(pingMillis = pingMs, publicIp = publicIp)
                    SstpEngineState.state.value = PluginConnectionState.CONNECTED
                    SstpEngineState.lastDetail.value = null
                    startPingRefreshOnForeground(host)
                    startTrafficStatsPolling()
                    return@launch
                }
                delay(2_000)
            }
            // Пять попыток не дали ответа — сервис формально запущен
            // (ROOT_STATE=true), но реального ответа от сервера нет.
            ensureActive()
            SstpEngineState.state.value = PluginConnectionState.CONNECTED
            SstpEngineState.lastDetail.value = "Туннель поднят, но нет ответа от сервера"
            startTrafficStatsPolling()
        }
    }

    /** Приём взят из IKEv2Plugin.kt — там же объяснение, почему он вообще
     *  работает: TrafficStats.getUidRxBytes/getUidTxBytes считает трафик по
     *  UID приложения, а не по конкретной сети/интерфейсу — а раз это
     *  приложение держит VpnService, ВЕСЬ трафик, идущий через созданный им
     *  туннель (в том числе от других приложений), Android относит на его же
     *  UID. Поэтому подход протокол-агностичный и одинаково работает что для
     *  IKEv2, что для SSTP — берём разницу (дельту) от значения на момент
     *  подключения, а не абсолютные числа (которые считаются с момента
     *  загрузки устройства).
     *
     *  На части прошивок/устройств TrafficStats может быть недоступен — тогда
     *  оба метода возвращают TrafficStats.UNSUPPORTED (-1), и в этом случае
     *  честно ничего не показываем (трафик остаётся на 0), а не подставляем
     *  нули как будто это реальные данные. */
    private fun startTrafficStatsPolling() {
        statsPollJob?.cancel()
        val uid = Process.myUid()
        val baseRx = TrafficStats.getUidRxBytes(uid)
        val baseTx = TrafficStats.getUidTxBytes(uid)
        if (baseRx == UNSUPPORTED || baseTx == UNSUPPORTED) return

        trafficBaselineRx = baseRx
        trafficBaselineTx = baseTx
        statsPollJob = scope.launch {
            while (isActive) {
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                if (rx != UNSUPPORTED && tx != UNSUPPORTED) {
                    val baseR = trafficBaselineRx ?: rx
                    val baseT = trafficBaselineTx ?: tx
                    SstpEngineState.stats.value = SstpEngineState.stats.value.copy(
                        bytesReceived = (rx - baseR).coerceAtLeast(0),
                        bytesSent = (tx - baseT).coerceAtLeast(0)
                    )
                }
                delay(if (AppForegroundState.isForeground.value) STATS_POLL_INTERVAL_MS else STATS_POLL_INTERVAL_BACKGROUND_MS)
            }
        }
    }

    private fun startPingRefreshOnForeground(host: String) {
        scope.launch {
            AppForegroundState.onEnterForeground.collect {
                if (SstpEngineState.state.value != PluginConnectionState.CONNECTED) return@collect
                val pingMs = measurePingOnceMs(host)
                ensureActive() // см. комментарий в startHealthCheck() — та же причина
                if (SstpEngineState.state.value != PluginConnectionState.CONNECTED) return@collect
                SstpEngineState.stats.value = SstpEngineState.stats.value.copy(pingMillis = pingMs)
            }
        }
    }

    private fun logActiveNetworkInfo(momentLabel: String) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val linkProps = active?.let { cm.getLinkProperties(it) }
            val message = "Active network ($momentLabel): network=$active " +
                "hasVpnTransport=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)} " +
                "hasWifiTransport=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)} " +
                "hasCellularTransport=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)} " +
                "interfaceName=${linkProps?.interfaceName} " +
                "dnsServers=${linkProps?.dnsServers}"
            dlog(message)
            cm.allNetworks.forEach { net ->
                val c = cm.getNetworkCapabilities(net)
                val lp = cm.getLinkProperties(net)
                val line = "  network=$net isVpn=${c?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)} " +
                    "iface=${lp?.interfaceName}"
                dlog(line)
            }
        }
    }

    private suspend fun measurePingOnceMs(host: String): Long? = runCatching {
        val start = System.currentTimeMillis()
        // withTimeoutOrNull — доп. подстраховка сверху: isReachable(3000) в
        // теории уже ограничен 3 секундами сам по себе, но на некоторых
        // версиях Android/сетевых стеках этот встроенный таймаут не всегда
        // соблюдается надёжно.
        val reachable = withTimeoutOrNull(5_000) {
            withContext(Dispatchers.IO) { InetAddress.getByName(host).isReachable(3000) }
        } ?: false
        if (reachable) System.currentTimeMillis() - start else null
    }.getOrNull()

    private fun findVpnNetwork(): android.net.Network? {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrNull()
    }

    private suspend fun fetchPublicIp(network: android.net.Network?): String? = runCatching {
        // FIX v2: withTimeoutOrNull САМ ПО СЕБЕ здесь недостаточен — он
        // перестаёт ЖДАТЬ результат через 8 секунд, но НЕ убивает и не
        // прерывает уже запущенный блокирующий вызов URL.openStream() —
        // поток на Dispatchers.IO как был занят этим вызовом, так и остаётся
        // занят (возможно, навсегда, если сокет реально завис без ответа).
        // При множестве повторных попыток подключения в рамках одного и того
        // же процесса приложения такие "зависшие" потоки со временем могли
        // накопиться и вытеснить весь (ограниченный по размеру) пул
        // Dispatchers.IO — тогда уже СЛЕДУЮЩИЙ вызов встаёт в очередь просто
        // на free-поток, и это ожидание никаким withTimeoutOrNull не
        // покрывается (таймер стартует, когда код УЖЕ начал выполняться, а
        // не когда он встал в очередь на диспетчер). Настоящий фикс — таймаут
        // НА САМОМ СОЕДИНЕНИИ (setConnectTimeout/setReadTimeout): тогда сам
        // блокирующий вызов гарантированно кинет SocketTimeoutException и
        // реально освободит поток, а не просто перестанет быть интересен
        // вызывающему коду.
        //
        // FIX v3: подтверждено логом (logActiveNetworkInfo) — "активная по
        // умолчанию" сеть для этого процесса НЕ является VPN (hasVpnTransport=
        // false), даже когда туннель реально поднят. Обычный url.openConnection()
        // уходил в обход туннеля, поэтому показывал не тот IP. Явно открываем
        // соединение через найденную VPN-сеть (Network.openConnection), если
        // она есть; иначе — как раньше (для случая, когда VPN-сеть почему-то
        // не нашлась, лучше попытаться через обычную, чем не пытаться вовсе).
        withContext(Dispatchers.IO) {
            val url = URL("https://api.ipify.org")
            val connection = (network?.openConnection(url) ?: url.openConnection()) as java.net.HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            try {
                connection.inputStream.bufferedReader().use { it.readText().trim() }
            } finally {
                connection.disconnect()
            }
        }
    }.getOrNull()
}
