package com.amneziaclient.simple.vpn.plugins.vless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState

/**
 * VlessVpnService теперь работает в отдельном процессе (":vless" — см.
 * AndroidManifest.xml), чтобы (а) каждое новое подключение получало
 * гарантированно чистое состояние Go-рантайма (обходит баг библиотеки,
 * когда второй runXrayFromJson в том же процессе крашится) и (б) падение
 * стороннего нативного кода убивало только эту изолированную часть, а не
 * всё приложение целиком.
 *
 * Расплата за изоляцию: обычные общие объекты (VlessEngineState) больше не
 * работают между процессами — это два разных адресных пространства. Этот
 * класс — простой мост поверх широковещательных Intent'ов (работают между
 * процессами одного приложения без лишних разрешений), который отправляет
 * статус ИЗ процесса :vless и обновляет им VlessEngineState уже в основном
 * процессе — весь остальной код (UI, VlessPlugin.disconnect() и т.д.)
 * продолжает работать как раньше, ничего не зная о смене процесса.
 */
object VlessStateBridge {

    private const val ACTION_STATE_UPDATE = "com.amneziaclient.simple.vless.STATE_UPDATE"
    private const val EXTRA_STATE = "state"
    private const val EXTRA_LAST_DETAIL = "last_detail"
    private const val EXTRA_PING_MILLIS = "ping_millis"
    private const val EXTRA_PUBLIC_IP = "public_ip"
    private const val EXTRA_BYTES_RECEIVED = "bytes_received"
    private const val EXTRA_BYTES_SENT = "bytes_sent"

    private var receiverRegistered = false

    /** Вызывается из :vless процесса при любом изменении состояния. */
    fun broadcastState(
        context: Context,
        state: PluginConnectionState,
        lastDetail: String?,
        pingMillis: Long?,
        publicIp: String?,
        bytesReceived: Long,
        bytesSent: Long
    ) {
        val intent = Intent(ACTION_STATE_UPDATE).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_STATE, state.name)
            putExtra(EXTRA_LAST_DETAIL, lastDetail)
            if (pingMillis != null) putExtra(EXTRA_PING_MILLIS, pingMillis)
            putExtra(EXTRA_PUBLIC_IP, publicIp)
            putExtra(EXTRA_BYTES_RECEIVED, bytesReceived)
            putExtra(EXTRA_BYTES_SENT, bytesSent)
        }
        context.sendBroadcast(intent)
    }

    /** Вызывается ОДИН раз из основного процесса (VlessPlugin) — слушает
     *  обновления из :vless процесса и применяет их к VlessEngineState,
     *  который уже наблюдает весь остальной код приложения как раньше. */
    fun registerInMainProcess(context: Context) {
        if (receiverRegistered) return
        receiverRegistered = true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val stateName = intent.getStringExtra(EXTRA_STATE) ?: return
                val state = runCatching { PluginConnectionState.valueOf(stateName) }.getOrNull() ?: return

                VlessEngineState.state.value = state
                VlessEngineState.lastDetail.value = intent.getStringExtra(EXTRA_LAST_DETAIL)

                val prev = VlessEngineState.stats.value
                VlessEngineState.stats.value = prev.copy(
                    pingMillis = if (intent.hasExtra(EXTRA_PING_MILLIS)) intent.getLongExtra(EXTRA_PING_MILLIS, 0) else prev.pingMillis,
                    publicIp = intent.getStringExtra(EXTRA_PUBLIC_IP) ?: prev.publicIp,
                    bytesReceived = intent.getLongExtra(EXTRA_BYTES_RECEIVED, prev.bytesReceived),
                    bytesSent = intent.getLongExtra(EXTRA_BYTES_SENT, prev.bytesSent)
                )
            }
        }

        val filter = IntentFilter(ACTION_STATE_UPDATE)
        ContextCompat.registerReceiver(
            context.applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
