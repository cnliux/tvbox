# 虎牙 / 斗鱼 FLV 连续代理 · 续流验证报告

> 项目：聚合视频（com.tvbox.app） · 模块：`platform_live_go` / `app/src/main/java/com/tvbox/app/data/FlvContinuityProxy.kt`
> 记录日期：2026-11-11 · 适用版本：v1.0.4 起
> 本文不含任何 AI / 智能推荐 / 大模型能力相关内容。

---

## 一、问题现象

| 平台 | 现象 | 触发条件 |
| --- | --- | --- |
| 虎牙 | 播放约 2 分钟后，画面**重复回放 1~2 秒**（旧画面再播一遍），随后才继续 | 上游 CDN 单次 HTTP-FLV 会话到期（实测约 120~137s 被服务端正常关闭），代理重新解析、拿新签名续拉 |
| 斗鱼 | 同一时间点出现明显的**断流 / 重新缓冲**，感觉像播放器重新连接 | 同一续流路径：正常 EOF 后重新解析斗鱼签名流 |

播放器只连本机 `127.0.0.1/live.flv` 的一条「永不结束」FLV，上游重连由 Go 代理内部完成，Kotlin 侧无感知。

---

## 二、关键结论：**不能用时间戳测重复画面**

最初按「重连后新流时间戳从 0 开始 → 时间轴回退 → 播放器重播旧缓冲」的假设排查，但代码里已有 `spliceBase` / `acceptTs`：

- 每次重连把新流首帧时间戳整体顺延到 `lastSentTs + 帧间隔` 之后；
- `acceptTs` 拒绝回退时间戳。

因此代理**输出**时间轴始终严格单调，用时间戳检查只会得到「0 次回退」，完全测不出重复画面。

> 实测：`输出时间戳回退次数 = 0`，但同一次录制中重复画面帧数 = 30 帧 / 957ms。
> 时间轴连续 ≠ 画面不重复。

**正确判据**：比较 FLV video tag 的**帧内容指纹**。重连后 CDN 带回的旧缓冲，其编码帧内容与上一段已发出的帧完全一致；按 `tagType + data` 做 FNV-1a 哈希，找出「同一指纹第二次出现」的连续区间，即可量出重复时长。

---

## 三、检测方法

### 3.1 离线诊断器（本地工具）

`web/flv_replay_analyzer.html`（单文件、离线、被 `.gitignore` 忽略，仅本地使用）：
在浏览器里载入录制好的 `.flv`，解析 FLV header + tag，按 video tag 内容指纹识别重连回放区间，输出重复帧数、连续重复区间数、最长重复时长。

关键修正：`detectReconnects(tags, baseTs)` 按「距流首帧是否超过 1 秒」判定重连点，避免录制从流中段开始时把首个 AVC 序列头误判为重连。

用 Node 构造 1.5 秒回放的模拟流验证过解析器：能量出 45 帧 / 约 1485ms 的重复区间。

### 3.2 端到端检测程序（真实代理代码，仓库内可复跑）

`platform_live_go/flv_proxy_replay_detect_test.go` → `TestFlvProxyReplayDetection`

不是 mock 逻辑，而是直接起 `newFlvProxy` + 本地 `httptest` 上游，模拟：

1. 段 1：3 秒正常直播（90 帧，payload 每帧唯一，每 30 帧一个关键帧）；
2. 上游正常 EOF（模拟 CDN 关流），代理重新解析续拉；
3. 段 2：新流时间戳**从 0 开始**，先回放段 1 最后 45 帧（1.5 秒旧缓冲），再给 30 帧新内容；
4. 客户端读代理输出，按帧指纹统计重复帧、连续重复区间、最长重复时长。

```bash
cd platform_live_go
go test -count=1 -v -run TestFlvProxyReplayDetection ./          # 只出检测报告
TVBOX_REPLAY_ASSERT=1 go test -count=1 -run TestFlvProxyReplayDetection ./  # 作为回归守卫（重复必须为 0）
```

### 3.3 指纹算法

```go
func flvFrameFingerprint(tagType byte, data []byte) uint64 {
    h := fnv.New64a()
    _, _ = h.Write([]byte{tagType})
    _, _ = h.Write(data)
    return h.Sum64()
}
```

