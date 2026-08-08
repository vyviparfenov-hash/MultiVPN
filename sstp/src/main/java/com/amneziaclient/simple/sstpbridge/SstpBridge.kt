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
        certPem: String?,
        selectedApps: Set<String>
    ): String? {
        dlog("connect() called: host=$host port=$port username=$username insecure=$insecure certProvided=${certPem != null} selectedApps=${selectedApps.size}")
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
            // ВАЖНО: SSL_DO_VERIFY проверяет только совпадение ИМЕНИ ХОСТА в
            // уже прошедшем базовую проверку сертификате — она НЕ отвечает
            // за то, доверяем ли мы вообще самоподписанному сертификату (это
            // подтверждено по исходнику SSLTerminal.kt: проверка доверия к
            // сертификату происходит раньше и безусловно). Отключаем эту
            // проверку только как мелкое доп. послабление, не как основной
            // способ работы с самоподписанными серверами — для них
            // используется certPem ниже.
            if (insecure) {
                setBooleanPrefValue(false, OscPrefKey.SSL_DO_VERIFY, prefs)
            }

            // Настоящий способ подключиться к серверу с самоподписанным
            // сертификатом: явно указать движку доверять КОНКРЕТНОМУ файлу
            // сертификата (SSL_CERT_DIR — путь к папке, движок использует
            // ТОЛЬКО сертификаты из неё вместо системного хранилища). Файл
            // должен быть в формате .pem/.crt/.der (обычный X.509-сертификат,
            // не .p12/.pfx-бандл) — иначе движок ответит
            // "CERT: ERR_PARSING_FAILED".
            if (!certPem.isNullOrBlank()) {
                val certDir = java.io.File(context.filesDir, "sstp_cert").apply { mkdirs() }
                val certFile = java.io.File(certDir, "server.pem")
                runCatching { certFile.writeText(certPem) }
                    .onFailure { dloge("Failed to write server cert file", it) }
                setBooleanPrefValue(true, OscPrefKey.SSL_DO_SPECIFY_CERT, prefs)
                setStringPrefValue(certDir.absolutePath, OscPrefKey.SSL_CERT_DIR, prefs)
                // PATCH: библиотека (см. kittoku check.kt) отказывает в конфиге, если
                // SSL_DO_SPECIFY_CERT=true, а SSL_VERSION остался на "DEFAULT" (именно
                // так после importProfile(null, prefs) выше) — "Specifying trusted
                // certificates needs SSL version to be specified". Причина не
                // косметическая: SSLTerminal.kt при заданном сертификате вызывает
                // SSLContext.getInstance(selectedVersion) напрямую, т.е. "DEFAULT" туда
                // передать нельзя в принципе. TLSv1.2 — совместим и с Android (все
                // актуальные API-уровни), и с типичными SSTP-серверами (Windows RRAS,
                // MikroTik и т.п.); TLSv1.3 поддерживается ими не всегда.
                setStringPrefValue("TLSv1.2", OscPrefKey.SSL_VERSION, prefs)
                dlog("Custom server cert written to ${certFile.absolutePath}, SSL_CERT_DIR set")
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
