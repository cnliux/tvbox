# 开源软件许可声明（OSS Notices）

> S2-08：本项目以 Apache-2.0 / GPL-3.0 混合方式分发第三方依赖，按许可证义务在此集中列明。
> 数据来源：`gradle/libs.versions.toml` 与 `platform_live_go/go.mod`（**当前为人工汇总**，
> `tools/gen_oss_notices.py` 自动生成 + CI diff 门禁仍待接入）。
> 具体版本以 `libs.versions.toml` / `go.mod` / `go.sum` 为准；完整版权与许可证原文以各依赖发布包内
> `LICENSE` / `NOTICE` 文件为准（APK 打包时未剔除的第三方声明同样有效）。

## 一、Android / Kotlin 依赖

| 依赖 | 版本（见 version catalog） | 许可证 | 说明 |
|---|---|---|---|
| androidx.activity:activity-compose | 1.13.0 | Apache-2.0 | AndroidX |
| androidx.compose.*（foundation / material3 / runtime / ui / icons） | BOM 2026.05.01 | Apache-2.0 | AndroidX Compose |
| androidx.core:core | 1.18.0 | Apache-2.0 | AndroidX |
| androidx.datastore:datastore-preferences | 1.3.0 | Apache-2.0 | AndroidX |
| androidx.lifecycle:lifecycle-runtime-compose | 2.10.0 | Apache-2.0 | AndroidX |
| androidx.media3:*（exoplayer / session / hls / ui） | 1.10.1 | Apache-2.0 | AndroidX Media3 |
| androidx.window:window | 1.5.0-beta02 | Apache-2.0 | AndroidX |
| androidx.profileinstaller:profileinstaller | 1.4.0 | Apache-2.0 | AndroidX |
| io.coil-kt:coil-compose | 2.7.0 | Apache-2.0 | Coil 图片加载 |
| com.squareup.okhttp3:okhttp | 5.3.2 | Apache-2.0 | OkHttp |
| org.jetbrains.kotlinx:kotlinx-coroutines-android / -test | 1.11.0 | Apache-2.0 | Kotlin 协程 |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.11.0 | Apache-2.0 | Kotlin 序列化 |
| com.google.zxing:core | 3.5.3 | Apache-2.0 | 二维码解析 |
| junit:junit（仅测试） | 4.13.2 | EPL-1.0 | 单元测试 |
| androidx.test:*（仅测试） | runner 1.7.0 | Apache-2.0 | 单元测试 |

## 二、特殊许可证：需要单独注意

| 依赖 | 版本 | 许可证 | 义务提示 |
|---|---|---|---|
| **io.github.anilbeesetti:nextlib-media3ext** | 1.10.1-0.13.0 | **GPL-3.0** | 基于 FFmpeg 的软解码扩展，具有传染性。本 APK 的对应部分须按 GPL-3.0 提供对应源码；分发前请遵循 `docs/design` 中的许可证决策记录。 |

## 三、Go / gomobile 依赖（编入 `libplatformlive.so`）

| 依赖 | 版本（见 go.mod） | 许可证 |
|---|---|---|
| github.com/dop251/goja | v0.0.0-20261102140139-622d10077b6c | MIT |
| github.com/dlclark/regexp2/v2 | v2.11.0 | BSD-2-Clause |
| github.com/go-sourcemap/sourcemap | v2.1.3+incompatible | MIT |
| github.com/google/pprof | v0.0.0-20230207041349-798e818bf904 | BSD-3-Clause |
| golang.org/x/mobile / x/mod / x/sync / x/text / x/tools | v0.42.0 系列 | BSD-3-Clause |

## 四、本项目

- 项目代码按 GPL-3.0 分发（与 `nextlib-media3ext` 的传染性保持一致）；**仓库根目录尚缺 `LICENSE` 全文，待补**。提交即视为以同许可证授权，见 `CONTRIBUTING.md`。
- 本声明随 APK 分发；应用内入口见「设置 → 关于 → 开源许可」（端内页面接入仍待完成）。
- 新增依赖而未在此登记，CI 的 `oss-notices-diff` 门禁（待接入）会拒绝发布。
