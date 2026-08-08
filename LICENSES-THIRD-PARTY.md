# Сторонние компоненты

## L2TP/IPsec (модуль `:l2tp`)

Нативный C-движок IKEv1/L2TP/PPP/ESP взят из проекта **TunnelForge**:
https://github.com/evokelektrique/tunnel-forge

- Лицензия: **GPL-3.0-only** (см. https://github.com/evokelektrique/tunnel-forge/blob/main/LICENSE).
- В этот репозиторий файлы `.c`/`.h` самого движка НЕ закоммичены — они
  клонируются из upstream-репозитория заново на каждом прогоне CI (см.
  `.github/workflows/build.yml`, шаг "Vendor TunnelForge L2TP engine
  sources"), чтобы всегда собираться из проверяемого, актуального источника.
- Файлы `l2tp/src/main/kotlin/io/github/evokelektrique/tunnelforge/`:
  - `VpnBridge.kt` — вендорен из TunnelForge практически без изменений
    (набор `external fun`-деклараций, обязан совпадать с JNI-регистрацией
    в движке).
  - `TunnelVpnService.kt` и `VpnTunnelEvents.kt` — НЕ вендорены, это
    собственная минимальная реализация, которая лишь удовлетворяет точному
    JNI-контракту (имя класса/метода/сигнатура), который движок ищет через
    `FindClass`/`GetStaticMethodID`.
- Зависимость движка — **mbedTLS** (Apache-2.0), подтягивается через CMake
  `FetchContent` при сборке.

**Важно про copyleft:** так как компилируемый APK будет содержать объектный
код, слинкованный из GPL-3.0-лицензированного движка, при распространении
APK третьим лицам возникают обязательства GPL-3.0 (доступность
соответствующих исходников). Пока репозиторий приватный и APK не
распространяется публично, это не создаёт практической проблемы — но при
любой публикации сборки это нужно учитывать.

## OpenVPN (модуль `:openvpn`)

Движок — **ics-openvpn** (schwabe/ics-openvpn):
https://github.com/schwabe/ics-openvpn

- Лицензия: **GPL-2.0** (см. `doc/README.txt` в репозитории — там же явно
  сказано, что проект НЕ задуман как встраиваемая библиотека для чужих
  приложений; официально одобренный автором способ интеграции — через AIDL
  с отдельно установленным официальным приложением. Мы сознательно выбрали
  другой путь — полный вендоринг исходников в свой APK — при полном
  понимании этой позиции автора и связанных с ней лицензионных обязательств
  (см. историю переписки).
- Включает: `openvpn3` (C++ ядро от самой OpenVPN Inc.), классический
  `openvpn` 2.x (C), и зависимости `asio`, `fmt`, `lz4`, `lzo`, `mbedtls`,
  собственный форк `openssl`.
- В этот репозиторий исходники НЕ закоммичены — клонируются целиком
  (со всеми submodules) заново на каждом прогоне CI (см.
  `.github/workflows/build.yml`, шаг "Vendor ics-openvpn sources").
- Дополнительные требования к сборке: **SWIG** (кодогенерация JNI-моста
  для `openvpn3`) и **NDK 30.0.14904198** (версия, которую использует сам
  апстрим — модуль `:openvpn` использует именно её, отдельно от NDK 27,
  который использует остальной проект; Android Gradle Plugin поддерживает
  разные версии NDK для разных модулей одновременно).

## VLESS/Xray (модуль `:vless`)

Движок — **Xray-core** (XTLS/Xray-core), обёрнутый в готовый Android `.aar`
через **AndroidLibXrayLite** (2dust/AndroidLibXrayLite, тот же автор/команда,
что делает v2rayNG — самый популярный Android-клиент для V2Ray/Xray):
https://github.com/2dust/AndroidLibXrayLite

- Лицензия Xray-core: **MPL-2.0** (Mozilla Public License 2.0) — заметно
  мягче GPL: копилефт действует только на уровне изменённых файлов самой
  библиотеки, не распространяется на весь проект, который её использует.
- В отличие от L2TP/OpenVPN, здесь **не нужно вендорить исходники и
  собирать самим** — AndroidLibXrayLite публикует готовый `libv2ray.aar`
  прямо в GitHub Releases на каждый релиз. Скачивается заново на каждом
  прогоне CI через GitHub API (см. `.github/workflows/build.yml`, шаг
  "Download AndroidLibXrayLite AAR") в `vless/libs/libv2ray.aar` — сам
  `.aar` в git не коммитится.
- Go/gomobile в CI не нужны вообще — только скачивание готового бинарника.

## SSTP (модуль `:sstp`)

Движок — **kittoku/Open-SSTP-Client** (github.com/kittoku/Open-SSTP-Client),
чистый Kotlin, без нативного кода — не нужен NDK/CMake, в отличие от
L2TP/OpenVPN.

- Лицензия: **MIT**.
- Вендорится целиком (Kotlin-исходники + ресурсы) на каждом прогоне CI —
  см. `.github/workflows/build.yml`, шаг "Vendor kittoku/Open-SSTP-Client
  sources" — в `sstp/src/main/java/kittoku/` и `sstp/src/main/res/`. Сам
  вендоренный код в git не коммитится.
- `sstp/src/main/AndroidManifest.xml` — **наш собственный**, не из
  апстрима: у оригинального приложения там были свои
  MainActivity/BlankActivity (создали бы второй значок запуска) и
  SstpTileService (плитка быстрых настроек) — нам нужен только сам
  `SstpVpnService`, поэтому манифест урезан вручную и НЕ перезаписывается
  вендорингом.
