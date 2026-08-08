plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ВАЖНО: .c/.h файлы движка (io.github.evokelektrique/tunnel-forge, GPL-3.0)
// в этот каталог не коммитятся — они клонируются и копируются в
// src/main/cpp/ заново на каждом прогоне CI, ДО вызова gradle assembleDebug
// (см. .github/workflows/build.yml, шаг "Vendor TunnelForge L2TP engine
// sources"). Локально для сборки нужно выполнить этот шаг вручную —
// см. комментарий в начале build.yml.

android {
    namespace = "com.amneziaclient.simple.l2tpengine"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