`tagType` 参与哈希，避免音频帧与视频帧偶然碰撞；窗口保留最近 12 秒（`flvProxyReplayWindowMs = 12000`）已转发帧，按输出时间戳淘汰，内存有界。窗口长度必须大于 CDN 实际回放长度：真实「一起看」房间实测回放 6.08 秒，早期 5 秒窗口会漏掉最早一段旧帧。

### 3.4 真实 CDN 流复核（关键）

`platform_live_go/flv_proxy_live_test.go` → `TestFlvProxyLiveStreamReplay`：`newFlvProxy` 连真实虎牙 CDN，录制 200 秒（覆盖约 120 秒的关流周期），按同一套帧指纹算法统计。默认跳过，需显式开启：

```bash
TVBOX_LIVE_E2E=1 TVBOX_LIVE_ROOM=11336726 TVBOX_LIVE_DURATION_SEC=200 \
  TVBOX_LIVE_ASSERT=1 go test -count=1 -v -timeout 10m -run TestFlvProxyLiveStreamReplay ./
```

Android 侧不便录制（见 11.5），因此把帧指纹录制放在 PC 独立进程。**这一步才是最终判据**：本地 httptest 模拟的 1.5 秒回放与真实 CDN 的「全新关键帧 + 6 秒旧缓冲」形态不同，只跑模拟用例会漏掉真实缺陷。

---

## 四、实测数据

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| 上游会话数 | 3（1 首段 + 2 续拉） | 3 |
| 代理输出 tag 数 | 含 30 帧回放 | 111（视频帧 110） |
| 输出时间戳回退次数 | 0 | 0 |
| **重复画面帧数** | **30** | **0** |
| **连续重复区间数** | **1** | **0** |
| **最长重复时长** | **957 ms（0.96 s）** | **0 ms** |
| 回归断言 | 失败 | `PASS`（`TVBOX_REPLAY_ASSERT=1`） |

上表是**本地 httptest 模拟流**的结果。按用户建议改用「虎牙·一起看」入口后，真实 CDN 复核暴露了模拟场景没覆盖的缺陷，因此又做了一轮修复：

| 真实 CDN 指标（huya / 11336726，200 秒） | 第一轮修复后 | 最终（第三轮） |
| --- | --- | --- |
| 上游 CDN 关流时刻 | 120.5 s | 120.6 s |
| `reconnects` / `upstreamSessions` | 1 / 2 | 1 / 2 |
| `lastError` | `""` | `""` |
| 输出时间戳回退次数 | 0 | 0 |
| **重复画面帧数** | **153** | **0** |
| **连续重复区间数** | **1** | **0** |
| **最长重复时长** | **6080 ms（6.08 s）** | **0 ms** |
| `TVBOX_LIVE_ASSERT=1` | `FAIL` | `PASS` |

中间还有一轮：第二轮把去重放在「续段开头保护期」，健康流上也是 0 帧；但把同一份最终代码再复核时，CDN 侧变成短会话重连风暴（200 秒 51 次会话、`lastSessionMs=82`），测出 **330 帧 / 8 区间 / 最长 5.16 秒**。这促成第三轮的「去重贯穿整个续段」，详见 5.4 / 6.5。

> 「虎牙·一起看」（影视轮播长流）比赛事房间更能复现问题：它在 120 秒左右稳定触发 CDN 关流，且回放缓冲长达 6 秒。此前用的 `660000` 是「预告」房间，PC 侧每 ~110ms 就 403，属于不健康环境，测不出有效结论。

**最终真实流报告**：

```
──────── 真实 CDN 续流重复画面检测报告 ────────
平台 / 房间            : huya / 11336726
录制时长              : 200 s
代理输出 tag 数       : 14058（其中视频帧 5158）
输出时间戳回退次数    : 0
重复画面帧数          : 0
连续重复区间数        : 0
最长重复时长          : 0 ms (0.00 s)
代理统计              : {"bytesWritten":16984446,"candidateSwitches":0,"client":false,
                       "lastError":"","lastSessionMs":120577,"reconnects":1,
                       "tagsWritten":14058,"upstreamSessions":2,"uptimeSec":200,
                       "url":"http://127.0.0.1:37860/live.flv"}
--- PASS: TestFlvProxyLiveStreamReplay (200.00s)
```

