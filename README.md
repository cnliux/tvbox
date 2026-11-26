# 聚合视频

聚合视频（原 TVBox）是一个面向 Android TV / 电视盒子 / 手机的单包多端影视播放应用，
兼容 Android 7.0（API 24）及以上，使用 Kotlin、Jetpack Compose 与 Media3 ExoPlayer 构建。同一份 APK 通过运行时设备模式
在电视与手机之间切换布局与交互：电视端以遥控器操作优先，手机端提供触屏手势与竖屏布局。

当前版本以发布产物内的 `update.json` 为准（构建版本号见 `app/build.gradle.kts` 的 `TVBOX_VERSION_NAME` / `TVBOX_VERSION_CODE`，当前为 `1.0.49` / `20049`），不在此处写死以免漂移。Release 原生库只保留 `arm64-v8a`，
启用 R8 full mode 与资源压缩。

### 身份卡（认准这三样，别装错包）

| 项目 | 值 |
| --- | --- |
| 唯一官方源码/发布渠道 | `https://github.com/cnliux/tvbox`（Release 资产名 `juhe-video-v*.apk`） |
| 包名（applicationId） | `com.tvbox.app` |
| 签名指纹校验 | 以 GitHub Release 描述置顶的 SHA-256 前 8 字节为准；本地可用 `apksigner verify --print-certs <apk>` 核对 |
| 官方 OTA 清单 | `https://github.com/cnliux/tvbox` 仓库根目录 `update.json`（校验 SHA-256，缺失即拒绝安装） |

> 本项目是 `cnliux/tvbox` fork，不再使用上游 `ZxxXinI/tvbox` 的分发渠道。
> 任何要求你「关闭校验 / 装另一个签名包 / 先转账再给订阅」的渠道都不是官方渠道。
> 商标与命名边界见 [TRADEMARKS.md](TRADEMARKS.md)。

> 许可证：GPL-3.0（本项目） · 第三方清单见 [NOTICE.md](NOTICE.md) 与应用内「设置 → 关于 → 开源许可」。

> 本项目只提供客户端播放能力，不内置、不托管任何影视内容。请确保所使用的接口与
> 直播源具备合法授权。

## 功能组成

- **影视浏览**：豆瓣热播列表、海报与评分、分页加载、焦点高亮；点击后才按片名到当前
  数据源查找详情与播放资源。
- **源管理**：不内置视频源，接口全部由用户导入（订阅地址、扫码或 JSON 文件）。
- **搜索与详情**：多源并行搜索、增量去重；详情页优先展示主来源，后台补齐播放源与选集。
- **播放器**：Media3 ExoPlayer 负责解码与画面窗口，遥控器快进快退、切集、倍速、
  画面比例、自动下一集与手机手势由应用自行处理。
- **观看历史**：记录影片、线路、集数、进度；首页展示"继续观看"，优先沿用原来源。
- **电视直播**：支持 TVBox 文本与 M3U 源，分组、上下切台、左右换线、数字选台，
  主源不可用时自动尝试备用源。
- **平台直播**：虎牙、斗鱼、哔哩哔哩、抖音、快手、网易 CC、猫耳 FM、SHOWROOM、
  映客、微博，统一提供平台 → 一级分类 → 二级分类 → 直播间 → 播放器流程。
- **OTA 更新**：后台下载 APK，校验 SHA-256 后调用系统安装器。
- **设备模式与主题**：电视 / 手机模式本机记忆并优先于系统识别；统一默认主题，字号可单独调节。

## 遥控器快捷键

首页：

| 按键 | 功能 |
| --- | --- |
| 数字 1 / 2 / 4 / 5 / 6 | 历史 / 搜索 / 电视直播 / 平台直播 / 设置 |
| 方向键 | 移动焦点；列表底部继续下按加载下一页 |
| 确认键 | 打开当前焦点内容 |

点播播放器：

