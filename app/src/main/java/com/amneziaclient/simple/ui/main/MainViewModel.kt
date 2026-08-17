package com.amneziaclient.simple.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amneziaclient.simple.data.AppListRepository
import com.amneziaclient.simple.data.VpnConnectionState
import com.amneziaclient.simple.data.VpnUiState
import com.amneziaclient.simple.vpn.manager.ProfileManager
import com.amneziaclient.simple.vpn.manager.StoredProfile
import com.amneziaclient.simple.vpn.manager.VpnManager
import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.ImportResult
import com.amneziaclient.simple.vpn.plugin.ImportSource
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import com.amneziaclient.simple.vpn.registry.PluginRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val appListRepository: AppListRepository,
    private val vpnManager: VpnManager,
    private val pluginRegistry: PluginRegistry
) : ViewModel() {

    private val _oneTimeError = MutableStateFlow<String?>(null)
    val oneTimeError: StateFlow<String?> = _oneTimeError.asStateFlow()

    val uiState: StateFlow<VpnUiState> = run {
        val state = MutableStateFlow(
            VpnUiState(
                configLoaded = profileManager.activeProfile() != null,
                connectionState = VpnConnectionState.DISCONNECTED,
                selectedAppsCount = appListRepository.selectedCount()
            )
        )
        viewModelScope.launch {
            combine(
                profileManager.activeProfileFlow,
                vpnManager.connectionState
            ) { profile, connState ->
                VpnUiState(
                    configLoaded = profile != null,
                    connectionState = mapState(connState),
                    selectedAppsCount = appListRepository.selectedCount()
                )
            }.collect { state.value = it }
        }
        state.asStateFlow()
    }

    /** Список всех сохранённых профилей ЛЮБОГО протокола — для диалога выбора/удаления. */
    val profiles: StateFlow<List<StoredProfile>> = profileManager.profiles

    /** Реактивный поток активного профиля — нужен экрану "Профили", чтобы
     *  список корректно перерисовывался при смене активного профиля (не
     *  только при изменении самого списка). */
    val activeProfileFlow: StateFlow<StoredProfile?> = profileManager.activeProfileFlow

    /** Реальная статистика активного подключения (трафик, пинг, публичный IP). */
    val stats: StateFlow<ConnectionStats> = vpnManager.stats

    fun activeProfile(): StoredProfile? = profileManager.activeProfile()

    /** true, если для активного профиля нужно спрашивать системное разрешение
     *  VpnService.prepare() у самой Activity (AmneziaWG/WireGuard). Для
     *  протоколов вроде IKEv2 разрешение запрашивает сам плагин/его экран. */
    fun activeProfileUsesOwnVpnServicePermissionFlow(): Boolean {
        val protocol = activeProfile()?.protocol ?: return true
        return protocol == VpnProtocolType.AMNEZIAWG ||
            protocol == VpnProtocolType.WIREGUARD ||
            protocol == VpnProtocolType.L2TP ||
            protocol == VpnProtocolType.OPENVPN ||
            protocol == VpnProtocolType.VLESS ||
            protocol == VpnProtocolType.SSTP
    }

    private fun mapState(state: PluginConnectionState): VpnConnectionState = when (state) {
        PluginConnectionState.CONNECTED -> VpnConnectionState.CONNECTED
        PluginConnectionState.CONNECTING,
        PluginConnectionState.VALIDATING -> VpnConnectionState.CONNECTING
        PluginConnectionState.DISCONNECTING -> VpnConnectionState.DISCONNECTING
        PluginConnectionState.DISCONNECTED -> VpnConnectionState.DISCONNECTED
        PluginConnectionState.ERROR -> VpnConnectionState.ERROR
    }

    /**
     * Импортирует файл конфигурации как НОВЫЙ профиль (не затирая уже
     * загруженные) и делает его активным. Протокол определяется по
     * расширению файла через PluginRegistry — КРОМЕ .conf, который
     * используют И AmneziaWG, И обычный WireGuard одновременно (расширение
     * одинаковое, определить протокол по нему невозможно). Для .conf
     * заглядываем в содержимое: обфускационные поля Jc/Jmin/Jmax/S1/S2/H1-H4
     * есть только у AmneziaWG — их отсутствие означает обычный WireGuard.
     */
    fun onConfigFilePicked(name: String, fileExtension: String, rawText: String) {
        viewModelScope.launch {
            importConfigFile(name, fileExtension, rawText)
        }
    }

    /** Возвращает true при успешном импорте — используется и для одиночного
     *  файла, и для пакетного импорта из .zip (ProfilesFragment). */
    suspend fun importConfigFile(name: String, fileExtension: String, rawText: String): Boolean {
        val plugin = resolveConfPlugin(fileExtension, rawText)
            ?: pluginRegistry.byFileExtension(fileExtension)
            ?: pluginRegistry.byProtocol(VpnProtocolType.AMNEZIAWG)
            ?: run {
                _oneTimeError.value = "Не удалось определить протокол конфигурации"
                return false
            }

        return when (val result = plugin.importProfile(ImportSource.FileText(name, rawText))) {
            is ImportResult.Success -> {
                val draft = result.draft
                profileManager.addProfile(
                    id = UUID.randomUUID().toString(),
                    name = name.ifBlank { draft.suggestedName },
                    protocol = draft.protocol,
                    configBlob = draft.configBlob,
                    subtitle = draft.suggestedName
                )
                true
            }
            is ImportResult.Error -> {
                _oneTimeError.value = result.message
                false
            }
        }
    }

    private val amneziaWgObfuscationKeys = listOf(
        "Jc", "Jmin", "Jmax", "S1", "S2", "H1", "H2", "H3", "H4"
    )

    private fun resolveConfPlugin(fileExtension: String, rawText: String): VpnPlugin? {
        if (!fileExtension.equals("conf", ignoreCase = true)) return null
        val isAmneziaWg = amneziaWgObfuscationKeys.any { key ->
            Regex("(?m)^\\s*$key\\s*=").containsMatchIn(rawText)
        }
        val protocol = if (isAmneziaWg) VpnProtocolType.AMNEZIAWG else VpnProtocolType.WIREGUARD
        return pluginRegistry.byProtocol(protocol)
    }

    /** QR-код не несёт информации о протоколе (в отличие от файла, где есть
     *  расширение) — определяем по содержимому: vless:// префикс, либо
     *  WireGuard-style [Interface]/[Peer] (с той же эвристикой AmneziaWG-vs-
     *  WireGuard, что и в resolveConfPlugin). Поддержаны только протоколы,
     *  чьи конфиги реально помещаются в один QR (см. supportsQrImport). */
    fun onQrScanned(text: String) {
        viewModelScope.launch {
            val plugin = when {
                text.trim().startsWith("vless://", ignoreCase = true) ->
                    pluginRegistry.byProtocol(VpnProtocolType.VLESS)
                text.contains("[Interface]") && text.contains("[Peer]") -> {
                    val isAmneziaWg = amneziaWgObfuscationKeys.any { key ->
                        Regex("(?m)^\\s*$key\\s*=").containsMatchIn(text)
                    }
                    pluginRegistry.byProtocol(if (isAmneziaWg) VpnProtocolType.AMNEZIAWG else VpnProtocolType.WIREGUARD)
                }
                else -> null
            }

            if (plugin == null) {
                _oneTimeError.value = "Не удалось определить протокол по содержимому QR-кода."
                return@launch
            }

            when (val result = plugin.importProfile(ImportSource.QrPayload(text))) {
                is ImportResult.Success -> {
                    val draft = result.draft
                    profileManager.addProfile(
                        id = UUID.randomUUID().toString(),
                        name = draft.suggestedName,
                        protocol = draft.protocol,
                        configBlob = draft.configBlob,
                        subtitle = draft.suggestedName
                    )
                }
                is ImportResult.Error -> _oneTimeError.value = result.message
            }
        }
    }

    /** Меняет активный профиль. Если VPN в этот момент подключён —
     *  переподключает его уже на новом профиле (раньше VPN оставался
     *  висеть на СТАРОМ профиле до ручного отключения). */
    fun setActiveProfile(id: String) {
        viewModelScope.launch {
            val wasConnected = vpnManager.connectionState.value == PluginConnectionState.CONNECTED
            if (wasConnected) {
                vpnManager.disconnect()
                delay(700)
            }
            profileManager.setActiveProfile(id)
            if (wasConnected) {
                vpnManager.connect()
            }
        }
    }

    fun deleteProfile(id: String) = profileManager.deleteProfile(id)

    /** Плагины, доступные для ручного ввода профиля (недоступные заглушки
     *  вроде L2TP/OpenVPN/SSTP/SoftEther автоматически не показываются). */
    fun availableProtocolsForManualEntry() = pluginRegistry.available()

    /** Возвращает содержимое профиля в исходном "экспортируемом" виде
     *  (то, чем можно поделиться/сохранить в файл) — у каждого протокола
     *  свой формат (vless://, .conf, .ovpn и т.д.), плагин сам знает, как
     *  привести configBlob к нему. null — если плагин недоступен. */
    suspend fun exportProfile(profile: StoredProfile): String? {
        val plugin = pluginRegistry.byProtocol(profile.protocol) ?: return null
        return runCatching { plugin.exportProfile(profile.configBlob) }.getOrNull()
    }

    /** Добавляет профиль, введённый вручную (без файла) — например, для IKEv2
     *  сервер/логин/пароль, для AmneziaWG/WireGuard — ключи и endpoint. */
    fun importManualProfile(protocol: VpnProtocolType, fields: Map<String, String>) {
        viewModelScope.launch {
            val plugin = pluginRegistry.byProtocol(protocol) ?: run {
                _oneTimeError.value = "Протокол недоступен"
                return@launch
            }
            when (val result = plugin.importProfile(ImportSource.ManualFields(fields))) {
                is ImportResult.Success -> {
                    val draft = result.draft
                    val name = fields["name"]?.ifBlank { null } ?: draft.suggestedName
                    profileManager.addProfile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        protocol = draft.protocol,
                        configBlob = draft.configBlob,
                        subtitle = draft.suggestedName
                    )
                }
                is ImportResult.Error -> _oneTimeError.value = result.message
            }
        }
    }

    fun consumeError() {
        _oneTimeError.value = null
    }

    fun currentSelectedAppsCount(): Int = appListRepository.selectedCount()

    /**
     * Разбирает уже сохранённый configBlob обратно в поля формы — чтобы
     * экран редактирования открывался с уже заполненными текущими
     * значениями, а не пустым. Для AmneziaWG/WireGuard (.conf) — простой
     * построчный разбор "Ключ = значение"; для IKEv2 (JSON) — читаем поля
     * напрямую из объекта.
     */
    fun fieldsForEditing(profile: StoredProfile): Map<String, String> {
        val fields = mutableMapOf("name" to profile.name)
        return when (profile.protocol) {
            VpnProtocolType.AMNEZIAWG, VpnProtocolType.WIREGUARD -> {
                val keyValueRegex = Regex("""^\s*([A-Za-z]+)\s*=\s*(.*)$""")
                profile.configBlob.lines().forEach { line ->
                    keyValueRegex.find(line)?.let { match ->
                        val (key, value) = match.destructured
                        fields[key] = value.trim()
                    }
                }
                fields
            }
            VpnProtocolType.IKEV2 -> {
                runCatching {
                    val json = org.json.JSONObject(profile.configBlob)
                    json.optString("name", null)?.let { fields["name"] = it }
                    json.optJSONObject("remote")?.let { remote ->
                        remote.optString("addr", null)?.let { fields["server"] = it }
                        remote.optString("cert", null)?.let { fields["cert"] = it }
                    }
                    json.optJSONObject("local")?.optString("eap_id", null)?.let { fields["username"] = it }
                    json.optString("_password", null)?.let { fields["password"] = it }
                }
                fields
            }
            VpnProtocolType.L2TP -> {
                runCatching {
                    val json = org.json.JSONObject(profile.configBlob)
                    json.optString("server", null)?.let { fields["server"] = it }
                    json.optString("username", null)?.let { fields["username"] = it }
                    json.optString("password", null)?.let { fields["password"] = it }
                    json.optString("psk", null)?.let { fields["psk"] = it }
                }
                fields
            }
            VpnProtocolType.OPENVPN -> {
                runCatching {
                    val json = org.json.JSONObject(profile.configBlob)
                    json.optString("ovpnContent", null)?.let { fields["ovpnContent"] = it }
                    json.optString("username", null)?.let { fields["username"] = it }
                    json.optString("password", null)?.let { fields["password"] = it }
                }
                fields
            }
            VpnProtocolType.VLESS -> {
                runCatching {
                    val json = org.json.JSONObject(profile.configBlob)
                    val query = buildList {
                        add("encryption=${json.optString("encryption", "none")}")
                        json.optString("flow").takeIf { it.isNotBlank() }?.let { add("flow=$it") }
                        add("security=${json.optString("security", "none")}")
                        json.optString("sni").takeIf { it.isNotBlank() }?.let { add("sni=$it") }
                        json.optString("fingerprint").takeIf { it.isNotBlank() }?.let { add("fp=$it") }
                        add("type=${json.optString("network", "tcp")}")
                        json.optString("wsPath").takeIf { it.isNotBlank() }?.let { add("path=$it") }
                        json.optString("wsHost").takeIf { it.isNotBlank() }?.let { add("host=$it") }
                        json.optString("grpcServiceName").takeIf { it.isNotBlank() }?.let { add("serviceName=$it") }
                        json.optString("publicKey").takeIf { it.isNotBlank() }?.let { add("pbk=$it") }
                        json.optString("shortId").takeIf { it.isNotBlank() }?.let { add("sid=$it") }
                    }.joinToString("&")
                    fields["vlessLink"] = "vless://${json.optString("uuid")}@${json.optString("server")}:${json.optInt("port", 443)}?$query"
                }
                fields
            }
            VpnProtocolType.SSTP -> {
                runCatching {
                    val json = org.json.JSONObject(profile.configBlob)
                    fields["server"] = json.optString("server")
                    fields["port"] = json.optInt("port", 443).toString()
                    fields["username"] = json.optString("username")
                    fields["password"] = json.optString("password")
                    fields["insecure"] = if (json.optBoolean("insecure", false)) "yes" else "no"
                    fields["cert"] = json.optString("cert")
                    fields["dns"] = json.optString("dns")
                }
                fields
            }
            else -> fields
        }
    }

    /** Обновляет уже существующий профиль (тот же id, тот же протокол) новыми
     *  значениями полей — используется экраном редактирования. */
    fun updateProfileFromEdit(id: String, protocol: VpnProtocolType, fields: Map<String, String>) {
        viewModelScope.launch {
            val plugin = pluginRegistry.byProtocol(protocol) ?: run {
                _oneTimeError.value = "Протокол недоступен"
                return@launch
            }
            when (val result = plugin.importProfile(ImportSource.ManualFields(fields))) {
                is ImportResult.Success -> {
                    val draft = result.draft
                    val name = fields["name"]?.ifBlank { null } ?: draft.suggestedName
                    profileManager.updateProfile(
                        id = id,
                        name = name,
                        protocol = draft.protocol,
                        configBlob = draft.configBlob,
                        subtitle = draft.suggestedName
                    )
                }
                is ImportResult.Error -> _oneTimeError.value = result.message
            }
        }
    }

    /** Вызывается кнопкой СТАРТ/СТОП. [alreadyConnected] — текущее состояние
     *  на момент нажатия (решает, подключаться или отключаться). */
    fun toggleConnection(alreadyConnected: Boolean) {
        viewModelScope.launch {
            if (alreadyConnected) vpnManager.disconnect() else vpnManager.connect()
        }
    }
}
