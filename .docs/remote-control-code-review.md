# TVBox 操控机制代码审查清单

> 审查范围：`app/src/main/java/com/tvbox/app/ui/` + `TvBoxViewModel.kt` + `data/`
> 审查时间：2026-11-03
> 项目路径：`G:\git\tvbox`

---

## 一、IPTV 直播操控（LiveScreen.kt + TvBoxViewModel.kt）

### 提示词 1：频道与线路的遥控器交互

#### ① 左右切台首尾循环判断方式

**代码位置：** `TvBoxViewModel.kt:1646–1674`

```kotlin
// playNextLiveChannel (L1646)
val nextIndex = (state.liveChannelIndex + 1) % channels.size  // ✅ 取模循环

// playPreviousLiveChannel (L1661)
val previousIndex = if (state.liveChannelIndex <= 0) channels.lastIndex
                    else state.liveChannelIndex - 1  // ⚠️ 显式首尾判断（非取模）
```

**结论：** 下一台用取模，上一台用 `if` 首尾判断。两种实现逻辑等价，但代码风格不一致，建议统一为取模写法 `((index - 1) % size + size) % size`。

#### ② 上下切线路时单线路行为

**代码位置：** `TvBoxViewModel.kt:1706–1731`

```kotlin
fun playNextLiveLine(): Boolean {
    val lines = channel.lines
    if (lines.size <= 1) return false  // ← 静默返回 false，无提示
    ...
}
fun playPreviousLiveLine(): Boolean {
    if (lines.size <= 1) return false  // ← 同上
    ...
}
```

**结论：** 单线路时按键完全无效，无任何 UI 提示（无 Toast、无 prompt）。TV 端用户按上下键无反应，体验有缺陷。

#### ③ 数字选台超时清空机制

**代码位置：** `LiveScreen.kt:179, 217–221, 486–496, 1343–1345`

```kotlin
private const val CHANNEL_NUMBER_COMMIT_DELAY_MS = 1_000L  // 1秒
private const val MAX_CHANNEL_NUMBER_DIGITS = 4            // 最多4位

fun handleNumberKey(number: Int): Boolean {
    channelNumberInput = (channelNumberInput + number.toString())
        .takeLast(MAX_CHANNEL_NUMBER_DIGITS)   // 超过4位截断
    showPrompt("频道 $channelNumberInput")
    channelNumberNonce++
    return true
}

// LaunchedEffect(channelNumberNonce) 中：
delay(CHANNEL_NUMBER_COMMIT_DELAY_MS)  // 1秒后自动提交
```

**结论：**
- 超时值：**1 秒**（常量 `CHANNEL_NUMBER_COMMIT_DELAY_MS`），在 `LiveScreen.kt:1343`
- 最长位数：**4 位**（`MAX_CHANNEL_NUMBER_DIGITS`）
- 机制：每次按键后启动 1 秒延迟，新按键会重置 nonce 触发新延迟，等价于"最后一次按键后 1 秒提交"

---

### 提示词 2：频道列表的焦点与返回栈

#### ① 弹出后焦点默认落点

**代码位置：** `LiveScreen.kt:928–955`

```kotlin
val safeSelectedIndex = state.liveChannelIndex.coerceIn(0, channels.lastIndex)
val visibleSelectedIndex = visibleChannels.indexOfFirst {
    it.number == channels.getOrNull(safeSelectedIndex)?.number
}
val focusTargetIndex = if (visibleSelectedIndex >= 0) visibleSelectedIndex else 0
```

**结论：** 焦点默认落在**当前正在播放的频道**（优先命中），找不到时回落到 index 0。

#### ② 列表内方向键是否实时预览

**代码位置：** `LiveScreen.kt:586–602`

```kotlin
AndroidKeyEvent.KEYCODE_DPAD_UP -> {
    if (channelListVisible) {
        actions.playPreviousLiveChannel()   // ← 直接换台
        keepChannelListVisibleWhileBrowsing()
    } else { ... }
    true
}
```

**结论：** 方向键在频道列表内**直接切换频道并立即播放**，不是预览。焦点移动和换台同步发生。

#### ③ 返回键两级返回实现

