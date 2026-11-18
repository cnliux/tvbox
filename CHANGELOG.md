## v1.0.23 - 2026-11-19

审查修复与历史更新检查健壮性：

- **历史更新检查逐源失败隔离**：`probeLatestEpisodeCount` 单源全 baseUrl 抛错只让该源计 0，不再中止整批检查；`CancellationException` 仍原样上抛。
- **探测并发限流**：`probeLatestEpisodeCount` 内部用 `Semaphore(3)` 限制每条的源并发，避免绕过 `HISTORY_UPDATE_PARALLELISM` 一次性打满全部源。
- **整批总预算**：`checkHistoryUpdates` 增加整批 15s 兜底（`HISTORY_UPDATE_BATCH_TIMEOUT_MS`）+ 失败留日志（`TVBoxHistory`）。
- **续播兜底**：`getResumeDetail` 原在「历史源被移除/禁用」时 `require` 抛 `IllegalArgumentException` 崩溃，改为回退第一个可用源，取不到才返回 null 降级提示。

## v1.0.22 - 2026-11-18

手机版播放控件与历史列表优化：

- **手机历史页**：新增 `MobileHistoryScreen.kt` 移动端专属列表页（单列卡片、按今天/昨天/日期分组、单条删除、空态引导去首页），替代手机端复用电视网格与焦点体系的旧实现。
- **历史数据增强**：`WatchHistoryItem` 增加跨源更新检查字段（`latestEpisodeCount`/`lastUpdateCheckEpochMs`），派生 `updateHint`（「更新至 N 集」/「还有 N 集」），历史页展示更新徽标；仓储新增 `updateEpisodeCheck`/`removeHistoryItem`，删除与进度保存共用写锁避免竞态。
- **历史更新检查改用最快响应源**：新增 `MovieRepository.probeLatestEpisodeCount`，并发扇出到全部已启用源、任一源返回有效集数即采纳（先到先得），不再被「原记录源先按 id 取详情拿片名」这条阻塞链卡住——原源慢/挂时也能靠最快的可用源判定是否更新。
- **更新检查健壮性（审查修复）**：`probeLatestEpisodeCount` 逐源失败隔离——某源整条链路抛错（全 baseUrl 失败）只让该源计 0，不再中止整批；内部用 `Semaphore(3)` 限制每条的源并发，避免绕过 `HISTORY_UPDATE_PARALLELISM` 打满全部源；`checkHistoryUpdates` 增加整批 15s 总预算兜底 + 失败留日志（`TVBoxHistory`）。
- **续播健壮性（审查修复）**：`getResumeDetail` 原在「历史源被移除/禁用」时 `require` 抛 `IllegalArgumentException` 导致续播崩溃，改为与 `requireLine` 同款兜底（回退第一个可用源，取不到才返回 null 降级提示）。
- **手机播放控件**：点播页移动端返回退出统一走 `exitPlayer()` 单入口（先保存进度再返回），与硬件返回键/屏幕返回按钮保持一致。
- **历史分组跨天修复**：分组标签「今天/昨天」纳入本地自然日 key，页面跨午夜后任一重组即按新日期重新分组；分组头改用序号 key，避免跨年日期标签重复碰撞。
- **历史分组单测**：新增 `MobileHistorySectionsTest` 锁定「今天/昨天/更早」归类、同日合并、空列表与跨午夜刷新语义。
- 验证：`compileDebugKotlin` + `testDebugUnitTest` 通过。

## v1.0.21 - 2026-11-18

手机历史跨源更新提示与播放控件优化：

- **历史跨源更新提示**：历史记录新增「还有 N 集 / 更新至 N 集」徽标，依据本机保存的线路总集数与跨源检查结果，无需进详情即可发现新更新。
- **播放控件优化**：手机端播放控件交互细节完善（竖屏精简保留调试/重连档位等关键入口）。

## v1.0.20 - 2026-11-18

斗鱼房间级自适应重连、TV 焦点修复与 Go 崩溃栈落盘：

- **斗鱼房间级自适应重连**：重连策略按房间运行情况自适应，避免固定参数误伤稳定线路。
- **TV 焦点修复**：修复电视端遥控焦点移动错位。
- **Go 崩溃栈落盘**：`platform_live_go` 侧崩溃栈写入本地文件，便于现场定位续流代理异常。

## v1.0.19 - 2026-11-18

工程清理：删除已废弃的 Python 解析服务，移除从未可用的 Kotlin 兜底中继，重写 README。

### 删除
- **`platform_live_server/`（14 个文件、约 4400 行）**：Python 版解析服务。功能已由
  `platform_live_go` 完整接管（`cmd/platform-live-server` 覆盖其全部路由，另多
  `resolve-link`），且 `app/build.gradle.kts`、`settings.gradle.kts`、`.github/` 均无引用。
  同源 `douyin_sign.js` 在 `platform_live_go/platformassets/` 仍有副本。
- **Kotlin 兜底中继（约 1180 行）**：`LiveContinuityRelay`、`FlvSplicer`、`FlvTagReader`
  及对应测试。该路径 JVM 端到端测试可过，但 Android 真机必定在 250ms 内报
  `ERROR_CODE_IO_UNSPECIFIED`，从未验证可用——保留它等于把"兜底"做成"一定失败"。
  现在 `FlvContinuityProxy` 只有 Go 代理一条路径，失败时退回直连（直连只是每两分钟
  重连一次，仍可播放）。线路归一化测试拆到 `LiveLineNormalizerTest` 保留。
- 一次性脚本 `tools/_fix_settings.py`、`_fix_settings2.py`（硬编码本机绝对路径）。

### 修正的设计问题
- `tools/live_longrun_monitor.py` 原本 `subprocess` 启动已删除的
  `platform_live_server/renew_server.py`，工具会静默失效；改为自行编译并拉起
  `cmd/platform-live-server`，接口切到 `/v1/live/resolve` 与 `streams` 字段。
- **`flv_proxy.go` 真实缺陷**：回放跨度 `replaySpanMin/Max` 从不清零，跨会话无限累积，
  使"跨度达窗口 60%"恒成立，窗口一路顶到 24s 上限后不再回落，自适应退化为"永久最大
  窗口"。新增 `resetReplaySpan()`，按上游会话重置，改为单段统计。
- 删除无调用代码：`flvProxyFileHeader`、`liveHostSupported`、`showroomStreamsFromLive`、
  `sortedStable`，以及未使用的 `audioResyncDrops` 字段。
- `FlvContinuityProxy` 头注释把优先级写反（实际 Go 优先），已修正。
- `.gitignore`、`LOCAL_DEV.md`、`PlatformLiveRepository.kt` 中指向 Python 服务的残留引用。

### 文档
- README 重写为按实际代码组织：功能、遥控键位、架构（同一份 Go 源码的 AAR 与 HTTP
  两种形态）、连续播放代理（指纹去重 / 自适应窗口 / 音频回拉 / 三档互斥调参）、
  构建与 AAR 绑定、解析服务接口表、长跑监控、发布流程。原 README 的版本号停留在
  1.0.5、仍指向 Python 服务、且写着已被替换的"5 秒指纹窗口"。

### 验证
- Go：`go build ./...`、`go vet ./...`、`go test ./...` 全部通过（142 个用例）。
- Kotlin：`compileDebugKotlin` + `testDebugUnitTest` 通过；`LiveLineNormalizerTest` 9 个用例
  0 失败。
- 长跑工具对接 Go 服务实测：`ensure_service()` 返回 True，`sign()` 取到 3 条真实 CDN 线路。

## v1.0.18 - 2026-11-18

平台直播「一起看」自适应修复搬到**生产路径（Go 代理）**，并补 TV 遥控房间列表导航。

### 根因与改动
- v1.0.17 后的自适应改动只落在 Kotlin 兜底中继（`FlvSplicer`），而虎牙/斗鱼实际走
  Go 代理（`FlvContinuityProxy.start` 先返回 Go 句柄），因此对线上无效。本次把两处
  机制移植进 `platform_live_go/flv_proxy.go`：
  - **自适应回放窗口**：命中回放时按源时间轴跨度放宽指纹窗口（起始为各平台默认，
    达窗口 60% 时翻倍，封顶 24s）。固定 12s/8s 装不下更长的回吐缓冲（实测 133 帧回放）。
  - **音频回拉**：音频领先视频超过 700ms 时整体回拉，落到已交付音频之前则钳位而非丢帧。
    针对实测 1636/1960/2247ms 的音画偏移，避免逐帧丢音频造成的断续。
