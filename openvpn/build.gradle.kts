plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ВАЖНО: исходники движка (openvpn/openvpn3/asio/fmt/lz4/lzo/mbedtls/
// openssl — все submodules ics-openvpn) в этот каталог не коммитятся —
// клонируются целиком заново на каждом прогоне CI, ДО вызова
// gradle assembleDebug (см. .github/workflows/build.yml, шаг "Vendor
// ics-openvpn sources"). Локально для сборки нужно выполнить этот шаг
// вручную — см. комментарий в начале build.yml.
//
// NDK-версия и C++ стандарт взяты 1-в-1 из апстрима (main/build.gradle.kts
// в schwabe/ics-openvpn), чтобы не ловить несовместимости тулчейна —
// движок сам использует C++23 и требует ровно эту версию NDK.

android {
    namespace = "com.amneziaclient.simple.openvpnengine"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // ВАЖНО: то, что native-сборка (CMakeLists.txt) сама вызывает SWIG для
    // генерации ovpncli_wrap.cxx — этого недостаточно! Тот вызов кладёт
    // сгенерированные .java-файлы в приватную папку CMake-сборки конкретного
    // ABI, которая НЕ подключена ни к одному Java/Kotlin source set.
    //
    // Первая попытка подключить их через androidComponents.onVariants +
    // variant.sources.java?.addGeneratedSourceDirectory (1-в-1 как в
    // апстриме) молча не сработала: сам SWIG успешно отработал (это видно по
    // реальным warning'ам из dns_options.hpp в логе), но compileDebugKotlin/
    // compileDebugJavaWithJavac всё равно вышли NO-SOURCE — то есть
    // сгенерированная папка так и не зарегистрировалась как source set.
    // Переходим на классический, десятилетиями обкатанный способ:
    // явный java.srcDir на sourceSet "main" + явная task-зависимость ниже.
    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/ovpn3swig"))
        }
    }
}

var swigcmd = "swig"
if (file("/opt/homebrew/bin/swig").exists()) swigcmd = "/opt/homebrew/bin/swig"
else if (file("/usr/local/bin/swig").exists()) swigcmd = "/usr/local/bin/swig"

val ovpn3SwigOutDir = layout.buildDirectory.dir("generated/ovpn3swig/net/openvpn/ovpn3")

val generateOpenVPN3Swig = tasks.register<Exec>("generateOpenVPN3Swig") {
    val outDir = ovpn3SwigOutDir.get().asFile
    doFirst { mkdir(outDir) }
    commandLine(
        listOf(
            swigcmd, "-outdir", outDir.absolutePath, "-outcurrentdir",
            "-c++", "-java", "-package", "net.openvpn.ovpn3",
            "-Isrc/main/cpp/openvpn3/client", "-Isrc/main/cpp/openvpn3/",
            "-DOPENVPN_PLATFORM_ANDROID",
            "-o", "${outDir.absolutePath}/ovpncli_wrap.cxx", "-oh", "${outDir.absolutePath}/ovpncli_wrap.h",
            "src/main/cpp/openvpn3/client/ovpncli.i"
        )
    )
    inputs.files("src/main/cpp/openvpn3/client/ovpncli.i")
    outputs.dir(outDir)
}

// Явная зависимость: обе Kotlin- и Java-компиляции всех вариантов (debug,
// release и т.д.) должны дождаться генерации перед стартом. afterEvaluate —
// потому что имена task'ов compileXxxKotlin/compileXxxJavaWithJavac
// регистрируются AGP/Kotlin-плагином только после конфигурации variant'ов.
afterEvaluate {
    tasks.matching { task ->
        task.name.startsWith("compile") && (task.name.contains("Kotlin") || task.name.contains("JavaWithJavac"))
    }.configureEach {
        dependsOn(generateOpenVPN3Swig)
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
