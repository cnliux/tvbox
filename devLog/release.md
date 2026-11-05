# Release - 2026-06-25

## 2026-11-05 - 发布 v1.3.33：直播卡片固定 4 列与 versionCode 校正

### 文件修改、原因与目的

- `app/build.gradle.kts`：版本升级至 `10333 / 1.3.33`。根因：v1.3.32/v1.3.33 两个功能提交只改了 `versionName`，`versionCode` 一直是 `10331`；OTA 以 `versionCode` 比较大小，已装 1.3.31 的用户不会收到更新提示。
- `ui/LiveScreen.kt`、`ui/PlatformLiveScreen.kt`：手机触摸模式直播卡片统一为 `GridCells.Fixed(4)`（已随 f8ea514 提交）。
- `CHANGELOG.md`、`README.md`、`update.json`：同步 v1.3.33 与 OTA 清单。

### 构建与发布校验

- 本地 `assembleDebug` / `assembleRelease`（含 R8、gomobile 绑定）通过；`testDebugUnitTest` 因孤儿测试 `DeviceModeTest.kt` 引用已删除的 `DeviceMode.Auto/detect/resolveEffective` 编译失败，未纳入提交（待与设备模式重构一并处理）。
- CI 首次构建（run 41953776655）在 `Build release APK` 步骤约 70 秒失败，为 runner 拉取 Go 模块网络抖动；本地全链路复现无问题，删除并重推 `v1.3.33` tag 后重跑（run 41956421102）成功，发布 [TVBox v1.3.33](https://github.com/cnliux/tvbox/releases/tag/v1.3.33)。
- Release 附件 `app-release.apk`：`19510214` 字节（与 1.3.31 同大小系巧合），下载后 `aapt2 dump badging` 确认包内 `versionCode=10333 versionName=1.3.33`、`native-code: 'arm64-v8a'`；本地计算 SHA-256 `7dac1949bd0b8ef2071dc132dc467fa2606f5c8f52e1d5e3c546c4900e0dadff`，已回填 `update.json`。

### 导航

- 主日志：`devLog/README.md`

## 2026-11-02 - 发布 v1.3.23：手机版源管理页内化、窄屏自适应与播放控件重做

### 文件修改、原因与目的

- `ui/TvBoxApp.kt`：手机设置页删除"手机远程管理（网页）"入口，"首页渲染数据"卡的添加入口改为进入视频接口管理页；视频接口管理页在手机版隐藏网页配置入口与"添加接口"按钮，改由页内"导入配置"粘贴订阅地址/JSON（`HomeApiLineSetting.onOpenConfig` 改为可空）。新增 `ManageScreenHeader`：三个管理页头部在手机版竖向堆叠、按钮 `FlowRow` 换行；`SourceManagerSetting` 工具按钮流式换行，手机版源卡片把测速/屏蔽/删除放到源名下方；手机版源选择下拉补 `weight(1f)`。
- `ui/DetailScreen.kt`：手机版片名 `headlineLarge → titleLarge`，类型/年份/备注标签改 `FlowRow` 换行，避免与右侧按钮抢空间。
- `ui/PlayerControls.kt`：重写 `PlayerControlsMobile`——顶部返回/片名栏，底部"进度条 → 上一集/圆形播放暂停/下一集 → 选集·线路·倍速·画面·投屏"三层；竖屏等宽均分、横屏横滑；新增 `MobileSectionPanel` 单面板（选集/线路/倍速/画面可切换收起）、`MobileChip`、`MobilePlayPauseButton`（Canvas 绘制，免新增图标依赖）；进度条拖动改为本地预览、松手才 seek。根因：原实现 6 个按钮挤在一行 `SpaceEvenly`，窄屏被折叠成一字宽。
- `ui/PlayerViewport.kt`：新增每 3 秒系统栏巡检（`IMMERSIVE_REHIDE_INTERVAL_MS`），旋转、下拉通知栏、切后台返回后重新出现的状态栏/导航栏会再次隐藏；退出仍恢复原状态。配合控件 `safeDrawing`，系统导航栏不再压住底部按钮。
- `app/build.gradle.kts`：版本升级至 `10323 / 1.3.23`；`CHANGELOG.md`、`README.md`、`update.json` 同步。

### 构建与发布校验

- 本地 `:app:compileDebugKotlin` 通过。
- CI 发布：推送 `v1.3.23` 标签触发 `.github/workflows/release.yml` 成功，发布 [TVBox v1.3.23](https://github.com/cnliux/tvbox/releases/tag/v1.3.23)。
- Release 附件 `app-release.apk`：`50886726` 字节，下载后本地计算 SHA-256 `401bf8d4e76ec188056e73a4690ff4bb7f5ff68715523f3cdbd804beb8d06138`，已回填 `update.json` 与 README 示例。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/mobile-mode.md`。

## 2026-11-02 - 发布 v1.3.22：手机版海报片名不可见修复

### 文件修改、原因与目的

- `ui/components/Common.kt`：`MoviePosterCard` 与 `DoubanHotPosterCard` 片名显式 `onSurface` 前景色（与 v1.3.20 设置页不可见文字同一根因：手机版 edge-to-edge 下默认内容色在深色卡片上几乎同色），保留最多两行自适应换行。
- `app/build.gradle.kts`：版本升级至 `10322 / 1.3.22`；`CHANGELOG.md`、`README.md`、`update.json` 同步。

### 构建与发布校验

- 本地 `:app:compileDebugKotlin` 通过。
- CI 发布：推送 `v1.3.22` 标签触发 `.github/workflows/release.yml` 成功，发布 [TVBox v1.3.22](https://github.com/cnliux/tvbox/releases/tag/v1.3.22)。
- Release 附件 `app-release.apk`：`50886786` 字节，下载后本地计算 SHA-256 `d398736d08555a345cf493ef894008cece0df489d2b81d46b7a1468c16904511`，与大小一致；`update.json` 与 README 示例按该附件实际值更新。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/mobile-mode.md`。

## 2026-11-02 - 发布 v1.3.21：视频卡片只显示节目名称与手机版直播大卡片自适应

### 文件修改、原因与目的

- `ui/components/Common.kt`：`MoviePosterCard` 与 `DoubanHotPosterCard` 删除第二行"年份 / 地区 / 语言 / 备注"信息（用户反馈卡片应显示节目名称而不是年份等信息），片名改最多两行完整显示；更新状态仍保留在海报角标。
- `ui/SearchScreen.kt`：搜索结果卡片同样去掉 `movie.subtitle` 年份信息行，保留来源角标与更新状态角标。
- `ui/PlatformLiveScreen.kt`：手机版房间列表基准列数 4→2（大卡片、标题不再挤成单字）；触摸模式列数按屏宽分级自适应（≥500dp +1、≥720dp +2），站点/分类页同步受益。
- `app/build.gradle.kts`：版本升级至 `10321 / 1.3.21`；`CHANGELOG.md`、`README.md`、`update.json` 同步。

### 构建与发布校验

- 本地 `:app:compileDebugKotlin` 通过。
- CI 发布：推送 `v1.3.21` 标签触发 `.github/workflows/release.yml`（run `41387695064`）成功，发布 [TVBox v1.3.21](https://github.com/cnliux/tvbox/releases/tag/v1.3.21)。
- Release 附件 `app-release.apk`：`50886698` 字节，下载后本地计算 SHA-256 `701fb1146c8c99329aa09d4f82a55596c45a6e67f0d139d89f28b59f8d8e74e2`，与大小一致；`update.json` 与 README 示例按该附件实际值更新。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/mobile-mode.md`、`devLog/platform-live.md`。

## 2026-11-02 - 发布 v1.3.20：内置直播源清理、平台直播手机版重构与安全区修复

### 文件修改、原因与目的

- `data/LiveRepository.kt`：清理内置电视直播源，仅保留项目最初的内置源地址（历史记录已脱敏为 `REDACTED`，该地址已在后续版本彻底移除）；多源按顺序探测、首个成功即用；新增 `normalizeSourceUrl` 地址归一化去重。
- `domain/LiveParser.kt`：TXT 源没有 `#genre#` 分组标记时不再丢台，统一归入默认"电视直播"分组；`test/PlaybackParserTest.kt` 同步用例。
- `ui/PlatformLiveScreen.kt`：手机版列表列数按屏幕宽度分级（2/2/3/4 列）；房间卡改竖版 3:4 封面、收藏星标叠在封面上；播放页控制栏 4 秒无操作自动隐藏、点击唤出并吸收安全区；"加载更多"改通栏按钮。
- `ui/TvBoxApp.kt`：直播源文案更新；`MobileSettingsScreen` 与三个源管理页增加 `safeDrawing` 内边距；"启动时检查更新"、线路质量统计等文字显式设置 `onSurface` 前景色，修复深色底上不可见。
- `ui/DetailScreen.kt`：手机版详情页吸收状态栏/导航栏安全区。
- `app/build.gradle.kts`：版本升级至 `10320 / 1.3.20`；`CHANGELOG.md`、`README.md`、`update.json` 同步。

### 构建与发布校验

- CI 发布：推送 `v1.3.20` 标签触发 `.github/workflows/release.yml`（run `41385200451`）成功，发布 [TVBox v1.3.20](https://github.com/cnliux/tvbox/releases/tag/v1.3.20)。
- Release 附件 `app-release.apk`：`50886750` 字节，下载后本地计算 SHA-256 `b386d0877b042ce7e89e8a7238a5a068fd7aa1a4533e405f0d320364b47ea098`，与大小一致；`update.json` 与 README 示例按该附件实际值更新。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/mobile-mode.md`、`devLog/platform-live.md`。

## 2026-11-02 - 发布 v1.3.19：底部导航遮挡修复与直播列表 4 列铺满

### 文件修改、原因与目的

- `ui/MobileNavigationBar.kt`：edge-to-edge 下底部 `NavigationBar` 未吸收系统导航栏 insets，导致"首页/历史/搜索/电视/直播"被三键栏遮挡；增加 `windowInsetsPadding(WindowInsets.navigationBars)`（置于 `height(56.dp)` 之前，总高 = 56dp + inset）。
- `ui/PlatformLiveScreen.kt`：触摸模式平台直播浏览页外层 padding 28dp→8dp、网格间距 14dp→8dp、contentPadding 8dp→2dp，消除两侧黑框；列数竖屏 2→4、宽屏 3→6，两行至少可见 8 个卡片；`PlatformLiveImageCard` 触摸模式压缩文字区（bodySmall/labelSmall、padding 6dp）与收藏按钮（44dp→28dp），标题、主播与人气清晰可读。
- `app/build.gradle.kts`：版本升级至 `10319 / 1.3.19`；`CHANGELOG.md`、`README.md`、`update.json` 同步。

### 构建与发布校验

- 本地 `:app:assembleDebug` 与 `:app:testDebugUnitTest` 通过。
- 192.168.1.45 真机验证：首页底部导航五标签完整可见不被遮挡；站点/分类/房间列表均 4 列铺满、无两侧黑框、卡片信息可读。
- CI 发布：推送 `v1.3.19` 标签触发 `.github/workflows/release.yml`（run `41373114909`）成功，发布 [TVBox v1.3.19](https://github.com/cnliux/tvbox/releases/tag/v1.3.19)。
- Release 附件 `app-release.apk`：`50886810` 字节，下载后本地计算 SHA-256 `e718286f618926efc75a325478a5a32bbd8c0c61cd3f96f9597aedc78e0a80cc`，与大小一致；`update.json` 与 README 示例按该附件实际值更新。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/mobile-mode.md`、`devLog/platform-live.md`。

## 2026-11-02 - 发布 v1.3.18：热播对齐 TV 版与直播全屏

### 文件修改、原因与目的

- `ui/TvBoxViewModel.kt`：热播点击多源回退查找（当前源不支持搜索时依次尝试其他源，上限 6 个），与 TV 版行为一致；`userMessage()` 剥离内部异常类名前缀（如 `DouyuResolverError:`）并识别中文"超时"，不再向用户暴露原始 JSON/异常文本。
- `ui/LiveScreen.kt`、`ui/PlatformLiveScreen.kt`、`ui/MobileNavigationBar.kt`：直播播放页手机模式沉浸全屏（隐藏系统栏与顶底栏）；新增"全屏/还原"按钮（`RESIZE_MODE_ZOOM` 裁剪铺满消除黑边）；平台直播支持横竖屏切换；电视直播单线路频道无法播放自动切下一台；频道名徽标常驻与"返回"按钮。
- `ui/TvBoxApp.kt`、`ui/components/Common.kt`、`ui/MobileTouchMode.kt`（新增）：触摸模式点按手势替代 clickable+focusable，消除焦点高亮残留；首页热播 3 列大图、平台直播房间 2 列大卡；通知文案截断。
- `ui/PlayerScreen.kt`：ExoPlayer 播放错误按 `errorCode` 映射友好文案。
- `data/LiveRepository.kt`：直播源 URL 归一化去重（仅对清单类扩展名 trim 尾部斜杠）。
- `app/build.gradle.kts`：版本升级至 `10318 / 1.3.18`；`CHANGELOG.md`、`README.md`、`update.json` 同步。

### 构建与发布校验

- 本地 `:app:assembleDebug` 与 `:app:testDebugUnitTest` 通过。
- 192.168.1.45 真机验证：热播点击进《小城良方》详情页；电视直播/平台直播"全屏⇄还原"切换生效；平台直播错误显示"网络超时，请稍后重试"。
- CI 发布：推送 `v1.3.18` 标签触发 `.github/workflows/release.yml`（run `41327454568`）成功，发布 [TVBox v1.3.18](https://github.com/cnliux/tvbox/releases/tag/v1.3.18)。
- Release 附件 `app-release.apk`：`50886774` 字节，下载后本地计算 SHA-256 `5b59f4eac0fde59b7cebb08898e3cca0fc42f82adb2ef7de6ce04e31d37460b0`，与大小一致；`update.json` 与 README 示例按该附件实际值更新。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/mobile-mode.md`、`devLog/platform-live.md`。

## 2026-11-01 19:10 - 发布 v1.3.17：手机模式默认竖屏与移除内置源

### 文件修改、原因与目的

- `MainActivity.kt`、`ui/PlayerScreen.kt`、`domain/AppSettings.kt`：手机模式默认竖屏（启动同步读取设备模式设置基准方向并收集变化即时生效；点播转屏兜底/退出恢复方向按设备模式）；详见 `devLog/mobile-mode.md`。
- `data/LiveRepository.kt`、`domain/AppSettings.kt`、`data/AppSettingsRepository.kt`、`ui/TvBoxViewModel.kt`、`TvBoxApp.kt`、`data/RemoteManageServer.kt`、`app/src/debug/.../AcceptanceActivity.kt`：删除内置电视直播源机制与全部内置源残留；详见 `devLog/platform-live.md`。
- 文档：删除 `影视线路地址整理.md`、`devLog/video-source-addresses.md`、`devLog/live-source.md`、`docs/tvbox-live-source-nodejs-deployment.md`；README/CHANGELOG/devLog/release.yml 同步清除内置源与服务器地址信息。
- `app/build.gradle.kts`：版本升级至 `10317 / 1.3.17`。
- 提交：`dc56b8f`（移除内置源）、`238a8e7`（手机竖屏+v1.3.17）。

### 构建与发布校验

- 本地 `:app:assembleDebug` 与 `:app:testDebugUnitTest` 通过；192.168.1.45 真机验证竖屏首页/直播页无崩溃。
- 推送 tag `v1.3.17` 触发 `.github/workflows/release.yml`（run `41299577446`）成功，发布 [TVBox v1.3.17](https://github.com/cnliux/tvbox/releases/tag/v1.3.17)。
- Release 附件 `app-release.apk`：`50886826` 字节，下载后本地计算 SHA-256 `4e6b07638133c269556028dce52ee4fea9b72566e6f16d856b07cfe7b4ef8fcf`，与大小一致；`update.json` 与 README 示例按该附件实际值更新。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/mobile-mode.md`、`devLog/platform-live.md`。

## 2026-11-01 17:50 - 发布 v1.3.16：手机模式二期与 OTA 指向本项目

### 文件修改、原因与目的

- `app/src/main/java/com/tvbox/app/ui/MobileNavigationBar.kt`、`TvBoxApp.kt`、`SearchScreen.kt`：手机模式顶部栏单行（左源切换 / 右搜索 / 设置）、底部导航改为 首页/历史/搜索/电视/直播、搜索热搜 chips 与结果大图卡片网格；详见 `devLog/mobile-mode.md`。
- `app/src/main/java/com/tvbox/app/data/RemoteManageServer.kt`、`BlockedCategoriesStore.kt`、`AppSettingsRepository.kt`、`domain/AppSettings.kt`、`ui/TvBoxViewModel.kt` 等：无 token 远程管理页与视频源分类屏蔽常驻 JSON；播放器线路按延迟排序与片头/片尾跳过；详见 `devLog/remote-manage.md`、`devLog/playback-experience.md`。
- `app/src/main/java/com/tvbox/app/data/AppUpdateRepository.kt`：OTA 清单地址由 `ZxxXinI/tvbox` 改为本项目 `cnliux/tvbox`。
- `app/build.gradle.kts`：版本升级至 `10316 / 1.3.16`。
- `CHANGELOG.md`、`README.md`：同步 v1.3.16 说明，OTA 地址与 Release 链接改为 `cnliux/tvbox`，并把此前“未发布”条目归入 v1.3.15。
- `update.json`：v1.3.16 的清单在 CI 产物生成后按实际附件大小与 SHA-256 更新。

### 构建与发布校验

- `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:assembleRelease` 均通过（R8 混淆 + 资源压缩 + Chaquopy）。
- 本地签名 APK `app-release.apk`：`50887554` 字节；SHA-256 `cea2978c087f06bce6a81642832178b3212ed8a9577e9036ffd56bc1da00b8f2`；包内版本 `10316 / 1.3.16`。
- v2 签名校验通过，证书 DN `CN=TVBox, OU=App, O=TVBox, L=Beijing, ST=Beijing, C=CN`（与 v1.3.14 起的正式签名一致）。
- 手机模式改动已在 192.168.1.45 验证：顶部栏切换源、热搜 chips、搜索结果卡片、平台直播入口均正常，`logcat -s AndroidRuntime:E` 无崩溃。
- CI 发布：推送 `v1.3.16` 标签触发 `.github/workflows/release.yml`（run `41295367055`），构建成功并创建 [TVBox v1.3.16](https://github.com/cnliux/tvbox/releases/tag/v1.3.16)。
- Release 附件 `app-release.apk`：`50886750` 字节，SHA-256 `ae9fa2a8847762f24ccd050af50ee245245cbf81d70432886471a9eb5885bd95`，包内版本 `10316 / 1.3.16`；`update.json` 与 README 示例已按该附件实际值更新。
- OTA 地址切换只对由本版本及之后版本内置的清单地址生效；此前版本仍读取上游 `ZxxXinI/tvbox` 清单，需要手动下载安装一次。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/mobile-mode.md`、`devLog/remote-manage.md`、`devLog/playback-experience.md`。

## 2026-11-01 09:30 - 发布 v1.3.14：移除内置点播接口并启用 GitHub Actions 自动发布

### 文件修改、原因与目的

- `app/src/main/java/com/tvbox/app/data/MacCmsApi.kt`、`MovieRepository.kt`、`domain/AppSettings.kt` 及相关 UI（提交 `2fc90b9`）：删除全部内置点播接口（30+ 条 ApiLines），项目不再内置任何点播数据，视频源仅由用户导入。
- `app/src/main/java/com/tvbox/app/data/LiveRepository.kt`（提交 `df69a72`）：平台直播站点（斗鱼/虎牙等）可在管理页删除/恢复；电视直播源完全由用户导入（内置源已于后续版本移除）。
- `.github/workflows/release.yml`：新增发布工作流，推送 `v*` 标签或手动触发时构建签名 Release APK 并自动创建 GitHub Release；签名材料从 Actions secrets 注入。
- `gradlew`：补上可执行位（`100644 -> 100755`），修复 Linux runner `Permission denied`。
- `app/build.gradle.kts`：版本升级至 `10314 / 1.3.14`。

### 签名与 CI 配置

- 新生成 release keystore（证书 DN `CN=TVBox, OU=App, O=TVBox, L=Beijing, ST=Beijing, C=CN`），保存于仓库外 `%USERPROFILE%\.tvbox-release\`，口令备份同目录 `credentials.txt`，未入库。
- GitHub Actions secrets：`TVBOX_RELEASE_STORE_FILE_B64`、`TVBOX_RELEASE_STORE_PASSWORD`、`TVBOX_RELEASE_KEY_ALIAS`、`TVBOX_RELEASE_KEY_PASSWORD`、`TVBOX_PLATFORM_LIVE_SERVICE_URL`。
- 注意：新签名证书与旧版（`7244ed4d…`）不同，旧版 APK 无法覆盖安装或 OTA 升级本版本，需卸载后全新安装。
- CI 排障三轮：`android-actions/setup-android@v3` 因 `tools` 包已从 SDK 仓库移除而失败，改用 runner 预装 SDK；`gradlew` 缺执行位；Chaquopy 需要 Python 3.11，增加 `actions/setup-python@v5` 并注入 `TVBOX_BUILD_PYTHON`。

### 构建与发布校验

- 本地 `:app:assembleRelease` 通过（R8 混淆 + 资源压缩 + Chaquopy），签名校验通过；`app-release.apk` 安装到 192.168.1.45 模拟器启动正常、无崩溃日志。
- CI run `41259427842` 成功；Release：[TVBox v1.3.14](https://github.com/cnliux/tvbox/releases/tag/v1.3.14)，附件 `app-release.apk` 50788438 字节。
- 本轮改动（`df69a72`、`2fc90b9`）此前已实机验证通过；`update.json` 未指向 v1.3.14，旧版 OTA 不会收到升级提示（签名不兼容，故不推送 OTA）。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/platform-live.md`。

## 2026-10-13 11:37 - 发布 v1.3.13：合并搜索输入修复

### 文件修改、原因与目的

- `app/src/main/java/com/tvbox/app/ui/SearchScreen.kt`：通过合并 PR #5 纳入关键词末尾光标修复，保留贡献者提交与作者信息。
- `app/build.gradle.kts`：版本升级至 `10313 / 1.3.13`，允许旧版收到 OTA 更新。
- `CHANGELOG.md`、`README.md`、`update.json`：同步本次搜索修复、贡献者、正式版本与实际 APK 校验值；保留此前功能说明及友情链接。
- `devLog/README.md`、`devLog/home-player-ui.md`、本日志：关联缺陷审查、合并记录与发布结果；主日志中的无关兼容条目保留在本地。

### 构建与发布校验

- 用户授权合并、回复及发布；PR 已合并，合并提交 `37bbbd04b05e3160473343eb2f1ce3eaa8285127`。
- `:app:assembleRelease` 构建成功（包含构建自带 vital lint）；APK 包内版本 `10313 / 1.3.13`，最低 API 28，目标 API 36。
- `TVBox-v1.3.13.apk`：`5034437` 字节；SHA-256 `2c69db103a867ed74d28481dcafd5f395f90d87b5cef6d0592ff12e167597edf`。
- v2 签名和 zipalign 校验通过，签名证书 SHA-256 `7244ed4db1ee7488c98d1df3c80b2ccacf72e7983aca3f6341c6268ae1dd8b09`，与此前正式版一致。
- 不运行单元或设备功能测试，不操作 ADB，不将作者的本地构建报告描述为本轮实机验证。
- 发布顺序：核对签名与清单，精确暂存主工程及相关文档，推送标签，草稿上传并校验附件，公开 Release 后推送 main，再核对远端 OTA 清单并回复 PR。
- Release：[TVBox v1.3.13](https://github.com/ZxxXinI/tvbox/releases/tag/v1.3.13)。旧版可 OTA 更新或手动覆盖安装，保留本机数据。
- 排除 Android 4.4 / Android 6 独立工程、参考目录和其他无关文件；不修改旧 Release 或旧标签。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/home-player-ui.md`。

## 2026-10-08 12:01 - 发布 v1.3.12：设备模式与 OTA 兼容

### 文件修改、原因与目的

- `app/build.gradle.kts`：用户验收后明确要求发布 v1.3.12，设置 `versionCode=10312`、`versionName=1.3.12`。
- `app/src/main/java/com/tvbox/app/domain/AppSettings.kt`、`data/AppSettingsRepository.kt`、`ui/DeviceModeDetection.kt`、`ui/PlayerScreen.kt`、`ui/LiveScreen.kt`、`ui/TvBoxApp.kt`、`ui/TvBoxViewModel.kt`：纳入默认电视模式、本机设备模式选择与短剧横屏修复，用户选择优先于识别参考。
- `app/src/main/java/com/tvbox/app/MainActivity.kt`、`data/AppUpdateRepository.kt`、`data/UpdateDownloadService.kt`、`data/UpdateApkFiles.kt`、`data/UpdateApkExporter.kt`、`app/src/main/AndroidManifest.xml`：纳入授权续接、备用下载和 APK 保存；缺少系统组件时仍能下载或保存到公共目录。
- `CHANGELOG.md`、`README.md`、`update.json`：同步版本、手机模式入口、更新说明、下载示例及实际 APK 校验值，保留友情链接。
- `docs/validation/2026-10-08-ota-download-export.md`：归档此前 5554 定向复查结果及未覆盖分支，避免将有限验证描述为全部设备通过。
- `devLog/README.md`、`devLog/ota-update.md`、`devLog/playback-experience.md`、本日志：关联开发记录、用户验收和正式发布信息；无关兼容日志保持在工作区。

### 构建与发布校验

- 用户反馈设备模式没有问题并授权发布；沿用此前 5554 下载、安装器拉起及本地保存验证，本轮不重复运行功能/单元测试，不操作 ADB。
- `:app:assembleRelease` 构建通过（包含构建自带 vital lint）；最低 API 28，包内版本 `10312 / 1.3.12`。
- `TVBox-v1.3.12.apk`：`5034437` 字节；SHA-256 `54fa16b488d98475137382b2e33d8797a471e341befec1c5b0086b8195db49fe`。
- v2 签名校验通过，证书 SHA-256 `7244ed4db1ee7488c98d1df3c80b2ccacf72e7983aca3f6341c6268ae1dd8b09`，与已发布版本一致。
- 发布流程：仅暂存本轮主工程及相关文档，推送新标签，草稿上传 APK 和相同 OTA 清单并校验，公开 Release 后推送 main，核对远端附件与根清单。
- Release：[TVBox v1.3.12](https://github.com/ZxxXinI/tvbox/releases/tag/v1.3.12)。默认电视模式，手机用户需选择“设置 → 设备模式 → 手机模式”；旧版无法在线更新时可手动覆盖安装。
- Android 4.4 / Android 6 独立工程、无关文件与日志不纳入提交、推送或附件。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/ota-update.md`、`devLog/playback-experience.md`。

## 2026-10-05 20:07 - 发布 v1.3.11：自动换线与点播画面

### 文件修改、原因与目的

- `app/build.gradle.kts`：用户确认本地测试没有明显问题，版本升级至 `10311 / 1.3.11`，允许旧版正常 OTA 更新。
- `app/src/main/java/com/tvbox/app/domain/PlaybackRecovery.kt`、`PlaybackAgent.kt`、`PlaybackBufferMonitor.kt`、`ui/PlayerScreen.kt`、`ui/TvBoxViewModel.kt`：正式纳入 Issue #3 的自动换线进度、对应集数和缓冲恢复提示修复，详细缺陷记录见模块日志。
- `domain/AppSettings.kt`、`data/AppSettingsRepository.kt`、`ui/PlayerViewport.kt`、`ui/PlayerControls.kt`：纳入点播画面模式持久化和仅播放页的沉浸式窗口；不改变普通直播或独立兼容工程。
- `app/src/test/java/com/tvbox/app/domain/PlaybackRecoveryTest.kt`、`PlaybackBufferMonitorTest.kt`：纳入此前新增/调整的回归用例；本轮不重复执行测试。
- `CHANGELOG.md`、`README.md`、`update.json`：同步正式版本、修复说明、下载示例与实际 APK 校验信息，保留现有友情链接。
- `devLog/README.md`、`devLog/playback-experience.md`、本日志：关联用户验收、构建产物和正式发布流程，主日志中的无关兼容条目不提交。

### 验证与交付

- 用户已自行测试并反馈“没什么问题”；按要求不重复运行单元或设备测试，不操作 ADB。
- `:app:assembleRelease` 构建通过；包内版本 `10311 / 1.3.11`，最低 API 28，v2 签名校验通过；证书 SHA-256 `7244ed4db1ee7488c98d1df3c80b2ccacf72e7983aca3f6341c6268ae1dd8b09`。
- APK `TVBox-v1.3.11.apk`：`5001469` 字节；SHA-256 `4ab85ed43e9d5fe91b0a2b525161dd6eaff1334de590a369899b395916848333`。根清单、Release 清单和 README 示例必须与 APK 一致。
- 仅暂存主工程与对应文档；先推送新标签，创建草稿 Release 并校验附件，公开 Release 后推送 main，使 OTA 不指向尚未公开的附件。
- 发布地址：[TVBox v1.3.11](https://github.com/ZxxXinI/tvbox/releases/tag/v1.3.11)；完成公开发布后回复 [Issue #3](https://github.com/ZxxXinI/tvbox/issues/3)，说明修复与升级入口，保留 Issue 开放供反馈。
- 发布流程不覆盖已有 v1.3.10 资产或修改旧标签；不上传 Android 4.4 / Android 6 工程及无关文件。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/playback-experience.md`。

## 2026-10-04 11:33 - 正式 v1.3.10 同版本重新上传

### 文件修改及目的

- `app/src/main/java/com/tvbox/app/MainActivity.kt`、`data/AppUpdateRepository.kt`、`ui/TvBoxViewModel.kt`、`ui/TvBoxApp.kt`、`app/src/main/res/xml/update_file_paths.xml`：将系统后台下载、应用内进度卡片、任务恢复与一次性自动安装入口纳入正式版本，解决更新弹窗关闭后缺少状态和安装入口的问题；详细缺陷记录见 `devLog/ota-update.md`。
- `CHANGELOG.md`、`README.md`、`update.json`：保持 `10310 / 1.3.10`，同步说明、实际 APK 大小及 SHA-256；明确已安装同版本用户需手动下载覆盖安装。
- `devLog/README.md`、`devLog/ota-update.md`、`devLog/release.md`：关联开发与正式重打包记录，原发布记录保留。

### 构建与发布校验

- `:app:assembleRelease` 构建通过（包含 Release vital lint），未执行设备功能测试。
- APK `TVBox-v1.3.10.apk`：`5001469` 字节；SHA-256 `cde557c130b3ea1b2045a0ea89f421bc3ef71756584232b5059127bee7701f87`。
- 包内版本 `10310 / 1.3.10`，最低 API 28；v2 签名校验通过，证书 SHA-256 `7244ed4db1ee7488c98d1df3c80b2ccacf72e7983aca3f6341c6268ae1dd8b09`。
- 替换现有 v1.3.10 Release 的 APK 和配套清单，核对远程附件后同步 main 分支 OTA；同版本标签随源码更新，旧提交保留在历史中。
- 本次不重复安装：重新构建产物与此前已覆盖安装到 192.168.0.8 的后台更新测试包 SHA-256 相同。
- 排除 Android 4.4 / Android 6 独立工程及主日志中的无关 Android 4.4 条目。

### 导航

- 主日志：`devLog/README.md`；模块日志：`devLog/ota-update.md`。

## 2026-10-04 10:08 - 发布 v1.3.10：播放体验与继续观看

## File Changes

- `app/build.gradle.kts`：将版本升级至 `10310 / 1.3.10`，支持正式 OTA 更新。
- `CHANGELOG.md`、`README.md`、`update.json`：同步本轮播放亮屏、中断暂停、轻量控制栏、继续观看及焦点修复，更新正式下载地址与 APK 校验信息。
- `app/src/main`、`app/src/test`、`app/src/debug`：纳入本轮主工程代码、回归用例和仅 Debug 打包的验收入口。
- `tools`、`docs/validation/2026-10-03-playback-experience.md`：归档可重复验收工具和验证边界；缓存文件不入库。
- `devLog/README.md`、`devLog/home-player-ui.md`、`devLog/playback-experience.md`：记录本轮改动与发布导航，开发日志中的无关记录保留在工作区。

## Verification

- 执行签名 Release 构建，核对包内版本、证书、附件大小及 SHA-256，并与 OTA 清单一致性校验。
- 历史回归用例为 69 项通过；本次遵循用户要求不执行设备功能测试，不将未完成的设备续播复测或模拟器自动熄屏检查记为通过。
- Release 构建通过；APK 包内版本 `10310 / 1.3.10`，最低 API 28，v2 签名校验通过；证书 SHA-256 `7244ed4db1ee7488c98d1df3c80b2ccacf72e7983aca3f6341c6268ae1dd8b09`。
- APK `TVBox-v1.3.10.apk`：`4985005` 字节；SHA-256 `6c788fecf8c96ae8373a8b2ccb640eeeb7e7703c95338eb082cac14871e08ec0`。OTA 清单与 APK 一致；Release 不包含 Debug 验收 Activity。
- 先公开 Release，再推送 main，使 OTA 指向已公开的资产。

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/playback-experience.md`

## 2026-09-21 19:03 - 发布 v1.3.9：电视(4)手机页面兼容

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 用户已完成“电视(4)”手机适配验收，需要发布新版本。
  - Purpose: 设置 `versionCode=10309`、`versionName=1.3.9`。
- File path: `CHANGELOG.md`、`README.md`、`update.json`
  - Reason: 发布说明、安装示例和 OTA 信息必须与 v1.3.9 签名 APK 一致。
  - Purpose: 写入“电视(4)”手机适配说明、Release 地址、SHA-256 和 APK 大小。
- File path: `app/build/outputs/apk/release/TVBox-v1.3.9.apk`
  - Reason: 提供正式发布的签名安装包。
  - Purpose: 发布 v1.3.9 APK。

## Verification

- `:app:testDebugUnitTest`：passed，61 项测试通过。
- `:app:assembleRelease`：passed。
- APK 包内版本：`10309 / 1.3.9`；最低 API 28。
- APK 签名：passed；证书为项目现有 TVBox Release 证书。
- APK 大小：`4919465` 字节；SHA-256：`51feda6cb53005d8681fcaac3bc9833bb47eb2111e4efa9fc599d222d0a26c20`。
- 设备功能由用户已完成测试，本次未重复执行设备测试。

## Navigation

- Master doc: `devLog/README.md`
- Branch docs: `devLog/home-player-ui.md`、`devLog/release.md`

## 2026-09-17 07:24 - 发布 v1.3.8：内容过滤修复与直播收藏

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 新功能与内容过滤修复需要高于正式 v1.3.7 的版本号，以支持 OTA 检测。
  - Purpose: 设置 `versionCode=10308`、`versionName=1.3.8`。
- File path: `CHANGELOG.md`、`README.md`、`update.json`
  - Reason: 发布说明、安装示例和 OTA 下载信息必须与签名 APK 一致。
  - Purpose: 记录过滤修复、直播亮屏与收藏功能，并写入 v1.3.8 的地址、大小和 SHA-256。
- File path: `app/build/outputs/apk/release/TVBox-v1.3.8.apk`、`app/build/outputs/apk/release/update.json`
  - Reason: 提供正式发布可用的配套产物。
  - Purpose: 构建签名 APK 与相同版本的 OTA 清单。

## Verification

- `:app:testDebugUnitTest`：passed，61 项测试通过。
- `:app:assembleRelease`：passed。
- APK 签名：passed；签名证书 SHA-256 `7244ed4db1ee7488c98d1df3c80b2ccacf72e7983aca3f6341c6268ae1dd8b09`。
- APK 包内版本：`10308 / 1.3.8`；最低 API 28；平台直播服务地址已在 Release 配置中设置。
- APK 大小：`4903077` 字节；SHA-256：`84a9dbeb2ec0c64983545d044cfbecc87dc4b8803a7d0e3a9f7c2e8c542e0d30`。
- 按用户之前的测试分工，未执行设备功能测试。

## Navigation

- Master doc: `devLog/README.md`
- Branch docs: `devLog/content-filter.md`、`devLog/platform-live.md`、`devLog/release.md`

## 2026-09-17 07:01 - 直播收藏功能 Release 测试包

## File Changes

- File path: `app/build/outputs/apk/release/TVBox-v1.3.7-live-favorites-test.apk`
  - Reason: 用户希望自行测试直播亮屏和平台房间本机收藏功能。
  - Purpose: 提供已签名的 Release 测试包，不覆盖已发布同名产物。
- File path: `devLog/README.md`、`devLog/release.md`
  - Reason: 记录构建产物与验证边界。
  - Purpose: 区分本地测试包与 GitHub 已发布的 v1.3.7。

## Verification

- `:app:assembleRelease`：passed。
- `apksigner verify --print-certs`：passed；签名证书与正式 v1.3.7 相同。
- APK 包内版本：`versionCode=10307`、`versionName=1.3.7`、最低 API 28。
- 平台直播服务地址已在 Release 构建配置中设置（未在此记录具体地址）。
- 测试 APK：`4903081` 字节，SHA-256 `0fef430c1f02bd312153bb7882ea6917c62272439478446f8497e207664ac895`。
- 正式 v1.3.7 APK 从 GitHub Release 恢复至原本地文件名并核对 SHA-256 `1d09449c9e2b5418d79db34f3db6f86e11a43b73971dee5aa6b309ea270f6011`；线上 Release 未更改。
- 没有上传、打 tag、修改 `update.json` 或执行设备功能测试；由于版本号未提高，OTA 不会把此测试包识别为更新。

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-09-16 11:57 - 正式发布 v1.3.7：播放器遥控器控制权回收

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 用户确认当前功能已完善，并要求正式版本保持为 1.3.7。
  - Purpose: 将本地候选版本号从 `1.3.8 / 10308` 回退并固定为 `1.3.7 / 10307`。
- File path: `app/src/main/java/com/tvbox/app/ui/PlayerScreen.kt`
  - Reason: Media3 原生控件截获焦点和按键。
  - Purpose: 关闭原生控制器并恢复 TVBox 自有方向键、数字键、菜单键和播放暂停处理。
- File path: `CHANGELOG.md`、`README.md`、`update.json`
  - Reason: 发布说明和 OTA 清单需要与新安装包一致。
  - Purpose: 写入 v1.3.7 的完整功能说明、下载地址、大小和 SHA-256。
- File path: `app/build/outputs/apk/release/TVBox-v1.3.7.apk`、`app/build/outputs/apk/release/update.json`
  - Reason: 提供用户可继续测试的正式签名产物。
  - Purpose: 生成最终 v1.3.7 Release APK 与配套 OTA 清单。

## Bug Record

- Time: 2026-09-16 11:57
- Symptoms: 数字 1/3、菜单键失效，左右键行为受 Media3 原生控制器焦点影响。
- Attempted fix: 关闭 `PlayerView` 控制器、阻止子控件聚焦，并由 Compose 根节点统一消费遥控器事件。
- Temporary solution: 无。

## Verification

- `./gradlew.bat :app:assembleRelease --console=plain --no-daemon`：passed。
- `apksigner verify --print-certs app\\build\\outputs\\apk\\release\\TVBox-v1.3.7.apk`：passed。Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`。
- 按用户要求未执行功能测试。
- Release assets:
  - APK: `app/build/outputs/apk/release/TVBox-v1.3.7.apk`
  - Size: `4870313`
  - SHA-256: `1d09449c9e2b5418d79db34f3db6f86e11a43b73971dee5aa6b309ea270f6011`

## Navigation

- Master doc: `devLog/README.md`
- Branch docs: `devLog/home-player-ui.md`、`devLog/release.md`

## 2026-09-15 21:38 - 发布 v1.3.7：遥控器焦点与影视线路扩充

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 本次播放器交互和线路扩充需要独立可安装版本。
  - Purpose: 将版本升级为 `1.3.7 / 10307`。
- File path: `CHANGELOG.md`、`README.md`、`update.json`
  - Reason: 版本说明、安装示例和 OTA 清单需要与新安装包保持一致。
  - Purpose: 记录焦点、进度条和线路变更，并写入 v1.3.7 的下载地址、大小和 SHA-256。
- File path: `app/build/outputs/apk/release/TVBox-v1.3.7.apk`、`app/build/outputs/apk/release/update.json`
  - Reason: 用户要求生成可安装的正式版本。
  - Purpose: 提供正式签名 APK 与配套 OTA 清单。
- File path: `devLog/README.md`、`devLog/home-player-ui.md`、`devLog/release.md`
  - Reason: 项目要求每次代码修改具备主时间线与模块记录。
  - Purpose: 归档变更原因、实现范围、产物和验证边界。

## Bug Record

- Time: 2026-09-15 21:38
- Symptoms: 播放器与搜索操作的遥控器焦点难以辨认，进度条遥控器步长不明确。
- Attempted fix: 增强焦点视觉状态、固定 10 秒 seek 步长、增加 M3U 直播回退。
- Temporary solution: 无。

## Verification

- `./gradlew.bat :app:assembleRelease --console=plain --no-daemon`：passed。
- `apksigner verify --print-certs app\\build\\outputs\\apk\\release\\TVBox-v1.3.7.apk`：passed。Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`。
- ADB `emulator-5554`：`adb install -r` 覆盖安装成功；系统显示 `versionCode=10307`、`versionName=1.3.7`。
- 按用户要求未启动应用、未执行播放器 UI、搜索或线路功能测试。
- Release assets:
  - APK: `app/build/outputs/apk/release/TVBox-v1.3.7.apk`
  - Size: `4870313`
  - SHA-256: `822975349e1f4ad4926a5cc1d6a1e57fc30aa466bed7370353b375ff41bbfb9b`

## Navigation

- Master doc: `devLog/README.md`
- Branch docs: `devLog/home-player-ui.md`、`devLog/release.md`

## 2026-08-25 07:46 - 发布 v1.3.6：多来源搜索与渐进补线

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 多来源加载优化需要新版本号，才能触发已安装客户端的 OTA 更新。
  - Purpose: 将版本升级为 `1.3.6 / 10306`。

- File path: `CHANGELOG.md`、`README.md`、`update.json`
  - Reason: 版本说明、手动安装说明和 OTA 清单必须使用同一版本、下载地址、哈希与大小。
  - Purpose: 记录多来源并行搜索、渐进补线、缓存和线路冷却，并指向 v1.3.6 Release APK。

- File path: `app/build/outputs/apk/release/TVBox-v1.3.6.apk`、`app/build/outputs/apk/release/update.json`
  - Reason: GitHub Release 需要正式签名 APK 和对应 OTA 清单资产。
  - Purpose: 提供可安装且可校验的 v1.3.6 发布资产。

- File path: `devLog/README.md`、`devLog/release.md`
  - Reason: 项目要求记录每次发布的时间线和产物信息。
  - Purpose: 归档本次版本范围、验证和发布动作。

## Bug Record

- Time: 2026-08-25 07:13
- Symptoms: 全量单测中的豆瓣热播解析和内容过滤模块各有一项失败。
- Attempted fix: 核对失败文件与本次发布范围，确认均不位于多来源加载改动模块。
- Temporary solution: 保留两项既有失败，新增多来源仓库测试和 ADB 真机流程均通过。

## Verification

- `./gradlew.bat :app:assembleRelease --console=plain --offline`：passed。
- `apksigner verify --print-certs app\\build\\outputs\\apk\\release\\TVBox-v1.3.6.apk`：passed。Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`。
- ADB `emulator-5554`：Debug v1.3.5 验证 8/8 条来源完成、16 个去重结果，并成功聚合三条播放线路。
- Release assets:
  - APK: `app/build/outputs/apk/release/TVBox-v1.3.6.apk`
  - Size: `4870313`
  - SHA-256: `4fd6f30e25c96e71fd9a7653dfa38add2db4a6fc4f31ba340da5d7518532c1dc`

## Navigation

- Master doc: `devLog/README.md`
- Branch docs: `devLog/release.md`、`devLog/multi-source-loading.md`

## 2026-08-16 21:02 - 发布 v1.3.5：原生播放器与智能比例适配

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 播放器与手机端交互功能完成后需要提供可 OTA 更新的正式版本。
  - Purpose: 将版本更新为 `1.3.5 / 10305`。

- File path: `CHANGELOG.md`、`README.md`、`update.json`
  - Reason: 用户安装、OTA 检测和发布说明必须指向同一份 v1.3.5 安装包。
  - Purpose: 记录原生播放器、手势和比例适配，并写入 APK 下载地址、SHA-256 与文件大小。

- File path: `app/build/outputs/apk/release/TVBox-v1.3.5.apk`
  - Reason: GitHub Release 需要稳定的版本化安装包。
  - Purpose: 保存已签名 v1.3.5 APK，供手动安装和 OTA 下载。

- File path: `devLog/README.md`、`devLog/release.md`
  - Reason: 用户要求每次开发与发布后记录内容。
  - Purpose: 登记 v1.3.5 的版本范围、构建结果和完整性数据。

## Bug Record

- Time: 2026-08-16 21:02
- Symptoms: 完整单测中有两项内容过滤测试失败。
- Attempted fix: 已定位到当前 `ContentFilter` 的屏蔽词改为 `x`，与测试期望不一致。
- Temporary solution: 按用户要求保留当前过滤行为；本次正式构建、签名与播放器 ADB 验证均正常。

## Verification

- `./gradlew.bat assembleDebug --console=plain --offline --no-daemon`：passed。
- `./gradlew.bat assembleRelease --console=plain --offline --no-daemon`：passed。
- ADB `emulator-5554`：横屏影片实播，原生控制器、画面比例与播放器手势正常。
- ADB `192.168.0.7:5555`：v1.3.5 正式签名 APK 覆盖安装成功；系统显示 `versionCode=10305`、`versionName=1.3.5`，应用进程正常，未发现新的崩溃日志。
- `apksigner verify --print-certs app\build\outputs\apk\release\TVBox-v1.3.5.apk`：passed。Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`。
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.3.5.apk`
  - Size: `4853925`
  - SHA-256: `83499f7a98a05cdae1a11366a56af49868f0c66737396261716a22a220910ed3`

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-08-07 13:45 - 发布 v1.3.4：豆瓣热播首页

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 热播首页功能完成后需要提供可被 OTA 识别的正式版本。
  - Purpose: 将版本更新为 `1.3.4 / 10304`。

- File path: `CHANGELOG.md`、`README.md`、`update.json`
  - Reason: 用户安装、OTA 检测和发布说明必须指向同一个版本化 APK。
  - Purpose: 记录热播功能，并写入 v1.3.4 APK 的下载地址、SHA-256 与文件大小。

- File path: `app/build/outputs/apk/release/TVBox-v1.3.4.apk`
  - Reason: GitHub Release 需要稳定的版本化安装包。
  - Purpose: 保存已签名 v1.3.4 APK，供用户安装和 OTA 下载。

- File path: `devLog/README.md`、`devLog/douban-hot-api.md`、`devLog/release.md`
  - Reason: 需要可追溯地记录接口实现、设备测试与发布结果。
  - Purpose: 登记本次功能、验证边界和完整性数据。

## Bug Record

- Time: 2026-08-07 13:45
- Symptoms: 无。
- Attempted fix: 不适用；本次为功能发布。
- Temporary solution: 无。

## Verification

- `./gradlew.bat testDebugUnitTest assembleRelease`
  - Result: passed.
- Android 9 设备：成功覆盖安装 `v1.3.4 / 10304`，豆瓣热播首屏返回 `243` 部；点击“这一秒过火”通过任一播放线路进入详情页；返回后仍为热播页；加载更多后显示 `40 / 243` 部。
- `apksigner verify --print-certs app\build\outputs\apk\release\TVBox-v1.3.4.apk`
  - Result: passed. Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`。
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.3.4.apk`
  - Size: `4853929`
  - SHA-256: `3875d84023e5b620536f7619ee2989a62f07eddc141ee1bad0e27891c898d74d`

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-08-07 07:50 - 发布 v1.3.3：修复启动闪退

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: v1.3.2 已发布但无法启动，必须提高版本号让已安装用户收到 OTA 修复。
  - Purpose: 将 `versionCode` 更新为 `10303`，将 `versionName` 更新为 `1.3.3`。

- File path: `CHANGELOG.md`
  - Reason: 用户需要明确知道本版本是紧急启动修复。
  - Purpose: 记录字体缩放崩溃修复和回归测试。

- File path: `README.md`
  - Reason: 当前安装、OTA 示例和手动发布命令应对应修复版。
  - Purpose: 更新版本、下载地址和 APK 校验值。

- File path: `update.json`
  - Reason: 旧版应用依赖 OTA 清单识别高版本修复。
  - Purpose: 指向 v1.3.3 Release APK，并提供实际 SHA-256 与文件大小。

- File path: `app/build/outputs/apk/release/TVBox-v1.3.3.apk`
  - Reason: GitHub 与 Gitee Release 需要稳定的版本化安装包。
  - Purpose: 保存已签名 v1.3.3 APK，供用户安装和 OTA 下载。

- File path: `devLog/README.md`
  - Reason: 主时间线需要记录线上版本问题和修复结果。
  - Purpose: 登记根因和设备验证结果。

- File path: `devLog/release.md`
  - Reason: 发布模块需要保留 v1.3.3 的完整性数据。
  - Purpose: 记录紧急修复发布准备、构建和签名验证。

## Bug Record

- Time: 2026-08-07 07:41
- Symptoms: v1.3.2 打开后立即闪退，无法进入首页。
- Attempted fix: 修正默认 `TextUnit.Unspecified` 的字体缩放逻辑，并添加回归测试。
- Temporary solution: 无，v1.3.3 为正式修复版本。

## Verification

- `./gradlew.bat testDebugUnitTest assembleRelease --console=plain --no-daemon`
  - Result: passed.
- Android 设备：安装 `v1.3.3 / 10303` 后，`MainActivity` 前台运行，PID 存在，无新崩溃日志。
- `apksigner verify --print-certs app\build\outputs\apk\release\TVBox-v1.3.3.apk`
  - Result: passed. Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`。
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.3.3.apk`
  - Size: `4837545`
  - SHA-256: `5896f3528961df579af6ee7dea95d610b53770196d8007b2a099914053756ff2`
- GitHub 和 Gitee 将在 commit 与 tag 推送后同步 v1.3.3 代码和发布资产。

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-08-06 22:45 - 发布 v1.3.2：字体大小与卡片布局适配

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 字体与布局设置完成后需要发布独立 Android 版本，供已有设备通过 OTA 识别更新。
  - Purpose: 将 `versionCode` 更新为 `10302`，将 `versionName` 更新为 `1.3.2`。

- File path: `CHANGELOG.md`
  - Reason: 用户需要知道本版本的可见功能变化。
  - Purpose: 登记字体大小、全局文字缩放和网格卡片适配。

- File path: `README.md`
  - Reason: 安装、OTA 示例和手动发布命令应对应当前正式版本。
  - Purpose: 更新版本、APK 文件名、下载地址、校验值和发布命令。

- File path: `update.json`
  - Reason: 已安装的旧版应用依赖该清单发现 v1.3.2 并校验下载结果。
  - Purpose: 指向 v1.3.2 Release APK，提供实际 SHA-256 与文件大小。

- File path: `app/build/outputs/apk/release/TVBox-v1.3.2.apk`
  - Reason: GitHub 和 Gitee Release 需要明确、稳定的版本化安装包名称。
  - Purpose: 保存 v1.3.2 已签名 Release APK，供上传作为发布资产。

- File path: `devLog/README.md`
  - Reason: 主开发时间线需要可追溯本次功能与发布。
  - Purpose: 登记 v1.3.2 的功能范围与构建验证。

- File path: `devLog/release.md`
  - Reason: 发布模块需要保留版本、APK 完整性数据和验证结果。
  - Purpose: 记录 v1.3.2 的正式发布准备工作。

## Bug Record

- Time: 2026-08-06 22:45
- Symptoms: 无。本次为正常功能版本发布。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Verification

- `./gradlew.bat testDebugUnitTest assembleRelease --console=plain --no-daemon`
  - Result: passed.
- `E:\Soft\Tools\AndroidSDK\build-tools\36.1.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\TVBox-v1.3.2.apk`
  - Result: passed. Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`。
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.3.2.apk`
  - Size: `4837545`
  - SHA-256: `9d35fa39208ce3bf0620677b7367d8a90964470a51dee48f95fe431715224ec5`
- GitHub 和 Gitee 将在 commit 与 tag 推送后上传 APK、`update.json`。

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-08-03 08:40 - v1.3.1 带平台直播服务地址覆盖构建

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: Release APK 需要连接统一平台直播服务。
  - Purpose: 使用构建参数 `TVBOX_PLATFORM_LIVE_SERVICE_URL=http://<your-server>:8868` 注入 `BuildConfig.PLATFORM_LIVE_SERVICE_URL`。
- File path: `update.json`
  - Reason: 代理下载地址和带服务配置的 APK 需要同步到 OTA 清单。
  - Purpose: 将 `apkUrl` 改为 `https://gh-proxy.org/` 前缀，并更新 APK SHA-256。
- File path: `README.md`
  - Reason: OTA 示例必须与实际清单和 Release 资产保持一致。
  - Purpose: 更新代理下载地址、校验值和说明。
- File path: `devLog/README.md`
  - Reason: 需要记录同版本覆盖构建的配置和发布动作。
  - Purpose: 在主时间线登记带平台直播地址的 v1.3.1 覆盖构建。
- File path: `devLog/release.md`
  - Reason: 发布记录需要保留本次 APK 配置、校验值和资产更新结果。
  - Purpose: 记录带平台直播服务地址的 v1.3.1 覆盖发布。

## Bug Record

- Time: 2026-08-03 08:40
- Symptoms: 原 v1.3.1 APK 未注入平台直播服务地址，平台直播无法请求统一服务。
- Attempted fix: 使用 `TVBOX_PLATFORM_LIVE_SERVICE_URL=http://<your-server>:8868` 重新构建并覆盖 Release 资产。
- Temporary solution: 无。

## Verification

- `.\gradlew.bat testDebugUnitTest assembleRelease --console=plain --no-daemon`
  - Result: passed.
- Release `BuildConfig.PLATFORM_LIVE_SERVICE_URL`：`http://<your-server>:8868`。
- `E:\Soft\Tools\AndroidSDK\build-tools\36.1.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\app-release.apk`
  - Result: passed. Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`。
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.3.1.apk`
  - Size: `4821161`
  - SHA-256: `29036c8f5f06b5a81c694aa9cb62c2ad6f418acf314a4001581204ea0229d947`
- OTA APK URL: `https://gh-proxy.org/https://github.com/ZxxXinI/tvbox/releases/download/v1.3.1/TVBox-v1.3.1.apk`
- GitHub v1.3.1 Release 资产将覆盖为本次带服务地址的 APK 和 `update.json`。

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-08-03 08:00 - 发布 v1.3.1

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 发布默认主题 / 影院主题和直播焦点修复，需要提升 Android 版本号。
  - Purpose: 将 `versionCode` 设置为 `10301`，将 `versionName` 设置为 `1.3.1`。
- File path: `CHANGELOG.md`
  - Reason: 新版本需要记录用户可见的主题、导航和遥控器改动。
  - Purpose: 增加 v1.3.1 更新说明。
- File path: `README.md`
  - Reason: 安装、OTA 和发布示例仍指向 v1.3.0。
  - Purpose: 更新当前版本、安装示例和 v1.3.1 发布命令。
- File path: `update.json`
  - Reason: OTA 需要识别并下载 v1.3.1。
  - Purpose: 更新版本号、Release APK 地址和 v1.3.1 变更说明，构建后写入校验值。
- File path: `devLog/README.md`
  - Reason: 用户要求记录每次发布和源码变更。
  - Purpose: 在主时间线登记 v1.3.1 发布。
- File path: `devLog/release.md`
  - Reason: 发布流程需要保留版本、资产和验证记录。
  - Purpose: 记录 v1.3.1 发布涉及的文件和后续构建结果。

## Bug Record

- Time: 2026-08-03 08:00
- Symptoms: 无新增缺陷；本次为正常版本发布。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Verification

- `.\gradlew.bat testDebugUnitTest assembleRelease --console=plain --no-daemon`
  - Result: passed.
- `E:\Soft\Tools\AndroidSDK\build-tools\36.1.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\app-release.apk`
  - Result: passed. Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`。
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.3.1.apk`
  - Size: `4821161`
  - SHA-256: `d79636cbdbcb1031aae4a6ccf412106744b2700b197cc74c4db2b3dd41a19ca2`
- GitHub Release 将在 commit 和 tag 推送后创建并上传 APK、`update.json`。

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-08-02 - v1.3.0 源码版本升级

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 多平台直播接入完成，需要提升 Android 版本号。
  - Purpose: 将 `versionCode` 设置为 `10300`，将 `versionName` 设置为 `1.3.0`。
- File path: `CHANGELOG.md`
  - Reason: 需要记录五平台直播和服务端部署能力。
  - Purpose: 增加 v1.3.0 变更说明。
- File path: `README.md`
  - Reason: 当前 README 缺少平台直播服务部署和构建地址说明。
  - Purpose: 增加五平台直播说明、部署文档链接和 `TVBOX_PLATFORM_LIVE_SERVICE_URL` 构建示例。

## Bug Record

- Time: 2026-08-02
- Symptoms: 无新增缺陷。本次为源码版本升级和部署文档同步。
- Attempted fix: 不适用。
- Temporary solution: 本次暂不更新 `update.json`，避免 OTA 指向尚未发布的 v1.3.0 Release。

## Verification

- 使用 `TVBOX_PLATFORM_LIVE_SERVICE_URL=http://<your-server>:8868` 完成 Debug 构建参数验证。
- 未发布 GitHub Release，待后续正式发布时再更新 `update.json` 和 Release 资产。

## 2026-08-02 11:23 - OTA 更新地址切回 GitHub

## File Changes

- File path: `app/src/main/java/com/tvbox/app/data/AppUpdateRepository.kt`
  - Reason: 当前 OTA 更新检测仍然请求 Gitee 地址。
  - Purpose: 将更新清单地址切换为 GitHub raw 的 `main/update.json`。

- File path: `README.md`
  - Reason: OTA 地址、安装来源和发布流程仍包含 Gitee 的当前说明。
  - Purpose: 统一记录 GitHub OTA 清单和 GitHub Release APK 发布方式。

## Bug Record

- Time: 2026-08-02 11:23
- Symptoms: 应用和文档的当前 OTA 入口仍指向 Gitee。
- Attempted fix: 切换应用清单地址和 README 当前发布说明至 GitHub。
- Temporary solution: 无。

## Verification

- `:app:testDebugUnitTest`：通过。
- 活动项目代码、README 和 `update.json` 中的当前 OTA 地址已核对为 GitHub。

## 2026-07-08 19:58 - 发布 v1.2.10

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 需要发布包含启动权限、直播手势和播放器颜色调整的正式版本。
  - Purpose: 将版本号升级到 `1.2.10`，版本码升级到 `10210`。

- File path: `CHANGELOG.md`
  - Reason: 新版本需要记录用户可见更新内容。
  - Purpose: 增加 v1.2.10 更新说明。

- File path: `README.md`
  - Reason: 安装和发布示例需要跟随当前版本。
  - Purpose: 更新 APK 文件名、版本号、校验值和 Release 命令示例。

- File path: `update.json`
  - Reason: OTA 需要识别 v1.2.10 并下载对应 APK。
  - Purpose: 更新版本码、版本名、APK 地址、SHA-256、大小和更新说明。

- File path: `app/build/outputs/apk/release/TVBox-v1.2.10.apk`
  - Reason: GitHub Release 需要上传正式安装包。
  - Purpose: 保存 v1.2.10 release APK，供用户下载安装和 OTA 使用。

- File path: `app/build/outputs/apk/release/release-notes-v1.2.10.md`
  - Reason: GitHub Release 需要清晰的中文版本说明。
  - Purpose: 记录 v1.2.10 发布内容和验证结果。

- File path: `devLog/README.md`
  - Reason: 用户要求每次修改后记录做了什么、为什么做。
  - Purpose: 在主时间线登记 v1.2.10 发布。

- File path: `devLog/release.md`
  - Reason: 本次属于正式发布任务。
  - Purpose: 记录版本升级、发布资产、校验值和验证命令。

## Bug Record

- Time: 2026-07-08 19:58
- Symptoms: 无。本次为正常版本发布。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Verification

- `./gradlew.bat testDebugUnitTest assembleRelease --console=plain`
  - Result: passed.
- `Get-FileHash app/build/outputs/apk/release/TVBox-v1.2.10.apk -Algorithm SHA256`
  - Result: `b74370f955f1741040267e3efc17c1a50e6750ddfd70be65325d779751eac2b3`.

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`
## 2026-07-08 08:00 - 发布 v1.2.9

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 需要发布包含新 UI 风格的正式版本。
  - Purpose: 将版本号升级到 `1.2.9`，版本码升级到 `10209`。

- File path: `CHANGELOG.md`
  - Reason: 新版本需要记录用户可见更新内容。
  - Purpose: 增加 v1.2.9 更新说明，聚焦影院黑 + 活力绿 UI 风格。

- File path: `README.md`
  - Reason: 安装和发布示例需要跟随当前版本。
  - Purpose: 更新 APK 文件名、版本号、校验值和 Release 命令示例。

- File path: `update.json`
  - Reason: OTA 需要识别 v1.2.9 并下载对应 APK。
  - Purpose: 更新版本码、版本名、APK 地址、SHA-256、大小和更新说明。

- File path: `app/build/outputs/apk/release/TVBox-v1.2.9.apk`
  - Reason: GitHub Release 需要上传正式安装包。
  - Purpose: 保存 v1.2.9 release APK，供用户下载安装和 OTA 使用。

- File path: `app/build/outputs/apk/release/release-notes-v1.2.9.md`
  - Reason: GitHub Release 需要清晰的中文版本说明。
  - Purpose: 记录 v1.2.9 发布内容和验证结果。

- File path: `app/build/outputs/apk/release/update.json`
  - Reason: GitHub Release 附件需要携带对应版本 OTA 清单。
  - Purpose: 与根目录 `update.json` 保持一致，便于手动或备份发布。

- File path: `devLog/README.md`
  - Reason: 用户要求每次修改后记录做了什么、为什么做。
  - Purpose: 在主时间线登记 v1.2.9 发布。

- File path: `devLog/release.md`
  - Reason: 本次属于正式发布任务。
  - Purpose: 记录版本升级、发布资产、校验值和验证命令。

## Bug Record

- Time: 2026-07-08 08:00
- Symptoms: 无。本次为正常版本发布。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Verification

- `./gradlew.bat testDebugUnitTest assembleRelease --console=plain`
  - Result: passed.
- `Get-FileHash app/build/outputs/apk/release/TVBox-v1.2.9.apk -Algorithm SHA256`
  - Result: `68f5c2e9b5763b4ab4b245f623932dd45d68e0ff51961c3ddf45548f2e048901`.

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`
## 2026-07-01 18:09 - 接入 Gitee OTA

## File Changes

- File path: `app/src/main/java/com/tvbox/app/data/AppUpdateRepository.kt`
  - Reason: GitHub 直连和代理下载在电视盒子上速度不稳定，需要切换到国内可访问的 OTA 清单地址。
  - Purpose: 将应用更新检测地址改为 `https://gitee.com/zhen-xin/tv-box/raw/agent/update.json`。
- File path: `update.json`
  - Reason: OTA 清单需要随 Gitee 仓库同步，避免依赖 GitHub Release 的 `latest/download` 地址。
  - Purpose: 新增根目录更新清单，当前指向 Gitee Release 的 `TVBox-v1.2.8.apk`。
- File path: `README.md`
  - Reason: 发布流程需要说明 GitHub 与 Gitee 双远端、Gitee OTA 清单和 Gitee Release APK。
  - Purpose: 更新安装、OTA 和发布命令说明。
- File path: `devLog/README.md`
  - Reason: 用户要求开发记录放在 `devLog` 文件夹下。
  - Purpose: 在主时间线加入 Gitee OTA 接入索引。
- File path: `devLog/release.md`
  - Reason: 发布流程是独立维护主题，需要记录 OTA 地址切换原因。
  - Purpose: 记录 Gitee OTA 接入涉及的文件、原因和后续发布方式。

## Bug Record

- Time: 2026-07-01 18:09
- Symptoms: GitHub 下载慢，S3 存储不可用。
- Attempted fix: 改用 Gitee raw 承载 `update.json`，Gitee Release 承载 APK。
- Temporary solution: GitHub Release 继续作为备份发布渠道。

## Verification

- `Get-Content -Raw -Encoding UTF8 update.json | ConvertFrom-Json`
  - Result: passed. Root `update.json` is valid JSON.
- `.\gradlew.bat testDebugUnitTest --console=plain`
  - Result: passed.

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`
## 2026-07-01 18:32 - 校正 Gitee v1.2.8 下载地址

## File Changes

- File path: `update.json`
  - Reason: Gitee Release tag 已改为 `v1.2.8`，OTA APK 地址需要与正式版本号保持一致。
  - Purpose: 将当前 OTA APK 地址设置为 `/releases/download/v1.2.8/TVBox-v1.2.8.apk`。
- File path: `devLog/release.md`
  - Reason: 发布流程需要记录本次 Gitee 链路验证结果。
  - Purpose: 记录 Gitee raw 清单和 APK 下载地址验证结果。

## Bug Record

- Time: 2026-07-01 18:32
- Symptoms: Gitee Release tag 已从 `1.28` 调整为 `v1.2.8`。
- Attempted fix: 将 `update.json` 中的 `apkUrl` 改回正式版本 tag `v1.2.8`。
- Temporary solution: 后续保持 Gitee Release tag 与应用版本一致。

## Verification

- `Invoke-WebRequest -Uri "https://gitee.com/zhen-xin/tv-box/raw/agent/update.json"`
  - Result: HTTP 200.
- `Invoke-WebRequest -Uri "https://gitee.com/zhen-xin/tv-box/releases/download/v1.2.8/TVBox-v1.2.8.apk" -Method Head`
  - Result: HTTP 200.
- `git ls-remote gitee "refs/tags/*"`
  - Result: `v1.2.8` tag exists on Gitee.

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-07-01 18:03 - 撤回 S3 发布流程

## File Changes

- File path: `scripts/publish-release-assets.ps1`
  - Reason: S3 存储端上传不可用，继续维护 S3 上传脚本会增加发布复杂度。
  - Purpose: 删除 S3 发布脚本，避免后续发版误用不可用链路。
- File path: `README.md`
  - Reason: README 当前仍描述 S3 下载 APK 的流程，但实际链路不可用。
  - Purpose: 恢复为 GitHub Release 上传 APK 和 `update.json` 的说明。
- File path: `devLog/README.md`
  - Reason: 用户要求开发记录放在 `devLog` 文件夹下。
  - Purpose: 在主时间线加入撤回 S3 发布流程索引。
- File path: `devLog/release.md`
  - Reason: 发布流程是独立维护主题，需要记录方案撤回原因。
  - Purpose: 记录 S3 发布脚本删除、README 恢复和后续 Gitee 方向。

## Bug Record

- Time: 2026-07-01 18:03
- Symptoms: S3 上传不可用，V4 鉴权返回 401，V2 尝试返回服务端 500。
- Attempted fix: 删除当前 S3 发布脚本和 README 中的 S3 发布说明。
- Temporary solution: 继续使用 GitHub Release；后续可切换到 Gitee 承载 OTA 清单和 APK。

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-07-01 10:40 - S3 发布流程

## File Changes

- File path: `scripts/publish-release-assets.ps1`
  - Reason: GitHub Release 下载 APK 在电视盒子上速度不稳定，即使代理也可能很慢。
  - Purpose: 新增发布脚本，生成指向 S3 的 `update.json`，并内置 AWS Signature V4 上传能力，支持把 APK 上传到 S3、把 APK 和 `update.json` 上传到 GitHub Release。
- File path: `README.md`
  - Reason: 发布链路从单纯 GitHub 下载改为 GitHub 获取清单、S3 下载 APK。
  - Purpose: 说明 S3 本地配置、`update.json` 示例、DryRun、正式上传命令，以及无需安装 AWS CLI。
- File path: `devLog/README.md`
  - Reason: 用户要求开发记录放在 `devLog` 文件夹下。
  - Purpose: 在主时间线加入 S3 发布流程索引。
- File path: `devLog/release.md`
  - Reason: 发布流程是独立维护主题，需要记录脚本、文档和验证结果。
  - Purpose: 记录 S3 发布流程涉及的文件、原因、目的和验证结果。

## Bug Record

- Time: 2026-07-01 10:40
- Symptoms: 无新增缺陷；本次为发布链路优化。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Verification

- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\publish-release-assets.ps1 -VersionName 1.2.8 -VersionCode 10208 -S3Bucket c68393c9e4fe40e88ec2a07527326176 -DryRun`
  - Result: passed. Generated S3 `apkUrl` and local `update.json`; upload was intentionally skipped.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\publish-release-assets.ps1 -VersionName 1.2.8 -VersionCode 10208`
  - Result: first run failed before upload because Windows PowerShell had not loaded `System.Net.Http`; script now loads the assembly explicitly before creating `HttpClient`.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\publish-release-assets.ps1 -VersionName 1.2.8 -VersionCode 10208 -NoAcl`
  - Result: upload reached S3 but the connection failed while streaming content; script now sends a byte array body with explicit `Content-Length`.

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-07-01 09:36 - v1.2.8

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 发布新版本需要提高应用版本号，确保 OTA 能识别为可更新版本。
  - Purpose: 将 `versionCode` 升级到 `10208`，将 `versionName` 升级到 `1.2.8`。

- File path: `CHANGELOG.md`
  - Reason: 已完成的自定义视频接口、手机播放手势和首页滚动体验需要归档到正式版本。
  - Purpose: 新增 `v1.2.8 - 2026-07-01` 发布记录，并清空未发布条目。

- File path: `README.md`
  - Reason: README 中安装示例、OTA 示例和发布命令仍指向旧版本。
  - Purpose: 更新示例版本到 `v1.2.8` / `10208`，并同步 release APK 的 SHA-256 与大小。

- File path: `devLog/README.md`
  - Reason: 用户要求开发记录放在 `devLog` 文件夹下。
  - Purpose: 在主时间线加入 v1.2.8 发布索引。

- File path: `devLog/release.md`
  - Reason: 发布流程是独立维护主题，需要记录版本、资产和验证结果。
  - Purpose: 记录 v1.2.8 发布涉及的文件、原因、目的和验证结果。

## Bug Record

- Time: 2026-07-01 09:36
- Symptoms: 无新增缺陷；本次为版本发布整理。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Verification

- `.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease --console=plain`
  - Result: passed.
- `E:\Soft\Tools\AndroidSDK\build-tools\36.1.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\TVBox-v1.2.8.apk`
  - Result: passed. Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`.
- `git diff --check`
  - Result: passed. Only line-ending warnings were reported.
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.2.8.apk`
  - Size: `4721013`
  - SHA-256: `598bef37d28f16898991395ea2a89e092c6320908f82ca381852d4e1403ab030`
- GitHub Release upload will be performed after commit/tag so release assets match committed source.

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-06-28 20:56 - v1.2.7

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 发布新版本需要提高应用版本号，确保 OTA 能识别为可更新版本。
  - Purpose: 将 `versionCode` 升级到 `10207`，将 `versionName` 升级到 `1.2.7`。

- File path: `CHANGELOG.md`
  - Reason: 设置页大模型配置和手机扫码配置能力需要归档到正式版本。
  - Purpose: 新增 `v1.2.7 - 2026-06-28` 发布记录，并清空未发布条目。

- File path: `README.md`
  - Reason: README 中安装示例、OTA 示例和功能说明仍指向旧版本。
  - Purpose: 更新示例版本到 `v1.2.7` / `10207`，并说明手机扫码配置模型名称和 API Key。

- File path: `devLog/README.md`
  - Reason: 用户要求开发记录放在 `devLog` 文件夹下。
  - Purpose: 在主时间线加入 v1.2.7 发布索引。

- File path: `devLog/release.md`
  - Reason: 发布流程是独立维护主题，需要记录版本、资产和验证结果。
  - Purpose: 记录 v1.2.7 发布涉及的文件、原因、目的和验证结果。

## Bug Record

- Time: 2026-06-28 20:56
- Symptoms: 无新增缺陷；本次为版本发布整理。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Verification

- `.\gradlew.bat testDebugUnitTest assembleDebug --console=plain`
  - Result: passed.
- `.\gradlew.bat assembleRelease --console=plain`
  - Result: passed.
- `E:\Soft\Tools\AndroidSDK\build-tools\36.1.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\TVBox-v1.2.7.apk`
  - Result: passed. Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`.
- `git diff --check`
  - Result: passed. Only line-ending warnings were reported.
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.2.7.apk`
  - Size: `4671861`
  - SHA-256: `f1fb17d27f90ecd853382d103a004e03a44395d26d6c8e18d0ab6000763fb088`
- GitHub Release upload will be performed after commit/tag so release assets match committed source.

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-06-28 17:35 - 覆盖发布 v1.2.6

## File Changes

- File path: `CHANGELOG.md`
  - Reason: 首页资源切换和 AI 焦点优化需要归入当前 v1.2.6 说明。
  - Purpose: 在 `v1.2.6 - 2026-06-28` 版本块补充本次覆盖发布内容。
- File path: `README.md`
  - Reason: 同一个 v1.2.6 APK 重新构建后 SHA-256 与大小发生变化。
  - Purpose: 同步 `update.json` 示例中的 `apkSha256` 和 `apkSize`。
- File path: `devLog/release.md`
  - Reason: 用户要求本次更新仍发布为 v1.2.6，不新增版本号。
  - Purpose: 记录同版本覆盖发布的产物信息和验证结果。

## Verification

- `.\gradlew.bat assembleRelease --console=plain`
  - Result: passed.
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.2.6.apk`
  - Size: `4622709`
  - SHA-256: `406bdea9a3b0a107790945d3bba1d79f81c46171f765068ef7a2c99feb4ac9e8`
- GitHub Release asset upload:
  - Will be performed after commit/tag update so assets match committed source.

## Bug Record

- Time: 2026-06-28 17:35
- Symptoms: 无新增缺陷；本次为同版本覆盖发布。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-06-28 17:01 - v1.2.6

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 发布新版本需要提高应用版本号，确保 OTA 能识别为可更新版本。
  - Purpose: 将 `versionCode` 升级到 `10206`，将 `versionName` 升级到 `1.2.6`。

- File path: `CHANGELOG.md`
  - Reason: 已完成的 AI 找片和语音修复需要归档到正式版本。
  - Purpose: 新增 `v1.2.6 - 2026-06-28` 发布记录，并清空未发布条目。

- File path: `README.md`
  - Reason: README 中安装示例、OTA 示例和发布命令仍指向旧版本。
  - Purpose: 更新示例版本到 `v1.2.6` / `10206`，并在构建后同步 release APK 的 SHA-256 与大小。

- File path: `devLog/README.md`
  - Reason: 用户要求开发记录放在 `devLog` 文件夹下。
  - Purpose: 在主时间线加入 v1.2.6 发布索引。

- File path: `devLog/release.md`
  - Reason: 发布流程是独立维护主题，需要记录版本、资产和验证结果。
  - Purpose: 记录 v1.2.6 发布涉及的文件、原因、目的和验证结果。

## Bug Record

- Time: 2026-06-28 17:01
- Symptoms: 无新增缺陷；本次为版本发布整理。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Verification

- `.\gradlew.bat testDebugUnitTest --console=plain`
  - Result: passed.
- `.\gradlew.bat assembleRelease --console=plain`
  - Result: passed.
- `E:\Soft\Tools\AndroidSDK\build-tools\36.1.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\TVBox-v1.2.6.apk`
  - Result: passed. Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`.
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.2.6.apk`
  - Size: `4606325`
  - SHA-256: `3a375705659488a45e40dd20d9abb986b84c457518168351b6f29c5b2e762937`
- GitHub Release upload will be performed after commit/tag so release assets match committed source.

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`

## 2026-06-25 08:22 - v1.2.5

## File Changes

- File path: `app/build.gradle.kts`
  - Reason: 发布新版本需要提高应用版本号，确保 OTA 能识别为可更新版本。
  - Purpose: 将 `versionCode` 升级到 `10205`，将 `versionName` 升级到 `1.2.5`。

- File path: `CHANGELOG.md`
  - Reason: 已完成的播放管家和 OTA 权限改动需要归档到正式版本。
  - Purpose: 新增 `v1.2.5 - 2026-06-25` 发布记录，并清空未发布条目。

- File path: `README.md`
  - Reason: README 中安装示例、OTA 示例和发布命令仍指向旧版本。
  - Purpose: 更新示例版本到 `v1.2.5` / `10205`，并同步 release APK 的 SHA-256 与大小。

- File path: `devLog/README.md`
  - Reason: 用户要求开发记录放在 `devLog` 文件夹下。
  - Purpose: 在主时间线加入 v1.2.5 发布索引。

- File path: `devLog/release.md`
  - Reason: 发布流程是独立维护主题，需要记录版本、资产和验证结果。
  - Purpose: 记录 v1.2.5 发布涉及的文件、原因、目的和验证结果。

## Bug Record

- Time: 2026-06-25 08:22
- Symptoms: 无新增缺陷；本次为版本发布整理。
- Attempted fix: 不适用。
- Temporary solution: 不适用。

## Verification

- `.\gradlew.bat testDebugUnitTest --console=plain`
  - Result: passed.
- `.\gradlew.bat assembleRelease --console=plain`
  - Result: passed.
- `E:\Soft\Tools\AndroidSDK\build-tools\36.1.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\TVBox-v1.2.5.apk`
  - Result: passed. Certificate DN: `CN=TVBox, OU=TVBox, O=TVBox, L=Unknown, ST=Unknown, C=CN`.
- Release asset:
  - APK: `app/build/outputs/apk/release/TVBox-v1.2.5.apk`
  - Size: `4573449`
  - SHA-256: `54a1d50f713d9b1d8dac2383d493c5f2b767dbc90980ae0e6f841d61a9c844ef`

## Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/release.md`
