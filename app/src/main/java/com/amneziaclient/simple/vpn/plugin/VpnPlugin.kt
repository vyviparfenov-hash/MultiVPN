package com.amneziaclient.simple.vpn.plugin

import kotlinx.coroutines.flow.StateFlow

/** Протоколы, которые приложение потенциально поддерживает. Новый протокол
 *  добавляется сюда и регистрируется как Plugin — код экранов/VpnManager
 *  трогать не нужно (см. PluginRegistry). */
enum class VpnProtocolType {
    AMNEZIAWG,
    WIREGUARD,
    IKEV2,
    OPENVPN,
    L2TP,
    SSTP,
    SOFTETHER,
    VLESS
}

/** Источник, из которого пользователь добавляет профиль. */
sealed class ImportSource {
    data class FileText(val fileName: String, val rawText: String) : ImportSource()
    data class QrPayload(val text: String) : ImportSource()
    data class ClipboardText(val text: String) : ImportSource()
    data class Uri(val uri: String) : ImportSource()
    data class ManualFields(val fields: Map<String, String>) : ImportSource()
}

/** Профиль, распознанный и провалидированный конкретным Plugin, но ещё не
 *  сохранённый — сохранением занимается ProfileManager. */
data class ImportedProfileDraft(
    val suggestedName: String,
    val protocol: VpnProtocolType,
    /** Конфигурация в виде, специфичном для плагина (для AmneziaWG/WireGuard —
     *  это текст .conf; для будущих плагинов — их собственный формат). */
    val configBlob: String
)

sealed class ImportResult {
    data class Success(val draft: ImportedProfileDraft) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

enum class PluginConnectionState {
    DISCONNECTED,
    VALIDATING,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class ConnectionStats(
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val connectedSinceMillis: Long? = null,
    /** Измеряется один раз сразу после установления соединения (не постоянно). */
    val pingMillis: Long? = null,
    /** Публичный IP-адрес после подключения (виден при выходе через VPN). */
    val publicIp: String? = null
)

/**
 * Единый интерфейс, который реализует каждый VPN-протокол ("Plugin").
 * VpnManager и UI работают ТОЛЬКО через этот интерфейс и никогда не знают
 * о конкретных протоколах — это и есть суть Plugin Architecture из задания.
 *
 * Реализация протокола (крипто, установка туннеля) находится в отдельном
 * Adapter, которым Plugin пользуется — Plugin сам является тонкой обвязкой
 * над Adapter для единообразного API.
 */
interface VpnPlugin {
    /** Стабильный уникальный id, используется как ключ в БД/хранилище. */
    val id: String
    val protocol: VpnProtocolType
    val displayName: String

    /** false для протоколов-заглушек (нет официальной библиотеки) — такие
     *  Plugin регистрируются, но UI показывает понятное сообщение вместо
     *  попытки подключения. */
    val isAvailable: Boolean

    /** Если недоступен — здесь причина, показывается пользователю и пишется в лог. */
    val unavailableReason: String?

    val supportsSplitTunnel: Boolean
    val supportsQrImport: Boolean
    val supportedFileExtensions: List<String>

    val connectionState: StateFlow<PluginConnectionState>
    val stats: StateFlow<ConnectionStats>

    suspend fun importProfile(source: ImportSource): ImportResult

    /** Экспортирует профиль обратно в текстовый формат (для BackupManager/ExportManager). */
    suspend fun exportProfile(configBlob: String): String

    suspend fun validate(configBlob: String): ValidationResult

    /** [selectedApps] — пусто, если сплит-туннель выключен (VPN на всё устройство). */
    suspend fun connect(configBlob: String, selectedApps: Set<String>)

    suspend fun disconnect()
}