| 按键 | 功能 |
| --- | --- |
| 确认键 | 展开控制栏，或操作当前聚焦控件 |
| 媒体播放暂停键 | 直接播放或暂停 |
| 方向左 / 右 | 控制栏关闭时快退 / 快进 10 秒（可长按）；打开时导航控件 |
| 菜单键 | 切换倍速 |
| 数字 1 / 3 | 上一集 / 下一集 |
| 返回键 | 依次关闭面板、关闭操作栏、返回详情 |

电视直播播放器：

| 按键 | 功能 |
| --- | --- |
| 方向上 / 下 | 上一个 / 下一个频道 |
| 方向左 / 右 | 上一条 / 下一条线路 |
| 确认键 | 打开线路浮层（控制栏） |
| 菜单键 | 打开频道列表 |
| 数字 0 / 9 | 频道号直选，2 秒无输入自动跳转，确认键立即跳转，返回键清空 |
| 返回键 | 清空台号输入 → 关闭浮层 → 退出播放页 |

平台直播播放器：

| 按键 | 功能 |
| --- | --- |
| 方向左 / 右 | 上一个 / 下一个房间 |
| 方向上 / 下 | 切换候选线路 |
| 确认键 | 打开「节目列表」并进入房间导航 |
| 节目列表内 ▲ / ▼ | 上下切换房间，确认键播放并返回播放页 |
| 返回键 | 逐层收起浮层，最后退出播放页 |

## 架构

```text
app/src/main/java/com/tvbox/app
├── data       # 仓库、网络、直播数据、OTA、历史
├── domain     # 领域模型与解析规则
├── ui         # Compose 页面、播放器、直播页
├── util       # 通用工具
├── crash      # 崩溃捕获
├── MainActivity.kt
└── TvBoxApplication.kt
```

平台直播解析有两份同源代码形态：

| 形态 | 位置 | 用途 |
| --- | --- | --- |
| gomobile AAR | `platform_live_go` → `app/libs/platformlive.aar` | APK 内置解析与连续播放代理 |
| HTTP 服务 | `platform_live_go/cmd/platform-live-server` | 局域网解析服务，供工具与排查复用 |

两者共用同一套 resolver 与 FLV 代理代码，不存在"服务端一套、客户端另一套"的偏差。

## 连续播放代理（虎牙 / 斗鱼）

虎牙对单次 HTTP-FLV 拉流有约 120 秒会话上限（实测 120.7s 被服务端正常关闭），
斗鱼用 `expire` 租约到期断流。播放器直连平台地址时，每两分钟必然经历
`ENDED → 重新解析 → 重新 prepare` 的可见断流。

Go 侧代理对播放器只暴露一条永不结束的本地 FLV（`127.0.0.1:<临时端口>/live.flv`），
上游断开就用新签名续拉，并把新一段时间轴接在已交付位置之后：

- **回放识别**：按 FLV tag 内容指纹（FNV-1a）判重。时间轴被强制单调，回放帧在
  时间戳上完全看不出来，只能靠帧内容判断。
- **自适应窗口**：窗口起点为各平台默认值（虎牙 12s / 斗鱼 8s / 通用 10s）。
  命中回放时按**单段**源时间轴跨度评估，达到当前窗口 60% 即翻倍，封顶 24s。
- **音频回拉**：音频领先视频超过 700ms 时整体回拉，落到已交付位置之前则钳位
  而不是丢帧——逐帧丢音频会造成断续，钳位不会让时间轴停住。
- **分段调参**：虎牙 / 斗鱼 / 其它平台三档互斥，其它平台不借用前两者的站点假设。

`FlvContinuityProxy` 只有 **Go 代理** 一条路径；启动失败时返回 null，调用方退回直连
平台地址（直连只是每两分钟重连一次，仍可正常播放）。

历史上曾有一条 Kotlin 本机中继作为兜底，但它在 JVM 端到端测试中能通过、Android 真机
却必定报 `ERROR_CODE_IO_UNSPECIFIED`，从未验证可用；留着等于把"兜底"做成"一定失败"，
因此已整体移除（`LiveContinuityRelay`、`FlvSplicer`、`FlvTagReader` 及其测试，约 1180 行）。

算法细节与实测数据见 `platform_live_go/FLV_CONTINUITY_VALIDATION.md`。

