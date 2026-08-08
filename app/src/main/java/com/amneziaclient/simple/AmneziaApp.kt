package com.amneziaclient.simple

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.amneziaclient.simple.vpn.registry.PluginRegistry
import dagger.hilt.android.HiltAndroidApp
import org.strongswan.android.logic.StrongSwanApplication
import javax.inject.Inject

/**
 * Наследуемся от StrongSwanApplication (а не от голого Application), чтобы
 * не потерять инициализацию, которую делает библиотека strongSwan в своём
 * onCreate() (TrustedCertificateManager и т.п.) — манифест всё равно
 * указывает НАШ класс как android:name (см. tools:replace в
 * AndroidManifest.xml, нужен из-за конфликта с манифестом :strongswan).
 */
@HiltAndroidApp
class AmneziaApp : StrongSwanApplication() {

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "amnezia_vpn_channel"
        private const val TAG = "AmneziaApp"
    }

    // Инъекция здесь заставляет Hilt на этапе сборки провалидировать ВЕСЬ
    // граф Set<VpnPlugin> (PluginModule) — если какой-то плагин не
    // компилируется/не резолвится, сборка упадёт сразу, а не когда-то потом
    // при первом реальном использовании реестра из экрана.
    @Inject lateinit var pluginRegistry: PluginRegistry
    @Inject lateinit var appSettingsRepository: com.amneziaclient.simple.data.AppSettingsRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        logRegisteredPlugins()
        com.amneziaclient.simple.vpn.AppForegroundState.register()
        com.amneziaclient.simple.vpn.VpnDebugLog.init(
            context = this,
            initiallyEnabled = appSettingsRepository.isConnectionLoggingEnabled(),
            enabledAt = appSettingsRepository.connectionLoggingEnabledAt(),
            onAutoDisabled = { appSettingsRepository.setConnectionLoggingEnabled(false) }
        )
    }

    private fun logRegisteredPlugins() {
        val all = pluginRegistry.all()
        val available = pluginRegistry.available()
        Log.i(
            TAG,
            "VPN-плагины зарегистрированы: ${all.size} всего, ${available.size} доступно на этом устройстве " +
                "(${available.joinToString { it.displayName }})"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
