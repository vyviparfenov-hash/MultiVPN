package com.amneziaclient.simple.vpn.plugins.softether

import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.ImportResult
import com.amneziaclient.simple.vpn.plugin.ImportSource
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.ValidationResult
import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ЗАГЛУШКА — задокументированная причина.
 *
 * SoftEther VPN — открытый проект (SoftEther Project, University of
 * Tsukuba), но его "Cedar" движок распространяется как исходники на C,
 * компилируемые под конкретную ОС, а не как готовая Android-библиотека
 * или AAR в Maven Central. Официального Android SDK от авторов проекта
 * не существует. Чтобы добавить реальную поддержку, потребуется отдельная
 * сборка Cedar под Android NDK — задача сравнимого объёма с добавлением
 * OpenVPN через ics-openvpn.
 */
@Singleton
class SoftEtherPlugin @Inject constructor() : VpnPlugin {

    override val id: String = "softether"
    override val protocol: VpnProtocolType = VpnProtocolType.SOFTETHER
    override val displayName: String = "SoftEther"
    override val isAvailable: Boolean = false
    override val unavailableReason: String =
        "SoftEther недоступен: официальный SDK — это исходники на C (движок Cedar), " +
            "не публикуемые как Android-библиотека. Нужна отдельная сборка под NDK."
    override val supportsSplitTunnel: Boolean = false
    override val supportsQrImport: Boolean = false
    override val supportedFileExtensions: List<String> = emptyList()

    private val _state = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<PluginConnectionState> = _state.asStateFlow()
    override val stats: StateFlow<ConnectionStats> = MutableStateFlow(ConnectionStats()).asStateFlow()

    override suspend fun importProfile(source: ImportSource): ImportResult =
        ImportResult.Error(unavailableReason)

    override suspend fun exportProfile(configBlob: String): String = configBlob

    override suspend fun validate(configBlob: String): ValidationResult =
        ValidationResult.Invalid(unavailableReason)

    override suspend fun connect(configBlob: String, selectedApps: Set<String>) {
        throw UnsupportedOperationException(unavailableReason)
    }

    override suspend fun disconnect() = Unit
}