模拟流（第一轮修复后）完整报告：

```
──────── 虎牙续流重复画面检测报告 ────────
上游会话数            : 3（1=首段, 2=重连带回放）
代理输出 tag 数       : 111（其中视频帧 110）
输出时间戳回退次数    : 0（0 表示时间轴单调，符合预期）
重复画面帧数          : 0
连续重复区间数        : 0
最长重复时长          : 0 ms (0.00 s)
代理统计              : {"bytesWritten":4318,"candidateSwitches":0,"client":false,
                       "lastError":"","lastSessionMs":0,"reconnects":2,
                       "tagsWritten":111,"upstreamSessions":3,"uptimeSec":5,
                       "url":"http://127.0.0.1:24404/live.flv"}
--- PASS: TestFlvProxyReplayDetection (5.00s)
```

---

## 五、根因

### 5.1 虎牙重复画面（不是时间戳断层）

1. **旧 `skipReplay` 比较对象错了**：用「已加偏移的 `adjusted`」去和「偏移前的 `prefixLastTs`」比较，新流首帧一进来就大于阈值，条件永远不成立，回放帧一帧都丢不掉。
2. **回放段里的关键帧被误当成新流起点**：原来的 `awaitKeyframe` 一见到关键帧就解除等待、重新设置 `offset`，而 CDN 带回的 1.5 秒旧缓冲里本身就含关键帧，于是「等关键帧」这道防线被回放段自己破掉。
3. 结果：时间轴被顺延得漂漂亮亮，但**内容**里多播了一段旧画面 —— 正好对应肉眼看到的 1~2 秒。

> 结论：v1.3.61 做的「关键帧对齐」方向没错，但只解决了「从非关键帧开始解码」的黑屏/花屏，解决不了「旧缓冲整段被重发」。

### 5.2 斗鱼断流重连感

正常 EOF 后 `streamLoop` **每次都重新 `Resolve`**。斗鱼的 `Resolve` 需要重新拉签名 JS、用 goja 执行、再逐个 CDN 取流，耗时明显；这段空窗里播放器缓冲耗尽，用户感受就是「断流 → 重连」，虎牙同样链路但没有斗鱼这么慢。

### 5.3 第一轮指纹去重为何仍漏掉 6 秒回放（真实「一起看」场景）

按用户建议换成「虎牙·一起看」入口后，真实 CDN 复核测出 **153 帧 / 6.08 秒**重复。三个叠加原因：

1. **去重状态与「等关键帧」状态被绑在一起**：`dropReplay` 一旦遇到「未转发过的关键帧」就置为 false，此后整个会话都不再检查指纹。而真实 CDN 的续流形态是「**先给一个全新关键帧，再跟上 6 秒旧缓冲**」——新关键帧一到，去重提前解除，后面整段旧缓冲被原样转发。
2. **5 秒指纹窗口短于 6.08 秒回放**：最早那段旧帧的记录已被按输出时间戳淘汰，即使检查也认不出来。
3. **丢弃回放会撕出时间轴空洞**：旧帧被丢掉后，后续新内容仍按 `sourceTs + offset` 输出，播放器侧会看到约 6 秒的时间戳跳跃，表现为卡住/快进。

> 教训：模拟用例用的是「先回放 1.5 秒、再给新帧」，恰好绕开了上述三个问题；必须用真实 CDN 流复核。

### 5.4 第三轮：会话中途的零散回放与短会话重连风暴

第二轮把去重放在「续段开头的保护期」，在健康流（120 秒关流 + 开头 6 秒回放）上拿到 0 帧。但最终代码再复核时，CDN 侧状态变为**短会话重连风暴**（200 秒 51 次会话、`lastSessionMs=82`），测出 **330 帧 / 8 个区间 / 最长 5.16 秒**重复。说明：

1. 回放**不只发生在续段开头**：CDN 在会话中途（已稳定输出上百个全新帧之后）也会零散重发旧缓冲，保护期一解除就漏；
2. 短会话下「正常 EOF → 同地址快速续拉」会连续空转，200 秒打出 51 次上游会话。

