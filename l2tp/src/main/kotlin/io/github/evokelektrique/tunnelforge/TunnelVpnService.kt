package io.github.evokelektrique.tunnelforge

import androidx.annotation.Keep

/**
 * НЕ вендорено из TunnelForge — это наша собственная минимальная реализация,
 * которая существует только для того, чтобы движок (util.c: engine_jni_init)
 * нашёл через JNI FindClass/GetStaticMethodID ровно этот класс и эти два
 * статических метода с этими сигнатурами:
 *   protectSocketFd(I)Z         — util_protect_fd() в C вызывает это перед
 *                                 IKE/L2TP/ESP-сокетами, чтобы они не
 *                                 заворачивались обратно в наш же туннель.
 *   onNativeTunnelReady(Ljava/lang/String;)V — engine_notify_tunnel_ready().
 *
 * Реальный android.net.VpnService, который может вызвать protect(fd), — это
 * [com.amneziaclient.simple.vpn.plugins.l2tp.L2tpVpnService]. Он сам
 * регистрирует себя здесь при старте и снимает регистрацию при остановке —
 * этот класс лишь тонкий мост, статический по требованию нативного кода.
 */
@Keep
object TunnelVpnService {

    @Volatile
    private var protectCallback: ((Int) -> Boolean)? = null

    @Volatile
    private var readyCallback: ((String) -> Unit)? = null

    fun register(protect: (Int) -> Boolean, onReady: (String) -> Unit) {
        protectCallback = protect
        readyCallback = onReady
    }

    fun unregister() {
        protectCallback = null
        readyCallback = null
    }

    @JvmStatic
    fun protectSocketFd(fd: Int): Boolean = protectCallback?.invoke(fd) ?: false

    @JvmStatic
    fun onNativeTunnelReady(detail: String?) {
        readyCallback?.invoke(detail?.takeIf { it.isNotBlank() } ?: "TUN interface ready; tunnel loop active")
    }
}