**代码位置：** `LiveScreen.kt:273–296`

```kotlin
// 页面级 BackHandler（默认优先级最低）
BackHandler {
    if (isMobile) showExitLiveConfirm = true
    else actions.goBack()
}

// 面板级 BackHandler（优先级更高，覆盖上面的）
BackHandler(enabled = channelListVisible) {
    if (state.liveGroupPanelStage == LiveGroupStage.Channels) {
        actions.backToLiveGroups()       // Channels → Groups
    } else {
        channelListVisible = false       // Groups → 关闭面板
        showMobileControls()
        if (isTelevision) playerFocusRequester.requestFocus()
    }
}
```

**结论：**
- 使用 **Compose 嵌套 BackHandler** 分层，优先级高的 `enabled` 参数覆盖底层。
- 两级返回链：`Channels 列表 → Groups 分类 → 关闭面板`
- **菜单键行为：** `LiveScreen.kt:575–584` 处，MENU/INFO 键在频道列表打开时直接关闭列表，不在列表时打开列表。

---

### 提示词 3：数字选台映射规则

**代码位置：** `TvBoxViewModel.kt:2095–2101`

```kotlin
fun selectLiveChannelNumber(number: Int): Boolean {
    val index = number - 1           // ← 频道号直接映射为列表索引（1-based → 0-based）
    val channels = _state.value.liveChannels
    if (index !in channels.indices) return false
    selectLiveChannel(index)
    return true
}
```

**结论：**
- 频道号 = **列表顺序索引 + 1**，不看 `tvg-chno` 属性，也不重新排序。
- 最长 **4 位**数字。
- 要让央视一套固定为频道号 1，需修改**直播源文件**使其排在第一位，或改代码添加自定义排序映射（当前无此功能）。

---

## 二、平台直播操控（PlatformLiveScreen.kt + TvBoxViewModel.kt）

### 提示词 4：多级分类的遥控器导航

#### ① 进入下一级时的焦点传递

**代码位置：** `PlatformLiveScreen.kt:179–184, 205`

```kotlin
// 一级分类页：聚焦第一项
LaunchedEffect(state.platformLiveParentCategories) {
    if (!touchMode && state.platformLiveParentCategories.isNotEmpty()) {
        firstCardFocusRequester.requestFocus()
    }
}
// 卡片上挂 focusRequester
modifier = if (index == 0) Modifier.focusRequester(firstCardFocusRequester) else Modifier
```

**结论：** 进入下一级时焦点**自动落到第一项**，不记忆上次位置（与首页/history 页面的 ReturnFocus 机制不同）。

#### ② 返回键焦点正确性

**代码位置：** `TvBoxViewModel.kt:2369–2408`

```kotlin
TvScreen.PlatformLive -> {
    when (current.platformLiveDestination) {
        Player     -> 返回 platformLivePlayerReturnDestination
        Rooms      -> Categories
        Favorites  -> Sites
        Categories -> ParentCategories
        ParentCategories -> Sites
        Sites      -> Home
    }
}
```

**结论：** 返回时按状态机弹出栈，**不保留焦点位置**（每次进入新页面都 reset 到第一项 focus）。

#### ③ 快速连按方向键焦点防抖

**代码位置：** 未找到专门的防抖/节流逻辑。

**结论：** 焦点切换**无节流**，快速按方向键会连续高亮切换。Compose 焦点系统本身有轻微硬件延迟，但代码层面没有额外保护。

---

### 提示词 5：直播间收藏卡片操控

#### ① 收藏卡片的遥控器操作

**代码位置：** `PlatformLiveScreen.kt:350` + ViewModel.kt:1841–1853

```kotlin
// 卡片上显示收藏星
isFavorite = state.platformLiveFavorites.any { it.id == room.id }
```

**结论：** 收藏卡片与普通直播间卡片样式相同，**按确认直接播放**该直播间。收藏/取消收藏通过星形按钮（触摸模式在封面右上角，TV 模式需在卡片内找操作入口）。

#### ② 收藏数据存储

**代码位置：** `data/PlatformLiveFavoritesRepository.kt:19–51`