> 结论：去重必须贯穿整个续段会话，而不是只保护开头 N 帧；快速续拉也需要连续次数上限与失败退避。

---

## 六、修复方案（`platform_live_go/flv_proxy.go`）

### 6.1 帧指纹去重（解决重复画面）

- `flvProxy` 新增 `recent map[uint64]int64` + `recentList []flvReplayEntry`，记录最近 5 秒已转发帧指纹；
- 每次重连后进入 `dropReplay` 状态：
  - 命中 `isReplayedFrame(fp)` 的帧直接 `continue` 丢弃；
  - AVC 序列头仍要转发给播放器（否则解码器缺 SPS/PPS），但用 `controlledTs()` 生成受控时间戳，不占新流 offset；
  - 关键帧前的非关键视频帧继续丢弃，保持关键帧对齐；
  - 只有**没见过的**关键帧才解除 `dropReplay`，避免被回放段里的关键帧骗过；
- 正常转发的每一帧都 `rememberFrame(fp, adjusted)`，窗口按时间戳淘汰。

### 6.2 同地址快速续拉（解决斗鱼断流）

- 正常 EOF 且本段确实输出过 tag 时，把当前 `candidate` / `headers` 存进 `fastReconnectCandidate`，下一轮直接复用同一 URL 续拉，跳过签名解析；
- 快速续拉失败（403 / 签名过期 / 网络错误）时**不计入硬失败**，直接回退到重新 `Resolve`；
- 只有重新解析路径才做线路旋转与退避，避免把「URL 过期」误判成「平台故障」。

### 6.3 测试假流配套修正

`flv_proxy_test.go` 的 `writeTestFLV` / `writeTestFLVEx` 追加 `seed + index` 到 payload，保证段内 / 跨段每帧唯一，否则引入指纹去重后正常续流会被误判成回放。

### 6.4 第二轮加固（第一轮真实「一起看」复核后）

- **拆开两个状态**：`dropReplay` 只负责「等未转发过的首关键帧（解码前提）」；新增独立的 `guardActive` 负责指纹去重保护期。即使 `dropReplay` 已被新关键帧解除，保护期内仍对续段开头的**每一帧**（含音频、含新关键帧之后）检查 `isReplayedFrame`，命中即丢弃。
- **保护期自适应退出**：连续 `flvProxyReplayGuardCleanFrames = 120` 帧都没命中回放才解除，另有 `flvProxyReplayGuardMaxFrames = 1200` 硬上限；连续命中会把干净计数清零，因此 6 秒回放段会被完整覆盖。AVC 序列头单独放行（编码参数必需，且内容常与上一段相同）。
- **窗口放宽到 12 秒**：`flvProxyReplayWindowMs = 5000 → 12000`，覆盖实测 6.08 秒回放并留余量。
- **补回被丢弃的源时间轴**：对每个被丢弃帧累加它占用的源时间增量 `droppedSourceMs`，转发时 `adjusted = sourceTs - droppedSourceMs + offset`，并用 `controlledTs()` 做严格不回退兜底（仅续段启用，避免把首帧 0 抬成 1）。这样丢掉 6 秒回放后，新内容紧接上一帧输出，不会撕出时间戳空洞。
- **新增回归用例** `TestFlvProxyReplayAfterFreshKeyframe`：模拟「全新关键帧 + 6 秒旧缓冲 + 新内容」，断言 0 重复帧、0 回退、相邻帧最大间隔 ≤ 1000ms（实测 33ms）。

### 6.5 第三轮加固（最终）

- **去重贯穿整个续段**：去掉「连续 120 帧干净就解除保护期」的逻辑，`guardActive` 只在续段整段结束后失效，硬上限放宽到 `flvProxyReplayGuardMaxFrames = 7200`（约 4 分钟 @30fps，正常 120 秒会话不会触及）。这样会话中途零散重发的旧缓冲同样被丢弃。
- **快速续拉空转保护**：新增 `flvProxyMaxFastReconnectStreak = 3`，连续同地址续拉最多 3 次，之后强制重新解析并清空旧候选；快速续拉失败时清零计数并加 `flvProxyRetryDelay` 退避，避免 CDN 短会话时每秒重连多次、打爆平台接口。
- **新增回归用例** `TestFlvProxyReplayMidSession`：续段先输出 150 个全新帧，再在**会话中途**插入 60 帧旧缓冲，断言 0 重复帧（实测输出 302 tag / 0 重复 / 0 回退）。