## 构建

环境要求：

- JDK 17
- Android SDK（`compileSdk 36`，最小 `minSdk 24`）
- Go 1.22+ 与 NDK（仅当需要重新生成 AAR）
- 使用项目内 Gradle Wrapper

```powershell
# 单元测试 + Debug 包
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

`app/libs/platformlive.aar` 不入库，构建时按 Go 源码哈希决定是否重新 `gomobile bind`。
本机缺少 Go 工具链时可跳过绑定，但需事先准备 AAR：

```powershell
.\gradlew.bat assembleDebug -PtvboxSkipPlatformBind=true --console=plain
```

重新生成 AAR：

```powershell
powershell -ExecutionPolicy Bypass -File tools/bind-platform-live.ps1
```

该脚本要求 `go`、`gomobile`、`javac` 均在 PATH 中；Windows 上需把 JDK 的 `bin`
目录加入 PATH，否则会在 `gomobile bind` 阶段报 `javac` 找不到。

模拟器调试（额外产出 x86/x86_64，仅 Debug）：

```powershell
.\gradlew.bat assembleDebug -PtvboxEmulatorAbis=true --console=plain
```

Release 只由 CI 构建，本地不要执行 `assembleRelease`。

## 本地运行解析服务

```powershell
cd platform_live_go
go run ./cmd/platform-live-server -host 0.0.0.0 -port 8868
```

| 接口 | 说明 |
| --- | --- |
| `GET /health` | 服务状态与版本 |
| `GET /v1/live/sites` | 平台列表 |
| `GET /v1/live/categories?site=douyu` | 分类树 |
| `GET /v1/live/rooms?site=douyu&roomId=...` | 房间列表 |
| `GET /v1/live/resolve?site=huya&roomId=...` | 解析播放地址（`streams` 为候选线路） |
| `GET /v1/live/resolve-link?text=...` | 分享口令 / 短链解析 |

## 长跑监控

`tools/live_longrun_monitor.py` 对真实 CDN 做多轮随机房间长跑，统计会话时长、
回放帧、时间轴回退与音画偏移。它通过上面的 Go 服务取签名，服务不可用时会自行拉起：

```powershell
python tools/live_longrun_monitor.py --rounds 6 --rooms 10 --seconds 600
```

结果写入 `.tmp_dbg/longrun/`。

## 发布流程

1. 升 `app/build.gradle.kts` 的 `versionName` / `versionCode`，同步 `CHANGELOG.md`。
2. 提交并推送 `main`，然后打 tag：

```powershell
git tag -a v1.0.24 -m "聚合视频 v1.0.24"
git push origin v1.0.24
```

3. `.github/workflows/release.yml` 由 `v*` tag 触发，构建 `juhe-video-v<version>.apk`
   并发布 Release，随后自动提交 `update.json`。
4. 发布后核对 Release 资产与线上 `update.json`，不要仅凭 tag 判定成功。

签名信息从 `local.properties`、Gradle 属性或环境变量读取，密钥文件不入库：

```properties
TVBOX_RELEASE_STORE_FILE=signing/xxx.jks
TVBOX_RELEASE_STORE_PASSWORD=***
TVBOX_RELEASE_KEY_ALIAS=***
TVBOX_RELEASE_KEY_PASSWORD=***
```

## 文档

- 变更记录：[CHANGELOG.md](CHANGELOG.md)
- 隐私与数据处理：[PRIVACY.md](PRIVACY.md)
- 安全披露：[SECURITY.md](SECURITY.md) · 与上游的关系：[UPSTREAM.md](UPSTREAM.md)
- 第三方许可：[OSS_NOTICES.md](OSS_NOTICES.md)（应用内入口：设置 → 关于 → 开源许可）
- 连续播放验证：[platform_live_go/FLV_CONTINUITY_VALIDATION.md](platform_live_go/FLV_CONTINUITY_VALIDATION.md)
- 本地调试：`LOCAL_DEV.md`（本地保留，不入库）

代码审查、缺陷修复报告、方案计划等过程文档只在本地保留，不入版本库。
