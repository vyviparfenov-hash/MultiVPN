package com.amneziaclient.simple.vpn.plugins.ikev2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.TrafficStats
import android.os.IBinder
import android.os.Process
import com.amneziaclient.simple.vpn.AppForegroundState
import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.ImportResult
import com.amneziaclient.simple.vpn.plugin.ImportSource
import com.amneziaclient.simple.vpn.plugin.ImportedProfileDraft
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.ValidationResult
import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileDataSource
import org.strongswan.android.data.VpnProfileSource
import org.strongswan.android.data.VpnType
import org.strongswan.android.logic.TrustedCertificateManager
import org.strongswan.android.logic.VpnStateService
import org.strongswan.android.ui.VpnProfileControlActivity
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IKEv2/IPsec через официальную библиотеку strongSwan (вендорена в
 * strongswan-module/, см. settings.gradle.kts) — НЕ через урезанный
 * системный android.net.Ikev2VpnProfile (у него нет сплит-туннеля по
 * приложениям в принципе, см. историю разработки).
 *
 * Формат configBlob — официальный, документированный JSON-профиль strongSwan
 * (.sswan), см. https://docs.strongswan.org/docs/latest/os/androidVpnClientProfiles.html.
 * Поля apps/excluded-apps в этом формате — штатная, официально
 * поддерживаемая возможность самого strongSwan, а не наша самодеятельность.
 *
 * Подключение запускается через официальный, проверенный путь —
 * VpnProfileControlActivity с EXTRA_VPN_PROFILE_UUID, точно так же, как это
 * делает сам официальный экран профилей.
 */
