# 手机模式（DeviceMode.Mobile）

## 2026-11-01 17:50 - 顶部栏源切换、直播入口与搜索改版

### 用户要求与改动

1. **手机版 AI 标签改为直播**：底部导航由 首页/历史/搜索/直播/AI 调整为 首页/历史/搜索/**电视**/**直播**；“电视”进入内置直播（`TvScreen.Live`，央视等），“直播”进入平台直播（`TvScreen.PlatformLive`，斗鱼/虎牙等），与 TV 端导航命名一致；AI 推荐不再出现在手机导航。
2. **顶部栏单行合并**：视频源下拉放在左侧，搜索入口放在右侧，最右保留设置齿轮；切换源后首页分类与内容立即刷新，当前源名同步显示在顶部栏（`MobileNavigationBar.kt` 的 `MobileTopBar`）。
3. **搜索页热搜改版**：不再把热搜词铺成整行下拉列表，改为“热门搜索”标题 + 流式标签 chips（`FlowRow`），点击标签直接搜索。
4. **搜索结果卡片化**：`SearchResultCardMobile` 改为竖向大图卡片，`LazyVerticalGrid` 横屏 3 列、竖屏 2 列，铺满内容宽度；海报比例 0.72，左上角来源角标、右下角更新信息，标题与类型信息在海报下方。
5. **整页渲染**：详情、平台直播、设置、播放器与源管理仍由 `TvBoxScreen` 整页渲染，不套首页标签内容，避免详情/平台直播被错误映射成首页。

### 设备验证（192.168.1.45，重新构建安装后）

- 顶部栏：左侧显示当前源“速播”并可展开源列表，选择其他源后首页分类与内容刷新；右侧“搜索影片...”进入搜索页；齿轮进入手机设置。
- 搜索页：清空关键词后显示“热门搜索”两行 chips；点击“小城良方”等标签后进入结果列表。
- 搜索结果：横屏 3 列，卡片为竖向大图，来源角标（如“导入测试源”）与标题、年份/状态信息正常。
- 底部导航：首页/历史/搜索/电视/直播；点击“直播”进入平台直播站点页（收藏、虎牙、斗鱼、哔哩哔哩、抖音、快手），无崩溃日志。
- 说明：首次验证时设备仍是旧 APK（`lastUpdateTime` 未包含本轮改动），重新构建安装后界面才生效。

### 相关文件

- `app/src/main/java/com/tvbox/app/ui/MobileNavigationBar.kt`
- `app/src/main/java/com/tvbox/app/ui/TvBoxApp.kt`
- `app/src/main/java/com/tvbox/app/ui/SearchScreen.kt`

## 2026-11-01 17:05 - 手机模式逐页检查与修复

### 背景

设置里的“手机模式”此前只在代码里实现，未在设备上逐页验证。本轮在 `192.168.1.45`（MuMu 模拟器，Android 12 / SDK 32，横屏 1920x1080）把手机模式的每个页面走了一遍，并修复发现的问题。

### 排查结论：设备上是旧 APK

首次检查时设备仍显示电视布局。对比发现设备已安装 APK 与本地构建哈希一致，但 dex 中检索不到 `手机远程管理（网页）` 等手机模式文案，且源码修改时间（15:51–16:07）晚于上次构建（15:19）—— 结论是 **APK 过期**，重新 `assembleDebug` 并安装后手机模式生效。后续每次改动都重新构建安装再验证。

### 修复项

1. **底部导航跑到顶部**：`MobileNavWrapper` 里 `NavigationBar` 写在内容 `Box` 之前，改为内容之后，成为真正的底部导航。
2. **导航图标显示成方块**：原用私有区字体字形（`\uE88A` 等），APK 未打包对应字体，改用 Material 矢量图标（`Icons.Filled.Home/DateRange/Search/PlayArrow/Star`，由 material3 传递引入 `material-icons-core`）。
3. **手机模式进不去设置**：底部导航没有设置入口，在顶部搜索栏右侧加齿轮按钮，调用 `openSettings`。
4. **切换标签页闪退**：`MobileSearchScreen` 用 `rememberSaveable` 保存 `TextFieldValue`，切换页面保存状态时抛 `IllegalArgumentException: MutableState containing TextFieldValue(...) cannot be saved`；改为 `remember`。
5. **打开设置闪退**：`MobileNavScreenContent` 外层 `SaveableStateProvider(state.screen.name)` 与 `TvBoxScreen` 内部的同名 provider 重复，抛 `Key Settings was used multiple times`；整页页面（详情/播放/设置/源管理）直接把 `state.screen` 交给 `TvBoxScreen`，其余标签页才包 provider。
6. **点海报像没反应**：`state.screen` 已经是 `Detail`，但手机模式的分发把 `Detail` 映射成 `Home` 标签页内容，实际仍渲染首页。详情页（以及 `PlatformLive`）也改为整页渲染。
7. **播放页没有返回入口**：手机播放页只有底部控制条，`PlayerControlsMobile` 增加顶部标题栏（返回按钮 + 影片名 + 集名）；点“返回”会保存进度后退出播放。
8. **播放页“更多”面板会被自动隐藏**：控件 5 秒自动隐藏不感知手机端“更多”面板，选集/切线路来不及操作；把面板展开状态回调给 `PlayerScreen`，展开期间暂停自动隐藏。
9. **手机设置页“手机远程管理（网页）”无反应**：`MobileSettingsScreen` 没有渲染 `RemoteManageDialog`，状态虽然置位却没有弹窗；补上条件渲染，弹窗显示二维码与 `http://<设备IP>:9978/`。

### 设备验证（重新构建安装后）

- 首页：顶部搜索栏（搜索 + 齿轮）+ 分类 chips + 豆瓣热播网格 + 底部五标签导航；分类切换正常。
- 详情页：点海报进入，隐藏搜索栏与底部导航，返回键回到首页；搜索结果进入详情同样正常。
- 播放页：顶部“返回 + 片名 + 第01集”，底部进度条与暂停/下一集/投屏/选集/···；选集面板可滚动、展开 10 秒后仍保持；返回回到详情页并保留进度。
- 历史 / 搜索 / 直播 / AI：历史列表、热搜、搜索结果列表、直播 CCTV 播放与频道列表、AI 找片（未配置 Key 时提示“请先在设置中配置 AI API Key”）均正常，无崩溃日志。
- 设置页：卡片列表滚动正常；视频接口管理、直播源管理、全部源管理均为整页且可返回；远程管理弹窗显示二维码与地址。
- `assembleDebug`、`testDebugUnitTest` 通过。

### 相关文件

- `app/src/main/java/com/tvbox/app/ui/MobileNavigationBar.kt`
- `app/src/main/java/com/tvbox/app/ui/TvBoxApp.kt`
- `app/src/main/java/com/tvbox/app/ui/SearchScreen.kt`
- `app/src/main/java/com/tvbox/app/ui/PlayerScreen.kt`
- `app/src/main/java/com/tvbox/app/ui/PlayerControls.kt`
