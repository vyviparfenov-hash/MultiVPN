package com.amneziaclient.simple.vpn.plugins.l2tp

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L2TP/IPsec (IKEv1) через нативный движок TunnelForge (GPL-3.0,
 * github.com/evokelektrique/tunnel-forge) — см. LICENSES-THIRD-PARTY.md.
 *
 * ВАЖНО, задокументированное осознанно: это НЕ официальная библиотека
 * протокола (в отличие от strongSwan для IKEv2 или amneziawg-android для
 * AmneziaWG/WireGuard) — это сторонняя community-реализация IKEv1/L2TP/PPP/
 * ESP с нуля на C. Решение использовать её принято явно, несмотря на
 * первоначальное ограничение "не писать свою реализацию протокола" — см.
 * историю переписки. Публичного официального API для L2TP/IPsec под
 * современный Android действительно не существует.
 *
 * Сам движок (JNI, builder/establish/protect) живёт в [L2tpVpnService] —
 * этот класс лишь парсит/валидирует профиль и стартует/останавливает сервис,
 * а состояние получает из [L2tpEngineState] (записывает его сервис).
 */
@Singleton
class L2tpPlugin @Inject constructor(
    @ApplicationContext private val context: Context
) : VpnPlugin {

    override val id: String = "l2tp"
    override val protocol: VpnProtocolType = VpnProtocolType.L2TP
    override val displayName: String = "L2TP/IPSec"
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    override val supportsSplitTunnel: Boolean = true
    override val supportsQrImport: Boolean = false
    override val supportedFileExtensions: List<String> = listOf("l2tp")

    override val connectionState: StateFlow<PluginConnectionState> = L2tpEngineState.state
    override val stats: StateFlow<ConnectionStats> = L2tpEngineState.stats

    override suspend fun importProfile(source: ImportSource): ImportResult {
        // FileText — это re-импорт файла, который сами же экспортировали
        // (exportProfile отдаёт этот же JSON как есть, "родного" текстового
        // формата у L2TP/PSK нет) — просто разбираем его так же, как и
        // обычный configBlob.
        if (source is ImportSource.FileText) {
            return runCatching {
                val json = JSONObject(source.rawText)
                val server = json.optString("server")
                if (server.isBlank()) return ImportResult.Error("Укажите адрес сервера.")
                ImportResult.Success(
                    ImportedProfileDraft(
                        suggestedName = source.fileName.substringBeforeLast(".").ifBlank { server },
                        protocol = VpnProtocolType.L2TP,
                        configBlob = source.rawText
                    )
                )
            }.getOrElse { ImportResult.Error("Не удалось разобрать файл профиля L2TP: ${it.message}") }
        }

        val fields = (source as? ImportSource.ManualFields)?.fields
            ?: return ImportResult.Error("L2TP/IPSec: поддерживается ручной ввод полей или файл, ранее экспортированный этим же приложением.")

        val server = fields["server"]?.trim().orEmpty()
        if (server.isEmpty()) {
            return ImportResult.Error("Укажите адрес сервера.")
        }

        val json = JSONObject().apply {
            put("server", server)
            put("username", fields["username"]?.trim().orEmpty())
            put("password", fields["password"].orEmpty())
            put("psk", fields["psk"].orEmpty())
            put("mtu", fields["mtu"]?.toIntOrNull() ?: 1400)
        }

        return ImportResult.Success(
            ImportedProfileDraft(
                suggestedName = server,
                protocol = VpnProtocolType.L2TP,
                configBlob = json.toString()
            )
        )
    }

    override suspend fun exportProfile(configBlob: String): String = configBlob

    override suspend fun validate(configBlob: String): ValidationResult {
        return try {
            val json = JSONObject(configBlob)
            if (json.optString("server").isBlank()) {
                ValidationResult.Invalid("Не указан адрес сервера.")
            } else {
                ValidationResult.Valid
            }
        } catch (e: Exception) {
            ValidationResult.Invalid("Некорректный формат профиля L2TP/IPSec: ${e.message}")
        }
    }

    override suspend fun connect(configBlob: String, selectedApps: Set<String>) {
        val json = JSONObject(configBlob)
        val server = json.optString("server")
        if (server.isBlank()) {
            L2tpEngineState.state.value = PluginConnectionState.ERROR
            return
        }

        val intent = Intent(context, L2tpVpnService::class.java).apply {
            action = L2tpVpnService.ACTION_START
            putExtra(L2tpVpnService.EXTRA_SERVER, server)
            putExtra(L2tpVpnService.EXTRA_USERNAME, json.optString("username"))
            putExtra(L2tpVpnService.EXTRA_PASSWORD, json.optString("password"))
            putExtra(L2tpVpnService.EXTRA_PSK, json.optString("psk"))
            putExtra(L2tpVpnService.EXTRA_MTU, json.optInt("mtu", 1400))
            putStringArrayListExtra(L2tpVpnService.EXTRA_SELECTED_APPS, ArrayList(selectedApps))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override suspend fun disconnect() {
        context.startService(
            Intent(context, L2tpVpnService::class.java).setAction(L2tpVpnService.ACTION_STOP)
        )
        // См. OpenVpnPlugin.disconnect() — та же гонка при быстром
        // отключение+переподключение (например, смена сплит-туннеля).
        withTimeoutOrNull(5_000) {
            L2tpEngineState.state.first { it == PluginConnectionState.DISCONNECTED || it == PluginConnectionState.ERROR }
        }
    }
}
