package com.amneziaclient.simple.vpn.plugins.sstp

import android.content.Context
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private var currentServerHost: String? = null

    private fun dlog(message: String) {
        Log.d(TAG, message)
        VpnDebugLog.log(TAG, message)
    }

    companion object {
        private const val TAG = "SstpPlugin"
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
                        currentServerHost = null
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

        val configBlob = JSONObject().apply {
            put("server", server)
            put("port", port)
            put("username", username)
            put("password", password)
            put("insecure", insecure)
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
            repeat(5) { attempt ->
                val pingMs = measurePingOnceMs(host)
                val publicIp = if (pingMs != null) fetchPublicIp() else null
                dlog("health-check attempt ${attempt + 1}/5: pingMs=$pingMs publicIp=$publicIp")
                if (pingMs != null || publicIp != null) {
                    SstpEngineState.stats.value = SstpEngineState.stats.value.copy(pingMillis = pingMs, publicIp = publicIp)
                    SstpEngineState.state.value = PluginConnectionState.CONNECTED
                    SstpEngineState.lastDetail.value = null
                    startPingRefreshOnForeground(host)
                    return@launch
                }
                delay(2_000)
            }
            // Пять попыток не дали ответа — сервис формально запущен
            // (ROOT_STATE=true), но реального ответа от сервера нет.
            SstpEngineState.state.value = PluginConnectionState.CONNECTED
            SstpEngineState.lastDetail.value = "Туннель поднят, но нет ответа от сервера"
        }
    }

    private fun startPingRefreshOnForeground(host: String) {
        scope.launch {
            AppForegroundState.onEnterForeground.collect {
                if (SstpEngineState.state.value != PluginConnectionState.CONNECTED) return@collect
                val pingMs = measurePingOnceMs(host)
                SstpEngineState.stats.value = SstpEngineState.stats.value.copy(pingMillis = pingMs)
            }
        }
    }

    private suspend fun measurePingOnceMs(host: String): Long? = runCatching {
        val start = System.currentTimeMillis()
        val reachable = withContext(Dispatchers.IO) { InetAddress.getByName(host).isReachable(3000) }
        if (reachable) System.currentTimeMillis() - start else null
    }.getOrNull()

    private suspend fun fetchPublicIp(): String? = runCatching {
        withContext(Dispatchers.IO) {
            URL("https://api.ipify.org").openStream().bufferedReader().use { it.readText().trim() }
        }
    }.getOrNull()
}