---

## 七、回归测试清单

```bash
cd platform_live_go
go build ./...
go vet ./
go test -count=1 ./...
TVBOX_REPLAY_ASSERT=1 go test -count=1 -run TestFlvProxyReplayDetection ./
# 可选：真实 CDN 200 秒复核（需联网，默认跳过）
TVBOX_LIVE_E2E=1 TVBOX_LIVE_ROOM=11336726 TVBOX_LIVE_DURATION_SEC=200 \
  TVBOX_LIVE_ASSERT=1 go test -count=1 -timeout 10m -run TestFlvProxyLiveStreamReplay ./
```

| 用例 | 覆盖点 |
| --- | --- |
| `TestFlvProxyReconnectNoRollback` | 重连后输出时间戳严格单调 |
| `TestFlvProxyFirstSegmentForwardsLeadingNonKeyframe` | 首段不误丢前置非关键帧 |
| `TestFlvProxyKeyframeHelpers` | `isAvcSequenceHeader` / `isVideoKeyframe` 解析 |
| `TestFlvProxyStitchesSessions` | 上游关流后能续上（快速续拉只 `Resolve` 1 次、sessions ≥ 2） |
| `TestFlvProxyFastReconnectReusesURL` | 正常 EOF 复用同地址，不再重跑解析 |
| `TestFlvProxyFastReconnectFallsBackToResolve` | 快速续拉 403 后回退重新解析 |
| `TestFlvProxyReplayDetection` | 1.5 秒回放的端到端检测（报告 + 断言两种模式） |
| `TestFlvProxyReplayAfterFreshKeyframe` | **真实形态 A**：新关键帧 + 6 秒旧缓冲；断言 0 重复 + 无时间轴空洞 |
| `TestFlvProxyReplayMidSession` | **真实形态 B**：会话中途（150 帧之后）零散回放；断言 0 重复 |
| `TestFlvProxyLiveStreamReplay` | 可选联网：真实 CDN 200 秒帧指纹复核（`TVBOX_LIVE_E2E=1`） |

全套结果：`ok github.com/cnliux/tvbox/platform_live_go 11.355s`（`go build ./...` / `go vet ./` 均通过）。

真实形态回归输出：

```
代理输出 tag 数       : 303
输出时间戳回退次数    : 0
相邻帧最大时间戳间隔  : 33 ms（回放 6 秒若未补偿会出现约 6000ms 空洞）
重复画面帧数          : 0
连续重复区间数        : 0 / 最长 0 ms
```

---

## 八、AAR 重绑定与真机验证

### 8.1 踩过的坑：改了 Go，但 APK 里还是旧 .so

`app/build.gradle.kts` 的 `bindPlatformLive` 有：

```kotlin
onlyIf { !project.hasProperty("tvboxSkipPlatformBind") && !platformLiveAar.exists() }
```

即 **AAR 存在就不重绑**。旧 `app/libs/platformlive.aar` 里 `jni/arm64-v8a/libgojni.so` 是 11-10 16:45 的产物，Go 修复是 11-11 10:03，直接 `assembleDebug` 装到真机上验证的仍是旧代码。

另外 `gomobile init` 需要下载 `gobind`，默认 `GOPROXY` 会失败：

```
go install golang.org/x/mobile/cmd/gobind@latest failed: exit status 1
gomobile init 失败 → :app:bindPlatformLive FAILED
```

### 8.2 正确流程（已跑通）

```powershell
$env:GOPROXY = "https://goproxy.cn,direct"
$env:GOSUMDB = "off"
& .\tools\bind-platform-live.ps1
```

