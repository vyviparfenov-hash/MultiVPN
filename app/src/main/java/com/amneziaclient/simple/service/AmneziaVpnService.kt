package com.amneziaclient.simple.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.app.NotificationCompat
import com.amneziaclient.simple.AmneziaApp
import com.amneziaclient.simple.R
import com.amneziaclient.simple.data.AppListRepository
import com.amneziaclient.simple.ui.MainActivity
import com.amneziaclient.simple.vpn.manager.ProfileManager
import com.amneziaclient.simple.vpn.registry.PluginRegistry
import org.amnezia.awg.backend.AbstractBackend
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Наследуемся от официального AbstractBackend.VpnService (библиотека
 * com.zaneschepke:amneziawg-android, Maven Central; подтверждено реальным
 * reflection-дампом — у GoBackend нет своего VpnService, он объявлен на
 * уровне базового AbstractBackend), чтобы protect()/establish() внутри
 * AmneziaWG backend работали как положено — мы НЕ реализуем establish() сами.
 *
 * ВАЖНО: этот сервис используется ТОЛЬКО для протоколов, чей движок обязан
 * работать внутри живого android.net.VpnService (AmneziaWG, WireGuard) — см.
 * VpnManager.needsOwnForegroundService(). IKEv2 (strongSwan) через этот
 * сервис не проходит: он поднимает и обслуживает VPN полностью сам.
 * Протокол-специфичной логики здесь нет — мы просто вызываем
 * plugin.connect()/disconnect() для активного профиля через PluginRegistry.
 *
 * Поверх добавляем: постоянное foreground-уведомление, авто-переподключение
 * при смене сети, обработку кнопки "Отключить" из уведомления.
 *
 * ВАЖНО: НЕ используем @AndroidEntryPoint здесь — валидатор Hilt не всегда
 * надёжно распознаёт цепочку наследования через сторонний базовый класс как
 * Service. Получаем зависимости через официальный механизм Hilt для таких
 * случаев — @EntryPoint + EntryPointAccessors.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VpnServiceEntryPoint {
    fun profileManager(): ProfileManager
    fun pluginRegistry(): PluginRegistry
    fun appListRepository(): AppListRepository
}

class AmneziaVpnService : AbstractBackend.VpnService() {

    private lateinit var profileManager: ProfileManager
    private lateinit var pluginRegistry: PluginRegistry
    private lateinit var appListRepository: AppListRepository

    private var connectedSinceMillis: Long? = null
    private lateinit var connectivityManager: ConnectivityManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Сеть изменилась/восстановилась — пробуем восстановить туннель.
            serviceScope.launch { runCatching { connectActiveProfile() } }
        }
    }

    companion object {
        const val ACTION_START = "com.amneziaclient.simple.action.START"
        const val ACTION_STOP = "com.amneziaclient.simple.action.STOP"
        private const val NOTIFICATION_ID = 42
    }

    override fun onCreate() {
        super.onCreate()

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            VpnServiceEntryPoint::class.java
        )
        profileManager = entryPoint.profileManager()
        pluginRegistry = entryPoint.pluginRegistry()
        appListRepository = entryPoint.appListRepository()

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ВАЖНО: всегда START_NOT_STICKY. START_STICKY заставлял систему
        // повторно доставлять onStartCommand (в т.ч. с null intent) уже после
        // явного disconnect()/stopSelf(), из-за чего VPN "мигал" и не удавалось
        // нормально остановить его кнопкой — сервис сам перезапускался.
        when (intent?.action) {
            ACTION_STOP -> stopTunnelAndService()
            else -> {
                // Идемпотентно: если уже подключены/подключаемся — просто
                // обновляем уведомление, не трогая время подключения повторно.
                if (connectedSinceMillis == null) {
                    connectedSinceMillis = System.currentTimeMillis()
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                serviceScope.launch { runCatching { connectActiveProfile() } }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun connectActiveProfile() {
        val profile = profileManager.activeProfile() ?: return
        val plugin = pluginRegistry.byProtocol(profile.protocol) ?: return
        plugin.connect(profile.configBlob, appListRepository.getSelectedPackages())
    }

    private suspend fun disconnectActiveProfile() {
        val profile = profileManager.activeProfile() ?: return
        val plugin = pluginRegistry.byProtocol(profile.protocol) ?: return
        plugin.disconnect()
    }

    private fun stopTunnelAndService() {
        serviceScope.launch { runCatching { disconnectActiveProfile() } }
        connectedSinceMillis = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, AmneziaVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val profile = profileManager.activeProfile()
        val serverLabel = profile?.subtitle?.ifBlank { null } ?: profile?.name ?: "VPN"

        return NotificationCompat.Builder(this, AmneziaApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(getString(R.string.notification_vpn_connected))
            .setContentText(getString(R.string.notification_server_name, serverLabel))
            .setUsesChronometer(true)
            .setWhen(connectedSinceMillis ?: System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.notification_disconnect), stopIntent)
            .build()
    }

    override fun onDestroy() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        serviceScope.cancel()
        super.onDestroy()
    }
}
