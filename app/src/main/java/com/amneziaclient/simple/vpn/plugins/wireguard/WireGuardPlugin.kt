package com.amneziaclient.simple.vpn.plugins.wireguard

import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.ImportResult
import com.amneziaclient.simple.vpn.plugin.ImportSource
import com.amneziaclient.simple.vpn.plugin.ImportedProfileDraft
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.ValidationResult
import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import com.amneziaclient.simple.vpn.plugins.amneziawg.AmneziaWgAdapter
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Обычный WireGuard — использует ТОТ ЖЕ движок, что и AmneziaWG
 * (AmneziaWgAdapter), потому что формат WireGuard .conf является
 * подмножеством формата AmneziaWG (просто без полей обфускации
 * Jc/Jmin/Jmax/S1/S2/H1-H4 — они в библиотеке опциональны). Отдельная
 * официальная библиотека WireGuard (com.wireguard.android) не нужна —
 * не плодим два движка ради одного и того же протокола.
 *
 * ВАЖНО: т.к. движок общий, состояние подключения (connectionState/stats)
 * общее для AmneziaWgPlugin и WireGuardPlugin — это ожидаемо и корректно,
 * поскольку одновременно активен только один туннель, как в любом VPN-клиенте.
 */
@Singleton
class WireGuardPlugin @Inject constructor(
    private val adapter: AmneziaWgAdapter
) : VpnPlugin {

    override val id: String = "wireguard"
    override val protocol: VpnProtocolType = VpnProtocolType.WIREGUARD
    override val displayName: String = "WireGuard"
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
            is ImportSource.ManualFields -> return ImportResult.Error(
                "Ручной ввод для WireGuard пока не поддержан — используй файл .conf или QR-код."
            )
            is ImportSource.Uri -> return ImportResult.Error(
                "Импорт WireGuard-профиля по прямой ссылке (URI) пока не поддержан."
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
                        suggestedName = name.ifBlank { "WireGuard" },
                        protocol = VpnProtocolType.WIREGUARD,
                        configBlob = rawText.trim()
                    )
                )
            }
        }
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
