package io.github.evokelektrique.tunnelforge

import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * НЕ вендорено из TunnelForge — движок (util.c: engine_jni_init) ищет через
 * JNI ровно класс "io/github/evokelektrique/tunnelforge/VpnTunnelEvents" и
 * статический метод emitEngineLogFromNative(ILjava/lang/String;Ljava/lang/String;)V
 * для форварда своих внутренних логов (tunnel_engine_log в C). Мы пишем их в
 * logcat под тем же тегом, что и остальной движок, а заодно по ключевым
 * словам вытаскиваем человекочитаемую "фазу" подключения — движок не даёт
 * отдельного колбэка на каждый этап (только финальный onNativeTunnelReady),
 * так что это единственный способ показать в UI живой прогресс вместо
 * статичного "Идёт соединение..." на всё время хэндшейка.
 */
@Keep
object VpnTunnelEvents {

    private val _phase = MutableStateFlow<String?>(null)
    val phase: StateFlow<String?> = _phase

    fun resetPhase() {
        _phase.value = null
    }

    @JvmStatic
    fun emitEngineLogFromNative(priority: Int, tag: String, msg: String) {
        Log.println(priority.coerceIn(Log.VERBOSE, Log.ASSERT), tag, msg)
        _phase.value = phaseFor(msg) ?: return
    }

    private val failureIndicators = listOf(
        "fail", "not received", "wait failed", "timeout", "error", "canceled", "cancelled", "exceeded"
    )

    private fun phaseFor(msg: String): String? {
        if (failureIndicators.any { msg.contains(it, ignoreCase = true) }) return null
        return when {
            msg.contains("IKE+QM ok") -> "Шифрование установлено, поднимаем L2TP…"
            msg.contains("ike_send_recv") || msg.contains("HASH_R") || msg.contains("Main Mode") ->
                "Устанавливаем защищённое соединение (IKE)…"
            // send_ctrl логируется и для SCCRQ (tid=0, ещё не согласован), и для
            // SCCCN/ICRQ/ICCN (ненулевой tid — значит SCCRP УЖЕ получен успешно).
            // Раньше здесь ошибочно ловили саму подстроку "SCCRP", которая
            // встречается и в сообщениях об ОШИБКЕ ("SCCRP wait failed",
            // "SCCRP not received") — из-за этого прогресс врал об успехе.
            msg.contains("l2tp send_ctrl:") && msg.contains("tid=0 ") -> "Согласовываем L2TP-туннель…"
            msg.contains("l2tp send_ctrl:") -> "L2TP-туннель установлен, поднимаем PPP…"
            msg.contains("ppp", ignoreCase = true) || msg.contains("LCP") || msg.contains("IPCP") ->
                "Поднимаем PPP-сессию…"
            else -> null
        }
    }
}
