// Версия из git: имя — `git describe` по тегу v<число>.<число>.<число> (релиз собирает .github/workflows/release.yml
// по такому тегу), код — число коммитов. Без тегов (или без git) — 0.0.1-dev-<хеш>; сборка после тега — 1.2.3-5-gabcdef.
fun gitOutput(vararg command: String): String = try {
    providers.exec {
        commandLine(*command)
        isIgnoreExitValue = true   // нет git или тегов — не ломать сборку
    }.standardOutput.asText.get().trim()
} catch (_: Exception) {
    ""
}

val gitVersionCode: Int by lazy { gitOutput("git", "rev-list", "--count", "HEAD").toIntOrNull() ?: 1 }

val gitVersionName: String by lazy {
    val described = gitOutput("git", "describe", "--tags", "--match", "v[0-9]*", "--dirty")
    if (described.isNotEmpty() && !described.contains("fatal")) described.removePrefix("v")
    else gitOutput("git", "rev-parse", "--short", "HEAD").let { if (it.isNotEmpty()) "0.0.1-dev-$it" else "0.0.1" }
}

// Ключ подписи релиза — из окружения (CI): путь KEYSTORE_FILE относительно модуля app. Без него release подписывается
// debug-ключом (для установки на свою приставку). Обновление поверх установленной сборки возможно только при той же подписи.
fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val releaseKeystorePath: String? = envOrNull("KEYSTORE_FILE")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "top.rootu.perfecttv"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.rootu.perfecttv"
        minSdk = 23
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = gitVersionName
    }

    signingConfigs {
        releaseKeystorePath?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = envOrNull("KEYSTORE_PASSWORD")
                keyAlias = envOrNull("RELEASE_SIGN_KEY_ALIAS")
                keyPassword = envOrNull("RELEASE_SIGN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystorePath != null) "release" else "debug")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.effect)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    implementation(libs.coil)
    implementation(libs.tvprovider)
    // QR-код для ввода с телефона (только кодирование)
    implementation(libs.zxing.core)
    // программные аудиодекодеры FFmpeg (MP2, AC-3, E-AC-3, DTS…) для устройств, где их нет в системе; Media3 1.9.0
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // поиск утечек памяти: только в отладочной сборке (в release нет ни библиотеки, ни её экрана)
    debugImplementation(libs.leakcanary.android)

    testImplementation(libs.junit)
    // XmlPullParser для JVM-тестов (на устройстве — встроенный KXml)
    testImplementation(libs.kxml2)
    // настоящий org.json для JVM-тестов (в android.jar для тестов — заглушки)
    testImplementation(libs.json)
}
