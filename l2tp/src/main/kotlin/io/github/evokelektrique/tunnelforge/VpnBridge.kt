// Вендорено из io.github.evokelektrique/tunnel-forge (GPL-3.0), файл
// android/app/src/main/kotlin/io/github/evokelektrique/tunnelforge/VpnBridge.kt —
// см. LICENSES-THIRD-PARTY.md в корне репозитория.
//
// ВАЖНО: пакет/имя класса и КАЖДАЯ сигнатура ниже должны совпадать с тем, что
// регистрирует vpn_jni.c через RegisterNatives (см. VPN_BRIDGE_CLASS =
// "io/github/evokelektrique/tunnelforge/VpnBridge" в исходнике движка).
// RegisterNatives регистрирует ВСЕ методы одним вызовом — если хотя бы один
// отсутствует или не совпадает по сигнатуре, вызов целиком проваливается и
// JNI_OnLoad возвращает JNI_ERR (System.loadLibrary бросит
// UnsatisfiedLinkError) — поэтому здесь оставлены и методы, которые мы сами
// не вызываем (gVisor/proxy-only режим TunnelForge нам не нужен, но их
// декларации должны остаться).
package io.github.evokelektrique.tunnelforge

import androidx.annotation.Keep

/** JNI `tunnel_engine`. TUN fd владеет вызывающая сторона (не закрывать из native). */
@Keep
object VpnBridge {
    init {
        System.loadLibrary("tunnel_engine")
    }

    @JvmStatic
    external fun nativeRunTunnel(tunFd: Int, server: String, user: String, password: String, psk: String, tunMtu: Int): Int

    /**
     * При успехе заполняет [outClientIpv4] четырьмя октетами 0–255 (локальный
     * IPv4 из PPP IPCP), если параметр не null и длина >= 4.
     */
    @JvmStatic
    external fun nativeNegotiate(
        server: String,
        user: String,
        password: String,
        psk: String,
        tunMtu: Int,
        outClientIpv4: IntArray?,
        outPrimaryDnsIpv4: IntArray?,
        outSecondaryDnsIpv4: IntArray?,
    ): Int

    @JvmStatic
    external fun nativeSetSocketProtectionEnabled(enabled: Boolean)

    @JvmStatic
    external fun nativeStartLoop(tunFd: Int): Int

    @JvmStatic
    external fun nativeStartProxyLoop(): Int

    @JvmStatic
    external fun nativeGvisorStart(clientIpv4: IntArray, mtu: Int): Int

    @JvmStatic
    external fun nativeGvisorStop()

    @JvmStatic
    external fun nativeGvisorInjectInbound(packet: ByteArray): Int

    @JvmStatic
    external fun nativeGvisorReadOutbound(maxLen: Int, timeoutMs: Int): ByteArray?

    @JvmStatic
    external fun nativeGvisorTcpOpen(remoteIpv4: IntArray, port: Int, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeGvisorTcpOpenCancelable(openId: Int, remoteIpv4: IntArray, port: Int, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeGvisorTcpCancelOpen(openId: Int): Int

    @JvmStatic
    external fun nativeGvisorTcpRead(sessionId: Int, maxLen: Int, timeoutMs: Int): ByteArray?

    @JvmStatic
    external fun nativeGvisorTcpWrite(sessionId: Int, bytes: ByteArray, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeGvisorTcpClose(sessionId: Int)

    @JvmStatic
    external fun nativeGvisorStats(): IntArray

    @JvmStatic
    external fun nativeGvisorLastOpenDiagnostics(): String

    @JvmStatic
    external fun nativeGvisorOpenDiagnostics(openId: Int): String

    @JvmStatic
    external fun nativeIsProxyPacketBridgeActive(): Boolean

    @JvmStatic
    external fun nativeQueueProxyOutboundPacket(packet: ByteArray): Int

    @JvmStatic
    external fun nativeReadProxyInboundPacket(maxLen: Int): ByteArray?

    @JvmStatic
    external fun nativeSetVpnDnsInterceptIpv4(ipv4: String?): Int

    @JvmStatic
    external fun nativeReadVpnDnsQuery(maxLen: Int): ByteArray?

    @JvmStatic
    external fun nativeQueueVpnDnsResponse(packet: ByteArray): Int

    @JvmStatic
    external fun nativeStopTunnel()
}
