import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun configProperty(name: String): String {
    return providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: providers.environmentVariable(name).orNull
        ?: ""
}

fun String.toBuildConfigString(): String {
    val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

val releaseStoreFile = configProperty("TVBOX_RELEASE_STORE_FILE")
val releaseStorePassword = configProperty("TVBOX_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = configProperty("TVBOX_RELEASE_KEY_ALIAS")
val releaseKeyPassword = configProperty("TVBOX_RELEASE_KEY_PASSWORD")
val aiApiKey = configProperty("TVBOX_AI_API_KEY")
val platformLiveServiceUrl = configProperty("TVBOX_PLATFORM_LIVE_SERVICE_URL")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isNotBlank() }

android {
    namespace = "com.tvbox.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tvbox.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 10326
        versionName = "1.3.26"
        buildConfigField("String", "AI_API_KEY", aiApiKey.toBuildConfigString())
        buildConfigField("String", "PLATFORM_LIVE_SERVICE_URL", platformLiveServiceUrl.toBuildConfigString())
        ndk {
            // 平台直播解析通过 gomobile 产出 .so（platformlive.aar），与以下 ABI 对齐。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // 三种发行版：universal（手机+电视）、tv（仅电视）、mobile（仅手机）
    flavorDimensions += listOf("device")
    productFlavors {
        create("universal") {
            dimension = "device"
            applicationId = "com.tvbox.app"
            versionNameSuffix = ""
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
        }
        create("tv") {
            dimension = "device"
            applicationId = "com.tvbox.app.tv"
            versionNameSuffix = "-tv"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
        }
        create("mobile") {
            dimension = "device"
            applicationId = "com.tvbox.app.mobile"
            versionNameSuffix = "-mobile"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_mobile"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseSigning && configProperty("TVBOX_SIGN_DEBUG_WITH_RELEASE") == "true") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 平台直播 Go 解析库（gomobile bind）。
// preBuild 前自动执行 tools/bind-platform-live.*，产出 app/libs/platformlive.aar。
// 无 Go 工具链时可用 -PtvboxSkipPlatformBind=true 跳过（需事先准备 app/libs/platformlive.aar）。
val platformLiveAar = file("libs/platformlive.aar")
val bindPlatformLive = tasks.register<Exec>("bindPlatformLive") {
    val scriptName = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        "bind-platform-live.ps1"
    } else {
        "bind-platform-live.sh"
    }
    val script = rootProject.file("tools/$scriptName")
    inputs.dir(rootProject.file("platform_live_go"))
    if (platformLiveAar.exists()) {
        outputs.file(platformLiveAar)
    }
    onlyIf { !project.hasProperty("tvboxSkipPlatformBind") }
    if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        commandLine(
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            script.absolutePath,
        )
    } else {
        commandLine("bash", script.absolutePath)
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(bindPlatformLive)
}

dependencies {
    // 平台直播解析（虎牙/斗鱼/B站/抖音/快手）由 Go 实现（platform_live_go），
    // 以 gomobile bind 产出 AAR：tools/bind-platform-live.(ps1|sh)
    implementation(files("libs/platformlive.aar"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
}
