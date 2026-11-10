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
