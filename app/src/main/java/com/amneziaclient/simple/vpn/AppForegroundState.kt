package com.amneziaclient.simple.vpn

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Экономия батареи: пока приложение свёрнуто/экран выключен, никто не
 * смотрит на живые цифры пинга/трафика — незачем опрашивать их с той же
 * частотой (2 сек), что и когда экран открыт. Регистрируется один раз из
 * [com.amneziaclient.simple.AmneziaApp.onCreate] через
 * ProcessLifecycleOwner (не путать с lifecycle конкретной Activity/Fragment
 * — это про приложение в целом, включая случаи, когда один экран сменяет
 * другой, но приложение остаётся на переднем плане).
 *
 * [onEnterForeground] — событие "приложение только что вернулось на
 * передний план" (открыли/разблокировали экран). Используется для пинга:
 * он больше не опрашивается непрерывно даже в фоне с редким интервалом —
 * вместо этого меряется один раз при подключении и один раз при каждом
 * таком событии, а всё остальное время просто показывает последнее
 * зафиксированное значение (см. IKEv2Plugin/L2tpVpnService).
 */
object AppForegroundState {
    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground

    private val _onEnterForeground = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val onEnterForeground: SharedFlow<Unit> = _onEnterForeground

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _isForeground.value = true
                _onEnterForeground.tryEmit(Unit)
            }

            override fun onStop(owner: LifecycleOwner) {
                _isForeground.value = false
            }
        })
    }
}