设置 `GOPROXY` 是为了让 `gomobile init` 能装到 `gobind`；`GOSUMDB=off` 是为了避开 `go mod tidy` 在 gomobile 临时工作目录写 `pkg/sumdb/.../latest` 时的 `Access is denied`（设成 `sum.golang.google.cn` 仍会去访问 `sum.golang.org` 而失败）。

重绑定约 30 秒完成，本轮共绑了三次。判断 AAR 是否为最新，**看 `jni/arm64-v8a/libgojni.so` 的大小/符号，不要看 AAR 总大小**（Gradle 的 `stripPlatformLiveAar` 会在构建时剥离符号并改写 AAR）：

| 轮次 | `libgojni.so` 大小 |
| --- | --- |
| 旧产物（11-10） | 17,266,160 B |
| 第一轮帧指纹去重 | 17,271,896 B |
| 第二轮保护期修复 | 17,272,440 B |
| **第三轮（最终）** | **17,272,624 B** |

最终 APK 内 `lib/arm64-v8a/libgojni.so` 同为 17,272,624 B。在新 `.so` 中可查到 `rememberFrame` / `isReplayedFrame` / `flvFrameFingerprint` / `controlledTs` 等符号，确认新 Go 代码已编译进产物。

> 后续建议（历史记录，已于 2026-11-13 修复）：改完 `platform_live_go/*.go` 后，明确执行一次上述脚本（或先删 AAR 再构建），不要依赖 Gradle 的 `onlyIf`。
>
> **2026-11-13 更新**：`bindPlatformLive` 已改为按 `platform_live_go/**` + `tools/bind-platform-live.*` 的内容指纹决定是否需要重绑，指纹落在 `app/libs/platformlive.fingerprint`（与 AAR 同目录，CI 缓存同步恢复），`stripPlatformLiveAar` 原地改写 AAR 不再破坏判定。现在改了 Go 直接 `assembleDebug` 就会自动重绑；`-PtvboxSkipPlatformBind=true` 仍可跳过。`GOPROXY` / `GOSUMDB` 的设置要求不变。

### 8.3 真机验证

见第九节「真机验证记录」。设备：`192.168.1.45:5555`（SDY-AN00，Android 12）。

---

## 九、同批修复：手机设置页文字被遮挡 / 无法显示

真机截图（`.workbuddy/tmp/set_xl_playback.png`、`set_xl_landing.png`）确认：大字号下「播放」页里标题被压成左侧窄条、说明文字被截、选项 chips 在 52% 宽度里反复换行堆叠、底部版本信息又小又暗。

根因在 `SettingsMobileScreen.kt` 的 `SettingsRow`：标题与右侧控件横向并排，`trailing` 被 `maxWidth * 0.52f` 限制，标题强制 `maxLines = 1 + Ellipsis`，副标题 `maxLines = 2 + Ellipsis`；`ChoiceChips` 又各自 `maxLines = 1 + Ellipsis`。

修复：

- `SettingsRow` 增加 `stackedTrailing` 参数，改为 `Column`：标题 / 说明占满整行**自然换行，不再裁切**；19 个含 `ChoiceChips` 的设置行让选项组独占下方一整行（左侧留 50dp 与文字对齐）；开关 / 按钮等窄控件仍留在右侧，宽度上限放宽到 180dp。
- `ChoiceChips`：去掉单行省略，未选中项文字由 `TextSecondary` 提亮到 `TextPrimary 90%`，字号 `labelMedium → bodySmall`，chip 内边距加大、最小宽高 48dp。
- `CategoryCard` 副标题、`HeroStat` 值、`SkipSecondsRow` 标题/说明全部取消强制省略；`HeroStat` 标签改用 `labelMedium + TextSecondary`。
- 底部版本信息由 `labelSmall + TextTertiary` 改为 `bodySmall + TextSecondary`。
- `TvBoxApp.kt` 的 `SettingsActionButton` 取消单行省略（长文案如「打开远程管理（网页）」不再只剩前半截），补 8dp 垂直留白、文字居中；视频源管理列表里的源地址由 `labelSmall` 单行改为 `bodySmall` 两行。

---

## 十、发布与后续

