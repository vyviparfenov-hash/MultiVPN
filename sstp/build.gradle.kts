plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

// ВАЖНО: Kotlin-исходники и ресурсы (sstp/src/main/java/kittoku/, sstp/src/main/res/)
// в этот модуль НЕ коммитятся — вендорятся заново на каждом прогоне CI из
// kittoku/Open-SSTP-Client (MIT, см. LICENSES-THIRD-PARTY.md), шаг
// "Vendor kittoku/Open-SSTP-Client sources" в build.yml. Локально для
// сборки этот шаг нужно выполнить вручную (склонировать репозиторий и
// скопировать app/src/main/java/kittoku -> sstp/src/main/java/kittoku,
// app/src/main/res -> sstp/src/main/res).
//
// AndroidManifest.xml в этом модуле — НАШ СОБСТВЕННЫЙ, не из апстрима:
// у оригинального приложения там были свои MainActivity/BlankActivity/
// SstpTileService с launcher-intent-filter'ами — нам нужен только
// SstpVpnService, поэтому манифест урезан вручную и коммитится как есть
// (не перезаписывается вендорингом).

android {
    namespace = "kittoku.osc"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // Библиотечные модули не имеют versionCode/versionName в
        // defaultConfig (это поля только com.android.application) — а их
        // вендоренный (неиспользуемый нами) MainActivity.kt ссылается на
        // BuildConfig.VERSION_NAME, поэтому добавляем поле напрямую.
        buildConfigField("String", "VERSION_NAME", "\"1.0\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