- **TV 遥控**：确认键打开的「节目列表」改为在按下时就消费方向键，避免可聚焦的房间
  行先执行默认焦点移动，导致选中行与焦点错位。

### 验证
- Go：`go build ./...`、`go test ./...` 全通过；新增 9 个用例覆盖扩窗、回拉、端到端
  续段音画收敛（`flv_proxy_adaptive_test.go`）。
- Kotlin：`compileDebugKotlin` + `testDebugUnitTest` 通过。
- 已重新 `gomobile bind` 产出 `app/libs/platformlive.aar`（arm64）。

## v1.0.17 - 2026-11-17

虎牙/斗鱼「一起看」断流专项：**用 10 分钟 × 6 房间实跑定位根因**，修复 5 个已确证缺陷。

### 关键实测结论（真机/真实 CDN，非推断）

- **虎牙 anti-code URL 是一次性的**：房间 11336726 的 AL 线路，第一次请求拉到
  `100667ms / 9.2MB` 后正常 EOF；**用同一个 URL 第二次请求，135ms 就返回 403**。
  这是此前「同地址快速续拉」策略在虎牙上失效的直接原因。
- **虎牙单次会话上限**实测 `120.7s`（房间 11342412，50.7MB 后被服务端正常关闭）。
- **斗鱼**两个房间可连续拉满 10 分钟（`599418ms` / `600547ms`）零重连，机理与虎牙不同。

### 修复的缺陷

- **线路修复层（新增 `LiveLineNormalizer`，纯客户端，不改 Go/AAR）**：
  - `codec` 参数**唯一化**：先删同名再写入，绝不追加。实测在已带 `codec` 的地址后
    再拼 `&codec=264`，虎牙直接返回 HTML/403（表现为「无法连接」）。
  - 虎牙 FLV 强制 `codec=264`，避免下发 HEVC 导致「有声音没画面」。
  - 按签名里的 `ctype/t` 配对应 `User-Agent/Referer/Origin`（`tars_mobile/t=103`
    必须配移动端 UA + `m.huya.com`，错配会 403 或十几秒被掐流）。
  - 候选按规范化地址去重：解析层生成的「移动端」候选与 PC 候选实际是同一路流
    （页面 antiCode 本身即 `ctype=tars_mobile`，字符串替换匹配不到），等于少一条备线。
- **连续代理**：
  - HTTP 响应头曾被写进 chunked 分帧，播放器报 `Invalid Http response` → 完全无法起播；
    改为响应头直写 socket、正文才进 chunked。
  - 中继监听地址显式绑 `127.0.0.1`：`getLoopbackAddress()` 在部分 Android 上返回 IPv6
    `::1`，播放器连 IPv4 被拒（`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`）。
  - 虎牙地址不可复用：`reusableAddress()` 对 huya 返回 false，每次续流必须重签；
    计划内预建连接也必须用**新签名**，否则第二条连接立刻 403。
- **FLV 续接（新增 `FlvSplicer`，12s 指纹窗 + 7200 帧硬顶）**：
  - 序列头改为只比**标签体**：原先比整条标签，标签头时间戳每段不同 → 每段重发 config，
    解码器反复重建。
  - 「同轴」判定增加**回退容差**：原先只按距离判断，时间戳归零的新段可能被误判同轴，
    随后整段被单调性检查逐条丢光，表现为换段后长时间黑屏。
  - 换段到首个关键帧之间**音视频一起丢**（只丢视频会让声音领先）；
    音轨只做单调约束、不做指纹去重（AAC 静音帧内容相同，按指纹丢会误删正常音频）。

### 10 分钟 × 6 房间实跑结果（修复后）

| 房间 | 会话数 | 中位会话 | 回放帧 | 最大 A/V 偏移 | 错误 |
|---|---|---|---|---|---|
| huya:11336726 | 5 | 120624ms | 4 | 40ms | 0 |
| huya:11342412 | 5 | 120550ms | 4 | 42ms | 0 |
| huya:30590779 | 5 | 120550ms | 4 | 35ms | 0 |
| douyu:4549169 | 1 | 600547ms | 0 | 37ms | 0 |
| douyu:1487654 | 1 | 599418ms | 0 | 33ms | 0 |
| douyu:4184217 | 2+ | 300233ms | 0 | 32ms | SSL 失败（见下） |

虎牙从修复前的 **278~420 个会话 / 中位 152~168ms**（403 重连风暴）收敛到
**5 个会话 / 中位 ~120.6s**，即 10 分钟里恰好 5 次计划内续流，无 403、无重连风暴。

### 已知问题（未修复，如实记录）

- `douyu:4184217` 的第二条 CDN 候选（`*.edgesrv.com:443`）出现连续 `SSLError`，
  探针在连接失败时不会轮换候选（`renew(0)` 恒取第一条），因此重试同一坏线 149 次。
  续流侧的候选轮换逻辑待补。
- Kotlin 中继在真机上仍会在 250ms 内让播放器报 `ERROR_CODE_IO_UNSPECIFIED`
  （JVM 端到端测试通过，差异在 Android 的 HTTP 处理上）。因此**Go 代理保持为默认路径**，
  Kotlin 中继仅作兜底。

### 同版新增：一起看监控自愈 + 播放器/详情/历史 UI 改造

- **监控工具 `tools/live_longrun_monitor.py`（新增，可复用）**：签名服务探活失败即重启子进程并重试，
  单房间失败不拖垮整轮；每轮从虎牙/斗鱼「一起看」池随机抽 10 个房间并行跑 600 秒。
  同时修掉监控长期"零产出"的三个真因：读循环缺截止判断导致挂死、文件缓冲不刷盘、
  签名服务永久缓存导致把已失效 URL 当新签名（此前 278~420 会话的坏数据即由此而来）。
- **播放器调试信息移到顶部**：原压在左下角会挡住控制条与进度条，改为右上角并压到 30~34% 高度。
- **换源跟随同一集（修 bug）**：`selectPlaySource` 原写死 `selectedEpisodeIndex = 0`，
  每次换源都跳回第 1 集；改为用 `correspondingEpisodeIndex` 按编号/标题对应到同一集。
- **剧集标题去掉编号**：编号正则扩展到 `12`/`第12集`/`EP12`/`12.`/`[12]`/`12集` 等各站风格；
  新增 `displayEpisodeTitle()` 剥离前缀，纯编号标题保留编号避免出现空标题。
- **减少播放器弹层**：「线路面板 → 查看完整 URL」原要连开两层全屏浮层、返回键按两次，
  改为面板内行内展开，删除整块全屏 URL 浮层及对应 BACK 层级。
- **线路延迟副标题**：新增 `LiveLineLatency`，并发测首字节（`Range: bytes=0-2047`、3s 超时、
  并发 6、保持平台优先级顺序）；线路列表按绿≤300ms / 黄≤800ms / 红>800ms 显示，无障碍描述同步带上。
- **历史更新提示**：`WatchHistoryItem` 新增 `episodeCount` → `updateHint`（「还有 N 集」）；
  手机在观看时间后显示徽标，电视接在副标题末尾（不额外占行，避免压小海报）。
- **详情页位置记忆**：按「影片 id + 线路」记住选集滚动位置，`snapshotFlow` 实时回写，
  返回详情页优先恢复而不是被"滚到选中集"拉回去。

## v1.0.16 - 2026-11-17

斗鱼 + 虎牙「一起看」断流/重连专项修复（第二弹），参考 livepurer / pure_live 思路：

- **虎牙重连参数运行时可调**：虎牙断流机理与斗鱼不同（一次性 anti-code 签名 + CDN 约 120s
  会话上限，靠预建双连接关键帧交接），原先 6 个参数写死在 Go 常量。现改为默认值 + 可写全局，
  经 gomobile 新增 `SetHuya*`（预建提前量/标称会话/空闲读超时/短段退避 base+max/换线阈值），
  每档范围钳制；通过**使用点读取**使档位切换对**在播会话下一段立即生效**（斗鱼现有实现只影响
  新建会话）。仅影响虎牙，其它平台不变。
- **双站点档位按钮**：原「斗鱼重连调优」泛化为 `LiveTuningDebugPrefs`（按站点分档，兼容旧 key）。
  播放页控制条（竖屏保留）与「设置→播放」按当前房间站点显示对应三档按钮；虎牙三档 =
  稳健(预建20s/空闲10s/1→8s/少换线) / 默认(12s/8s/0.5→5s) / 耐心(8s/9s/2→12s/多换线)。