```kotlin
private val prefs = context.getSharedPreferences("platform_live_favorites", Context.MODE_PRIVATE)
// 存储格式：JSON 数组，key="rooms"
```

**结论：**
- 存储：**SharedPreferences**（非 DataStore、非数据库）
- 排序：按**插入时间倒序**（`toggleFavoriteRecords` 总是 `listOf(new) + current`）
- 去重：按 `site:roomId` 组合键去重

#### ③ 下播/解析失败时的焦点

**代码位置：** `PlatformLiveScreen.kt:984–988`

```kotlin
if (session.interrupted && player != null) {
    ControlButton("继续播放", { session.play(player); actions.reconnectPlatformLiveChannel() }, ...)
}
```

**结论：** 收藏房间若已下播或解析失败，播放器进入 `session.interrupted` 状态，显示"继续播放"按钮。焦点落在该按钮上，用户按确认触发重连。

---

### 提示词 6：虎牙续流代理与操控交互

**代码位置：** `data/FlvContinuityProxy.kt` + `PlatformLiveScreen.kt:922–947`

#### ① 上下切线路时代理行为

```kotlin
// FlvContinuityProxy.kt:28
fun shouldWrap(site: String, protocol: String): Boolean =
    protocol.equals("flv", ignoreCase = true) && site.lowercase() in supportedSites
// supportedSites = {"huya", "douyu"}
```

**结论：** 代理包装发生在解析层（URL 替换），切线路时重新走解析→代理流程。播放器侧重新 prepare，期间显示**加载圈**（`setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)`）。

#### ② 代理端口释放

代理通过 Native 方法 `Platformlive.startFlvProxy()` 管理，代码中无显式 stop/close 调用。依赖 JNI 层的引用计数释放。**存在潜在泄漏风险**（需检查 Go 侧代码确认）。

#### ③ 增加"强制重连代理"快捷键

应修改 `PlatformLiveScreen.kt:922–947` 的 `onPreviewKeyEvent` 块，在 `else -> false` 分支前添加新 keyCode 处理，调用 `actions.reconnectPlatformLiveChannel()`。

---

## 三、播放器操控（PlayerScreen.kt）

### 提示词 7：快进快退与长按

**代码位置：** `PlayerScreen.kt:708–732, 1253–1277`

```kotlin
private const val REMOTE_SEEK_STEP_MS = 10_000L     // 10秒
private const val REMOTE_SEEK_REPEAT_INTERVAL_MS = 150L  // 连发间隔

// PlayerRemoteKeyState.shouldHandleSeek()
fun shouldHandleSeek(eventTimeMs: Long, repeatCount: Int): Boolean {
    if (repeatCount == 0 ||
        lastSeekEventTimeMs == Long.MIN_VALUE ||
        eventTimeMs - lastSeekEventTimeMs >= REMOTE_SEEK_REPEAT_INTERVAL_MS
    ) {
        lastSeekEventTimeMs = eventTimeMs
        return true
    }
    return false
}
```

**结论：**
- 步长：**10 秒**（常量 `REMOTE_SEEK_STEP_MS`，`PlayerScreen.kt:1276`），改 15 秒只需改此常量。
- 长按逻辑：**固定间隔触发**（150ms/次），无加速度递增。
- 提交时机：**按下去立即 seek**（KeyDown 触发），不是松开才提交。
- 松开后：KeyUp 触发 `finishRemoteSeek()`，仅重置 `seekChanged` 标志，不额外 seek。

---

### 提示词 8：返回键三级返回与画面比例记忆

#### ① 三级返回判定顺序

**代码位置：** `PlayerScreen.kt:141–164`

```kotlin
BackHandler {
    when {
        controlsPanel != PlayerPanel.None -> controlsPanel = PlayerPanel.None  // 第1级：关子面板
        controlsVisible -> { controlsVisible = false; playerFocusRequester.requestFocus() }  // 第2级：关控制栏
        else -> actions.goBack()  // 第3级：退出播放页
    }
}
```

**结论：** 使用单个 BackHandler + **状态机 `when` 分支**实现，优先级按代码顺序从上到下。

#### ② 画面比例记忆维度

**代码位置：** `TvBoxViewModel.kt:767–771`

