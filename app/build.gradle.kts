plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

android {
    namespace = "com.amneziaclient.simple"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.amneziaclient.simple"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Две транзитивные зависимости библиотеки AmneziaWG (okhttp-dnsoverhttps и jspecify)
    // кладут файл по одному и тому же пути META-INF/versions/9/OSGI-INF/MANIFEST.MF.
    // Это не код, а просто метаданные OSGi, которые Android всё равно не использует —
    // безопасно взять файл только из одного источника.
    packaging {
        resources {
            pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // Официальный AmneziaWG backend, опубликован в Maven Central
    // (собранный tunnel-модуль amnezia-vpn/amneziawg-android)
    implementation("com.zaneschepke:amneziawg-android:2.3.7")

    // Официальный IKEv2/IPsec backend (strongSwan), вендорен в strongswan-module/
    // с патчами application->library (см. settings.gradle.kts для деталей)
    implementation(project(":strongswan"))

    // L2TP/IPsec (IKEv1) — нативный движок TunnelForge (GPL-3.0, community,
    // НЕ официальная библиотека протокола) — см. LICENSES-THIRD-PARTY.md.
    implementation(project(":l2tp"))

    // OpenVPN — официальный движок ics-openvpn (GPL-2.0, см.
    // LICENSES-THIRD-PARTY.md и settings.gradle.kts).
    implementation(project(":openvpn"))

    // SSTP — вендоренный kittoku/Open-SSTP-Client (MIT).
    implementation(project(":sstp"))

    // VLESS/Xray — официальный движок XTLS/libXray (готовый .aar от самой
    // команды XTLS, авторов Xray-core/VLESS/REALITY — см.
    // LICENSES-THIRD-PARTY.md). Библиотечный модуль (:vless) не может
    // напрямую зависеть от локального .aar (ограничение AGP) — поэтому,
    // в отличие от L2TP/OpenVPN, отдельного Gradle-модуля тут нет вообще
    // (нативной сборки/CMake тоже нет — просто готовый бинарник), .aar
    // подключается прямо здесь. Скачивается заново на каждом прогоне CI в
    // app/libs/LibXray.aar (см. build.yml, шаг "Download XTLS/libXray AAR")
    // — сам файл в git не коммитится.
    implementation(files("libs/LibXray.aar"))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.transition:transition-ktx:1.5.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.google.dagger:hilt-android:2.57")
    kapt("com.google.dagger:hilt-compiler:2.57")
    // Явно фиксируем читалку метаданных Kotlin под нашу версию компилятора (2.2.21),
    // чтобы kapt/Hilt не падал на "Provided Metadata instance has version X,
    // while maximum supported version is Y" (см. https://github.com/google/dagger/issues/4779).
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.2.21")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Сканирование QR-кодов при добавлении профиля (WireGuard/AmneziaWG/VLESS —
    // единственные протоколы, чьи конфиги реально помещаются в один QR-код;
    // OpenVPN/IKEv2 с сертификатами обычно превышают ёмкость QR).
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