- **两段式卡顿误判修复**（pure_live 思路）：走代理的直播页不再"静止 4s 就重建播放器"——
  曾推进过的流静止 4s 只提示"正在重连"（StallSoft，不触发恢复），12s 才判硬卡死；新增渲染帧
  对账与缓冲 slack 判定（位置在动/缓冲充足即视为健康，忽略引擎 buffering 标志）。真卡死兜底
  不变（硬阈值 12s > Go 虎牙空闲读 8s，代理先自愈；真卡死仍触发 `recoverPlatformLiveChannel`）。
- **上游坏 FLV 标签改为软重连**：真机监控复现虎牙上游偶发 14~16MB 非法 tag 长度（正常几十 KB），
  旧实现按「硬失败」处理（600ms 等待 + 失败计数），并让 `lastError` 长时间停留在误导性的线路故障
  文本上；与半开连接叠加时会放大卡顿。现改为专用软错误：立即清空快速续拉候选、
  重新解析新签名续流，不计硬失败、不换线，并新增 `malformedTags` 统计与
  `malformedFlvTag`/`malformedTagSoftReconnect` 事件便于现场核对；预建连接遇到坏 tag 同样计入。
- **上游会话中途截断按干净 EOF 处理**：房间 2 真机数据出现 `unexpected EOF`（CDN 在一个 FLV
  tag 数据/尾部字段中途关连接）被记成硬失败，白等 600ms 且累计失败次数，实测 10s 无画面。
  现把 FLV 头、tag 数据、PreviousTagSize 三处的 `io.EOF`/`io.ErrUnexpectedEOF` 统一视为正常
  会话结束，交给 `streamLoop` 走既有的快速续拉/短段退避，不计硬失败、不换线。
- **虎牙空闲读超时压到看门狗阈值以下（关键修复）**：真机 20 分钟监控发现卡顿不是随机发生，
  而是稳定出现在**每次代理生命周期的第 3 次计划交接之后**（08:08/08:14/08:20 三次完全一致）。
  此时预备连接进入半开（HTTP 不断、但不再吐数据），代理 `upstreamSessions`/`tagsWritten` 完全冻结；
  而虎牙空闲读超时是 15s，Kotlin 硬卡顿看门狗 12s 就先重建了播放器/代理，代理永远来不及自愈。
  现把虎牙空闲超时默认改为 8s、可调范围收紧到 3~20s，三档分别 10s/8s/9s，保证代理软重连
  早于 Kotlin 硬恢复；半开连接触发 `upstreamIdleTimeout` 后走原有软重连路径，不再产生可见断流。
- **代理侧进度看门狗（最终兜底）**：修复后首轮真机复测仍出现「代理不报错、不吐数据、`lastError`
  为空、`malformedTags`/`idleTimeouts` 都为 0」的冻结——交接后底层读既不返回 EOF 也不超时，
  只能靠 Kotlin 12s 硬恢复。现新增与故障类型无关的进度看门狗：每段开始重置基准，连续 8s 没有
  任何标签写出就取消本段连接（普通段取消 HTTP context，预建段取消预备连接），强制走既有软重连。
  8s 严格小于 Kotlin 12s 硬阈值，保证代理先自愈；`progressStallReconnect` 事件写入统计便于核对。
- **丢帧 / 画面声音不同步修复**：正式复测房间 2 出现「代理持续转发 24fps 视频，播放器 10 秒只渲染
  5 帧、音频位置照常前进」的音画失步。定位到两个时间轴问题：① 虎牙归零续段仍用「两轨最大时间戳
  +1」做全局钳制，同刻的音视频帧被逐帧错开；改为只在本轨内钳到上一帧，保留上游相对偏移。
  ② 回放去重把正常重复的静音 AAC 帧当旧缓冲删除并压缩时间轴；改为「视频严格按指纹去重、
  音频按连续历史序列判定（前驱帧匹配才算回放）」，避免误删正常音频。③ 回放压缩量原先按两轨
  共用一个累计值，视频回放比音频长时会连带把音频时间戳拉回，实测 A/V 偏移一度累积到 2.1s；
  现改为按音/视频轨道分别统计丢弃时长并分别压缩。④ 续段等新关键帧的“视频黑窗”里仍继续转发音频，
  实测 `videoDroppedAwaitKey=41` 时声音已领先约 1.7s；现改为黑窗期音频同步丢弃，视频关键帧写出后
  两轨一起恢复，避免画面回来时声音跑在前面。统计新增 `lastAudioOutTs` /
  `lastVideoOutTs` / `avOutOffsetMs`，播放健康日志新增 `decoderIn`/`decoderOut`/`skippedOut`/
  `maxDropped`（当前 Media3 版本无 decoderIn/decoderOut 字段），便于现场确认 A/V 偏移与渲染状态。
  Go 单测锁定同刻音视频不被错开、静音帧不误删、
  真回放仍能全部拦截。
- **验证**：Go 单测（虎牙 setter/clamp/跨站点隔离/in-use 即时生效 + 坏 tag 软重连 + 会话截断软重连
  + 空闲超时必须小于 Kotlin 看门狗 + 进度看门狗冻结恢复）+ 全部 Kotlin 单测 + lint 通过；
  真机（斐讯 p230）斗鱼/虎牙各 3 房间 × 20 分钟监控采集 `TVBoxPlatformLive` 统计，见下方数据。

### v1.0.16 真机监控数据（斐讯 p230，最终修复版）

虎牙 3 房 × 20 分钟（`huya_v1016_final2`）：

| 房间 | 软卡顿 | 硬看门狗 | 平台恢复 | 重连 | 坏 tag | 渲染中位数/10s | 最大 A/V 偏移 | 备注 |
|---|---|---|---|---|---|---|---|---|
| room_1 (huya:11336726) | 7 | 1 | 4 | 8 | 2 | 250 帧 | 444ms（仅播放器重建瞬间） | 重建后立即回落到 26ms |
| room_2 (huya:11342412) | 1 | 0 | 0 | 6 | 2 | 240 帧 | 40ms | 1 次低渲染窗口 |
| room_3 (huya:11342412) | 2 | 0 | 0 | 6 | 1 | 240 帧 | 35ms | 日志采集中断过，手动补测 |

对照：旧版同组房间基线合计 9 次硬看门狗、17 个坏 tag 形态；修复后仅 1 次硬恢复，且
A/V 偏移从最高 2135ms 收敛到 35~444ms（唯一 444ms 出现在播放器重建瞬间，随后回落）。
`progressStallReconnect` 与 `malformedTagSoftReconnect` 均在代理层完成自愈，未触发播放器重建。

斗鱼 3 房 × 20 分钟（`douyu_v1016_final2`）：

| 房间 | 软卡顿 | 硬看门狗 | 平台恢复 | 重连 | 坏 tag | 渲染中位数/10s | 最大 A/V 偏移 | 备注 |
|---|---|---|---|---|---|---|---|---|
| room_1 (douyu:4184217) | 0 | 0 | 0 | 4 | 0 | 300 帧 | 31ms | 4 次计划内租约续期 |
| room_2 | 0 | 0 | 0 | 0 | 0 | 250 帧 | 29ms | 20 分钟零重连 |
| room_3 | 0 | 0 | 0 | 0 | 0 | 245 帧 | 34ms | 20 分钟零重连 |

合计：双站点 6 房 × 20 分钟，仅虎牙 room_1 出现 1 次硬恢复；全部房间 A/V 偏移
常态维持在 30~40ms，无低渲染窗口扩散，斗鱼三房全程无可见卡顿。

## v1.0.15 - 2026-11-16

斗鱼「一起看」重连问题专项修复与现场可调参数：

- **重连参数从硬编码改为运行时可调**：斗鱼续流的租约续期提前量、上游空闲读超时、短段退避
  基数/上限原先写死在 Go 常量里，现场无法调整。现改为「默认值 + 可写全局」，通过 gomobile
  新增 `SetDouyuLeaseRefreshLeadMs/SetDouyuIdleReadTimeoutMs/SetDouyuShortBackoffBaseMs/
  SetDouyuShortBackoffMaxMs` 暴露给 Kotlin；每档做范围钳制，非法值被忽略。仅影响斗鱼，
  虎牙/其它平台行为不变。
