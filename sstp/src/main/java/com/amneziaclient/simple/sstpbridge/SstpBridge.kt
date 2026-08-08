package com.amneziaclient.simple.sstpbridge

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kittoku.osc.preference.LIST_TYPE_ALLOWED
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kittoku.osc.preference.accessor.setSetPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.importProfile
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единственная точка входа в вендоренный kittoku/Open-SSTP-Client (MIT) —
 * весь остальной код приложения обращается только сюда, не напрямую к
 * пакету kittoku.osc.
 *
 * Конфигурация у апстрима идёт не через объект/JSON, а через
 * SharedPreferences (их собственный enum OscPrefKey) — этот класс переводит
 * наши параметры в их ключи и пользуется их же готовыми
 * importProfile()/checkPreferences() (сбрасывают остальные настройки на
 * разумные умолчания, чтобы не дублировать эту логику самим).
 *
 * ВАЖНО про статус: апстрим НЕ обновляет OscPrefKey.HOME_STATUS по ходу
 * подключения (проверено по исходнику), а ROOT_STATE становится true сразу
 * при старте сервиса — ДО того, как PPP/SSTP-согласование реально
 * завершится. Честного "подключено" от них напрямую не получить — поэтому
 * ROOT_STATE=true трактуем как CONNECTING, а реальное CONNECTED
 * подтверждаем уже на своей стороне (health-check пингом/IP), тем же
 * приёмом, что и для VLESS.
 */
object SstpBridge {

    private const val TAG = "SstpBridge"

    private fun dlog(message: String) {
        Log.d(TAG, message)
    }

    private fun dloge(message: String, t: Throwable? = null) {
        Log.e(TAG, message, t)
    }

    enum class RawState { DISCONNECTED, CONNECTING }

    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val _rawState = MutableStateFlow(RawState.DISCONNECTED)
    val rawState: StateFlow<RawState> = _rawState.asStateFlow()

    fun ensureObserving(context: Context) {
        if (listener != null) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        _rawState.value = if (getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)) RawState.CONNECTING else RawState.DISCONNECTED
        val l = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == OscPrefKey.ROOT_STATE.name) {
                val newValue = getBooleanPrefValue(OscPrefKey.ROOT_STATE, changed)
                dlog("ROOT_STATE changed: $newValue")
                _rawState.value = if (newValue) RawState.CONNECTING else RawState.DISCONNECTED
            }
        }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    /** Возвращает текст ошибки от checkPreferences(), если конфигурация
     *  некорректна — в этом случае подключаться не пытаемся вовсе. */
    fun connect(
        context: Context,
        host: String,
        port: Int,
        username: String,
        password: String,
        insecure: Boolean,
        selectedApps: Set<String>
    ): String? {
        dlog("connect() called: host=$host port=$port username=$username insecure=$insecure selectedApps=${selectedApps.size}")
        ensureObserving(context)
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

            // Сбрасываем ВСЁ на умолчания апстрима (их же собственная функция,
            // не дублируем это руками), затем переопределяем нужные нам поля.
            importProfile(null, prefs)

            setStringPrefValue(host, OscPrefKey.HOME_HOSTNAME, prefs)
            setStringPrefValue(username, OscPrefKey.HOME_USERNAME, prefs)
            setStringPrefValue(password, OscPrefKey.HOME_PASSWORD, prefs)
            setIntPrefValue(port, OscPrefKey.SSL_PORT, prefs)
            // По умолчанию апстрим проверяет сертификат сервера (SSL_DO_VERIFY=true)
            // — для самоподписанных серверов (частый случай, например RouterOS/
            // MikroTik "из коробки") это ожидаемо и честно проваливает
            // подключение с CERT_PATH: ERR_VERIFICATION_FAILED. insecure=true
            // отключает эту проверку — пользователь должен явно на это пойти.
            if (insecure) {
                setBooleanPrefValue(false, OscPrefKey.SSL_DO_VERIFY, prefs)
            }

            if (selectedApps.isNotEmpty()) {
                setBooleanPrefValue(true, OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, prefs)
                setStringPrefValue(LIST_TYPE_ALLOWED, OscPrefKey.ROUTE_APP_LIST_TYPE, prefs)
                // Своё приложение — всегда в списке (см. остальные протоколы),
                // иначе наши же диагностические запросы идут мимо VPN.
                setSetPrefValue(selectedApps + context.packageName, OscPrefKey.ROUTE_SELECTED_APPS, prefs)
            }

            val checkError = checkPreferences(prefs)
            if (checkError != null) {
                dloge("checkPreferences() rejected config: $checkError")
                return checkError
            }
            dlog("checkPreferences() passed, starting SstpVpnService...")

            val intent = Intent(context, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
            ContextCompat.startForegroundService(context, intent)
            dlog("startForegroundService() called")
            return null
        } catch (t: Throwable) {
            dloge("connect() threw an exception", t)
            return "${t::class.simpleName}: ${t.message}"
        }
    }

    fun disconnect(context: Context) {
        dlog("disconnect() called")
        val intent = Intent(context, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT)
        context.startService(intent)
    }
}