@Singleton
class IKEv2Plugin @Inject constructor(
    @ApplicationContext private val context: Context
) : VpnPlugin {

    override val id: String = "ikev2-strongswan"
    override val protocol: VpnProtocolType = VpnProtocolType.IKEV2
    override val displayName: String = "IKEv2/IPsec (strongSwan)"
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null

    // В отличие от системного Ikev2VpnProfile, у strongSwan сплит-туннель по
    // приложениям реально есть (apps/excluded-apps в формате профиля).
    override val supportsSplitTunnel: Boolean = true
    override val supportsQrImport: Boolean = false
    override val supportedFileExtensions: List<String> = listOf("sswan", "json")

    private val _connectionState = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<PluginConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow(ConnectionStats())
    override val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()

    private var vpnStateService: VpnStateService? = null
    private var stateListener: VpnStateService.VpnStateListener? = null
    private var isBoundToStateService = false
    private var bindDeferred: CompletableDeferred<Unit>? = null
    private var lastProfileUuid: String? = null
    private var lastServerHost: String? = null
    private var extrasMeasuredForCurrentSession = false
    private var extrasJob: Job? = null
    private var statsPollJob: Job? = null
    private var pingRefreshJob: Job? = null
    private var trafficBaselineRx: Long? = null
    private var trafficBaselineTx: Long? = null
    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var safetyTimeoutJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? VpnStateService.LocalBinder)?.service ?: run {
                bindDeferred?.complete(Unit)
                return
            }
            vpnStateService = service
            val listener = object : VpnStateService.VpnStateListener {
                override fun stateChanged() {
                    safetyTimeoutJob?.cancel()
                    val newState = mapState(service.state?.name)
                    android.util.Log.d("IKEv2Plugin", "strongSwan state changed: ${service.state?.name} -> $newState")
                    com.amneziaclient.simple.vpn.VpnDebugLog.log(
                        "IKEv2Plugin", "strongSwan state changed: ${service.state?.name} -> $newState"
                    )
                    _connectionState.value = newState
                    if (newState == PluginConnectionState.CONNECTED && !extrasMeasuredForCurrentSession) {
                        extrasMeasuredForCurrentSession = true
                        measureConnectionExtrasOnce()
                        startTrafficStatsPolling()
                        startPingRefreshOnForeground()
                    }
                    if (newState == PluginConnectionState.DISCONNECTED) {
                        extrasMeasuredForCurrentSession = false
                        statsPollJob?.cancel()
                        pingRefreshJob?.cancel()
                        trafficBaselineRx = null
                        trafficBaselineTx = null
                        _stats.value = ConnectionStats()
                    }
                }
            }
            stateListener = listener
            service.registerListener(listener)
            _connectionState.value = mapState(service.state?.name)
            bindDeferred?.complete(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnStateService = null
        }
    }

    /**
     * ВАЖНО: привязка к VpnStateService (и тем более BIND_AUTO_CREATE, который
     * реально ЗАПУСКАЕТ сервис) НЕ должна происходить в конструкторе/init —
     * IKEv2Plugin создаётся Hilt'ом сразу при старте приложения (для
     * валидации графа зависимостей, см. AmneziaApp), то есть до того, как
     * пользователь вообще коснулся IKEv2. Именно это, судя по всему, и
     * вызывало самопроизвольное "Подключение..." сразу после добавления
     * профиля — сервис/strongSwan запускался ещё на старте приложения, в
     * обход любых настроек автоподключения. Теперь привязка происходит
     * только при реальном connect().
     *
     * ВАЖНО #2: bindService() асинхронный — раньше мы вызывали его и сразу
     * же, не дожидаясь готовности сервиса, запускали VpnProfileControlActivity.
     * На "холодном" первом запуске (сервис ещё не поднят) активность,
     * похоже, не успевала получить нужное состояние и падала в никуда без
     * видимой ошибки — отсюда "Старт срабатывает только со второго раза".
     * Теперь функция suspend и реально ЖДЁТ подключения (с таймаутом), и
     * только после этого продолжается запуск.
     */
    private suspend fun ensureBoundToStateService() {
        if (isBoundToStateService && vpnStateService != null) return
        if (!isBoundToStateService) {
            isBoundToStateService = true
            val deferred = CompletableDeferred<Unit>()
            bindDeferred = deferred
            val started = runCatching {
                context.bindService(
                    Intent(context, VpnStateService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            }.getOrDefault(false)
            if (!started) {
                deferred.complete(Unit)
            }
        }
        withTimeoutOrNull(5_000) { bindDeferred?.await() }
    }

    /** Счётчики трафика для IKEv2 через TrafficStats — общий Android API, а
     *  не strongSwan-специфичный. Подтверждённого API счётчиков байт внутри
     *  самого strongSwan НЕТ ни в одном из проверенных мест: ни в
     *  VpnStateService.java (см. диагностику через CI), ни в
     *  CharonVpnService.java, ни в JNI-мосту (charonservice.c/android_jni.c) —
     *  их читал напрямую из vendored-копии в strongswan-module/.
     *
     *  Зато Android сам атрибутирует ВЕСЬ туннелированный трафик (то есть уже
     *  зашифрованные пакеты, идущие в реальный физический интерфейс) на UID
     *  того приложения, которое держит VpnService — это то же самое
     *  поведение, из-за которого в системном экране "Расход трафика" у любого
     *  VPN-клиента показывается большой расход. Именно поэтому подход не
     *  является протокол-специфичным и годится не только для IKEv2.
     *
     *  Берём разницу (дельту) между значением на момент CONNECTED и текущим —
     *  сами по себе TrafficStats.getUidRxBytes/getUidTxBytes считают с
     *  момента загрузки устройства, абсолютные числа нам не нужны.
     *
     *  На части прошивок/устройств TrafficStats может быть недоступен —
     *  тогда оба метода возвращают TrafficStats.UNSUPPORTED (-1). В этом
     *  случае честно ничего не показываем (трафик остаётся на 0/скрытым),
     *  а не подставляем нули как будто это реальные данные. */
    private fun startTrafficStatsPolling() {
        statsPollJob?.cancel()
        val uid = Process.myUid()
        val baseRx = TrafficStats.getUidRxBytes(uid)
        val baseTx = TrafficStats.getUidTxBytes(uid)
        if (baseRx == UNSUPPORTED || baseTx == UNSUPPORTED) return

        trafficBaselineRx = baseRx
        trafficBaselineTx = baseTx
        statsPollJob = pluginScope.launch {
            while (isActive) {
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                if (rx != UNSUPPORTED && tx != UNSUPPORTED) {
                    val baseR = trafficBaselineRx ?: rx
                    val baseT = trafficBaselineTx ?: tx
                    _stats.value = _stats.value.copy(
                        bytesReceived = (rx - baseR).coerceAtLeast(0),
                        bytesSent = (tx - baseT).coerceAtLeast(0)
                    )
                }
                delay(if (com.amneziaclient.simple.vpn.AppForegroundState.isForeground.value) STATS_POLL_INTERVAL_MS else STATS_POLL_INTERVAL_BACKGROUND_MS)
            }
        }
    }

    /** Публичный IP меряется один раз при подключении и не обновляется
     *  повторно за сессию (он и не должен меняться, пока туннель жив).
     *  Пинг меряется тут же (сразу после коннекта), а также отдельно —
     *  см. [startPingRefreshOnForeground] — при каждом возврате приложения
     *  на передний план, но НЕ постоянно и НЕ в фоне (незачем, если никто
     *  не смотрит на экран). */
    private fun measureConnectionExtrasOnce() {
        val host = lastServerHost ?: return
        extrasJob?.cancel()
        extrasJob = pluginScope.launch {
            delay(2_000)
            val pingMs = measurePingOnceMs(host)

            logActiveNetworkInfo("before publicIp fetch")

            // PATCH: раньше здесь была попытка явно привязать запрос к VPN-сети
            // (Network.openConnection()) — но для IKEv2 она гарантированно и
            // всегда падает с "SocketException: ... EPERM (Operation not
            // permitted)". Причина не временная и не наша: strongSwan сам,
            // безусловно, исключает своё же приложение из собственного туннеля
            // (см. CharonVpnService.java апстрима, комментарий "exclude our own
            // app, otherwise the fetcher is blocked" — это нужно, чтобы их
            // CRL/OCSP-fetcher при проверке сертификата сервера мог сходить в
            // сеть МИМО ещё не поднятого туннеля). А раз наш процесс исключён —
            // Android на уровне netd/ядра запрещает ЛЮБУЮ привязку сокета к этой
            // VPN-сети из этого UID, в том числе явную через Network.bindSocket()
            // /openConnection(). Это не обходится кодом уровня приложения.
            // Поэтому для IKEv2 сразу отдаём null: HomeFragment.kt показывает его
            // как "—" (не путать с "0.0.0.0" или ошибкой) — честно, без попытки,
            // которая 100% упадёт, и без пугающего стектрейса в логе на каждое
            // подключение.
            val publicIp: String? = null
            com.amneziaclient.simple.vpn.VpnDebugLog.log(
                "IKEv2Plugin",
                "measureConnectionExtrasOnce: publicIp check skipped for IKEv2 — app is excluded " +
                    "from its own tunnel by strongSwan (cert fetcher), binding to it always fails " +
                    "with EPERM; showing \"—\" instead"
            )

            android.util.Log.d("IKEv2Plugin", "measureConnectionExtrasOnce: server=$host pingMs=$pingMs publicIp=$publicIp")
            com.amneziaclient.simple.vpn.VpnDebugLog.log(
                "IKEv2Plugin", "measureConnectionExtrasOnce: server=$host pingMs=$pingMs publicIp=$publicIp"
            )

            _stats.value = _stats.value.copy(pingMillis = pingMs, publicIp = publicIp)
        }
    }

    /** Прямая проверка через ConnectivityManager — однозначно показывает,
     *  какую сеть Android считает "активной по умолчанию" для НАШЕГО
     *  процесса прямо сейчас (в том числе — идёт ли это через VPN-туннель,
     *  TRANSPORT_VPN, или в обход него). Не зависит от поведения внешних
     *  сервисов вроде ipify.org. */
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
            android.util.Log.d("IKEv2Plugin", message)
            com.amneziaclient.simple.vpn.VpnDebugLog.log("IKEv2Plugin", message)

            // Заодно все AllNetworks — вдруг Android видит VPN-сеть, но
            // считает "активной по умолчанию" для нашего приложения
            // какую-то другую (это уже было бы совсем другим объяснением).
            cm.allNetworks.forEach { net ->
                val c = cm.getNetworkCapabilities(net)
                val lp = cm.getLinkProperties(net)
                val line = "  network=$net isVpn=${c?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)} " +
                    "iface=${lp?.interfaceName}"
                android.util.Log.d("IKEv2Plugin", line)
                com.amneziaclient.simple.vpn.VpnDebugLog.log("IKEv2Plugin", line)
            }
        }
    }

    private suspend fun measurePingOnceMs(host: String): Long? = runCatching {
        val start = System.currentTimeMillis()
        val reachable = withContext(Dispatchers.IO) {
            InetAddress.getByName(host).isReachable(3000)
        }
        if (reachable) System.currentTimeMillis() - start else null
    }.getOrNull()

    /** Пинг НЕ опрашивается непрерывно — только один раз при подключении
     *  (см. measureConnectionExtrasOnce) и один раз при каждом возврате
     *  приложения на передний план (открыли/разблокировали экран), пока
     *  туннель ещё жив. Пока приложение свёрнуто — пинг просто не трогаем,
     *  на экране остаётся последнее зафиксированное значение. */
    private fun startPingRefreshOnForeground() {
        pingRefreshJob?.cancel()
        pingRefreshJob = pluginScope.launch {
            AppForegroundState.onEnterForeground.collect {
                val host = lastServerHost ?: return@collect
                val pingMs = measurePingOnceMs(host)
                _stats.value = _stats.value.copy(pingMillis = pingMs)
            }
        }
    }

    private fun mapState(rawStateName: String?): PluginConnectionState = when (rawStateName) {
        "CONNECTED" -> PluginConnectionState.CONNECTED
        "CONNECTING" -> PluginConnectionState.CONNECTING
        "DISCONNECTING" -> PluginConnectionState.DISCONNECTING
        "DISABLED" -> PluginConnectionState.DISCONNECTED
        null -> PluginConnectionState.DISCONNECTED
        else -> PluginConnectionState.ERROR
    }

    override suspend fun importProfile(source: ImportSource): ImportResult {
        val rawText = when (source) {
            is ImportSource.FileText -> source.rawText
            is ImportSource.ClipboardText -> source.text
            is ImportSource.QrPayload -> source.text
            is ImportSource.Uri -> return ImportResult.Error("Импорт по ссылке для IKEv2 пока не поддержан")
            is ImportSource.ManualFields -> buildManualProfileJson(source.fields)
        }

        return try {
            val json = JSONObject(rawText)
            if (!json.has("uuid")) {
                json.put("uuid", UUID.randomUUID().toString())
            }
            if (!json.has("remote") || !json.getJSONObject("remote").has("addr")) {
                return ImportResult.Error("В профиле не указан адрес сервера (remote.addr)")
            }
            val name = json.optString("name", "IKEv2")
            ImportResult.Success(
                ImportedProfileDraft(
                    suggestedName = name,
                    protocol = VpnProtocolType.IKEV2,
                    configBlob = json.toString()
                )
            )
        } catch (e: Exception) {
            ImportResult.Error("Не удалось разобрать .sswan/JSON-профиль: ${e.message}")
        }
    }

    private fun buildManualProfileJson(fields: Map<String, String>): String {
        val json = JSONObject()
        json.put("uuid", UUID.randomUUID().toString())
        json.put("name", fields["name"] ?: "IKEv2")
        json.put("type", fields["type"] ?: "ikev2-eap")
        val remote = JSONObject()
        remote.put("addr", fields["server"].orEmpty())
        // CA-сертификат сервера (для самоподписанных серверов) — храним как
        // есть (PEM-текст), CertificateFactory.generateCertificate() умеет
        // разбирать PEM с заголовками -----BEGIN CERTIFICATE----- напрямую.
        fields["cert"]?.takeIf { it.isNotBlank() }?.let { remote.put("cert", it) }
        json.put("remote", remote)
        val local = JSONObject()
        fields["username"]?.let { local.put("eap_id", it) }
        json.put("local", local)
        // Пароль руками в JSON-профиль по документированному формату не
        // сохраняется (там его нет намеренно) — передаём его отдельно при
        // подключении через bundle, как это делает и официальный экран.
        fields["password"]?.let { json.put("_password", it) }
        return json.toString()
    }

    override suspend fun exportProfile(configBlob: String): String = configBlob

    override suspend fun validate(configBlob: String): ValidationResult {
        return try {
            val json = JSONObject(configBlob)
            if (!json.has("name")) return ValidationResult.Invalid("Не указано имя профиля")
            if (!json.has("remote") || !json.getJSONObject("remote").has("addr")) {
                return ValidationResult.Invalid("Не указан адрес сервера")
            }
            ValidationResult.Valid
        } catch (e: Exception) {
            ValidationResult.Invalid("Некорректный JSON: ${e.message}")
        }
    }

    override suspend fun connect(configBlob: String, selectedApps: Set<String>) {
        ensureBoundToStateService()
        val json = JSONObject(configBlob)
        // VpnProfileDataSource — интерфейс; VpnProfileSource — конкретная
        // реализация с конструктором Context (подтверждено реальным исходником).
        //
        // ВАЖНО (найдено по crash.txt): open() ВОЗВРАЩАЕТ инициализированный
        // объект, а не мутирует текущий "на месте" — раньше здесь результат
        // open() отбрасывался, и все дальнейшие вызовы (getVpnProfile,
        // insertProfile, updateVpnProfile) шли на ещё не готовый объект,
        // из-за чего внутри VpnProfileSource падал NullPointerException на
        // необращённом объекте.
        val dataSource: VpnProfileDataSource = VpnProfileSource(context).open()
        try {
            val profile = jsonToVpnProfile(json, selectedApps)
            val existing = dataSource.getVpnProfile(profile.uuid)
            if (existing != null) {
                profile.id = existing.id
                // ВАЖНО (найдено по реальному исходнику VpnProfileSource.java,
                // строка updateVpnProfile): обновление делегируется через
                // САМ объект профиля — profile.getDataSource().updateVpnProfile(profile).
                // У свежесозданного нами VpnProfile() это поле никогда не
                // устанавливалось (null) — отсюда и был NullPointerException.
                // У профиля, полученного через getVpnProfile(uuid), оно уже
                // корректно проставлено — копируем его.
                profile.dataSource = existing.dataSource
                dataSource.updateVpnProfile(profile)
            } else {
                dataSource.insertProfile(profile)
            }

            _connectionState.value = PluginConnectionState.CONNECTING
            lastProfileUuid = profile.uuid.toString()
            lastServerHost = json.optJSONObject("remote")?.optString("addr", null)
            extrasMeasuredForCurrentSession = false
            val intent = Intent(context, VpnProfileControlActivity::class.java).apply {
                // ВАЖНО (найдено по реальному исходнику VpnProfileControlActivity —
                // handleIntent() проверяет intent.getAction() и ничего не делает,
                // если он не задан): раньше action не устанавливался вообще,
                // из-за чего Activity открывалась пустым окном и НИЧЕГО не
                // делала — ни диалога разрешения VPN, ни подключения.
                action = VpnProfileControlActivity.START_PROFILE
                putExtra(VpnProfileControlActivity.EXTRA_VPN_PROFILE_UUID, profile.uuid.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            // Страховка: если реальное состояние от VpnStateService так и не
            // придёт (сеть недоступна, сервер не отвечает, сама Activity не
            // смогла стартовать и т.п.) — не оставляем интерфейс залипшим
            // на "Подключение..." навсегда, а через таймаут явно показываем
            // ошибку, чтобы кнопку снова можно было нажать.
            safetyTimeoutJob?.cancel()
            safetyTimeoutJob = pluginScope.launch {
                delay(25_000)
                if (_connectionState.value == PluginConnectionState.CONNECTING) {
                    _connectionState.value = PluginConnectionState.ERROR
                }
            }
        } finally {
            dataSource.close()
        }
    }

    private fun jsonToVpnProfile(json: JSONObject, selectedApps: Set<String>): VpnProfile {
        val profile = VpnProfile()
        profile.uuid = UUID.fromString(json.getString("uuid"))
        profile.name = json.optString("name", "IKEv2")
        profile.vpnType = mapVpnType(json.optString("type", "ikev2-eap"))

        val remote = json.getJSONObject("remote")
        profile.gateway = remote.getString("addr")
        remote.optString("id", null)?.let { profile.remoteId = it }

        // ВАЖНО (найдено по реальному исходнику VpnProfileImportActivity —
        // это тот самый код, которым официальный экран сохраняет CA-сертификат
        // самоподписанного сервера): сертификат нужно 1) разобрать через
        // CertificateFactory, 2) вычислить его алиас в "LocalCertificateStore"
        // (детерминированный, по содержимому сертификата), 3) реально
        // добавить его в это хранилище, 4) попросить TrustedCertificateManager
        // перечитать хранилище, 5) сохранить алиас в самом профиле.
        remote.optString("cert", null)?.takeIf { it.isNotBlank() }?.let { certPem ->
            runCatching {
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certPem.byteInputStream()) as X509Certificate
                val store = KeyStore.getInstance("LocalCertificateStore")
                store.load(null, null)
                val alias = store.getCertificateAlias(certificate)
                store.setCertificateEntry(null, certificate)
                TrustedCertificateManager.getInstance().reset()
                profile.certificateAlias = alias
            }.onFailure {
                // Некорректный/повреждённый сертификат — просто не подставляем
                // алиас, а не роняем всё подключение целиком.
            }
        }

        json.optJSONObject("local")?.let { local ->
            local.optString("eap_id", null)?.let { profile.username = it }
            local.optString("id", null)?.let { profile.localId = it }
        }

        // ВАЖНО (найдено по реальному исходнику VpnProfileControlActivity):
        // пароль читается методом profile.getPassword() ИЗ БАЗЫ ДАННЫХ, а не
        // из extras присылаемого нами Intent — Intent.KEY_PASSWORD, который
        // мы передавали раньше, попросту НИКОГДА не читается этим кодом.
        // Пароль обязательно нужно сохранить прямо в объекте профиля.
        json.optString("_password", null)?.let { profile.password = it }

        // Наш экран выбора приложений — источник истины, а не то, что было
        // в исходном .sswan (если пользователь явно что-то выбрал в нашем UI).
        // setSelectedApps перегружен (String / SortedSet<String>) — поэтому
        // вызываем явно как метод, а не через свойство.
        if (selectedApps.isNotEmpty()) {
            profile.setSelectedAppsHandling(VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY)
            // Своё приложение — всегда в списке (см. L2tpVpnService/
            // OpenVpnVpnService/AmneziaWgAdapter): иначе наш же
            // measureConnectionExtrasOnce() (пинг/публичный IP) при
            // активном сплит-туннеле идёт мимо VPN.
            profile.setSelectedApps((selectedApps + context.packageName).toSortedSet())
        } else {
            applyJsonAppLists(json, profile)
        }

        return profile
    }

    private fun applyJsonAppLists(json: JSONObject, profile: VpnProfile) {
        val apps = json.optJSONArray("apps")
        val excluded = json.optJSONArray("excluded-apps")
        when {
            apps != null && apps.length() > 0 -> {
                profile.setSelectedAppsHandling(VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY)
                profile.setSelectedApps(jsonArrayToSet(apps).toSortedSet())
            }
            excluded != null && excluded.length() > 0 -> {
                profile.setSelectedAppsHandling(VpnProfile.SelectedAppsHandling.SELECTED_APPS_EXCLUDE)
                profile.setSelectedApps(jsonArrayToSet(excluded).toSortedSet())
            }
            else -> {
                profile.setSelectedAppsHandling(VpnProfile.SelectedAppsHandling.SELECTED_APPS_DISABLE)
            }
        }
    }

    private fun jsonArrayToSet(array: JSONArray): Set<String> =
        (0 until array.length()).map { array.getString(it) }.toSet()

    private fun mapVpnType(type: String): VpnType = when (type) {
        "ikev2-cert" -> VpnType.IKEV2_CERT
        "ikev2-cert-eap" -> VpnType.IKEV2_CERT_EAP
        "ikev2-eap-tls" -> VpnType.IKEV2_EAP_TLS
        "ikev2-byod-eap" -> VpnType.IKEV2_BYOD_EAP
        else -> VpnType.IKEV2_EAP
    }

    override suspend fun disconnect() {
        safetyTimeoutJob?.cancel()
        _connectionState.value = PluginConnectionState.DISCONNECTING

        // ВАЖНО (тот же реальный исходник VpnProfileControlActivity):
        // отключение тоже идёт через эту Activity с action=DISCONNECT, а не
        // напрямую через CharonVpnService — иначе (как было раньше) не
        // происходит вообще ничего, ровно как и с подключением без action.
        // UUID профиля обязателен: без него disconnect() внутри всегда
        // показывает диалог-подтверждение "Точно отключить?", который мы не
        // видим и не можем подтвердить — из-за этого "Отменить подключение"
        // выглядело зависшим.
        runCatching {
            val intent = Intent(context, VpnProfileControlActivity::class.java).apply {
                action = VpnProfileControlActivity.DISCONNECT
                lastProfileUuid?.let { putExtra(VpnProfileControlActivity.EXTRA_VPN_PROFILE_UUID, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        // Страховка: та же логика, что и при connect() — если
        // VpnStateService не подтвердит отключение, кнопка "Отменить/Стоп"
        // не должна оставаться нерабочей навсегда.
        safetyTimeoutJob = pluginScope.launch {
            delay(5_000)
            if (_connectionState.value == PluginConnectionState.DISCONNECTING) {
                _connectionState.value = PluginConnectionState.DISCONNECTED
            }
        }
    }

    private companion object {
        // Тот же интервал опроса, что и у AmneziaWgAdapter — для единообразия.
        const val STATS_POLL_INTERVAL_MS = 2_000L
        // Экономия батареи: пока приложение свёрнуто, никто не смотрит на
        // живые байты/пинг — опрашиваем куда реже (см. AppForegroundState).
        const val STATS_POLL_INTERVAL_BACKGROUND_MS = 15_000L
        val UNSUPPORTED = TrafficStats.UNSUPPORTED.toLong()
    }
}
