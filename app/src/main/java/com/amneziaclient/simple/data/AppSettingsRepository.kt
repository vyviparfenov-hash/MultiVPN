package com.amneziaclient.simple.data

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Простые общие переключатели приложения (не привязанные к конкретному
 *  VPN-профилю или протоколу). */
@Singleton
class AppSettingsRepository @Inject constructor(
    @Named("securePrefs") private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_AUTO_CONNECT_ON_OPEN = "auto_connect_on_open"
        private const val KEY_CONNECTION_LOGGING_ENABLED = "connection_logging_enabled"
        private const val KEY_CONNECTION_LOGGING_ENABLED_AT = "connection_logging_enabled_at"
    }

    /** Подключать VPN автоматически при каждом открытии приложения. По
     *  умолчанию выключено — пользователь сам решает, хочет ли он такое
     *  поведение. */
    fun isAutoConnectOnOpenEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CONNECT_ON_OPEN, false)

    fun setAutoConnectOnOpenEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT_ON_OPEN, enabled).apply()
    }

    /** Подробное логирование VPN-подключений в файл (для экспорта и
     *  диагностики) — выключено по умолчанию, автоматически выключается
     *  через 24 часа после включения (см. VpnDebugLog). */
    fun isConnectionLoggingEnabled(): Boolean = prefs.getBoolean(KEY_CONNECTION_LOGGING_ENABLED, false)

    fun connectionLoggingEnabledAt(): Long = prefs.getLong(KEY_CONNECTION_LOGGING_ENABLED_AT, 0L)

    fun setConnectionLoggingEnabled(enabled: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_CONNECTION_LOGGING_ENABLED, enabled)
        if (enabled) editor.putLong(KEY_CONNECTION_LOGGING_ENABLED_AT, System.currentTimeMillis())
        editor.apply()
    }
}