- **手机端新增「斗鱼重连调优」按钮**：三档策略「稳健 / 默认 / 耐心」一键循环切换，即时写回
  Go 并持久化（独立 SharedPreferences）：稳健=提前60s/空闲30s/退避3→12s，默认=45s/15s/2→8s
  （历史行为），耐心=30s/25s/4→16s。播放页控制条（竖屏也保留）与「设置→播放→斗鱼重连调优」
  均可调整，方便不同网络环境下现场定位最佳组合，无需重新发版。
- **租约边界修正**：`douyuLeaseDeadline` 的提前量改用运行时值；提前量大于租约总时长时按原语义
  回退被动路径，避免异常 deadline。
- **验证**：Go 单测（含新增调优 setter/clamp 与续期时刻跟随测试）与 Kotlin 编译/单测均通过。
  真机（斐讯 p230 / Android 7.1.2）安装调试版，对 5 个斗鱼热门房间各播放 10 分钟并采集
  `TVBoxPlatformLive` 统计：5 房间 `reconnects=0`、`upstreamSessions=1`、
  `idleTimeouts=0`、`candidateSwitches=0`，Kotlin 侧看门狗/恢复/流结束均为 0，唯一事件为进房
  初始化的 `pacerRebased`。基线在当前网络与热门房间下未复现「总是重连」，说明该问题与具体
  CDN 线路/网络环境相关；本版将关键参数改为现场可调 + 调试面板可见，便于对症调整，无需发版。

## v1.0.14 - 2026-11-16

手机搜索按源重做与 OTA 升级 CDN 动态择优（含上一版之后未发布的两组改动）：

- **手机搜索 UI 重做：按源筛选 + 按源分组**：结果改为按用户自定义源顺序分组展示，每组一个
  可折叠分组头（源名 + 结果数）；顶部新增「全部 / 各源」筛选条，默认「全部」，点选某源只看该源
  分组。底层仍搜全部源，切换筛选只改变展示视角、不重复发请求。同一部片在每个命中的源下都会显示
  （不再跨源折叠）。`MultiSourceSearchUpdate` 新增 `moviesBySource` 带出每源原始结果，TV 端仍用原
  去重扁平列表，互不影响；新增 `buildSearchSourceGroups` 分组纯函数与单测。
- **OTA 升级 CDN 改为按实测速度动态择优**：探活从 HEAD 可达性升级为 Range 下载 64KB 计时，
  排序以实测速率为主导（已测速可达 > 未测速未知 > 不可达），不再静态写死谁第一。删除失效节点
  `ghproxy.link`、`cf.ghproxy.cn`（返回 HTML）与极慢的 `ghfast.top`，新增当前最快的 `gh-proxy.org`；
  同步 `update.json`、release.yml 生成模板、README 与单测。设置页线路展示改用历史最快耗时排序。
- **修复 Elvis 警告**：`PlatformLiveScreen` 播放健康采样中多余的 Elvis 判断（`player` 已智能转换为
  非空），编译输出现已无警告。
- **验证**：`compileDebugKotlin` 与 `testDebugUnitTest` 通过（离线）；发布构建由 Actions 触发。

## v1.0.13 - 2026-11-16

手机端投屏、历史记录重设计与专项问题修复（本版聚焦手机体验，TV 端行为保持不变）：

- **修复手机端电视直播/平台直播不能投屏**：项目原本只在点播页 `PlayerScreen` 接入了投屏能力，
  两个手机直播页从未启用；且 `PlayerChrome.forOrientation` 在竖屏裁剪时会强制清掉 `cast`，
  手机竖屏连投屏按钮都看不到。现新增共享 `CastSupport.kt`（DLNA 发现/投屏/停止），两个直播页
  接入投屏按钮与设备选择对话框，竖屏裁剪保留投屏能力。平台直播投屏使用平台源地址 `stream.url`，
  不用本机续流代理地址，避免投放失败；停止投屏后本机自动恢复播放。
- **手机端历史记录重新设计**：手机端原先直接复用 TV 网格页，带 TV 焦点残留、重复的顶部提示条
  「观看记录：继续上次的线路、集数和播放进度」，空态按钮还是语义错误的「返回」。现新增
  `MobileHistoryScreen.kt` 移动端专属列表页：横向卡片（封面/标题/集数/进度/更新时间）、按
  今天/昨天/日期分组、支持单条删除、**无顶部提示**、空态按钮改为「去首页」。仓储与 ViewModel
  扩展 `removeHistory`，与进度保存共用写锁避免竞态；TV 端历史页不变。
- **修复搜索 Tab 状态丢失**：切 Tab 或点结果进详情再返回时，关键词/结果/滚动位置不再被清空，
  仅用户主动点 ✕ 时清除。
- **修复单线路频道无限换台死循环**：全为单线路频道时不再「报错→换台→再报错」无限循环，
  改走带退避门控的容错，耗尽后进入阻断重试态。
- **平台直播全屏方向失步修复**：全屏按钮改用真实屏幕方向判断，不再因本地布尔量不同步而锁反方向。
- **手机直播补齐交互一致性**：补分层返回（信息/地址面板打开时先收起而非直接退出）、
  离开页面时取消测速任务；平台直播补竖滑左半屏调亮度/右半屏调音量手势与提示，退出恢复亮度。
- **设置页清理**：移除 4 个仅 TV 生效、手机上点了无效的项（EPG 节目条数、数字选台候选行数、
  数字选台匹配上限、相关频道数量）。
- **布局细节**：首页骨架屏列数改为按屏宽与用户设置推导，消除加载完成时的列数闪跳；
  详情页无剧集空态列宽手机端改用 92dp。另清理手机导航中不可达的 `PlatformLive` 分支死代码。
- **验证**：`compileDebugKotlin` 与 `testDebugUnitTest` 通过（离线）；发布构建由 Actions 触发。

## v1.0.12 - 2026-11-13

手机端控制条改版与虎牙计划内交接残余卡顿修复（v1.0.11 之后未发布的两组改动）：

- **手机端移除「缓冲档位」按钮**：`PlayerChromeCapabilities` 不再下发 `pacerBufferMs / onCyclePacerBuffer`，
  「一起看」手机布局与普通直播手机版都不会再出现「缓冲Ns / 缓冲·关」循环按钮。电视端底部按钮行
  保留该按钮，仍可在遥控器上实时调续流缓冲档位。
- **新增手机端「调试」按钮**：作为专用能力注入，竖屏精简清空普通按钮后依然保留，与其它功能按钮
  同排同一样式。「一起看」点击后打开实时播放健康浮层（续流代理统计、重连/换线、丢帧、双轨拒绝、
  pacer 等待与重连事件，1s 刷新），面板宽度按手机竖屏从 0.62f 放宽到 0.9f；普通直播手机版点击后
  打开新的调试浮层（频道/线路/地址、缓冲时长、分辨率、解码计数、解码策略、首帧耗时与失败原因），
  横屏与竖屏播放区均可用。电视端「监控」按钮保持仅调试包可见。
- **修复虎牙计划内交接后第二条连接被级联取消**：`streamLoop` 在交接成功后 `stopPrepare()`，
  而预建 HTTP 请求原挂在同一个 context 下，刚接手的连接立刻失效。现请求只挂代理生命周期，
  等待预建/解析仍用短生命周期 context；交接返回与通道取出时提前标记「已取用」，防止 20s
  hold timeout 误关连接。
- **修复回放丢弃量重复扣减**：续段在 `spliceBase` 锁定偏移前累计的 `droppedSourceMs`
  又被后续帧扣一遍，输出时间戳被拉回上一段交付位置之前、只能靠每帧 +1ms 兜住，画面会卡住数秒。
  现记录 `droppedAtBase`，只扣基准之后的增量；斗鱼连续时间轴与通用档自适应路径不受影响。
- **修复 pacer 每段重新预填**：预填状态原本是 `consume` 局部变量，虎牙每约 2 分钟交接一次就把
  5 秒数据突发灌给播放器。现状态提升为 `flvProxy.pacer` 跨上游会话保持，预填只在开播做一次；
  交接积压旧帧落后超过 250ms 时重新对齐基准、只放行当前帧。`statsJSON` 新增 `pacerRebases`，
  事件区分 `pacerRebased` / `pacerCatchUpRebased`，便于真机确认交接点。
