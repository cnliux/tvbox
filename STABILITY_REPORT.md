# TVBox v1.3.31 稳定性排查与加固报告

## 已修复的问题

### 1. R8 full mode 混淆（🔴 最高优先级）
**文件**: `app/proguard-rules.pro`
**问题**: 原规则只保留了 gomobile 生成的类，缺少 Media3、kotlinx.serialization、Coil 等核心依赖的保留规则。这是 "debug 正常、release 闪退" 的最常见原因。
**修复**: 已重写 proguard-rules.pro，增加：
- Media3 全量保留（ExoPlayer、HLS/DASH/SS 解析器、UI 组件）
- kotlinx.serialization 反射模型保留（@Serializable 类的 companion 和字段名）
- Coil 组件保留
- 项目自定义 data class 字段名保留
- 移除 debug-only 日志类警告

### 2. 全局崩溃处理（🔴 最高优先级）
**文件**: `app/src/main/java/com/tvbox/app/MainActivity.kt`
**问题**: 无任何 UncaughtExceptionHandler，Go panic → SIGABRT 会导致进程直接终止且无日志。
**修复**: 
- 新增 `app/src/main/java/com/tvbox/app/crash/CrashHandler.kt`
- 在 MainActivity.onCreate 第一行调用 `CrashHandler.install(this)`
- 落盘位置: `<externalCacheDir>/crash_logs/crash_<timestamp>_<thread>.txt`
- 包含字段: versionCode, versionName, device model, ABI, SDK 版本, 完整堆栈

**取舍说明**: 选择本地落盘而非 Crashlytics：
- Crashlytics 需要 Google Services 插件 + Firebase 项目配置
- 本项目为开源 TVBox 合集，无后端基础设施
- 下次启动可通过 UI 展示崩溃报告列表，用户选择是否发送
- 后续如需接入后端，只需替换 CrashLogWriter 实现

### 3. PlaybackSession 音频焦点恢复逻辑（🟠 高优先级）
**文件**: `app/src/main/java/com/tvbox/app/ui/PlaybackSession.kt`
**问题**: 音频焦点中断后，foreground() 恢复 foreground=true，但 playRequested 仍为 false。当用户按播放键时，requestPlay() 返回 true，播放器开始播放——这与 README "返回后由用户手动恢复" 的描述一致，但代码注释需要明确此行为。
**修复**: 已在代码中添加注释说明，无需修改逻辑。

### 4. FLV 代理超时保护（🟠 高优先级）
**文件**: `app/src/main/java/com/tvbox/app/data/FlvContinuityProxy.kt`
**问题**: 代理启动失败或卡死时无超时保护。
**修复**: 添加 STARTUP_TIMEOUT_MS=5000ms 超时保护和 safeClose() 方法。

---

## 已知潜在风险（需进一步验证）

### 5. 电视直播线路切换并发
**文件**: `app/src/main/java/com/tvbox/app/ui/LiveScreen.kt:383`
**问题**: LaunchedEffect(currentLine.url) 切换线路时，旧 MediaSource 未完成 release 就可能加载新的。快速连按时可能触发 ExoPlayer IllegalStateException。
**建议**: 考虑在切换前调用 `player.stop()` 或等待旧 MediaSource release。
**风险等级**: 🟡 中 - 实际触发概率较低，需要快速连按测试。

### 6. FocusRequester 时序问题
**文件**: 多处使用 `playerFocusRequester.requestFocus()`
**问题**: 在 View 尚未 layout 时调用 requestFocus() 可能失败。
**现有处理**: LiveScreen.kt:468 已有 `LaunchedEffect(Unit) { playerFocusRequester.requestFocus() }`，但部分路径缺少 thisFrame 等待。
**建议**: 在关键路径添加 `withFrameNanos {}` 等待。
**风险等级**: 🟡 中 - 主要影响 TV 遥控器操作体验。

### 7. Go panic 无法被 try-catch 捕获
**文件**: `platform_live_go/*.go`
**问题**: Gomobile bind 的 Go panic 在某些情况下会产生 SIGABRT，无法被 Kotlin try-catch 捕获。
**当前状态**: CrashHandler 会落盘，但无法阻止进程终止。
**建议**: 在 Go 侧的关键入口添加 defer recover()，但需要重新编译 libgojni.so。
**风险等级**: 🟡 中 - 取决于具体 Go 代码路径。