- 版本：v1.0.4（`versionCode = 20004`），APK 名 `juhe-video-v1.0.4.apk`，`update.json` 保持 8 字段结构 + 可选 `apkMirrors`。
- 真机验证结果见下方「真机验证记录」补充章节。
- 后续若要复测真实 CDN，用 `web/flv_replay_analyzer.html` 录制代理输出的 FLV 并按帧指纹分析；单靠时间戳无法判断。

---

## 真机验证记录

设备：`192.168.1.45:5555` / SDY-AN00 / Android 12 / 1080×1920 / density 280。

### 11.1 构建与安装

- 用 `GOPROXY=https://goproxy.cn,direct` 重绑 AAR 后执行 `testDebugUnitTest + assembleDebug`，`BUILD SUCCESSFUL in 4m 44s`；`:app:stripPlatformLiveAar` 把新 `.so` 打进 APK（`lib/arm64-v8a/libgojni.so` 17,271,888 B）。
- `adb install -r app-debug.apk` → `Success`。logcat 确认代理正常接管：

```
【构建播放器】room=来了峡谷之巅冲击王者(huya:189201), site=huya,
  url=http://al.flv.huya.com, localUrl=http://127.0.0.1:35845,
  protocol=flv, candidates=3, continuityProxy=true
```

### 11.2 手机设置页（正常字号 + 超大字号）

- `settings_new_landing.png` / `settings_new_playback.png`：设置主页与「播放」页文字完整，版本信息由小灰字改为可读的 `bodySmall`，选项组独占整行。
- 在「通用 → 字体大小」切到「超大」后复拍 `settings_xl_new_playback.png`：「9:16 竖版」「裁剪铺满」「拉伸铺满」「强制硬解」等长文案全部完整可读，标题/说明不再被右侧控件挤压，无省略号、无重叠遮挡。
- 与修复前的 `set_xl_playback.png` 对比：原先标题被压成左侧窄条、chips 在 52% 宽度里堆叠换行、部分文案只剩半截，修复后消失。

### 11.3 虎牙连续播放（普通房间 huya:189201）

连续 240 秒，每 10 秒一条代理统计，关键片段：

```
uptimeSec=10   reconnects=0 upstreamSessions=1 tagsWritten=1611  lastError=""
uptimeSec=120  reconnects=0 upstreamSessions=1 tagsWritten=12944 lastError=""
uptimeSec=180  reconnects=0 upstreamSessions=1 tagsWritten=19119 lastError=""
uptimeSec=240  reconnects=0 upstreamSessions=1 tagsWritten=25305 lastError=""
bytesWritten: 7,975,562 → 135,315,485（稳定增长）
```

全程 `client=true`、`candidateSwitches=0`、无「看门狗触发」、无「播放结束」、无「线路提示」。

### 11.4 虎牙「一起看」入口（huya:11336726）

按实际使用入口复测：直播 → 常用分类「虎牙·一起看」→ 影视轮播房间 `huya:11336726`。代理地址 `127.0.0.1:39677`，启动后 `READY`。**这次真实捕捉到了 CDN 关流**：

```
uptimeSec=10   reconnects=0 upstreamSessions=1 tagsWritten=1063  lastError=""
uptimeSec=70   reconnects=0 upstreamSessions=1 tagsWritten=5151  lastError=""
uptimeSec=120  reconnects=0 upstreamSessions=1 tagsWritten=8551  lastError=""
uptimeSec=130  reconnects=1 upstreamSessions=2 tagsWritten=9655  lastError=""
               lastSessionMs=120497   ← 上游会话存活 120.5 秒后正常关流
```

续拉本身成功（无错误、无换线路、帧数继续增长），但同一时刻 logcat 出现一次 `【看门狗触发】kind=Stalled`（随后播放器重建、代理端口由 39677 变为 37197）——这与用户感受到的「重复 1~2 秒画面」吻合。PC 侧对同一房间的帧指纹检测也测出 153 帧 / 6.08 秒重复，因此做了 6.4 / 6.5 两轮加固。

**最终 APK 在同一房间复测（170 秒，代理端口 37831 全程未变）**：

```
uptimeSec=120  reconnects=0 upstreamSessions=1 tagsWritten=8526  lastError=""
uptimeSec=130  reconnects=1 upstreamSessions=2 tagsWritten=9165  lastError=""
               lastSessionMs=120548
uptimeSec=160  reconnects=1 upstreamSessions=2 tagsWritten=11214 lastError=""
```