- **约束**：三档 tuning 参数、斗鱼租约/连续时间轴、通用档自适应路径逐字未动；本轮为 FLV 续流
  与 pacer 的通用正确性修复，不是把虎牙假设外借给其它平台。另清理电视端按钮行两处恒真条件
  与冗余安全调用，编译警告由 6 条降至 1 条（余下为无关的历史 Elvis 提示）。
- **验证**：变异测试把修复改回旧实现后分别复现 `context canceled` 与「输出时间轴只前进 1ms」；
  `go vet` 干净、`go test -count=1 ./...` 全绿（121 用例，主包覆盖率 45.6%），新增用例重复 5 次稳定；
  `assembleDebug` BUILD SUCCESSFUL，AAR 按源码指纹判定 `bindPlatformLive SKIPPED`；
  `.37` 真机 `install -r` Success、启动正常、无崩溃日志。仍待真机盯一次虎牙约 2 分钟交接点
  （`plannedHandovers` 上升时是否仍有顿挫、`pacerRebases` 是否只在交接点出现）。

## v1.0.11 - 2026-11-13

FLV 连续代理工程护栏加固、三档策略彻底分离与计划内切换（含 v1.0.10 之后未发布的三组改动）：

- **斗鱼「一起看」独立重做（不复用虎牙思路）**：从 `pure_live` 提取 `expire=300` 与同轴时间轴线索，
  新增斗鱼专属 `patchDouyuURL`（ws 短租线改写为 `expire=0`）、`douyuLeaseDeadline`（到期前主动
  计划内重解析）、`douyuContinuityOffset`（同房间两次连接共享同一媒体时间轴）。`douyu-yqk`
  使用 continuous 时间轴，不再套用虎牙的归零式 splice。
- **其它平台独立通用档 `generic-live`**：自适应 `auto` 时间轴（动态容差 `min(1000ms, lastOut/2)`）、
  独立 `generic*` 常量族，不改写 URL、不解析租约、不套虎牙全局 `controlledTs` 钳制。
  `FlvContinuityProxy` 白名单仍只放虎牙/斗鱼，通用档作为防御性回退保留，其它平台不进代理、
  也就不会误用任何一家的假设。
- **修复代理单客户端致命缺陷**：旧 `ServeHTTP` 直接覆盖 `p.client`，第二个连接（解码器切换、
  播放器重连、外部录制）会让旧连接饿死且新连接缺 AVC 序列头导致黑屏。现改为按连接代次
  「新连接接管、旧连接退出」，并缓存真实 `onMetaData` / AVC / AAC 序列头按需补发；
  FLV header flags 按实际轨道推导，支持 HEAD 与 `Accept-Ranges: none`。
- **上游 15s 空闲读超时**：参考 livepurer 的 idle deadline，每次 `Read` 前刷新截止时间，
  半开连接自动软重连（不计硬失败、不换线），避免虎牙/通用档永久阻塞；租约 timer 用完即 `Stop`。
- **修正 pacer 目标缓冲语义**：旧实现只是给整条流加 5 秒直播延迟，并不会增加播放器 `bufferedMs`，
  这正是「代理蓄流 5s、播放器只有 1.5–2.8s」的根因。现改为「首段预填窗口内按上游到达速度
  立即转发，窗口之后实时匀速」，缓冲调试按钮才真正改变播放器侧缓冲；目标缓冲 per-proxy 化，
  调整会写回所有在播实例并在下一帧重对齐，不再出现全局 atomic 造成的瞬时灌入或长等待。
- **虎牙计划内预建双连接 + 关键帧切换**：提前 12s 预解析并预建第二条上游连接、缓冲到首个关键帧，
  当前段在自己的关键帧处交接，消除 EOF 后解析/握手空窗（对标 PureLive `FlvSplicer`）。
  刻意不按上一段时长学习，避免 125s→113s→101s 的正反馈；斗鱼租约与通用档 `lead=0`，
  三档仍互不借用。新增 `prepareReady`/`plannedHandover` 等统计字段便于真机确认。
- **写入与时间戳原子性**：`writeTag` 由 3 次 `Write` + `Flush` 合并为单次 `Write`；
  `acceptTs` 拆成 `validateTs` + `commitTs`，写成功才推进时间轴，避免写失败留下时间轴空洞。
- **Kotlin 超时对齐**：本地代理 `readTimeout` 20s→60s，看门狗首帧 12s→20s、stall 4s→15s，
  避免代理静默续流被误判卡死而重建播放器，抵消「不重新 prepare」的设计目标。
- **AAR 重绑守卫修复**：旧 `onlyIf { !platformLiveAar.exists() }` 导致 AAR 存在时改 Go 代码
  也不会重绑、打进旧 `.so`。现按 `platform_live_go/**` 内容 SHA-256 指纹判定，
  指纹落 `app/libs/platformlive.fingerprint`（CI 两处 cache path 同步），
  strip 原地改写不再破坏 up-to-date 判定。
- **工程清理**：移除 `handlers.go` 的 UTF-8 BOM（此前使 server 包 `go test -cover` 失败）；
  更新 `FLV_CONTINUITY_VALIDATION.md` 两处过时说明；修正测试夹具把普通音频帧误写成 AAC 序列头。
- 验证：`go vet` 干净、`go test -count=1 -cover ./...` 全绿（118 用例，覆盖率 43.1%→45.5%）、
  新增 10 个用例重复 5 次稳定；`assembleDebug` BUILD SUCCESSFUL 并自动触发 AAR 重绑；
  `.so` 内含 `prepareReady`/`plannedHandover`/`upstreamIdleTimeout`/`clientTakeover`/`pacerTargetRebased`；
  指纹守卫两方向验证（源码变→重绑、未变→SKIPPED）；真机 192.168.1.37 安装启动无崩溃。
  未覆盖：`-race` 因本机缺 gcc 未运行；adb over TCP 掉线，未做房间内长时监控。

## v1.0.10 - 2026-11-13

斗鱼「一起看」重连策略与虎牙正式分道：

- **斗鱼 CDN 稳定，不再走虎牙的激进重连逻辑**：重连策略从全局常量改为按站点分档
  （`flvSiteTuning` 新增 `maxFastReconnectStreak`/`shortSessionMs`/`maxShortStreak`/
  `shortBackoffBase`/`shortBackoffMax`，`streamLoop` 全部改为读各站配置）。
  - 斗鱼 `douyu-yqk`：EOF 后最多同地址续拉 1 次，随后一律重新解析拿新签名
    （斗鱼签名按会话发放）；短段（<4s）耐心退避 2s→8s；**永不因短段自动换线**，
    信任当前 CDN 线路，真正坏掉的线路由硬失败（HTTP 错误）路径兜底换线。
  - 虎牙 `huya-yqk` / 默认档：逐字保留历史策略（续拉 ×3、短段 <5s、退避 500ms→5s、
    连续 4 次短段换线），已有实测结论不回退。
- **修复退避移位溢出隐患**：斗鱼不换线时短段计数会持续增长，`1<<(n-1)` 移位超过
  63 位会溢出为负导致退避失效，已加封顶。
- **监控可观测性**：`statsJSON` 新增 `fastReconnectMax`/`shortSessionMs`/
  `shortStreakMax`/`shortBackoffMs`，真机监控可直接确认两个平台各走各的策略。
- 验证：Go 全套测试通过（含新增 `TestDouyuReconnectPolicyCalmerThanHuya`，
  断言斗鱼策略必须比虎牙更克制）；真机 192.168.1.37 斗鱼房间实测
  `tuning:"douyu-yqk"` 生效、播放稳定。



## v1.0.9 - 2026-11-12

直播「一起看」续流稳定性、播放器缓冲调试、OTA CDN 与交互优化：

- **斗鱼 / 虎牙「一起看」续流逻辑正式分档**：此前 `flv_proxy` 的关键参数（回放去重窗口、去重保护帧数、splice 步长、pacer 上限）是一套全局常量，实际是按虎牙「一起看」的回放形态调出来的，斗鱼「一起看」被迫共用同一套。
  现在新增按平台分档的 `flvSiteTuning`：`huya` 档位**逐字保留历史常量**（不回退已有实测结论），`douyu` 档位针对其更短、更稳的回放缓冲把指纹去重窗口从 12s 收紧到 8s（更快遗忘陈旧指纹、减少误伤新帧、降内存），其余安全参数保持一致；未知平台回退到与历史默认相等的保护档。代理按 `site` 装载对应档位，并在 `statsJSON` 暴露 `site`/`tuning` 字段，便于真机监控确认生效（如 `tuning:"douyu-yqk"`）。