### 8. 搜索三源并行去重
**文件**: `app/src/main/java/com/tvbox/app/ui/TvBoxViewModel.kt`
**问题**: 多源并行搜索时使用非线程安全的集合可能导致数据不一致。
**建议**: 使用新增的 `ConcurrentCollections` 工具类。
**风险等级**: 🟡 中 - 需要代码审查确认。

---

## 不需要修复的项目

### ✅ OTA PendingIntent FLAG_IMMUTABLE
**文件**: `app/src/main/java/com/tvbox/app/data/UpdateDownloadService.kt:143`
**状态**: 已正确使用 `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`。

### ✅ ABI 差异处理
**文件**: `app/build.gradle.kts:39-43`
**状态**: Debug 默认 arm64-v8a，可选 -PtvboxEmulatorAbis=true 添加 x86。Release 固定 arm64-v8a。设计正确。

### ✅ 亮屏标志恢复
**文件**: `app/src/main/java/com/tvbox/app/ui/KeepScreenOnWhileVisible.kt`
**状态**: onDispose 正确恢复 wasKeepingScreenOn，逻辑无误。

### ✅ DownloadManager 缺失降级
**文件**: `app/src/main/java/com/tvbox/app/data/AppUpdateRepository.kt:128-138`
**状态**: 已有降级到 UpdateDownloadService 的逻辑，设计完善。

---

## 新增文件清单

| 文件 | 用途 |
|------|------|
| `app/src/main/java/com/tvbox/app/crash/CrashHandler.kt` | 全局崩溃处理器，落盘到 cacheDir |
| `app/src/main/java/com/tvbox/app/ui/PlayerSafety.kt` | ExoPlayer 安全释放工具函数 |
| `app/src/main/java/com/tvbox/app/ui/PlayerStateMachine.kt` | 串行化播放器操作的状态机（备用） |
| `app/src/main/java/com/tvbox/app/domain/ConcurrentCollections.kt` | 并发集合工具 + 指数退避重试 |

---

## 验证步骤

### Debug 构建（arm64）
```bash
cd /path/to/tvbox
./gradlew.bat assembleDebug --console=plain
```

### 单元测试
```bash
./gradlew.bat testDebugUnitTest --console=plain
```

### Monkey 测试（需要设备连接）
```bash
adb shell monkey -p com.tvbox.app --throttle 300 -v 50000
```

### 验证崩溃日志
```bash
adb shell run-as com.tvbox.app ls /data/data/com.tvbox.app/cache/crash_logs/
adb shell run-as com.tvbox.app cat /data/data/com.tvbox.app/cache/crash_logs/crash_*.txt
```

---

## 后续建议

1. **Go 侧 Panic 防护**: 在 `platform_live_go/platformlive.go` 的每个 exported 函数入口添加 `defer func() { if r := recover(); r != nil { /* convert to error */ } }()`
2. **Crashlytics 接入**: 如需云端崩溃报告，可集成 Firebase Crashlytics，需额外配置 build.gradle
3. **集成测试**: 编写 UI 自动化测试覆盖切台、切线、播放暂停等关键路径
4. **性能监控**: 添加播放健康指标上报，便于线上问题定位

---

## 控制重设计概要

### 共享内核
- `InputHandler` 抽象接口：电视端（遥控器）+ 手机端（触屏手势）共用
- `PlayerManager` 状态机：IDLE/BUFFERING/PLAYING/PAUSED/ERROR
- 串行化操作：切集/切台/换线排队执行，超时自动重试

### 电视端增强
- 焦点记忆：返回频道列表恢复上次聚焦位置
- 分组侧边栏 + 频道网格布局
- 切台信息层 3 秒自动隐藏
- 换线提示"已切换到线路 X"
- 拼音首字母搜索

### 手机端增强
- 左半屏滑动手势：亮度/音量/进度
- 双击暂停 + 双指缩放
- 控制层 3 秒自动隐藏
- PiP 支持 + 降级提示
- 虎牙代理重连轻提示

详细设计见提示词 2/3。
