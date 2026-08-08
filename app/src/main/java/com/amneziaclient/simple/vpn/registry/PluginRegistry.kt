package com.amneziaclient.simple.vpn.registry

import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реестр всех VPN-плагинов. UI и VpnManager получают список плагинов ТОЛЬКО
 * отсюда и никогда не импортируют конкретные классы плагинов напрямую.
 *
 * Добавление нового протокола = написать новый VpnPlugin + добавить его в
 * набор, который Hilt передаёт сюда через @IntoSet (см. di/PluginModule.kt).
 * Код VpnManager, экранов и т.д. менять не нужно.
 */
@Singleton
class PluginRegistry @Inject constructor(
    plugins: Set<@JvmSuppressWildcards VpnPlugin>
) {
    private val byId: Map<String, VpnPlugin> = plugins.associateBy { it.id }
    private val byProtocol: Map<VpnProtocolType, VpnPlugin> = plugins.associateBy { it.protocol }

    fun all(): List<VpnPlugin> = byId.values.sortedBy { it.displayName }

    fun available(): List<VpnPlugin> = all().filter { it.isAvailable }

    fun byId(id: String): VpnPlugin? = byId[id]

    fun byProtocol(protocol: VpnProtocolType): VpnPlugin? = byProtocol[protocol]

    /** Определяет плагин по расширению импортируемого файла (используется ImportManager). */
    fun byFileExtension(extension: String): VpnPlugin? =
        all().firstOrNull { plugin -> plugin.supportedFileExtensions.any { it.equals(extension, ignoreCase = true) } }
}
