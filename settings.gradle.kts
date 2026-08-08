pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MultiVpnClient"

// Backend AmneziaWG подключается НЕ как git submodule, а как обычная библиотека
// из Maven Central: com.zaneschepke:amneziawg-android (см. app/build.gradle.kts).
// Это официальная сборка tunnel-модуля amnezia-vpn/amneziawg-android, просто
// уже опубликованная как готовый .aar — компилировать Go/NDK самим не нужно.
include(":app")

// Backend IKEv2/IPsec — библиотека strongSwan. Полный исходный код клонируется
// и патчится (application->library, стабы для MainActivity/LogActivity/
// VpnProfileDetailActivity, no-asm для OpenSSL) заново на каждом прогоне CI —
// см. .github/workflows/build.yml, шаги "Clone/patch/prepare strongSwan".
// ВАЖНО: это должна быть ПОЛНАЯ копия репозитория strongswan/strongswan
// (Android.mk ссылается на libcharon/libstrongswan/libipsec и т.д. ВНЕ app/),
// а не только папка app/ — частичная копия не соберётся.
include(":strongswan")
project(":strongswan").projectDir = file("strongswan-src/src/frontends/android/app")

// Backend L2TP/IPsec (IKEv1) — нативный C-движок TunnelForge (GPL-3.0,
// github.com/evokelektrique/tunnel-forge), НЕ официальная библиотека
// протокола, а сторонняя community-реализация — см. LICENSES-THIRD-PARTY.md.
// Исходники .c/.h клонируются и копируются заново на каждом прогоне CI (см.
// .github/workflows/build.yml, шаг "Vendor TunnelForge L2TP engine sources"),
// поэтому в git-репозитории самого движка нет — только обвязка модуля.
include(":l2tp")

// OpenVPN — официальный движок ics-openvpn (schwabe/ics-openvpn, GPL-2.0,
// "полу-официальный клиент сообщества", включает openvpn3 от самой OpenVPN
// Inc. + классический OpenVPN 2.x + OpenSSL). Вендорится ЦЕЛИКОМ вместе со
// всеми submodules (openvpn, openvpn3, asio, fmt, lz4, lzo, mbedtls, openssl)
// заново на каждом прогоне CI — см. .github/workflows/build.yml, шаг
// "Vendor ics-openvpn sources". Выборочное копирование файлов (как для L2TP)
// тут не годится — сборка слишком плотно связана между этими submodules.
include(":openvpn")

// SSTP — вендорим kittoku/Open-SSTP-Client (MIT) целиком, чистый Kotlin,
// без нативной сборки (см. LICENSES-THIRD-PARTY.md и build.yml).
include(":sstp")

