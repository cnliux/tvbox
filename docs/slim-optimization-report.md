# TVBox 极限瘦身与合集版优化报告

> 日期：2026-11-03
> 基线：`app/build/outputs/apk/universal/release/app-universal-release.apk`（1.3.25，本地 universal 产物，92,015,725 字节）
> 优化后：1.3.27 合集版 + 仅 `arm64-v8a`（18,613,098 字节）。正式 Release 由 GitHub Actions 构建与发布，本地只构建 Debug。

## 1. 结论

| 指标 | 瘦身前 | 瘦身后 | 变化 |
|---|---:|---:|---:|
| Release APK | 92,015,725 B（87.75 MiB） | 18,613,098 B（17.75 MiB） | **-73,402,627 B（-79.77%）** |
| Release AAB | 未产出 | 由 CI 构建（按 ABI/语言拆分下发） | 单设备只下发 arm64-v8a |
| `platformlive.aar` | 34,524,960 B（3 ABI、未剥离） | 11,524,281 B（2 ABI、已剥离，本地） | **-66.62%**；CI 仅 bind arm64 后更小 |
| 上一版 OTA APK（`update.json`） | 74,540,357 B | 18,613,098 B | **-75.0%** |

瘦身收益主要来自三处：

1. 删除 x86_64 / armeabi-v7a 与重复/未使用 native 库：-59.3 MB（相对基线）；
2. Go `libgojni.so` 去掉调试符号：单 ABI 29.57 MB → 15.96 MB（-46.0%）；
3. 语言资源裁剪、assets/META-INF/Kotlin 元数据清理：约 -0.37 MB。

## 2. TV / 手机 / 合集版边界分析

项目原本没有 `app/src/tv`、`app/src/mobile` 独立源集，三个 product flavor 全部复用 `app/src/main`，仅在 `applicationId`、`versionNameSuffix` 上有差异。真正的 TV/手机适配已经由运行时设备模式判断完成：

- `app/src/main/java/com/tvbox/app/domain/AppSettings.kt`：`DeviceMode.Television` / `DeviceMode.Mobile` 用户设置；
- `app/src/main/java/com/tvbox/app/ui/DeviceModeDetection.kt`：通过 `UI_MODE_TYPE_TELEVISION`、`FEATURE_LEANBACK`、`FEATURE_TELEVISION` 自动识别电视；
- `MainActivity.kt`、`TvBoxApp.kt`、`PlayerControls.kt`、`MobileNavigationBar.kt` 通过 `DeviceMode` 切换横竖屏、焦点/触控、手机底部导航与播放器控制。

因此本次“删除 TV 版/手机版”实际是删除 **flavor 配置与对应产物/CI 发布逻辑**，而不是删除运行时代码；合集版保留全部设备适配分支。

## 3. 删除与合并清单

### 3.1 版本/flavor

- 删除 `flavorDimensions("device")` 与 `productFlavors { universal / tv / mobile }`；
- 删除 tv 专属包名 `com.tvbox.app.tv`、mobile 专属包名 `com.tvbox.app.mobile` 及 `-tv/-mobile` 版本后缀；
- 删除 `manifestPlaceholders["appIcon"]` 与不存在的 `@mipmap/ic_launcher_mobile` 引用；
- CI 与 README 同步为单一合集版：仅产出 `app-release.apk` + `app-release.aab`。

### 3.2 依赖

| 依赖 | 处理 | 原因 |
|---|---|---|
| `androidx.tv:tv-foundation` | 删除 | 全项目无 `androidx.tv.*` 引用 |
| `androidx.tv:tv-material` | 删除 | 全项目无 `androidx.tv.*` 引用 |
| `com.squareup.retrofit2:retrofit` | 删除并替换 | 仅 `MacCmsApi` 使用，改为 OkHttp + kotlinx.serialization 轻量实现 |
| `com.squareup.retrofit2:converter-kotlinx-serialization` | 删除 | 同上 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 删除 | 无 `viewModel()` / `androidx.lifecycle.viewmodel.compose` 引用 |
| `androidx.compose.ui:ui-tooling` / `ui-tooling-preview` | 删除 | 无 `@Preview`，仅 debug 工具链 |
| `org.jetbrains.kotlin.android` Gradle 插件别名 | 删除 | AGP 9 已内置 Kotlin，构建未使用该插件 |
| `androidx.compose.material:material-icons-core` | 显式补回 | 原由 `tv-material` 传递提供；合集版仍使用 `Icons.Filled.*` |

### 3.3 代码/资源

- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`：删除（内容与 `ic_launcher.xml` 重复，`roundIcon` 改为引用 `@mipmap/ic_launcher`）。
- `app/src/main/java/com/tvbox/app/ui/components/Common.kt`：删除从未被引用的 `TinySpacer()`。
- 清理 8 个文件中的未使用/重复 import：
  - `data/SourceConfigCodec.kt`（`JsonElement`）
  - `ui/AiRecommendScreen.kt`（`Row`、`AlertDialog`、`Button`、`ButtonDefaults`）
  - `ui/LiveScreen.kt`（`tvFocusScale`）
  - `ui/MobileNavigationBar.kt`（`background`）
  - `ui/PlayerControls.kt`（`KeyEvent`）
  - `ui/TvBoxApp.kt`（重复 `Divider`、重复 `items`、`LazyListState`、`rememberLazyListState`、`collectAsState`、`LocalClipboardManager`）
- `app/build.gradle.kts`：删除未使用的 `PLATFORM_LIVE_SERVICE_URL` buildConfig 字段及对应 `configProperty`。
- `AndroidManifest.xml`：删除强制 `screenOrientation="landscape"`；补充 `microphone`、`wifi` 为 `required=false`，避免合集版在无麦/无 Wi-Fi 设备上被商店过滤。

### 3.4 资源/清单残留

- `resConfigs("zh")` 只保留中文语系，AndroidX/Media3 的其他语言字符串被裁剪；
- `packaging.resources.excludes` 清理 `META-INF` 的 LICENSE/NOTICE/`.version`、`kotlin/**`、`DebugProbesKt.bin`；
- `androidResources.ignoreAssetsPatterns += "!PublicSuffixDatabase.list"`：R8 已删除 OkHttp `PublicSuffixDatabase`，对应 assets 同步剔除（约 42 KB）；
- 保留 `assets/douyin_sign.js`、`platform_live_catalog.json`、`dexopt/baseline.prof`：均为运行/基线配置所需。

## 4. 压缩与构建优化清单

### 4.1 资源与产物

- `isMinifyEnabled = true`、`isShrinkResources = true`（release）；
- `resConfigs(listOf("zh"))`；
- `packaging.resources.excludes` 白名单式清理（见上）；
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`；
- `androidResources.ignoreAssetsPatterns` 剔除无用 assets；
- 删除重复 launcher 图标；项目本身无位图 PNG，无需 WebP 转换；
- ABI 按构建类型拆分：Release/AAB 仅 `abiFilters += ["arm64-v8a"]`；Debug 默认同样只含 arm64，加 `-PtvboxEmulatorAbis=true` 时才追加 `x86`/`x86_64` 供本地模拟器调试。

### 4.2 DEX / R8

- `android.enableR8.fullMode=true`、`android.r8.optimizedResourceShrinking=true`、`android.enableResourceOptimizations=true`；
- `android.nonTransitiveRClass=true`、`android.nonFinalResIds=true`；
- `proguard-rules.pro` 精简为：
  - `-allowaccessmodification`；
  - release 下删除 `Log.v/d/i/w` 调用（保留 `Log.e`）；
  - 仅保留 gomobile `platformlive.**` / `go.**`；
  - 删除 Retrofit 相关 keep / `Signature` / 注解 keep（kotlinx.serialization 自带 R8 规则）；
  - 补充 OkHttp 可选 TLS 平台的 `-dontwarn`。
- `buildFeatures` 关闭 `viewBinding`、`dataBinding`、`aidl`、`shaders`，仅开启 `compose` 与 `buildConfig`。

### 4.3 Native 库

- `tools/bind-platform-live.ps1` / `.sh`：
  - 默认 `-target android/arm64`（Release 不再绑定 arm/armv7，也不再绑定 amd64）；
  - 本地模拟器调试时设置 `TVBOX_EMULATOR_ABIS=1`（Gradle 侧为 `-PtvboxEmulatorAbis=true`）才追加 `android/amd64,android/386`；
  - `-ldflags="-s -w"`、`-trimpath`；
- `app/build.gradle.kts` 新增 `stripPlatformLiveAar`：
  - 在 `preBuild` 前用 NDK `llvm-strip --strip-unneeded` 对 AAR 内所有 `.so` 剥离符号；
  - 不再改写 AAR 的 ABI 组成（由 gomobile target 与 `abiFilters` 决定），保留 x86 Debug 所需的库；
  - 使用 `build/platformlive-aar-strip.stamp` 避免重复改写。
- 说明：Windows 下 AGP 自带 `stripReleaseDebugSymbols` 对 Go `.so` 报 “Unable to strip”，本项目任务作为兜底，CI 与本地行为一致。

## 5. 合集版关键保留文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/tvbox/app/MainActivity.kt` | 单一入口，按设备模式设置横竖屏 |
| `app/src/main/java/com/tvbox/app/ui/DeviceModeDetection.kt` | TV/手机运行时识别 |
| `app/src/main/java/com/tvbox/app/domain/AppSettings.kt` | `DeviceMode` 与用户手动切换 |
| `app/src/main/java/com/tvbox/app/ui/TvBoxApp.kt` | 合集版主界面与导航 |
| `app/src/main/java/com/tvbox/app/ui/MobileNavigationBar.kt` | 手机底部导航（仅手机模式显示） |
| `app/src/main/java/com/tvbox/app/ui/PlayerControls.kt` / `PlayerScreen.kt` | 电视遥控焦点与手机触控共用播放器 |
| `app/src/main/java/com/tvbox/app/ui/theme/Theme.kt` | TV/手机共用主题与尺寸令牌 |
| `app/src/main/java/com/tvbox/app/data/PlatformLiveGoBridge.kt` | 内置 Go 平台直播解析桥接 |
| `app/src/main/AndroidManifest.xml` | 同时声明 `LAUNCHER` + `LEANBACK_LAUNCHER`，兼容手机与电视 |

## 6. 体积对比报告

### 6.1 APK 分模块（压缩后字节）

| 模块 | 瘦身前 | 瘦身后 | 变化 | 变化率 |
|---|---:|---:|---:|---:|
| `lib/arm64-v8a` | 29,568,744 | 15,964,096 | -13,604,648 | -46.01% |
| `lib/armeabi-v7a` | 28,677,032 | 0 | -28,677,032 | -100% |
| `lib/x86_64` | 30,664,872 | 0 | -30,664,872 | -100% |
| `classes.dex` | 2,518,884 | 2,483,234 | -35,650 | -1.42% |
| `resources.arsc` + Manifest（`other`） | 336,402 | 41,779 | -294,623 | -87.58% |
| `res/` | 65,551 | 65,330 | -221 | -0.34% |
| `assets/` | 60,381 | 17,615 | -42,766 | -70.83% |
| `META-INF/` | 18,889 | 326 | -18,563 | -98.27% |
| `kotlin/` 元数据 | 12,350 | 0 | -12,350 | -100% |
| **合计** | **92,015,725** | **18,613,098** | **-73,402,627** | **-79.77%** |

补充：native 库合计 88,910,648 → 15,964,096 字节（-82.04%）；非 native 部分 3,105,077 → 2,649,002 字节（-14.69%）。

### 6.2 AAB / AAR

| 产物 | 瘦身前 | 瘦身后 | 变化 |
|---|---:|---:|---:|
| `app-release.aab` | 未产出 | 由 CI 构建发布 | 只含 `base/lib/arm64-v8a` |
| `platformlive.aar` | 34,524,960 B | 11,524,281 B（本地双 ABI） | -66.62%；CI 单 arm64 更小 |

AAB 只含 `base/lib/arm64-v8a`（无 armeabi-v7a / x86 / x86_64），不含 `PublicSuffixDatabase.list`。

## 7. 验证结果

已实际执行并通过：

```text
.\gradlew.bat assembleRelease -PtvboxSkipPlatformBind=true
BUILD SUCCESSFUL（lintVitalRelease 通过；仅用于确认 ABI 收敛后的体积）

.\gradlew.bat testDebugUnitTest -PtvboxSkipPlatformBind=true
BUILD SUCCESSFUL，17 个测试类 / 100 个测试用例，0 failure / 0 error
```

APK 校验：

```text
package=com.tvbox.app  versionCode=10327  versionName=1.3.27  minSdk=24  targetSdk=36
launchable-activity: com.tvbox.app.MainActivity
leanback-launchable-activity: com.tvbox.app.MainActivity
native-code: 'arm64-v8a'
uses-feature-not-required: microphone / touchscreen / wifi / leanback
apksigner: Verifies；APK Signature Scheme v2 = true
```

产物 SHA-256（策略验证时的本地临时构建；正式发布包由 CI 构建，SHA 以 Release 附件为准）：

```text
APK  6672B9826F7964557F76A8AF658803CF9A723814503DBA771B3533CD88C214C
```

## 8. 注意事项

1. `app/libs/platformlive.aar` 是 gitignore 的生成物；CI 会重新执行 `gomobile bind`（默认只 `android/arm64`），本次加了 `-ldflags="-s -w"` 与 Gradle `llvm-strip` 兜底。
2. 发布策略：本地**不再构建 Release**，只构建 Debug；Release/OTA 包必须由 GitHub Actions（`.github/workflows/release.yml`）在 tag 推送后构建并发布 `arm64-v8a` 版本。armv7 与 x86 不再发布。
3. 模拟器调试需同时满足两个条件：`-PtvboxEmulatorAbis=true` 且本机 gomobile 能联网生成 x86/x86_64（`gomobile init` 依赖 `golang.org/x/mobile` 模块）；否则 Debug 包仍只含 arm64。
4. 正式发版后需要回填 `update.json` 的 `apkSha256` / `apkSize`（取自 CI 上传的 Release 附件），未回填前留空会跳过校验。
5. `resourceConfigurations`（`resConfigs`）在 AGP 9.2 中已标记 deprecated 但仍可用；后续 AGP 10 可迁移到 `androidResources.localeFilters`。
