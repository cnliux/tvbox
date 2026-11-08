# TVBox

TVBox 是一个面向 Android TV / 电视盒子 / 手机的影视播放应用，使用 Kotlin、Jetpack Compose 和 Media3 ExoPlayer 构建。合集版根据设备类型自动适配：电视端强调遥控器操作，手机端提供触屏与竖屏布局，支持影视分类、搜索、详情、m3u8 播放、观看历史、电视直播和 OTA 更新。

当前源码版本为 `1.3.50`（`versionCode=10350`）。合集版统一为手机/电视单包，R8 full mode + 资源压缩；Release 原生库只保留 `arm64-v8a`（不再发布 armv7 与 x86），本机模拟器调试可临时开启 x86 Debug 包。

> 请确保使用的影视与直播接口具备合法授权。本项目仅提供客户端能力，不内置或托管影视内容。

## 界面预览

![TVBox 首页界面预览](docs/screenshots/home-preview.png)

## 功能特性

- Android TV 适配：支持 `LEANBACK_LAUNCHER`，可在电视桌面启动，同时保留普通 Android 启动入口。
- 遥控器友好：方向键、确认键、返回键、菜单键和数字键均有对应交互。
- 首页影视列表：默认展示豆瓣热播剧集，支持海报、评分、分页加载、焦点高亮；点击热播卡片后才按片名从当前影视数据源查找详情与播放资源。
- 主题切换：设置页支持“默认主题”和“影院主题”；默认主题保留原有顶部快捷入口，影院主题提供左侧图标导航和 Hero 推荐布局。
- 视频接口管理：项目不内置任何视频源，资源站接口全部由用户在源管理中导入（订阅地址、扫码或 JSON 文件）；设置页可选择首页数据源。
- 搜索与详情：关键词搜索最多三条来源并行，结果增量去重显示；重新进入搜索页时光标位于旧关键词末尾，可用遥控器退格删除。详情页优先显示主来源，后台补齐播放源和选集。
- AI 找片：支持文字、应用内语音识别、快捷推荐词和“换一批”，可在设置页用手机扫码配置大模型、模型名和 API Key。
- 播放器：基于 Media3 ExoPlayer，仅使用其视频解码与画面窗口；TVBox 自行处理遥控器快退/快进、切集、倍速、播放暂停、自动跳下一集和手机播放手势。
- 画面比例：点播操作栏“画面”支持自适应、裁剪铺满和拉伸铺满，本机记忆并实时生效；播放页隐藏系统栏，退出后恢复。
- 设备模式：设置提供“电视模式”和“手机模式”，默认电视模式，本机保存并优先于系统识别；电视短剧保持横屏，手机模式保留点播转屏和普通直播手机布局。
- 观看历史：记录影片、封面、播放线路、集数、播放进度和更新时间；首页显示最近六部未看完的“继续观看”，原来源优先续播，备用线路后台补齐。
- 返回定位：首页、搜索和历史页面恢复滚动位置与影片焦点；“继续观看”卡片可用方向上返回顶部导航。
- 电视直播：支持 TVBox 文本与 M3U 直播源，主源不可用时自动尝试备用源；支持分组、左右切台、上下切换线路、数字选台，以及线路异常时自动换线。
- 平台直播：支持斗鱼、虎牙、哔哩哔哩、抖音、快手、网易CC、猫耳 FM、SHOWROOM、映客和微博，统一提供平台、一级分类、二级分类、直播间和播放器流程，并按平台返回结果优先选择最高画质；直播间可在本机收藏，在直播入口的“收藏”卡片中直接打开。
- 播放亮屏：所有播放页在前台保持亮屏，暂停时同样生效；退出播放页或切到后台后恢复原设置。
- 播放中断：切到后台或发生音频焦点中断后暂停，返回后由用户手动恢复播放。
- OTA 更新：系统后台下载 APK，缺少系统下载组件时使用应用备用下载；小窗显示进度与授权状态，前台完成且获得授权后打开安装器，也可保存 APK 后通过文件管理器手动安装。
- 内容过滤：过滤伦理、电影解说、演员、新闻资讯等不需要的分类或资源。

## 遥控器快捷键

首页：

| 按键 | 功能 |
| --- | --- |
| 数字 1 | 历史 |
| 数字 2 | 搜索 |
| 数字 3 | AI 推荐 |
| 数字 4 | 电视直播 |
| 数字 5 | 平台直播 |
| 数字 6 | 设置 |
| 方向键 | 移动焦点 |
| 确认键 | 打开当前焦点内容 |
| 下滑到加载更多 | 自动加载下一页 |

播放器：

| 按键 | 功能 |
| --- | --- |
| 确认键 | 展开控制栏，或操作当前聚焦控件 |
| 媒体播放暂停键 | 直接播放或暂停 |
| 方向左 / 右 | 控制栏关闭时每次快退 / 快进 10 秒并支持长按；控制栏打开时导航按钮，聚焦进度条时调整进度 |
| 菜单键 | 切换倍速并显示当前倍速 |
| 数字 1 | 上一集 |
| 数字 3 | 下一集 |
| 返回键 | 依次关闭选择面板、关闭操作栏、返回详情页 |

