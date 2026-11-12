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