全程 **0 次「看门狗触发」、0 次「播放结束」、0 次线路切换**，`candidateSwitches=0`，播放器没有被重建——说明 6 秒回放已被丢弃且时间轴没有空洞，续流不再造成可见卡顿。

### 11.5 一次必须记录的干扰实验

曾尝试 `adb forward + curl` 直接录制 Android 代理输出。当时 Go 侧 `ServeHTTP` 是**单客户端**（`p.client = w` 会被后连者覆盖），外部录制会抢走播放器的 writer，触发 App 看门狗 `Stalled` → 重新解析 → 重建播放器与代理端口（45389 → 38087 → 40609 …），期间出现 30 秒 33 次重连、`上游 HTTP 403`。**这不是续流逻辑的回归**：移除转发、重新进入房间后连续 240 秒 `reconnects=0`。因此 Android 侧验证以 logcat 统计为准，帧指纹录制放到 PC 侧独立进程。

> **2026-11-13 更新**：该单客户端限制已修复。`ServeHTTP` 现在按连接代次（gen）做「新连接接管、旧连接退出」，并给新连接补发 FLV 头 + 缓存的 `onMetaData` / AVC / AAC 序列头；同时 `Accept-Ranges: none`、支持 HEAD、FLV header flags 按实际轨道推导。播放器侧对本地代理也放宽了读超时（60s）与看门狗 stall（15s），避免代理静默续流被误判成卡死。

### 11.6 PC 侧真实 CDN 流检测（最终判据）

| 轮次 | 房间 | 200 秒结果 |
| --- | --- | --- |
| 对照（选错入口） | `660000`（赛事预告非稳定流） | 2816 重复帧 / 33 区间 / 最长 6.84 s；`reconnects=198`、`lastSessionMs=110`、403 风暴 —— 不健康环境，不能代表 App |
| 第一轮修复 | `11336726`（虎牙·一起看） | **153 重复帧 / 1 区间 / 6.08 s**；`reconnects=1`、`lastSessionMs=120528`、`lastError=""` —— 健康流，暴露真实缺陷 |
| 第二轮（CDN 短会话风暴） | `11336726` | **330 重复帧 / 8 区间 / 5.16 s**；`reconnects=51`、`lastSessionMs=82` —— 会话中途零散回放 + 快速续拉空转 |
| 第三轮（最终） | `11336726` | **0 重复帧 / 0 区间 / 0 ms**；`reconnects=1`、`lastSessionMs=120577`、`lastError=""`、`TVBOX_LIVE_ASSERT=1` PASS |

> 对照轮 `660000` 的 PC 进程不带 App 的 `initialURL + headers`（走 `resolve()` 路径），且是预告流，CDN 每 ~110ms 就 403，属于解析/重连风暴；真机同一时段是单会话 240 秒 0 重连。该环境暴露的「短会话下快速续拉高频空转」已在第三轮用连续次数上限与失败退避处理（见 6.5）。

### 补充结论

1. **换入口是对的**：「虎牙·一起看」的影视轮播长流在 120 秒左右稳定触发 CDN 关流并带回约 6 秒旧缓冲，是复现该问题最可靠的环境；赛事预告房 `660000` 在网络受限时会退化成 403 重连风暴，测不出有效结论。
2. **模拟测试不足以判定**：本地 httptest 的 1.5 秒回放在第一轮就报「0 帧」，真实流却仍有 153 帧 / 6.08 秒。最终判据必须是真实 CDN 的帧指纹检测 + 真机跨关流 logcat。
3. **最终状态**：PC 真实流 200 秒 `0 重复帧 / 0 ms / 0 回退`，真机 170 秒跨关流 `reconnects=1 / lastError=""`、无看门狗、无播放器重建；`go build / go vet / go test ./...`、`testDebugUnitTest + assembleDebug` 全绿。
4. **版本**：以上修复随 v1.0.4 发布（`versionCode=20004`），AAR 已用最终 Go 代码重绑并打入 APK（`libgojni.so` 17,272,624 B）。