直播：

| 按键 | 功能 |
| --- | --- |
| 方向左 / 右 | 上一个 / 下一个频道，支持首尾循环 |
| 方向上 / 下 | 切换当前频道的上一条 / 下一条线路 |
| 确认 / 播放暂停 | 显示左侧频道列表 |
| 数字键 | 输入频道号，例如 `1`、`12` |
| 返回键 | 先关闭频道列表，再返回首页 |

## 安装

从 Release 下载最新 APK：

- [Latest Release](https://github.com/cnliux/tvbox/releases/latest)
- OTA 更新清单：`https://ghfast.top/https://raw.githubusercontent.com/cnliux/tvbox/main/update.json`

通过 ADB 安装：

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

如果是从 Release 下载的 APK：

```powershell
adb install -r app-release.apk
```

## OTA 更新机制

应用启动后会请求：

```text
https://ghfast.top/https://raw.githubusercontent.com/cnliux/tvbox/main/update.json
```

`update.json` 示例：

```json
{
  "versionCode": 10323,
  "versionName": "1.3.23",
  "apkUrl": "https://ghfast.top/https://github.com/cnliux/tvbox/releases/download/v1.3.23/app-release.apk",
  "apkSha256": "401bf8d4e76ec188056e73a4690ff4bb7f5ff68715523f3cdbd804beb8d06138",
  "apkSize": 50886726,
  "force": false,
  "changelog": [
    "手机版源管理改为页内管理：设置页移除网页远程管理入口，改为在页内导入配置与增删源。",
    "视频接口/直播源/全部源管理页与源卡片在窄屏自动换行，不再把标题和按钮挤出屏幕。",
    "重做手机版播放控件：进度条、上一集/播放暂停/下一集与选集·线路·倍速·画面·投屏分层等宽排布，按钮不再被折叠。",
    "播放页系统状态栏/导航栏在旋转、下拉通知栏或切后台返回后自动重新隐藏，控件同时吸收安全区。"
  ]
}
```

说明：

- `versionCode` 必须大于当前应用版本，才会提示更新。
- v1.3.23 可通过 1.3.16 及之后版本的 OTA 提示更新，也可手动下载 APK 覆盖安装，保留本机数据。
- 手机用户请到“设置 → 设备模式”选择手机模式；默认电视模式用于避免盒子播放短剧时切成竖屏。
- 已下载的 APK 可从小窗选择“保存安装包”。系统没有文件选择器时保存到下载目录的 `TVBox` 文件夹；Android 9 需存储授权，手动安装仍受系统权限限制。
- 旧版若无法在线更新，可从 Release 页面下载 APK 手动覆盖安装。
- 下载开始后关闭弹窗或按返回不取消任务；后台下载完成时，回到应用后打开安装界面。系统安装仍需用户确认，取消后可从小型进度卡片手动安装；无需悬浮窗权限。
- `apkUrl` 是 APK 下载地址，通过 CDN 代理指向 GitHub Release 附件（国内访问更稳定）。
- `apkSha256` 用于下载完成后的完整性校验。
- `force` 预留强制更新能力，当前普通更新可选择稍后再说。
- `update.json` 放在 GitHub 仓库根目录，随 `main` 分支更新后，应用会通过 CDN 代理地址读取。
- APK 附件上传到 GitHub Release，`update.json` 中的 `apkUrl` 指向对应 GitHub Release 附件。
- `git push` 只会上传代码和 tag，不会自动上传 Release 附件。

## 本地构建

环境要求：

- JDK 17
- Android SDK
- 使用项目内 Gradle Wrapper

AI 找片配置：

- APK 默认使用打包时配置的 `TVBOX_AI_API_KEY` 和内置 Agnes 参数。
- 用户可在设置页选择 Agnes、DeepSeek、SiliconFlow 或 Qwen。
- 模型名称和 API Key 不需要用遥控器输入；点击“模型”或“API Key”按钮后，电视会弹出二维码，手机扫码填写后自动同步到电视。
- 设置页只有手机提交了 API Key 后才会覆盖 APK 内置 AI 配置；未填写时继续使用默认配置。

打包时的默认 API Key 可放在本地配置中：

```properties
TVBOX_AI_API_KEY=你的测试密钥
```

`TVBOX_AI_API_KEY` 可以放在 `local.properties`、Gradle 属性或环境变量中。`local.properties` 已被 `.gitignore` 忽略，不会上传到仓库。

视频接口配置：

- 项目不内置任何视频接口，视频源完全由用户在源管理中导入，导入后不会被覆盖或混入默认数据。
- 设置页“视频接口”可选择当前首页、搜索和 AI 找片使用的数据源。
- 点击“添加接口”后，电视弹出二维码；手机扫码填写接口名称和 MacCms 地址，确认后自动同步到电视。
- MacCms 地址示例：`https://example.com/api.php/provide/vod`

平台直播解析配置：

- 默认使用内置 Go 解析库（`platform_live_go`，经 `gomobile bind` 打包为 `libgojni.so`），安装即可用，无需部署服务端。
- Go 解析库同时提供分享口令/短链解析：`ResolveLinkJson(text, siteHint)`；独立服务端对应 `GET /v1/live/resolve-link?text=<分享链接>&site=<可选平台>`，只访问已登记的平台域名（含重定向逐跳校验），避免被当作任意 URL 代理。
- 发布版只打包 `arm64-v8a` 原生库（`Release` 不再包含 armv7/x86），并对原生库做符号剥离以控制体积。
- 虎牙连续播放：虎牙 CDN 对单次 HTTP-FLV 拉流有约 2 分钟会话上限（实测 120~137 秒被服务端正常关闭）。App 内置 Go 侧本机代理（`platform_live_go/flv_proxy.go` + `FlvContinuityProxy.kt`），播放器只连 `127.0.0.1` 的一条永不结束的 FLV；上游被关闭时代理立即重新解析拿新签名续拉，并把新一段时间戳接在上一帧之后（丢弃回退帧）。实测连续 400 秒、3 次服务端关流均无 ENDED、无换线路。
- 本地模拟器调试：执行 `.\gradlew.bat assembleDebug -PtvboxEmulatorAbis=true`，会额外生成 `x86`/`x86_64` 原生库（gomobile 同时产出 `android/amd64`、`android/386`）。该开关不影响 Release 产物。
- 如需回到远程解析模式，可恢复 `RemotePlatformLiveRepository` 并自行注入服务地址构建参数。

运行测试与构建 Debug（本地只构建 Debug，不构建 Release）：

```powershell
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

> **调试规范：所有本地调试默认使用 `arm64-v8a` Debug 包，与 Release 产物保持一致。仅在需要本机模拟器调试时才加 `-PtvboxEmulatorAbis=true` 开关生成 x86/x86_64 版本。**

如需在本机模拟器调试（x86/x86_64）：

```powershell
.\gradlew.bat assembleDebug -PtvboxEmulatorAbis=true --console=plain
```

Debug 产物位置：

```text
app\build\outputs\apk\debug\app-debug.apk
```

> Release APK 一律由 GitHub Actions 构建与发布（仅 `arm64-v8a`，不再产出 AAB）。本地不要执行 `assembleRelease`，避免产出与 CI 不一致的非发布包。

## Release 签名

release 签名信息从 `local.properties`、Gradle 属性或环境变量读取，密钥文件不会提交到仓库。

需要配置：

```properties
TVBOX_RELEASE_STORE_FILE=signing/xxx.jks
TVBOX_RELEASE_STORE_PASSWORD=***
TVBOX_RELEASE_KEY_ALIAS=***
TVBOX_RELEASE_KEY_PASSWORD=***
```

签名校验：

```powershell
apksigner verify --print-certs app\build\outputs\apk\release\app-release.apk
```

## 发布新版本流程

> 发布包只由 GitHub Actions 构建（`Release` 仅含 `arm64-v8a`）。本地只改代码、跑 Debug 与测试。

1. 更新 `app/build.gradle.kts` 的 `versionCode` / `versionName`，同步 CHANGELOG、README；`update.json` 先保持上一版本，等 CI 发布完成后再一次性更新。
2. 仅暂存本次发布涉及的主工程代码及文档；如开发日志含无关修改，使用分段暂存。

```powershell
git add CHANGELOG.md README.md docs\slim-optimization-report.md app\build.gradle.kts app\src\main app\src\test app\src\debug tools\bind-platform-live.ps1 tools\bind-platform-live.sh .github\workflows\release.yml gradle.properties gradle\libs.versions.toml app\proguard-rules.pro
git commit -m "Release v1.3.27"
git push origin main
git tag -a v1.3.27 -m "TVBox v1.3.27"
git push origin v1.3.27
```

3. tag 推送后，GitHub Actions 自动构建并发布 `app-release.apk`（`arm64-v8a`）。
4. CI 发布完成后，从 Release 附件回填 `update.json` 的 `apkSha256` / `apkSize`，再提交并推送 `main`，使 OTA 清单指向已可下载的附件。

```powershell
# 例：下载 CI 产物后计算
(Get-FileHash .\app-release.apk -Algorithm SHA256).Hash.ToLower()
(Get-Item .\app-release.apk).Length
```

5. 若需要校验发布包的 ABI：

```powershell
aapt2 dump badging app-release.apk | Select-String "native-code"
# 期望输出：native-code: 'arm64-v8a'
```

> Release 附件由 CI 上传；`git push` 只上传代码和 tag，不会自动上传 APK。

## 项目结构

```text
app/src/main/java/com/tvbox/app
├── data        # API、仓库、历史记录、OTA、直播数据
├── domain      # 领域模型与解析规则
├── ui          # Compose 页面、播放器、直播页
└── MainActivity.kt
```

## 技术栈

- Kotlin
- Jetpack Compose
- Compose for TV 能力（聚焦/遥控器交互，按需自实现）
- Media3 ExoPlayer
- OkHttp
- kotlinx.serialization
- Coil

## 版本记录

详细变更见 [CHANGELOG.md](CHANGELOG.md)。

## 🔗 友情链接 / Friends

- [LINUX DO](https://linux.do/) — 新的理想型社区 / A new ideal community