- **OTA 升级「先探活再优选」**：此前 CDN 排序只依赖历史健康度（失败计数），从未真正检查线路当下是否可达，`buildCdnProbePlan` 探活计划一直未被接线。
  现在 `rankOtaCdnCandidates` 增加 `availability` 参数（不可达线路大幅降权），并在 `checkForUpdate`/`downloadUpdate` 的真实取数路径前先用 `probeCdnAvailability` 对前若干候选做轻量 HEAD 探活（短连接/读取超时、整体超时保护、异常安全降级为空→回退纯健康度排序，绝不因探活失败误杀可用线路）。设置页状态展示保持不联网。
- **OTA CDN 候选扩容**：实测探针确认 `ghfast.top` 主源当时不可达后，新增两条实测可用镜像 `ghproxy.link`、`cf.ghproxy.cn`（同时进入 APK 代理列表与清单源列表），探活计划覆盖数 3→5；发布工作流 `release.yml` 的 `apkMirrors` 同步加入这两条镜像。
- **修复上游重连风暴（真机复现根因）**：旧版上游 EOF 后零退避、且不计失败的立即重连，坏线路被高速反复重连（真机 3 分钟 48 次 reconnects、解码丢帧 0→119）。
  现在会话时长 <5s 视为短段，连续短段按 500ms·2^n（上限 5s）指数退避；连续 4 次短段自动切换下一条 CDN 线路（`短段频发已切换线路`）。真机复测：重连风暴彻底消除，`reconnects=0`、解码丢帧 `dropped=0` 全程保持。
- **pacer 目标缓冲 5s**：pacer 故意比实时慢约 5s 推送，让下游始终保有约 5s 已接收数据抗上游抖动；单帧等待上限 2s→6s，仅用于长空窗后追帧时重置基准。真机实测播放缓冲稳定、无饥饿。
- **新增「缓冲」调试按钮（电视端 + 手机端）**：pacer 目标缓冲改为运行时可调（Go 导出 `setPacerTargetBufferMs`/`getPacerTargetBufferMs`），播放器按钮行新增循环按钮「缓冲 5s → 3s → 1s → 0(关) → 8s → 5s」，选择持久化到本地、App 启动自动恢复，手机横竖屏均可调，方便用户按网络状况自行调试。
- **播放器交互调整**：确认键不再弹出线路切换浮层，改为打开与电视直播一致的「节目列表」（带房间封面，列出当前分类全部房间）；线路切换改为按钮触发；弹幕/监控按钮并入播放器统一按钮行（与其它按钮同排同样式，移除右上角悬浮组）。
- **修复搜索页状态残留**：搜索完成后跳转其他页面再返回，搜索词未正确清除的问题（`savedQuery`/输入框值改为 `rememberSaveable` + 单向同步）。
- 验证：Go `build`/`vet`/`test` 全绿（含分档、退避、pacer setter 新用例）；Kotlin 单测全绿；真机 192.168.1.37 实测重连风暴消除、缓冲稳定。
## v1.0.8 - 2026-11-12

直播续流与弹幕流畅度修复（针对 192.168.1.37 上「一起看」掉帧）：

- **修复代理把音视频两轨混在同一条时间轴上比较**：旧版用单一全局 `lastTs` 判断时间戳回退，
  而 FLV 的音/视频时间戳本来就是交错的，落后几十毫秒的那一轨会被整帧丢掉。
  现在音/视频各维护一条输出时间轴，只在同轨内部拒绝回退；`spliceBase` 改以两轨最大值
  为锚点。新增 `tsRejectedVideo/tsRejectedAudio` 计数便于现场确认。
- **新增 pacer 平滑输出**：上游 HTTP-FLV 常以突发方式一次送来数秒数据，旧版立即全量灌给
  播放器，容易出现快进/跳帧感。现在按输出媒体时间对齐墙钟发送，时间戳跳变超过 2s
  自动重置基准而不空等；`TVBOX_LIVE_PROXY_NO_PACER=1` 可关闭（离线测试用）。
  新增 `pacerWaitMs` 计数。
- **弹幕层降低低端设备绘制负担**：同屏上限 60→24 条；文字阴影去掉模糊
  （`blurRadius 4f → 0f` 偏移描边），避免几十条弹幕每帧走 MaskFilter 模糊。
- **补齐打包前 assets 校验**：`verifyRequiredAssets` 在 `preBuild` 检查
  `dy_abogus.js` / `platform_live_catalog.json`，缺文件直接构建失败。
  上一版本机产物因安全软件静默删掉源文件而漏打包抖音签名脚本，导致平台直播初始化失败。
- 验证：Go `build`/`vet`/`test` 全绿（含虎牙回放形态、会话中途回放等回归用例）；
  Kotlin 单测全绿；真机 192.168.1.37 虎牙「一起看」6 分钟 4 个上游会话 / 3 次续流，
  `tsRejected*=0`、`videoDroppedReplay=0`、`pacerWaitMs` 持续增长，解码丢帧仅 5 帧。

## v1.0.7 - 2026-11-11

手机版专项修复（错误提示不可见 / 搜索页双层头部 / 选集不定位 / 状态丢失等）：

- **修复手机版「错误提示完全看不见」**：`ErrorState` / `EmptyState` / `LoadingState` 的文字直接用了 `LocalContentColor`（Compose 默认是黑色）或 `colorScheme.onSurface*`，在没有 `Surface` 包裹的深色页面上（如手机搜索页）渲染成黑底黑字——用户只看到一个「重试」按钮，看不到任何失败原因。现在统一显式使用 `TvColors.TextPrimary/TextSecondary`。
- **修复手机搜索页叠了两层头部**：公共顶栏（含一个点了没反应的假搜索框）与页内真实搜索框同时渲染，白占约 120dp 且键盘一弹就顶掉结果区。现在搜索页不再渲染公共顶栏。
- **修复手机搜索输入被反复打断**：输入框 `remember(state.searchQuery)` 作 key，每敲一个字符 key 变化就重建 `TextFieldValue`，拼音组合区被反复清空、光标永远跳末尾。改为空 key + 单向同步；同时输入过程不再回写 `searchQuery`（该值语义改为「已提交的搜索词」），避免一边打字一边把结果列表滚回顶部。
- **手机搜索补「✕ 清空」与提交后收键盘**：此前改关键词只能长按全选删除；提交后 IME 常驻把结果压掉大半屏。
- **修复播放器「选集」永远从第 1 集开始**：`LazyRow` 没有状态也不定位，百集剧要横滑几十次才能找到正在看的集。现在打开面板即滚动到当前集（左侧留两格上下文）。
- **修复手机底部导航多出一条黑色空条**：`NavigationBar` 外层又加了一次 `windowInsetsPadding(navigationBars)`，与 Material3 自带的避让叠加，多出的高度没有 `containerColor` 显黑。改为只通过 `windowInsets` 参数避让一次。
- **修复源管理/直播源管理/视频源管理/历史页底部按钮被系统导航栏压住**：这几页只吸了 `statusBars`，底部那颗「返回」贴屏幕最底，三键导航机型被压住、手势机型落入上滑热区。改为 `systemBars`。
- **手机历史页去掉多余的「返回」**：它是底部 Tab 的一级页面，没有"返回上一页"语义，误点会把用户莫名送回首页。
- **手机直播筛选状态与滚动位置不再丢**：`remember` 改为 `rememberSaveable`（含 Saver）+ `rememberLazyListState`，切到「首页」再切回来不再把关键词/分组/只看收藏全部归零。
- **手机直播相关频道点击卡顿**：`channels.indexOf(related)` 在 item 内做 O(n) 深比较（`LiveChannel` 是 data class），上千频道时滚动必掉帧；改为预建索引表，O(1) 查询。
- **平台直播手机页补「‹ 返回」并收敛悬浮开关**：标题滚出后整页只剩网格、没有任何返回入口；「弹幕·开 / 调试监控」改为仅在控制条可见时显示、下移到顶栏下方（不再和右上角「全屏」抢点击），且「调试监控」只在 Debug 包出现。
- **手机首页空态补刷新**：分类被全部屏蔽或源无数据时，此前只剩一行「共 0 部」加一片空白，没有任何可点操作；现在给出说明 + 刷新按钮。
- 弹幕开关改 `rememberSaveable`，重组/切后台不再被重置为「开」。
- 真机验证（192.168.1.37 模拟 1080×1920 竖屏）：搜索页单层头部、输入与 ✕ 清空正常、错误提示可见、历史页无返回按钮、底部导航无黑条、首页连续下滑可加载完全部 208 部并出现「已经到底了」。
- 不含任何 AI / 智能推荐 / 大模型能力。