```kotlin
fun updateVideoScaleMode(mode: VideoScaleMode) {
    val settings = _state.value.appSettings.copy(videoScaleMode = mode)
    saveSettings(settings)
    _state.update { it.copy(appSettings = settings) }
}
```

**结论：** 画面比例存储在 **AppSettings（全局）**，非按影片维度。**全局一个值**，所有影片共享同一缩放模式。

#### ③ 系统栏全屏恢复逻辑

**代码位置：** `ui/PlayerViewport.kt:32–75`

- 使用 `WindowInsetsControllerCompat` 隐藏系统栏
- 旋转/切后台返回：`LifecycleEventObserver.ON_RESUME` 时调用 `hideBars()`
- 手机版播放页：每 3 秒巡检一次（`IMMERSIVE_REHIDE_INTERVAL_MS = 3_000L`），发现系统栏显示则重新隐藏
- 遥控器操作**不会打断**恢复流程（巡检独立于按键）

---

### 提示词 9：电视模式 vs 手机模式操控差异

#### ① 电视模式是否禁用触摸手势

**代码位置：** `PlayerScreen.kt:691–973`（touchGesture 相关逻辑）

电视模式（`isTelevision=true`）下，触摸手势代码仍在，但 TV 端通常无触摸屏。代码未显式禁用，通过 `isTelevision` 条件跳过部分触摸相关分支。

**结论：** 电视模式**未硬禁用**触摸手势，但实际 TV 设备无触屏，触摸不会触发。

#### ② 手机摸手势在电视模式下的表现

**结论：** 亮度和音量滑动手势在电视模式同样不生效（无触屏），但代码未做条件屏蔽，是隐式跳过。

#### ③ 设备模式优先级判断

**代码位置：** `DeviceModeDetection.kt:10–15` + `AppSettings.kt:28`

```kotlin
// DeviceModeDetection.kt
internal fun Context.detectDeviceMode(): DeviceMode {
    val television = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    return if (television) DeviceMode.Television else DeviceMode.Mobile
}
```

**结论：**
- 检测优先级：`UI_MODE_TYPE_TELEVISION > FEATURE_LEANBACK > FEATURE_TELEVISION`
- **用户设置优先于系统识别**：`AppSettings.deviceMode` 默认值为 `DeviceMode.Television`（`AppSettings.kt:28`），手动设置后永久覆盖检测。
- 强制手机用电视模式：设置 `deviceMode = Television` 后，遥控器按键映射正常生效（`LiveScreen.kt:147` 处 `isTelevision` 基于设置值）。

---

## 四、全局操控（TvBoxApp.kt + MainActivity）

### 提示词 10：首页数字快捷键与全局按键分发

**代码位置：** `TvBoxApp.kt:421–465`

```kotlin
.onPreviewKeyEvent { event ->
    when (event.nativeKeyEvent.keyCode) {
        KEYCODE_1 -> actions.openHistory()
        KEYCODE_2 -> actions.openSearch()
        KEYCODE_3 -> actions.openAiRecommend()
        KEYCODE_4 -> actions.openLive()
        KEYCODE_5 -> actions.openPlatformLive()
        KEYCODE_6 -> actions.openSettings()
        else -> false
    }
}
```

**结论：**
- 数字快捷键作用域：**仅在首页**（TvBoxApp 根节点）。播放器/详情页按数字键无效（false 交给下级处理）。
- 播放器内数字 1/3 有独立处理：`PlayerScreen.kt:763–775` 中 `KEYCODE_1` 映射"上一集"，`KEYCODE_3` 映射"下一集"——与首页冲突但**不会冲突**，因为处于不同屏幕层级。
- 增加数字 7 直达收藏：需修改 `TvBoxApp.kt:421–465`（首页快捷键）+ `TvBoxViewModel.kt`（添加 `openFavorites()` 方法）+ 可能在 `PlayerScreen.kt` 添加对应处理。

### 提示词 11：焦点恢复与滚动位置

#### ① 状态保存机制

