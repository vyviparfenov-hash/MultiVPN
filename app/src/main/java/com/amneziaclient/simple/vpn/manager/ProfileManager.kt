package com.amneziaclient.simple.vpn.manager

import android.content.SharedPreferences
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Один сохранённый VPN-профиль ЛЮБОГО протокола. [configBlob] — формат,
 *  специфичный для плагина ([protocol]): для AmneziaWG/WireGuard это текст
 *  .conf, для IKEv2 — JSON .sswan-профиля и т.д. VpnManager/UI никогда не
 *  разбирают configBlob сами — это всегда делает соответствующий VpnPlugin. */
data class StoredProfile(
    val id: String,
    val name: String,
    val protocol: VpnProtocolType,
    val configBlob: String,
    val subtitle: String
)

/**
 * Хранит НЕСКОЛЬКО профилей одновременно (любых протоколов, в
 * EncryptedSharedPreferences), плюс id активного профиля. Заменяет старый
 * AmneziaWG-специфичный ConfigRepository — теперь протокол хранится вместе
 * с профилем, и VpnManager определяет нужный VpnPlugin через PluginRegistry.
 */
@Singleton
class ProfileManager @Inject constructor(
    @Named("securePrefs") private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_PROFILE_IDS = "vpn_profile_ids"
        private const val KEY_ACTIVE_PROFILE_ID = "vpn_active_profile_id"
        private fun keyName(id: String) = "vpn_profile_name:$id"
        private fun keyProtocol(id: String) = "vpn_profile_protocol:$id"
        private fun keyBlob(id: String) = "vpn_profile_blob:$id"
        private fun keySubtitle(id: String) = "vpn_profile_subtitle:$id"
    }

    private val _profiles = MutableStateFlow(listProfiles())
    val profiles: StateFlow<List<StoredProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow(activeProfile())
    val activeProfileFlow: StateFlow<StoredProfile?> = _activeProfile.asStateFlow()

    fun listProfiles(): List<StoredProfile> {
        val ids = prefs.getStringSet(KEY_PROFILE_IDS, emptySet()) ?: emptySet()
        return ids.mapNotNull { id -> readProfile(id) }.sortedBy { it.name.lowercase() }
    }

    private fun readProfile(id: String): StoredProfile? {
        val name = prefs.getString(keyName(id), null) ?: return null
        val protocolName = prefs.getString(keyProtocol(id), null) ?: return null
        val protocol = runCatching { VpnProtocolType.valueOf(protocolName) }.getOrNull() ?: return null
        val blob = prefs.getString(keyBlob(id), null) ?: return null
        val subtitle = prefs.getString(keySubtitle(id), null) ?: ""
        return StoredProfile(id, name, protocol, blob, subtitle)
    }

    fun activeProfileId(): String? = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)

    fun activeProfile(): StoredProfile? = activeProfileId()?.let { readProfile(it) }

    /** Сохраняет НОВЫЙ профиль (не затирая уже загруженные). Активным его
     *  делаем ТОЛЬКО если активного профиля ещё нет вообще (самый первый
     *  профиль в приложении) — иначе просто добавление профиля "на
     *  будущее" неожиданно переключало бы (и отключало) уже работающий
     *  VPN на другом, только что добавленном и ещё не проверенном профиле. */
    fun addProfile(id: String, name: String, protocol: VpnProtocolType, configBlob: String, subtitle: String) {
        val ids = (prefs.getStringSet(KEY_PROFILE_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(id)

        val editor = prefs.edit()
            .putStringSet(KEY_PROFILE_IDS, ids)
            .putString(keyName(id), name.ifBlank { protocol.name })
            .putString(keyProtocol(id), protocol.name)
            .putString(keyBlob(id), configBlob)
            .putString(keySubtitle(id), subtitle)

        if (activeProfileId() == null) {
            editor.putString(KEY_ACTIVE_PROFILE_ID, id)
        }
        editor.apply()

        refresh()
    }

    fun setActiveProfile(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, id).apply()
        refresh()
    }

    /** Обновляет уже существующий профиль (тот же id), не трогая, какой
     *  профиль сейчас активен. */
    fun updateProfile(id: String, name: String, protocol: VpnProtocolType, configBlob: String, subtitle: String) {
        prefs.edit()
            .putString(keyName(id), name.ifBlank { protocol.name })
            .putString(keyProtocol(id), protocol.name)
            .putString(keyBlob(id), configBlob)
            .putString(keySubtitle(id), subtitle)
            .apply()

        refresh()
    }

    /** Удаляет профиль. Если он был активным — активный переключается на
     *  другой (если есть) или сбрасывается. */
    fun deleteProfile(id: String) {
        val ids = (prefs.getStringSet(KEY_PROFILE_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.remove(id)

        val editor = prefs.edit()
            .putStringSet(KEY_PROFILE_IDS, ids)
            .remove(keyName(id))
            .remove(keyProtocol(id))
            .remove(keyBlob(id))
            .remove(keySubtitle(id))

        if (activeProfileId() == id) {
            val next = ids.firstOrNull()
            if (next != null) editor.putString(KEY_ACTIVE_PROFILE_ID, next) else editor.remove(KEY_ACTIVE_PROFILE_ID)
        }
        editor.apply()

        refresh()
    }

    private fun refresh() {
        _profiles.value = listProfiles()
        _activeProfile.value = activeProfile()
    }
}