## v1.0.6 - 2026-11-11

数量参数按大屏体验重新调优 + 搜索并发拉满 + 修复首页「加载更多」被裁切卡死 + 斗鱼弹幕修复：

- **修复「首页滑到底加载不出更多」的残留根因**：桌面 `MovieGrid` 与手机 `MobileHomeFeed` 在豆瓣热播模式下都做了 `doubanHotMovies.take(homeHotContentCount)`，并把 `hotCapReached`（已显示条数 ≥ 设置值）当作加载更多的开关——一旦首屏铺满设置条数，既不再显示「加载更多」按钮、也不再触发滚动自动加载，看起来就是「下滑没反应」。现在该值只作为**首屏展示条数**，用户触发过一次「加载更多」（`page > 1`）后自动解除上限，列表随已加载内容持续增长。
- **数量类参数默认值按 1080p 电视 / 现代手机的实测体验重新调优**（设置里仍可逐项改小）：继续观看 6→8、EPG 节目单行数 4→5、数字换台匹配行数 6→8、数字换台搜索上限 50→100、相关频道 12→16、搜索热词 18→24、搜索联想 3→6、首页热播首屏 20→30、豆瓣分页 20→30、豆瓣回退线路 6→8、历史保留 100→200。
- **多源搜索并发默认拉满到 12（等于上限）**：此前默认 6，尾源要排队两轮才轮到；现一次扇出全部已启用线路，最大程度避免「多个源只搜到 1~2 个内容」。
- **修复斗鱼弹幕永远不显示**：`DouyuDanmakuClient` 用 `dms`（历史弹幕时间轴，现网 `chatmsg` 根本不带该字段）判空过滤，导致每条弹幕都被丢弃——连接、进房、收帧全部正常，画面上却一条没有。已删除该过滤，多消息切分由错误的 `split("//")` 改为按 `\u0000`，并补前 5 条 chatmsg 的日志便于现场确认。
- 真机验证（192.168.1.37 / p230 / Android 7.1.2）：斗鱼「一起看」房间连续播放 **420 秒**，`dropped=0`、`bufferedMs` 稳定 3.4s、`reconnects=0`、`segGap=0`、`segReset=false`、无重复帧；弹幕日志与画面渲染均正常。
- 验证：`testDebugUnitTest` + `assembleRelease` BUILD SUCCESSFUL。
- 不含任何 AI / 智能推荐 / 大模型能力。

## v1.0.5 - 2026-11-11

直播播放器诊断日志 + 画面比例修复 + 搜索并发修复 + 更多显示设置：

- **直播平台播放器增加调控日志（监控虎牙/斗鱼问题）**：新增每 10 秒播放健康采样（分辨率、解码帧/丢弃帧、缓冲时长、当前线路、连续代理统计），单独记录「视频尺寸变化」（虎牙/斗鱼重连换流的常见征兆）与「画面比例切换」；`PlayerSurface` 每次应用画面比例都打印生效模式与比例框，便于确认「画面比例设置」确实生效。
- **修复画面比例设置无效**：固定比例档位（16:9 / 4:3 / 1:1 / 21:9 / 9:16）原先误用 `RESIZE_MODE_FILL`，会把非同比例源拉伸变形、且在 16:9 源上看起来「没变」；现改为在目标比例框内用 `FIT` 保持视频原始比例（居中留黑边），四种比例档位终于真正生效且互不混淆。该修复同时覆盖电视直播、手机直播与点播四个播放页（共用 `PlayerSurface`）。
- **修复多源搜索并发只返回 1~2 个源内容**：搜索改为覆盖**全部已启用线路**（不再剔除冷却中的线路，冷却只服务于播放自动换线）；统一 6 秒超时预算（去掉按权重递减到 600ms 的饥饿分配），默认并发数由 3 提升到 6（上限 12）；搜索失败不再计入冷却，避免尾源被临时超时踢出后又进冷却、后续搜索再也搜不到它——这正是「多个源只搜到 1~2 个内容」的根因；并新增每源与汇总日志便于排查。
- **设置新增更多显示项**：新增「首页海报数量」「直播海报数量」（默认 36，可选「不限制」~ 120 / 200，参数可自由最大化），贯穿数据层、手机与电视设置 UI、以及首页与平台直播网格的实际渲染；同时把「多源搜索并发数」上限放宽到 12、默认 6。
- 验证：`go build ./...` / `go vet ./` / `go test -count=1 ./...` 全绿；`testDebugUnitTest` + `assembleDebug` BUILD SUCCESSFUL；新 APK 已安装至 192.168.1.45 真机验证。
- 不含任何 AI / 智能推荐 / 大模型能力。

## v1.0.4 - 2026-11-11

虎牙 / 斗鱼连续代理续流修复 + 手机设置页文字遮挡修复：

- **修复虎牙续流重复画面 1~2 秒（根因不是时间戳断层）**：代理输出时间轴由 `spliceBase` / `acceptTs` 保证严格单调，用时间戳测不出重复；真正原因是 CDN 重连时带回上一段末尾的旧缓冲，旧 `skipReplay` 用「偏移后的时间戳」与「偏移前的时间戳」比较导致条件永不成立，且回放段自带的关键帧会误解除「等待关键帧」。现改为按 `tagType + data` 做 FNV-1a 帧指纹去重，AVC 序列头用受控时间戳转发、只等「没见过的关键帧」。模拟流检测从「30 帧 / 957ms 重复」降为「0 帧 / 0ms」。
- **按「虎牙·一起看」入口做真实 CDN 复核并二次加固**：用户反馈该入口更贴近实际使用场景，复核发现第一轮修复在真实流上仍输出 **153 帧 / 6.08 秒**重复（模拟用例的 1.5 秒回放覆盖不到）。三个叠加原因：① `dropReplay` 一遇到「全新关键帧」就解除去重，而真实 CDN 是「先给新关键帧、再跟 6 秒旧缓冲」；② 5 秒指纹窗口短于 6.08 秒回放；③ 丢弃回放后未补偿源时间轴，会撕出 6 秒时间戳空洞。修复：拆出独立的 `guardActive` 去重保护期（连续 120 帧无命中才解除，1200 帧硬上限）、窗口放宽到 12 秒、累加 `droppedSourceMs` 让新内容紧接上一帧输出并做严格不回退兜底（仅续段，避免把首帧 0 抬成 1）。真实流 200 秒复核：`reconnects=1`、`lastSessionMs=120574`、`lastError=""`、**0 重复帧 / 0ms**，`TVBOX_LIVE_ASSERT=1` PASS。
- **第三轮：去重贯穿整个续段 + 快速续拉空转保护**：把最终代码再复核时，CDN 侧转为短会话重连风暴（200 秒 51 次会话、`lastSessionMs=82`），测出 **330 帧 / 5.16 秒**重复 —— 回放不只发生在续段开头，会话中途也会零散重发旧缓冲。现改为 `guardActive` 在整个续段内持续做指纹检查（硬上限 7200 帧），并给同地址快速续拉加连续 3 次上限与失败退避（`flvProxyMaxFastReconnectStreak`）。新增 `TestFlvProxyReplayMidSession` 回归（150 个全新帧之后插入 60 帧旧缓冲，断言 0 重复）。最终真实流 200 秒 **0 重复帧 / 0ms**、`TVBOX_LIVE_ASSERT=1` PASS；真机在「一起看」房间跨 120.5 秒关流播放 170 秒，`reconnects=1`、`lastError=""`、**0 次看门狗 Stalled / 0 次播放器重建**。
- **修复斗鱼断流重连感**：正常 EOF 且本段有数据输出时，复用同一地址快速续拉，跳过斗鱼重新拉签名 JS + goja 执行 + 逐 CDN 取流；快速续拉 403 / 签名过期时回退重新解析，且不计入硬失败、不误切候选线路。
- **新增三个检测程序**：`flv_proxy_replay_detect_test.go`（真实 `newFlvProxy` + httptest，含 1.5 秒回放检测与 `TestFlvProxyReplayAfterFreshKeyframe`「新关键帧 + 6 秒旧缓冲」真实形态回归，`TVBOX_REPLAY_ASSERT=1` 作为守卫）、`flv_proxy_live_test.go`（可选联网，连真实平台 CDN 录制 200 秒按帧指纹复核，默认跳过不影响 CI）。离线单文件诊断器 `web/flv_replay_analyzer.html` 同步保留在本地。完整根因、检测口径、实测数据与真机记录见 `platform_live_go/FLV_CONTINUITY_VALIDATION.md`。
- **AAR 重绑定**：`app/libs/platformlive.aar` 已用修复后的 Go 代码重新 `gomobile bind` 生成（新 `.so` 内含 `rememberFrame` / `isReplayedFrame` / `flvFrameFingerprint` / `controlledTs`）。此前 `bindPlatformLive` 只在 AAR 不存在时执行、旧 AAR 被持续复用，导致「改了 Go 但真机还是旧代码」；`gomobile init` 需设置 `GOPROXY=https://goproxy.cn,direct`。详见 `platform_live_go/FLV_CONTINUITY_VALIDATION.md`。
- **修复 IPTV 保存直播源后出现遮挡层且无法退出**：保存/清空后主动收起软键盘并清除焦点；三个源管理页由 `safeDrawing` 改为显式 `WindowInsets.statusBars`，页内接管系统返回键并补底部「返回设置」兜底按钮。
- **修复手机设置页字体被遮挡 / 无法显示**：`SettingsRow` 改为「标题与说明占满整行自然换行 + 选项组独占下一整行」，取消标题/副标题/chip 的强制单行省略（共 19 个选项行）；`ChoiceChips` 提亮未选中项文字、放大字号与触摸目标；分类卡副标题、配置摘要值、跳过秒数行、底部版本信息一并取消裁切或提亮；`SettingsActionButton` 长文案不再被截成前半截；视频源管理页的源地址由 `labelSmall` 单行改为 `bodySmall` 两行。真机在正常字号与「超大」字号下均复核通过。
- 验证：`go build ./...` / `go vet ./` / `go test -count=1 ./...` 全绿；`testDebugUnitTest` + `assembleDebug` BUILD SUCCESSFUL；新 AAR 已打入 `app-debug.apk` 并在 192.168.1.45 真机安装验证。
- 不含任何 AI / 智能推荐 / 大模型能力。

