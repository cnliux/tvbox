# 聚合视频 · 本地开发指南（入库版）

> 本文件是**公开版**：只写可复制到任何机器上的命令。你的设备 IP、签名密钥路径、
> `local.properties` 等本机信息请放在 `LOCAL_DEV.local.md`（已 gitignore）。
>
> 新人动线：`just doctor` → 按提示补齐 → `just test` → `just smoke`。
> `doctor` 最后一行一定会给出「还差几步、预计几分钟」，不会把你丢在「然后你去查文档」。

---

## 0. 环境矩阵与「预计 4 分钟」

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| JDK | 17（Temurin / Oracle 均可） | Gradle 9.x 要求 17+ |
| Android SDK | compileSdk 36、build-tools 任意近期版 | `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir` |
| NDK | 26.x（r26d 验证过） | 仅 `bind-platform-live` 剥离 `.so` 符号时需要 |
| Go | 见 `platform_live_go/go.mod` | gomobile bind 与 Go 单测共用 |
| gomobile | `go install golang.org/x/mobile/cmd/gomobile@<见 tools/bind-platform-live.ps1>` | 缺这一步报错是 `gomobile: unknown subcommand` |
| javac | 在 PATH 上 | gomobile bind 会调用它；报错是 `javac not found` |

检查命令：

```bash
./tools/doctor.sh          # Linux / macOS / Git Bash
powershell -ExecutionPolicy Bypass -File tools/doctor.ps1   # Windows
```

`doctor` 会打印每条缺失项的**可复制修复命令**，并给出「还差 N 步，预计 M 分钟」。

---

## 1. 构建快路径（T8-48，置顶）

**改一行 Compose 不要 assemble。** 本仓是单包多端（`DeviceMode` 运行时切换），没有
`TvDebug` / `MobileDebug` flavor，命令里的 `Debug` 就是唯一 UI 变体。

| 场景 | 命令 | 大致耗时 |
| --- | --- | --- |
| 只改 Compose UI | `./gradlew :app:compileDebugKotlin` + Compose Hot Reload | <10s |
| 只改 domain/data 纯 Kotlin | `./gradlew :app:testDebugUnitTest --tests "*EpKey*"` | ~20s |
| 只改 Go | `cd platform_live_go && go test ./... -run TestResolve`（**不 bind**） | ~6s |
| 要看真机 UI | `./gradlew installDebug` + `adb shell am start -n com.tvbox.app/.MainActivity` | ~70s |
| 改了资源/清单 | 才需要全量 `./gradlew assembleDebug` | ~180s |

本地 accelerator（**不入库**，copy 成 `gradle-local.properties` 或写进本机 `gradle.properties`）：

```properties
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx3g -XX:+UseParallelGC
kotlin.incremental.useClasspathSnapshot=true
android.enableGradleWorkers=true
# ⚠️ 别开 android.nonTransitiveRClass=false 之类的老技巧，会和 R8 full mode 打架
```

`tools/watch.sh` 可在 `.kt` 变更时增量编译 + 安装 + 拉起上次的 Activity（电视端抬头即见）。

---

## 2. 三条最常用的完整构建命令

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain  # 日常门禁
./gradlew assembleDebug --console=plain                                  # 出包
powershell -ExecutionPolicy Bypass -File tools/bind-platform-live.ps1    # 我改了 Go，需要重绑 AAR
```

`app/libs/platformlive.aar` **不入库**，由 `tools/bind-platform-live.*` 从
`platform_live_go` 生成。`build.gradle.kts` 用 Go 源码指纹（含 `go.mod`/`go.sum` 与绑定脚本）
决定是否重绑；改了 Go 不重绑会得到「以为修了、其实跑旧 AAR」的包。

如果本机缺 Go：`-PtvboxSkipPlatformBind=true` 会跳过绑定，但**必须事先有 AAR**；
否则 `verifyPlatformBind` 直接失败。该开关产出的包 applicationId 带 `.debugnopl` 烙印。

---

## 3. gomobile 排错表（把踩过的坑逐字记下来）

| 报错原文 | 原因 | 处理 |
| --- | --- | --- |
| `gomobile: unknown subcommand "bind"` | gomobile 未安装或 PATH 不对 | `go install golang.org/x/mobile/cmd/gomobile@...`，确认 `%GOPATH%\bin` 在 PATH |
| `javac not found` | JDK 只装了 JRE 或 JAVA_HOME 指错 | 设 `JAVA_HOME` 到 JDK 17 根目录，把 `%JAVA_HOME%\bin` 放 PATH 最前 |
| `NDK not configured` / `ndk not found` | NDK 未在 SDK 里 | `sdkmanager "ndk;26.3.11579264"` 或设 `ANDROID_NDK_HOME` |
| `ABI mismatch` / 安装后平台直播页无反应 | Go 小版本变化导致 AAR ABI 不兼容 | 删 `app/libs/platformlive.aar` 与 `.fingerprint` 后重绑 |
| `unable to find package for ...` | go.mod 未 tidy | `cd platform_live_go && go mod tidy && go test ./...` |
| `platformlive.aar 与 Go 源码不匹配` | 改了 Go 没重绑（或指纹缓存旧） | 跑 `tools/bind-platform-live.ps1` |
| `必备 assets 校验失败：dy_abogus.js` | 安全软件删了 assets | 从 `platform_live_go/platformassets/` 恢复 |
| `BUILD SUCCESSFUL` 但平台直播点不动 | AAR 是旧的 / skipBind 残包 | 看 applicationId 是否带 `.debugnopl`；重绑 |

---

## 4. 只跑单测 / 只跑一个测试类

```bash
./gradlew :app:testDebugUnitTest --tests "com.tvbox.app.data.network.RetryArbiterTest"
./gradlew :app:testDebugUnitTest --tests "com.tvbox.app.domain.*" --console=plain
cd platform_live_go && go test ./... -count=1
```

JVM 单测里 `android.util.Log` 等框架方法返回默认值（`isReturnDefaultValues = true`），
不要依赖它们的真实行为。

---

## 5. 造一个假源做本地联调

`tools/mock_site.py`（若尚未入库，用任意 30 行 HTTP server 即可）监听 `8899`，
可吐正常 TVBox JSON，也可吐畸形响应（缺字段 / 数字变字符串 / HTML 冒充 JSON / BOM），
用来喂 `T5-34` 的脏语料测试。

```bash
python tools/mock_site.py --port 8899 --evil
# 在 App「数据源 → 自定义接口」里填 http://127.0.0.1:8899/api.php
```

---

## 6. 我改了 Go，需要做什么

1. `cd platform_live_go && go test ./... && go vet ./...`
2. `./gradlew :app:testDebugUnitTest`（Go 桥接签名变动时 Kotlin 侧要同步）
3. 重绑 AAR（见 §3），确认 `app/libs/platformlive.fingerprint` 已更新
4. 只跑 Go 不需要 bind；**只有要真机验证解析结果时**才 bind
5. 动契约（AAR ↔ `platform-live-server`）按 ADR 走灰度，保留 `v1` 解码两个版本

---

## 7. 发版前必跑

```bash
just doctor && just smoke && just audit --fail-on P1
```

`smoke` = `tools/verify_release_smoke.sh` 五道闸（桥接类 / serializer / 焦点资源 /
明文开关 / dex 密钥）；`audit` = T-Q21~T-Q35 探针汇总。
