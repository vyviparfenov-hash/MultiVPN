package com.amneziaclient.simple.vpn.plugins.vless

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.ImportResult
import com.amneziaclient.simple.vpn.plugin.ImportSource
import com.amneziaclient.simple.vpn.plugin.ImportedProfileDraft
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import com.amneziaclient.simple.vpn.plugin.ValidationResult
import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VLESS через официальный движок Xray-core (XTLS/libXray, MIT — см.
 * LICENSES-THIRD-PARTY.md). Реальная работа — в [VlessVpnService]; этот
 * класс парсит стандартные `vless://`-ссылки и собирает JSON-конфиг Xray.
 *
 * В отличие от предыдущей версии (на AndroidLibXrayLite), сплит-туннель
 * теперь ПОДДЕРЖАН по-настоящему — у XTLS/libXray есть встроенный
 * socket-protect механизм (см. VlessVpnService), поэтому не нужно
 * исключать своё приложение из туннеля целиком.
 */
@Singleton
class VlessPlugin @Inject constructor(
    @ApplicationContext private val context: Context
) : VpnPlugin {

    init {
        // VlessVpnService теперь работает в отдельном процессе (":vless") —
        // см. VlessVpnService/VlessStateBridge. Регистрируем приёмник ОДИН
        // раз здесь (VlessPlugin — синглтон, создаётся рано в основном
        // процессе), чтобы VlessEngineState (который наблюдает весь
        // остальной код приложения) продолжал обновляться как раньше.
        VlessStateBridge.registerInMainProcess(context)
    }

    override val id: String = "vless"
    override val protocol: VpnProtocolType = VpnProtocolType.VLESS
    override val displayName: String = "VLESS"
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    override val supportsSplitTunnel: Boolean = true
    override val supportsQrImport: Boolean = true
    override val supportedFileExtensions: List<String> = emptyList()

    override val connectionState: StateFlow<PluginConnectionState> = VlessEngineState.state
    override val stats: StateFlow<ConnectionStats> = VlessEngineState.stats

    override suspend fun importProfile(source: ImportSource): ImportResult {
        val rawLink = when (source) {
            is ImportSource.ClipboardText -> source.text
            is ImportSource.QrPayload -> source.text
            is ImportSource.ManualFields -> source.fields["vlessLink"].orEmpty()
            is ImportSource.FileText -> source.rawText
            is ImportSource.Uri -> source.uri
        }.trim()

        if (!rawLink.startsWith("vless://", ignoreCase = true)) {
            return ImportResult.Error("Ожидается ссылка vless://... (обычно выдаётся панелью сервера, например 3X-UI/Marzban).")
        }

        val parsed = runCatching { parseVlessLink(rawLink) }.getOrElse {
            return ImportResult.Error("Не удалось разобрать vless-ссылку: ${it.message}")
        }

        return ImportResult.Success(
            ImportedProfileDraft(
                suggestedName = parsed.remark.ifBlank { parsed.server },
                protocol = VpnProtocolType.VLESS,
                configBlob = parsed.toJson().toString()
            )
        )
    }

    override suspend fun exportProfile(configBlob: String): String {
        val json = runCatching { JSONObject(configBlob) }.getOrNull() ?: return configBlob
        val query = buildList {
            add("encryption=${json.optString("encryption", "none")}")
            json.optString("flow").takeIf { it.isNotBlank() }?.let { add("flow=$it") }
            add("security=${json.optString("security", "none")}")
            json.optString("sni").takeIf { it.isNotBlank() }?.let { add("sni=$it") }
            json.optString("fingerprint").takeIf { it.isNotBlank() }?.let { add("fp=$it") }
            add("type=${json.optString("network", "tcp")}")
            json.optString("wsPath").takeIf { it.isNotBlank() }?.let { add("path=$it") }
            json.optString("wsHost").takeIf { it.isNotBlank() }?.let { add("host=$it") }
            json.optString("grpcServiceName").takeIf { it.isNotBlank() }?.let { add("serviceName=$it") }
            json.optString("publicKey").takeIf { it.isNotBlank() }?.let { add("pbk=$it") }
            json.optString("shortId").takeIf { it.isNotBlank() }?.let { add("sid=$it") }
        }.joinToString("&")
        return "vless://${json.optString("uuid")}@${json.optString("server")}:${json.optInt("port", 443)}?$query"
    }

    override suspend fun validate(configBlob: String): ValidationResult = try {
        val json = JSONObject(configBlob)
        if (json.optString("server").isBlank() || json.optString("uuid").isBlank()) {
            ValidationResult.Invalid("Не хватает адреса сервера или UUID.")
        } else {
            ValidationResult.Valid
        }
    } catch (e: Exception) {
        ValidationResult.Invalid("Некорректный формат профиля VLESS: ${e.message}")
    }

    override suspend fun connect(configBlob: String, selectedApps: Set<String>) {
        val json = JSONObject(configBlob)
        val server = json.optString("server")
        if (server.isBlank()) {
            VlessEngineState.state.value = PluginConnectionState.ERROR
            return
        }

        val xrayConfig = buildXrayConfig(json)
        val intent = Intent(context, VlessVpnService::class.java).apply {
            action = VlessVpnService.ACTION_START
            putExtra(VlessVpnService.EXTRA_CONFIG_JSON, xrayConfig.toString())
            putExtra(VlessVpnService.EXTRA_SERVER_HOST_HINT, server)
            putStringArrayListExtra(VlessVpnService.EXTRA_SELECTED_APPS, ArrayList(selectedApps))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override suspend fun disconnect() {
        context.startService(
            Intent(context, VlessVpnService::class.java).setAction(VlessVpnService.ACTION_STOP)
        )
        // Ждём подтверждения реальной остановки перед тем, как отдать
        // управление обратно (см. OpenVpnPlugin/L2tpPlugin.disconnect()) —
        // иначе быстрое переключение профиля может столкнуть старую и
        // новую сессии.
        withTimeoutOrNull(5_000) {
            VlessEngineState.state.first { it == PluginConnectionState.DISCONNECTED || it == PluginConnectionState.ERROR }
        }
    }

    // ---- vless:// parsing ----

    private data class ParsedVless(
        val server: String,
        val port: Int,
        val uuid: String,
        val encryption: String,
        val flow: String,
        val security: String,
        val sni: String,
        val fingerprint: String,
        val network: String,
        val wsPath: String,
        val wsHost: String,
        val grpcServiceName: String,
        val publicKey: String,
        val shortId: String,
        val spiderX: String,
        val remark: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("server", server)
            put("port", port)
            put("uuid", uuid)
            put("encryption", encryption)
            put("flow", flow)
            put("security", security)
            put("sni", sni)
            put("fingerprint", fingerprint)
            put("network", network)
            put("wsPath", wsPath)
            put("wsHost", wsHost)
            put("grpcServiceName", grpcServiceName)
            put("publicKey", publicKey)
            put("shortId", shortId)
            put("spiderX", spiderX)
        }
    }

    /**
     * Стандартный формат: vless://<uuid>@<host>:<port>?<query>#<remark>
     * Query-параметры (encryption, security, sni/host, fp, type, path,
     * serviceName, pbk, sid, flow) — общеупотребимый набор, тот же, что
     * используют v2rayNG/streisand и большинство панелей.
     */
    private fun parseVlessLink(link: String): ParsedVless {
        val uri = URI(link)
        val uuid = uri.userInfo ?: throw IllegalArgumentException("Нет UUID перед @")
        val server = uri.host ?: throw IllegalArgumentException("Нет адреса сервера")
        val port = if (uri.port > 0) uri.port else 443

        val query = (uri.rawQuery ?: "").split("&").filter { it.isNotBlank() }.associate { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) pair to "" else {
                val key = pair.substring(0, idx)
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                key to value
            }
        }

        val remark = uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()

        return ParsedVless(
            server = server,
            port = port,
            uuid = uuid,
            encryption = query["encryption"]?.ifBlank { null } ?: "none",
            flow = query["flow"].orEmpty(),
            security = query["security"]?.ifBlank { null } ?: "none",
            sni = query["sni"] ?: query["host"].orEmpty(),
            fingerprint = query["fp"].orEmpty(),
            network = query["type"]?.ifBlank { null } ?: "tcp",
            wsPath = query["path"].orEmpty(),
            wsHost = query["host"].orEmpty(),
            grpcServiceName = query["serviceName"].orEmpty(),
            publicKey = query["pbk"].orEmpty(),
            shortId = query["sid"].orEmpty(),
            spiderX = query["spx"].orEmpty(),
            remark = remark
        )
    }

    // ---- Xray JSON config construction ----

    /**
     * Шаблон (inbounds: socks+tun, outbounds: proxy/direct/block) взят
     * дословно из официального ассета v2rayNG (v2ray_config_with_tun.json) —
     * не придуман, сверен по исходнику. Не зависит от того, какой Go-враппер
     * стоит под капотом (AndroidLibXrayLite/XTLS-libXray) — это стандартный
     * формат конфига самого Xray-core.
     */
    private fun buildXrayConfig(p: JSONObject): JSONObject {
        val streamSettings = JSONObject().apply {
            put("network", p.optString("network", "tcp"))
            val security = p.optString("security", "none")
            put("security", security)
            when (security) {
                "tls" -> put("tlsSettings", JSONObject().apply {
                    put("serverName", p.optString("sni"))
                    if (p.optString("fingerprint").isNotBlank()) put("fingerprint", p.optString("fingerprint"))
                })
                "reality" -> put("realitySettings", JSONObject().apply {
                    put("serverName", p.optString("sni"))
                    if (p.optString("fingerprint").isNotBlank()) put("fingerprint", p.optString("fingerprint"))
                    put("publicKey", p.optString("publicKey"))
                    if (p.optString("shortId").isNotBlank()) put("shortId", p.optString("shortId"))
                    if (p.optString("spiderX").isNotBlank()) put("spiderX", p.optString("spiderX"))
                })
            }
            when (p.optString("network", "tcp")) {
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", p.optString("wsPath", "/"))
                    if (p.optString("wsHost").isNotBlank()) {
                        put("headers", JSONObject().apply { put("Host", p.optString("wsHost")) })
                    }
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", p.optString("grpcServiceName"))
                })
            }
        }

        val userObj = JSONObject().apply {
            put("id", p.optString("uuid"))
            put("encryption", p.optString("encryption", "none"))
            // flow имеет смысл только для network=tcp (xtls-rprx-vision и
            // подобные) — для ws/grpc/xhttp поле должно ПОЛНОСТЬЮ
            // отсутствовать, а не быть пустой строкой.
            if (p.optString("network", "tcp") == "tcp" && p.optString("flow").isNotBlank()) {
                put("flow", p.optString("flow"))
            }
        }

        val vlessOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", p.optString("server"))
                    put("port", p.optInt("port", 443))
                    put("users", JSONArray().put(userObj))
                }))
            })
            put("streamSettings", streamSettings)
            put("mux", JSONObject().put("enabled", false))
        }

        return JSONObject().apply {
            put("log", JSONObject().put("loglevel", "debug"))
            put("dns", JSONObject().apply {
                put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))
            })
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "socks")
                    put("port", 10808)
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                        put("userLevel", 8)
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    })
                })
                put(JSONObject().apply {
                    put("tag", "tun")
                    put("protocol", "tun")
                    put("settings", JSONObject().apply {
                        put("name", "xray0")
                        put("MTU", 1500)
                        put("userLevel", 8)
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    })
                })
            })
            put("outbounds", JSONArray().apply {
                put(vlessOutbound)
                put(JSONObject().apply {
                    put("tag", "direct")
                    put("protocol", "freedom")
                })
                put(JSONObject().apply {
                    put("tag", "block")
                    put("protocol", "blackhole")
                    put("settings", JSONObject().put("response", JSONObject().put("type", "http")))
                })
            })
            put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("rules", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("port", 53)
                        put("outboundTag", "proxy")
                    })
                })
            })
        }
    }
}