## v1.0.3 - 2026-11-10

历史内容清理 + 本地残留整理（无功能改动）：

- **CHANGELOG 只保留 1.0 起点之后**：移除 66 个 1.0 之前的版本小节（v1.3.61 回溯至 v1.0.0-2026-06-06），其中大量条目存在 UTF-8 乱码；文件由 348 行精简至 24 行。
- **README 去掉旧版本引用**：`update.json` 示例改用 1.0.x 真实值（versionCode/versionName/apkUrl/`apkSha256`/`apkSize`），发布流程示例的 `v1.3.27` 改为 `v1.0.3`，OTA 兼容说明改为「1.0.0 及之后版本可直接升级」。
- **移除 AI 能力下线后的遗留配置**：`app/build.gradle.kts` 删除 `TVBOX_AI_API_KEY` 读取与 `BuildConfig.AI_API_KEY` 注入，以及随之失效、已无调用点的 `toBuildConfigString()`。v1.0.0 起已不含任何 AI / 智能推荐能力，这些字段是空壳。
- **单测版本号归一**：`AppUpdateManifestTest`、`OtaCdnTest`、`BatchStateTest`、`PlaybackParserTest` 中 1.2.0 / 1.3.5x 的用例数据改为 1.0.x，并替换掉指向已删除 Release 的 URL。
- **本地残留清理**：删除根目录第 62–69 轮遗留的 28 个一次性临时文件（构建日志、提交信息草稿、CI 运行记录、`verify62.ps1` 等脚本）；`.gitignore` 补 `.workbuddy/` 与 `platform_live_go/flv_proxy_diag_test.go`（本地联网诊断测试，与已忽略的 `zz_debug_test.go` 同类）。
- 验证：`testDebugUnitTest` + `assembleDebug` BUILD SUCCESSFUL，76 个测试类 / 495 例全绿（0 失败 0 错误 0 跳过）。
- 不含任何 AI / 智能推荐 / 大模型能力。

## v1.0.2 - 2026-11-10

OTA 更新下载弱网兜底 + 仓库过程文档清洗：

- **更新清单单源超时**：`AppUpdateRepository.checkForUpdate` 给每个清单源加 10 秒整体 `withTimeout`，慢 CDN 快速失败并切下一条，启动检查不再被单个挂起连接长时间拖住；超时按线路失败记账并降权。
- **下载假死快速失败**：新增 domain 纯函数 `isOtaDownloadStalled`（阈值 `DEFAULT_OTA_DOWNLOAD_STALL_TIMEOUT_MS` = 90 秒），已收字节持续无变化即判定线路假死、切换下一条线路，不再干等 15 分钟整体截止；0 字节长期 PENDING/RUNNING 的任务同样被截断。系统 `DownloadManager` 与应用内下载服务两条路径都接入。
- **用户可取消下载**：新增 `AppUpdateRepository.cancelDownload`（终止系统下载任务、清理各线路半成品与持久化任务记录）与 `UpdateDownloadService` 的 `ACTION_CANCEL` 分支；`TvBoxViewModel.cancelUpdateDownload` / `dismissUpdateCard` 配合 `UpdateUiState.cardDismissed`，悬浮卡片下载中 X 即取消、失败态 X 只关卡片不打断返回导航。
- **弱网提示**：进度停在 0/未知超过 10 秒时卡片提示「网络较慢，长时间无进展，可取消后重试」，此时返回键映射为取消下载；正常推进时不拦截返回键。
- **失败归类**：`summarizeOtaCdnFailures` 增加「下载长时间无进展」归类，多线路失败汇总不再一律报超时。
- **仓库过程文档清洗**：`.docs/`、`docs/`、`plan/` 下的代码审查、缺陷修复报告、改造台账与方案计划全部移出版本库（本地保留），`.gitignore` 补 `docs/`、`.docs/`、`plan/` 规则；仓库文档只保留 `README.md`、`CHANGELOG.md`、`platform_live_server/*.md`，README 增加「仓库文档约定」并去掉失效的文档/图片引用。
- **历史内容清理（1.0 之前）**：CHANGELOG 只保留 `1.0.x` 小节，移除 66 个 1.0 之前的版本记录；README 的 `update.json` 示例与发布示例改用当前版本；构建脚本移除 AI 能力下线后遗留的 `TVBOX_AI_API_KEY` / `BuildConfig.AI_API_KEY` 与随之失效的 `toBuildConfigString`；单测与注释中的旧版本号改为 1.0.x 或中性表述。
- 不含任何 AI / 智能推荐 / 大模型能力。

## v1.0.0 - 2026-11-10（全新版本起点，应用更名为「聚合视频」）

紧急修复启动崩溃 + 品牌更名为聚合视频：

- **修复启动崩溃（根因不在删除 Hero，Hero 删除本身干净无残留）**：给 `MediaCard` 窄卡文字区加固定高度时，对 typography 的 `lineHeight` 直接 `toDp()`；而自定义 `TvTypography` 只设置了 `fontSize` 未设 `lineHeight`（M3 Typography 具名参数是整体替换，不是合并默认值），`lineHeight` 为 `Unspecified`，`TextUnit.toDp()` 对非 Sp 类型直接抛 `IllegalStateException`——手机首页渲染第一张窄卡即崩溃，表现为「APK 无法启动」。单测不渲染 Compose 故未拦截。
  - `Theme.kt`：全部 13 个 typography 样式显式补齐 `lineHeight`（保守 ~1.15-1.25 倍字号），字号缩放 `scaled()` 同步缩放 lineHeight。
  - `Common.kt`：新增 `effectiveLineHeight()` 防御回退（lineHeight 未指定时按字号 ×1.25 推导），保证进入 `toDp()` 的永远是合法 Sp。
- **更名为「聚合视频」**：启动器名称（`app_name`）、手机/TV 设置「关于」页应用名、OTA 更新通知标题、远程管理页标题、GitHub Release 名称与描述、Release APK 文件名（`juhe-video-v*.apk`）、README 全部更名。包名 `com.tvbox.app` 与数据结构不变，老版本可直接 OTA 升级（`versionCode=20000`）。
- 版本号重置为全新起点 `1.0.0`（versionCode 20000），删除此前全部历史 tag 与 Release。
- 不含任何 AI / 智能推荐 / 大模型能力。
