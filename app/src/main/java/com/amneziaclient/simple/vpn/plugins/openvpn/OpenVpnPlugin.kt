package com.amneziaclient.simple.vpn.plugins.openvpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.ImportResult
import com.amneziaclient.simple.vpn.plugin.ImportSource
import com.amneziaclient.simple.vpn.plugin.ImportedProfileDraft
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.ValidationResult
import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenVPN через официальный движок openvpn3 (schwabe/ics-openvpn, GPL-2.0 —
 * см. LICENSES-THIRD-PARTY.md). Реальная работа (VpnService.Builder,
 * protect(), connect()) — в [OpenVpnVpnService]; этот класс парсит/
 * валидирует .ovpn-профиль и стартует/останавливает сервис.
 */
@Singleton
class OpenVpnPlugin @Inject constructor(
    @ApplicationContext private val context: Context
) : VpnPlugin {

    override val id: String = "openvpn"
    override val protocol: VpnProtocolType = VpnProtocolType.OPENVPN
    override val displayName: String = "OpenVPN"
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    override val supportsSplitTunnel: Boolean = true
    override val supportsQrImport: Boolean = false
    override val supportedFileExtensions: List<String> = listOf("ovpn")

    override val connectionState: StateFlow<PluginConnectionState> = OpenVpnEngineState.state
    override val stats: StateFlow<ConnectionStats> = OpenVpnEngineState.stats

    override suspend fun importProfile(source: ImportSource): ImportResult {
        val rawText = when (source) {
            is ImportSource.FileText -> source.rawText
            is ImportSource.ClipboardText -> source.text
            is ImportSource.ManualFields -> source.fields["ovpnContent"].orEmpty()
            is ImportSource.QrPayload -> return ImportResult.Error("QR-импорт для OpenVPN не поддерживается.")
            is ImportSource.Uri -> return ImportResult.Error("Импорт по ссылке для OpenVPN пока не поддержан.")
        }
        if (rawText.isBlank()) {
            return ImportResult.Error("Пустой .ovpn-файл.")
        }
        if (!rawText.contains("client") && !rawText.contains("remote ")) {
            return ImportResult.Error("Не похоже на .ovpn-файл: не найдены директивы client/remote.")
        }

        val server = extractRemoteHost(rawText)
        // PATCH: username/password раньше ВСЕГДА писались пустыми строками —
        // то, что пользователь вводил в форме (при ручном вводе / при
        // повторном редактировании профиля), никуда не сохранялось. Для
        // OpenVPN-серверов с авторизацией по логину/паролю (частый случай у
        // коммерческих провайдеров) это означало, что подключение всегда
        // уходило с пустыми учётными данными, независимо от того, что
        // реально ввёл пользователь.
        val manualFields = (source as? ImportSource.ManualFields)?.fields
        val json = JSONObject().apply {
            put("ovpnContent", rawText)
            put("server", server ?: "")
            put("username", manualFields?.get("username")?.trim().orEmpty())
            put("password", manualFields?.get("password").orEmpty())
        }

        return ImportResult.Success(
            ImportedProfileDraft(
                suggestedName = server ?: (source as? ImportSource.FileText)?.fileName ?: "OpenVPN",
                protocol = VpnProtocolType.OPENVPN,
                configBlob = json.toString()
            )
        )
    }

    override suspend fun exportProfile(configBlob: String): String =
        runCatching { JSONObject(configBlob).optString("ovpnContent") }.getOrNull() ?: configBlob

    override suspend fun validate(configBlob: String): ValidationResult {
        return try {
            val json = JSONObject(configBlob)
            if (json.optString("ovpnContent").isBlank()) {
                ValidationResult.Invalid("Пустой .ovpn-файл.")
            } else {
                ValidationResult.Valid
            }
        } catch (e: Exception) {
            ValidationResult.Invalid("Некорректный формат профиля OpenVPN: ${e.message}")
        }
    }

    override suspend fun connect(configBlob: String, selectedApps: Set<String>) {
        val json = JSONObject(configBlob)
        val ovpnContent = json.optString("ovpnContent")
        if (ovpnContent.isBlank()) {
            OpenVpnEngineState.state.value = PluginConnectionState.ERROR
            return
        }

        val intent = Intent(context, OpenVpnVpnService::class.java).apply {
            action = OpenVpnVpnService.ACTION_START
            putExtra(OpenVpnVpnService.EXTRA_OVPN_CONTENT, ovpnContent)
            putExtra(OpenVpnVpnService.EXTRA_USERNAME, json.optString("username"))
            putExtra(OpenVpnVpnService.EXTRA_PASSWORD, json.optString("password"))
            putExtra(OpenVpnVpnService.EXTRA_SERVER_HOST_HINT, json.optString("server").ifBlank { extractRemoteHost(ovpnContent) })
            putStringArrayListExtra(OpenVpnVpnService.EXTRA_SELECTED_APPS, ArrayList(selectedApps))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override suspend fun disconnect() {
        context.startService(
            Intent(context, OpenVpnVpnService::class.java).setAction(OpenVpnVpnService.ACTION_STOP)
        )
        // Раньше здесь просто отправляли команду и сразу возвращали
        // управление — вызывающий код (например, смена сплит-туннеля)
        // ждал фиксированную паузу и тут же звал connect(), не зная, точно
        // ли старая сессия уже остановилась. Если реальная остановка
        // (stopTunnelAndService — теперь асинхронная) не успевала за это
        // время, новая и старая сессии писали в одно и то же состояние
        // одновременно, и UI мог зависнуть на "Старт" при реально
        // работающем туннеле. Теперь честно ждём подтверждения (с
        // таймаутом на случай, если что-то пойдёт не так и уведомление
        // об остановке никогда не придёт).
        withTimeoutOrNull(5_000) {
            OpenVpnEngineState.state.first { it == PluginConnectionState.DISCONNECTED || it == PluginConnectionState.ERROR }
        }
    }

    private fun extractRemoteHost(ovpnContent: String): String? {
        val matcher = Pattern.compile("^\\s*remote\\s+(\\S+)", Pattern.MULTILINE).matcher(ovpnContent)
        return if (matcher.find()) matcher.group(1) else null
    }
}
