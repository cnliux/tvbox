import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
// 仅本地调试需要 x86 模拟器包：-PtvboxEmulatorAbis=true
// 会给 Debug 追加 x86/x86_64，并让 gomobile bind 一并产出对应 ABI；Release 永远只有 arm64-v8a。
val emulatorAbisEnabled = listOf(
    providers.gradleProperty("tvboxEmulatorAbis").orNull,
    localProperties.getProperty("tvboxEmulatorAbis"),
    providers.environmentVariable("TVBOX_EMULATOR_ABIS").orNull,
).any { it != null && it.isNotBlank() && it != "0" && !it.equals("false", ignoreCase = true) }
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
        versionCode = 10331
        versionName = "1.3.31"
        buildConfigField("String", "AI_API_KEY", aiApiKey.toBuildConfigString())
        // 只保留中文资源，剔除 AndroidX/Media3 携带的其他语言字符串。
        resConfigs(listOf("zh"))
        // ABI 按构建类型分别配置：Release 只发 arm64-v8a，Debug 可选本机 x86。
    }

    androidResources {
        // OkHttp 公共后缀库仅 CookieJar 使用，R8 已删除相关类，这里同步剔除该 assets。
        ignoreAssetsPatterns += "!PublicSuffixDatabase.list"
    }

    // 单一合集发行版：同一套代码通过 DeviceMode 在运行时适配电视/手机，不再拆 flavor。

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
            // 本地调试默认仅 arm64-v8a；需要 x86 模拟器时加 -PtvboxEmulatorAbis=true。
            ndk {
                abiFilters += if (emulatorAbisEnabled) {
                    listOf("arm64-v8a", "x86", "x86_64")
                } else {
                    listOf("arm64-v8a")
                }
            }
            if (hasReleaseSigning && configProperty("TVBOX_SIGN_DEBUG_WITH_RELEASE") == "true") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // 只发布 arm64-v8a：不在应用商店/Release 中附带 armv7 与 x86 原生库。
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
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
        viewBinding = false
        dataBinding = false
        aidl = false
        shaders = false
    }

    dependenciesInfo {
        // 不再向 APK/AAB 写入依赖清单（仅 Google Play 使用）。
        includeInApk = false
        includeInBundle = false
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
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/**/LICENSE*",
                "/META-INF/**/NOTICE*",
                "/META-INF/*.version",
                "/META-INF/version-control-info.textproto",
                "/META-INF/native-image/**",
                // Kotlin 反射元数据与调试探针在 App 中未使用。
                "/kotlin/**",
                "/DebugProbesKt.bin",
            )
        }
    }
}

// 平台直播 Go 解析库（gomobile bind）。
// preBuild 前自动执行 tools/bind-platform-live.*，产出 app/libs/platformlive.aar。
// 无 Go 工具链时可用 -PtvboxSkipPlatformBind=true 跳过（需事先准备 app/libs/platformlive.aar）。
val platformLiveAar = file("libs/platformlive.aar")

fun resolveNdkDir(): File? {
    android.ndkPath?.let { path -> File(path).takeIf { it.isDirectory }?.let { return it } }
    System.getenv("ANDROID_NDK_HOME")?.let { path ->
        File(path).takeIf { it.isDirectory }?.let { return it }
    }
    val sdkRoot = System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: localProperties.getProperty("sdk.dir")
    if (!sdkRoot.isNullOrBlank()) {
        val ndkRoot = File(sdkRoot, "ndk")
        if (ndkRoot.isDirectory) {
            return ndkRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
        }
    }
    return null
}

val bindPlatformLive = tasks.register<Exec>("bindPlatformLive") {
    val scriptName = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        "bind-platform-live.ps1"
    } else {
        "bind-platform-live.sh"
    }
    val script = rootProject.file("tools/$scriptName")
    inputs.dir(rootProject.file("platform_live_go"))
    // 模拟器 ABI 开关变化时重新绑定，产出含 x86/x86_64 的 AAR。
    inputs.property("emulatorAbis", emulatorAbisEnabled)
    if (platformLiveAar.exists()) {
        outputs.file(platformLiveAar)
    }
    onlyIf { !project.hasProperty("tvboxSkipPlatformBind") }
    environment("TVBOX_EMULATOR_ABIS", if (emulatorAbisEnabled) "1" else "0")
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

// gomobile 产出的 libgojni.so 默认带 Go 调试符号（单 ABI 约 29MB）。
// 打包前用 NDK llvm-strip 去掉 .symtab/.debug_*，单 ABI 可降到约 16MB。
val stripPlatformLiveAar = tasks.register("stripPlatformLiveAar") {
    dependsOn(bindPlatformLive)
    outputs.upToDateWhen { false }
    doLast {
        val aar = platformLiveAar
        if (!aar.isFile) return@doLast
        val stampFile = layout.buildDirectory.file("platformlive-aar-strip.stamp").get().asFile
        val currentState = "${aar.length()}:${aar.lastModified()}"
        if (stampFile.isFile && stampFile.readText() == currentState) return@doLast

        val ndkDir = resolveNdkDir()
        if (ndkDir == null) {
            logger.warn("未找到 Android NDK，跳过 libgojni.so 调试符号剥离")
            return@doLast
        }
        val hostTag = when {
            org.gradle.internal.os.OperatingSystem.current().isWindows -> "windows-x86_64"
            org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val stripName = if (hostTag.startsWith("windows")) "llvm-strip.exe" else "llvm-strip"
        val strip = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin/$stripName")
        if (!strip.isFile) {
            logger.warn("未找到 $strip，跳过 libgojni.so 调试符号剥离")
            return@doLast
        }

        val tmpAar = File(temporaryDir, "platformlive-stripped.aar")
        ZipInputStream(aar.inputStream()).use { input ->
            ZipOutputStream(tmpAar.outputStream()).use { output ->
                var entry: ZipEntry? = input.nextEntry
                while (entry != null) {
                    val current = entry
                    if (current.isDirectory) {
                        output.putNextEntry(ZipEntry(current.name))
                        output.closeEntry()
                    } else if (current.name.startsWith("jni/") && current.name.endsWith(".so")) {
                        val so = File(temporaryDir, current.name.substringAfterLast('/'))
                        so.outputStream().use { input.copyTo(it) }
                        val process = ProcessBuilder(strip.absolutePath, "--strip-unneeded", so.absolutePath)
                            .redirectErrorStream(true)
                            .start()
                        val processLog = process.inputStream.bufferedReader().readText()
                        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                            error("llvm-strip 失败：$processLog")
                        }
                        output.putNextEntry(ZipEntry(current.name))
                        so.inputStream().use { it.copyTo(output) }
                        output.closeEntry()
                        so.delete()
                    } else {
                        output.putNextEntry(ZipEntry(current.name))
                        input.copyTo(output)
                        output.closeEntry()
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        tmpAar.copyTo(aar, overwrite = true)
        tmpAar.delete()
        stampFile.parentFile?.mkdirs()
        stampFile.writeText("${aar.length()}:${aar.lastModified()}")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(stripPlatformLiveAar)
}

dependencies {
    // 平台直播解析（虎牙/斗鱼/B站/抖音/快手）由 Go 实现（platform_live_go），
    // 以 gomobile bind 产出 AAR：tools/bind-platform-live.(ps1|sh)
    implementation(files("libs/platformlive.aar"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
