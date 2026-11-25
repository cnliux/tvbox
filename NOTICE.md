# NOTICE — 第三方开源软件声明

聚合视频（JuheVideo，本项目）以 **GPL-3.0** 分发。
本项目包含下列第三方开源组件，其许可证与版权声明随 APK 一并分发
（应用内：**设置 → 关于 → 开源许可**，数据来源 `app/src/main/assets/oss_notices.json`）。

> 清单由 `tools/gen_oss_notices.py` 从 `gradle/libs.versions.toml` 与
> `platform_live_go/go.mod` 生成；CI 用 `--check` 保证新增依赖不登记即构建失败。

| 组件 | 许可证 | 版权 / 来源 |
|---|---|---|
| AndroidX Activity / Compose / Core / DataStore / Lifecycle / Media3 / Window / ProfileInstaller | Apache-2.0 | The Android Open Source Project — https://developer.android.com/jetpack |
| Kotlinx Coroutines / Serialization | Apache-2.0 | JetBrains s.r.o. — https://github.com/Kotlin |
| OkHttp | Apache-2.0 | Square, Inc. — https://square.github.io/okhttp/ |
| Coil | Apache-2.0 | Coil contributors — https://coil-kt.github.io/coil/ |
| ZXing Core | Apache-2.0 | ZXing contributors — https://github.com/zxing/zxing |
| nextlib-media3ext（FFmpeg 软解扩展） | **GPL-3.0** | Anil Beesetti and contributors — https://github.com/anilbeesetti/nextlib |
| goja | MIT | dop251 — https://github.com/dop251/goja |
| regexp2 / go-sourcemap | MIT | 各自贡献者 |
| Google pprof / golang.org/x/{mobile,mod,sync,text,tools} | BSD-3-Clause | The Go Authors / Google Inc. |
| JUnit 4 | EPL-1.0 | JUnit contributors（仅测试，不进 APK） |

完整逐条版本与 URL 见应用内页面或 `oss_notices.json`。
若你发现某条许可证标注有误，请按 `SECURITY.md` 的私密渠道或直接开 issue 指出。
