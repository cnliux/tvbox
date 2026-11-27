import java.security.MessageDigest
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

// AI 找片的 Key / Base URL 只从构建期环境变量或 local.properties 读取，
// 缺失时注入空串；不写进偏好、不进仓库。转义避免 Key 中的引号/反斜杠破坏生成代码。
fun buildConfigString(value: String): String = "\"${value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")}\""

val releaseStoreFile = configProperty("TVBOX_RELEASE_STORE_FILE")
val releaseStorePassword = configProperty("TVBOX_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = configProperty("TVBOX_RELEASE_KEY_ALIAS")
val releaseKeyPassword = configProperty("TVBOX_RELEASE_KEY_PASSWORD")
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

// 版本号统一定义：defaultConfig 与 Release APK 文件名共用。
val TVBOX_VERSION_NAME = "1.0.54"
val TVBOX_VERSION_CODE = 20054

android {
    namespace = "com.tvbox.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tvbox.app"
        minSdk = 24
        targetSdk = 36
        versionCode = TVBOX_VERSION_CODE
        versionName = TVBOX_VERSION_NAME
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AI 找片：构建期注入，缺失为空串；UI 只读展示来源，Key 不写入偏好。
        buildConfigField("String", "AI_API_KEY", buildConfigString(configProperty("TVBOX_AI_API_KEY")))
        buildConfigField("String", "AI_BASE_URL", buildConfigString(configProperty("TVBOX_AI_BASE_URL")))
        // ABI 按构建类型分别配置：Release 只发 arm64-v8a，Debug 可选本机 x86。
    }

    androidResources {
        // 只保留中文资源，剔除 AndroidX/Media3 携带的其他语言字符串。
        // AGP 9 起 resConfigs 已弃用，语言资源改用 localeFilters。
        localeFilters += listOf("zh")
        // OkHttp 公共后缀库仅 CookieJar 使用，R8 已删除相关类，这里同步剔除该 assets。
        // aapt 资产规则里 '!' 前缀表示「保留」，与这里要剔除的意图相反；
        // 直接写文件名才是排除，避免把 OkHttp 的 PublicSuffixDatabase.list 误打进包。
        ignoreAssetsPatterns += "PublicSuffixDatabase.list"
    }

    lint {
        // 历史遗留 lint error 基线化，日常 CI 只拦截新引入的问题（见 .github/workflows/ci.yml）。
        baseline = file("lint-baseline.xml")
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
        // 「压缩优化调试版」：R8 混淆 + 资源收缩拿到接近 Release 的体积优化，
        // 但保留 isDebuggable=true 以便连 ADB 真机验证；不剥离 Log 调用（见专属 proguard 规则）。
        create("debugOptimized") {
            initWith(getByName("release"))
            // 覆盖 Release 的可调试/日志语义：调试版必须能连 ADB 调试，且保留 Log 调用。
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ".dbgopt"
            versionNameSuffix = "-dbgopt"
            // 用 debug 签名：调试版用 release key 不合适，也会污染 release 版本号历史。
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug", "release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-debug-optimize.pro",
            )
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

    testOptions {
        unitTests {
            // JVM 单测中 android.util.Log 等框架方法返回默认值而非抛 "not mocked"，
            // 允许数据层在降级 catch 里保留 debug 日志而不破坏测试。
            isReturnDefaultValues = true
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        // libgojni.so 已在 stripPlatformLiveAar 中用 NDK llvm-strip 剥离过调试符号，
        // AGP 再剥一次会失败并输出「Unable to strip ... packaging them as they are」噪音。
        // 显式声明保留，跳过 AGP 的二次剥离（不影响体积）。
        jniLibs {
            keepDebugSymbols += "**/libgojni.so"
        }
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

// Release APK 统一命名为带版本号的 juhe-video-v<versionName>.apk，避免无版本号的 app-release.apk。
// 使用 AGP 8+ 的 androidComponents API（applicationVariants 在新版 AGP 的 Kotlin DSL 中已不可用）。
androidComponents {
    onVariants { variant ->
        // T4-25 第三道闸：skipBind 的包在 APK 里烙印，流出去一眼可定性，不再静默错误。
        if (skipPlatformBind && variant.buildType == "debug") {
            variant.applicationId.set(variant.applicationId.get() + ".debugnopl")
        }
        if (variant.buildType == "release") {
            variant.outputs.forEach { output ->
                output.outputFileName.set("juhe-video-v${TVBOX_VERSION_NAME}.apk")
            }
        }
        // 压缩优化调试版：带版本号 + dbgopt 后缀，避免与 release/debug 混淆。
        if (variant.buildType == "debugOptimized") {
            variant.outputs.forEach { output ->
                output.outputFileName.set("juhe-video-v${TVBOX_VERSION_NAME}-dbgopt.apk")
            }
        }
    }
}

// 平台直播 Go 解析库（gomobile bind）。
// preBuild 前自动执行 tools/bind-platform-live.*，产出 app/libs/platformlive.aar。
// 无 Go 工具链时可用 -PtvboxSkipPlatformBind=true 跳过（需事先准备 app/libs/platformlive.aar）。
val platformLiveAar = file("libs/platformlive.aar")

// Go 源码指纹：决定是否需要重新 gomobile bind。
// 旧实现用 onlyIf { !platformLiveAar.exists() }，AAR 一旦存在就永不重绑，
// 改了 Go 代码仍会把旧 .so 打进 APK；而 stripPlatformLiveAar 又会原地改写
// 同一个 AAR，直接把它声明成 outputs 会让每次构建都误判为过期。
// 因此这里按源码内容做显式指纹，并把指纹放在 build/ 下，与 AAR 本体解耦。
fun goSourceFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val root = rootProject.rootDir
    val sources = mutableListOf<File>()
    rootProject.file("platform_live_go").walkTopDown().forEach { candidate ->
        if (candidate.isFile && (candidate.extension == "go" ||
                candidate.name == "go.mod" || candidate.name == "go.sum")
        ) {
            sources.add(candidate)
        }
    }
    rootProject.file("tools").listFiles()?.forEach { candidate ->
        if (candidate.isFile && candidate.name.startsWith("bind-platform-live.")) {
            sources.add(candidate)
        }
    }
    sources.sortedBy { it.relativeTo(root).path }.forEach { source ->
        digest.update(source.relativeTo(root).path.toByteArray())
        digest.update(source.readBytes())
    }
    digest.update(emulatorAbisEnabled.toString().toByteArray())
    return digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
}

// 指纹文件与 AAR 同目录，随 CI 缓存一起恢复；放 build/ 会被 clean 清掉，
// 导致 clean 之后即使 Go 源码变了也误判为「已绑定」。
val platformLiveFingerprintFile = file("libs/platformlive.fingerprint")

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
    // 不做 Gradle up-to-date 声明：本任务由下方的源码指纹显式决定是否需要执行，
    // 避免 strip 任务原地改写 AAR 造成「每次都过期」或「改了 Go 却不重绑」。
    onlyIf {
        if (project.hasProperty("tvboxSkipPlatformBind")) return@onlyIf false
        if (!platformLiveAar.exists()) return@onlyIf true
        val cached = if (platformLiveFingerprintFile.isFile) {
            platformLiveFingerprintFile.readText()
        } else {
            ""
        }
        // AAR 来自 CI 缓存但本地没有指纹时，强制重绑一次以建立基线。
        cached.isBlank() || cached != goSourceFingerprint()
    }
    doLast {
        platformLiveFingerprintFile.parentFile?.mkdirs()
        platformLiveFingerprintFile.writeText(goSourceFingerprint())
    }
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

// T4-25：skipPlatformBind 是一个「无声的正确性开关」，在下方 verifyPlatformBind 任务与
// androidComponents 烙印处共用；定义在此处保证配置期先于两处使用。
val skipPlatformBind = project.hasProperty("tvboxSkipPlatformBind")

val verifyPlatformBind = tasks.register("verifyPlatformBind") {
    group = "verification"
    description = "校验 platformlive.aar 与 Go 源码指纹一致，并拒绝 skipBind 且 AAR 缺失的残包"
    // 必须在 bind/strip 之后：fingerprint 文件由 bindPlatformLive 在 doLast 写入。
    dependsOn(stripPlatformLiveAar)
    outputs.upToDateWhen { false }
    doLast {
        if (skipPlatformBind) {
            check(platformLiveAar.isFile) {
                "❌ skipBind 且 AAR 缺失：这个包一定没有平台直播，拒绝产出。\n" +
                    "  → 跑 powershell -File tools/bind-platform-live.ps1，或显式承认这是调试包"
            }
            logger.warn("⚠️ tvboxSkipPlatformBind=true：这是未绑定 Go 源码的调试包，APK 将烙印 .debugnopl 后缀")
            return@doLast
        }
        check(platformLiveAar.isFile) {
            "❌ platformlive.aar 缺失：平台直播解析不可用，拒绝产出残包。\n" +
                "  → 跑 powershell -File tools/bind-platform-live.ps1"
        }
        val stamp = platformLiveFingerprintFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val current = goSourceFingerprint()
        check(stamp == current) {
            "❌ platformlive.aar 与 Go 源码不匹配（可能改了 .go/go.mod 却没重绑）。\n" +
                "  AAR stamp = $stamp\n  Go  now    = $current\n" +
                "  → 跑 powershell -File tools/bind-platform-live.ps1"
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyPlatformBind)
}

// T4-27：versionCode 单一真源门禁。
// 本仓是单包多端（运行时 DeviceMode 适配，无 tv/mobile flavor），因此
// 「三 flavor 靠人肉约定前缀导致 OTA 推错包」在这里变形为：版本号只能有一处
// 定义、versionCode 只能有一处赋值。杜绝复制粘贴出第二个真源。
val verifyVersionCode = tasks.register("verifyVersionCode") {
    group = "verification"
    description = "校验 versionCode 只有单一真源，杜绝复制粘贴导致的 OTA 错推"
    inputs.file("build.gradle.kts")
    outputs.upToDateWhen { false }
    doLast {
        val script = file("build.gradle.kts").readText()
        val definitions = Regex("""val\s+TVBOX_VERSION_CODE\s*=""").findAll(script).count()
        val assignments = Regex("""versionCode\s*=""").findAll(script).count()
        val literals = Regex("""versionCode\s*=\s*\d+""").findAll(script).count()
        check(definitions == 1 && assignments == 1 && literals == 0) {
            "❌ versionCode 真源不唯一（定义=$definitions / 赋值=$assignments / 硬编码=$literals）。\n" +
                "  → 只允许顶部 val TVBOX_VERSION_CODE 定义一次，并在 defaultConfig 中赋值一次"
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyVersionCode)
}

// 打包前校验必备 assets。
// 背景：曾出现过 assets/dy_abogus.js（抖音 a_bogus 签名脚本）被本机安全软件静默删除，
// 而构建仍然成功，最终发出一个缺少该资源的包 —— 运行时 assets.open() 抛
// FileNotFoundException，整个平台直播初始化失败（虎牙/斗鱼/抖音一起挂）。
// 这里在 preBuild 阶段直接拦死，避免再产出残缺包。
val verifyRequiredAssets by tasks.registering {
    val assetsDir = layout.projectDirectory.dir("src/main/assets").asFile
    // 最小体积兜底：被截断成 0 字节的文件同样视为缺失。
    val required = mapOf(
        "dy_abogus.js" to 1024L,
        "platform_live_catalog.json" to 64L,
    )
    // 每次构建都检查（两个文件 stat，代价可忽略），不要因 up-to-date 跳过。
    outputs.upToDateWhen { false }
    doLast {
        val bad = required.mapNotNull { (name, minBytes) ->
            val file = assetsDir.resolve(name)
            when {
                !file.isFile -> "$name(缺失)"
                file.length() < minBytes -> "$name(仅 ${file.length()} 字节)"
                else -> null
            }
        }
        if (bad.isNotEmpty()) {
            throw GradleException(
                "必备 assets 校验失败：${bad.joinToString("，")}。" +
                    "请从 platform_live_go/platformassets/ 恢复（cp platform_live_go/platformassets/douyin_sign.js " +
                    "app/src/main/assets/dy_abogus.js）后重新构建。",
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyRequiredAssets)
}

// 统一 TODO/FIXME/HACK 标记的域前缀 lint（P2-14）。
// 期望格式：`TODO(<域>-<序号>)`，如 `TODO(P0-S4)`、`TODO(P2-14)`。
// 规则：无域前缀 → warn；`P0-` 前缀 → fail（Release 分支不允许 P0- 遗留，见 §6 验收）。
val lintTodo = tasks.register("lintTodo") {
    group = "verification"
    description = "校验 TODO/FIXME/HACK 标记是否带统一域前缀"
    doLast {
        val sourceDirs = listOf(
            layout.projectDirectory.dir("src/main/java").asFile,
            layout.projectDirectory.dir("src/test/java").asFile,
        )
        val root = rootProject.rootDir
        val goDir = root.resolve("platform_live_go")
        val markerPattern = Regex("""\b(TODO|FIXME|HACK)\b""")
        val prefixPattern = Regex("""(?:TODO|FIXME|HACK)\s*\(\s*([A-Za-z0-9]+)-([A-Za-z0-9]+)\s*\)""")
        val files = mutableListOf<File>()
        sourceDirs.filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { files.add(it) }
        }
        if (goDir.isDirectory) {
            goDir.walkTopDown()
                .filter { it.isFile && it.extension == "go" }
                .forEach { files.add(it) }
        }
        var warnCount = 0
        var failCount = 0
        val warnings = mutableListOf<String>()
        val failures = mutableListOf<String>()
        files.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (markerPattern.containsMatchIn(line)) {
                    if (!prefixPattern.containsMatchIn(line)) {
                        warnings += "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
                        warnCount++
                    }
                    if (prefixPattern.find(line)?.groupValues?.get(1)?.startsWith("P0") == true) {
                        failures += "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
                        failCount++
                    }
                }
            }
        }
        warnings.forEach { logger.warn(it) }
        if (failures.isNotEmpty()) {
            failures.forEach { logger.error(it) }
            throw GradleException(
                "lintTodo 失败：$failCount 处 P0- 前缀标记（Release 分支不允许 P0- 遗留）：\n" +
                    failures.joinToString("\n"),
            )
        }
        logger.lifecycle(
            "lintTodo 完成：扫描 ${files.size} 个文件，$warnCount 处无域前缀标记（warn），$failCount 处 P0- 前缀（fail）",
        )
    }
}

// S4-23：依赖锁定——同一 commit 今天和三个月后构建出的依赖必须一致。
// 首次生成：./gradlew :app:dependencies --write-locks，提交 app/gradle.lockfile；
// CI/supply-chain.yml 用 go mod tidy 检查 Go 侧漂移，Gradle 侧由 lockfile 约束。
dependencyLocking {
    lockAllConfigurations()
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
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // FFmpeg 软解码扩展（GPL-3.0）：SoftwareOnly 档在系统软解缺失时由 FFmpeg 兜底。
    // Auto 档同样以 EXTENSION_RENDERER_MODE_ON 接入，作为硬解失败后的最后兜底。
    implementation(libs.nextlib.media3ext)
    implementation(libs.coil.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Compose UI 测试（instrumentation）：验证面板焦点陷阱等真实焦点行为。
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}






