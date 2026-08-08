package com.amneziaclient.simple.vpn.plugins.amneziawg

import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.ImportResult
import com.amneziaclient.simple.vpn.plugin.ImportSource
import com.amneziaclient.simple.vpn.plugin.ImportedProfileDraft
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.ValidationResult
import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AmneziaWgPlugin @Inject constructor(
    private val adapter: AmneziaWgAdapter
) : VpnPlugin {

    override val id: String = "amneziawg"
    override val protocol: VpnProtocolType = VpnProtocolType.AMNEZIAWG
    override val displayName: String = "AmneziaWG"
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    override val supportsSplitTunnel: Boolean = true
    override val supportsQrImport: Boolean = true
    override val supportedFileExtensions: List<String> = listOf("conf")

    override val connectionState: StateFlow<PluginConnectionState> = adapter.state
    override val stats: StateFlow<ConnectionStats> = adapter.stats

    override suspend fun importProfile(source: ImportSource): ImportResult {
        val rawText = when (source) {
            is ImportSource.FileText -> source.rawText
            is ImportSource.QrPayload -> source.text
            is ImportSource.ClipboardText -> source.text
            is ImportSource.ManualFields -> buildConfFromManualFields(source.fields)
            is ImportSource.Uri -> return ImportResult.Error(
                "Импорт AmneziaWG-профиля по прямой ссылке (URI) пока не поддержан — используй файл .conf, QR-код или буфер обмена."
            )
        }

        return when (val validation = adapter.validate(rawText)) {
            is ValidationResult.Invalid -> ImportResult.Error("Конфигурация повреждена: ${validation.reason}")
            ValidationResult.Valid -> {
                val name = when (source) {
                    is ImportSource.FileText -> source.fileName.substringBeforeLast(".")
                    else -> adapter.serverHost(rawText)
                }
                ImportResult.Success(
                    ImportedProfileDraft(
                        suggestedName = name.ifBlank { "AmneziaWG" },
                        protocol = VpnProtocolType.AMNEZIAWG,
                        configBlob = rawText.trim()
                    )
                )
            }
        }
    }

    private fun buildConfFromManualFields(fields: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        fields["PrivateKey"]?.let { sb.appendLine("PrivateKey = $it") }
        fields["Address"]?.let { sb.appendLine("Address = $it") }
        fields["DNS"]?.let { sb.appendLine("DNS = $it") }
        // Поля обфускации AmneziaWG — для обычного WireGuard-профиля их
        // просто нет в fields (пустые/отсутствуют), тогда строки не
        // добавляются, как и раньше. Если их потерять при пересборке —
        // движок считает AmneziaWG-конфигурацию повреждённой.
        listOf("Jc", "Jmin", "Jmax", "S1", "S2", "H1", "H2", "H3", "H4").forEach { key ->
            fields[key]?.takeIf { it.isNotBlank() }?.let { sb.appendLine("$key = $it") }
        }
        sb.appendLine()
        sb.appendLine("[Peer]")
        fields["PublicKey"]?.let { sb.appendLine("PublicKey = $it") }
        fields["PresharedKey"]?.let { sb.appendLine("PresharedKey = $it") }
        fields["AllowedIPs"]?.let { sb.appendLine("AllowedIPs = $it") }
        fields["Endpoint"]?.let { sb.appendLine("Endpoint = $it") }
        fields["PersistentKeepalive"]?.let { sb.appendLine("PersistentKeepalive = $it") }
        return sb.toString()
    }

    override suspend fun exportProfile(configBlob: String): String = configBlob

    override suspend fun validate(configBlob: String): ValidationResult = adapter.validate(configBlob)

    override suspend fun connect(configBlob: String, selectedApps: Set<String>) {
        adapter.connect(configBlob, selectedApps)
    }

    override suspend fun disconnect() {
        adapter.disconnect()
    }
}