| 页面 | 机制 |
|------|------|
| 首页电影网格 | `rememberLazyGridState()` + 返回时 `RestoreGridFocus()`（基于 `ReturnFocus` key+index） |
| 历史记录 | `rememberReturnFocus()` + `RestoreGridFocus(historyFocus, historyGrid, items)` |
| 搜索 | `rememberSaveable` 存 query，`rememberLazyGridState()` 存滚动（不持久化配置） |
| 首页分类网格 | `rememberLazyGridState()`，context 变化时 `scrollToItem(0)` |

**结论：** 使用 **`ReturnFocus` 自定义类**（`ReturnFocus.kt`）+ `rememberSaveable` 组合，非 `LazyListState.saver`。

#### ② 从详情页返回首页的焦点

**结论：** 首页焦点默认落到**"全部"分类按钮**（`allCategoryFocusRequester`，`TvBoxApp.kt:416`），不恢复到之前点击的影片卡片。

#### ③ "继续观看"向上焦点回溯

**代码位置：** `TvBoxApp.kt:1507–1513`

```kotlin
LazyRow(...).onPreviewKeyEvent { event ->
    if (event.nativeKeyEvent.keyCode != KEYCODE_DPAD_UP) false
    else {
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0)
            onMoveToNavigation()
        true
    }
}.focusGroup()
```

**结论：** 显式拦截 `DPAD_UP`，调用 `onMoveToNavigation()` 把焦点移到顶部导航区，**非 Compose 焦点搜索默认行为**。

---

### 提示词 12：触屏与遥控器双模适配

#### ① 设备类型自动适配判定

**代码位置：** `DeviceModeDetection.kt:10–15`

判定依据（优先级从高到低）：
1. `Configuration.UI_MODE_TYPE_TELEVISION`（系统 UI 模式）
2. `PackageManager.FEATURE_LEANBACK`（Leanback 特征）
3. `PackageManager.FEATURE_TELEVISION`（电视特征）

#### ② 竖屏/横屏布局是否为同一套代码

**结论：** **同一套 Compose 代码按条件切换**，通过 `isTelevision` / `isMobile` 布尔值分支渲染不同 UI。非两套独立页面。

示例：
- `LiveScreen.kt:147`：`val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile`
- `PlayerScreen.kt:102–103`：同样的 `isTelevision` / `isMobile` 判断

#### ③ 触屏点击后焦点框残留

**代码位置：** `PlatformLiveScreen.kt:414`（`val touchMode = LocalTouchMode.current`）+ 各卡片组件

**结论：** 触摸模式下焦点高亮样式与 TV 模式不同（`touchMode` 分支处理 padding/fontSize 等）。**触屏点击后可能出现焦点框残留**——代码中无明确的 `clearFocus()` 调用，依赖 Compose 默认的触摸模式焦点清除行为。

---

## 附录：关键文件索引

| 功能模块 | 主要文件 | 关键行号 |
|----------|----------|----------|
| IPTV 按键处理 | `LiveScreen.kt` | 527–636（onPreviewKeyEvent）|
| IPTV 频道切换/线路 | `TvBoxViewModel.kt` | 1646–1732 |
| 数字选台 | `LiveScreen.kt` | 179, 217–221, 486–496 |
| 频道列表焦点 | `LiveScreen.kt` | 928–955 |
| 返回键分层 | `LiveScreen.kt` | 273–296 |
| 播放器按键 | `PlayerScreen.kt` | 691–782 |
| 快进快退逻辑 | `PlayerScreen.kt` | 708–732, 1253–1277 |
| 播放器返回级联 | `PlayerScreen.kt` | 141–164 |
| 画面比例存储 | `TvBoxViewModel.kt` | 767–771 |
| 全屏/系统栏 | `PlayerViewport.kt` | 32–75 |
| 平台直播按键 | `PlatformLiveScreen.kt` | 922–947 |
| 平台直播返回栈 | `TvBoxViewModel.kt` | 2365–2410 |
| 收藏存储 | `data/PlatformLiveFavoritesRepository.kt` | 19–51 |
| 首页快捷键 | `TvBoxApp.kt` | 421–465 |
| 焦点恢复工具 | `ReturnFocus.kt` | 全文件 |
| 设备模式检测 | `DeviceModeDetection.kt` | 全文件 |
| 设备模式设置 | `domain/AppSettings.kt` | 28, 39–48 |
