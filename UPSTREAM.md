# 与上游的关系（Fork 身份说明 / S2-09）

> 本文件回答三个问题：**我是谁**、**我和上游差在哪**、**我该给谁提 issue**。
> 维护者：@cnliux ｜ 产品名：**聚合视频 JuheVideo**（原 TVBox）｜ 独立分支，非原作者出品。
> 上游：https://github.com/ZxxXinI/tvbox ｜ 本仓库：https://github.com/cnliux/tvbox

## 一句话

这是 TVBox 生态的**独立分支**：播放内核沿用上游思路，但 UI、数据层、Go 解析服务和发布流程已深度改造。
**问题请提给本仓库，勿扰上游**——上游作者没有参与这些改动，也没有义务为它们背书。

## 一、已回灌上游的（PR 号）

目前**尚无已合并回上游的 PR**。原因：本分支的改动大多以「单 APK 多端 + Go 解析内核」为前提，
与上游 Python 解析服务的架构不兼容，直接回灌会引入冲突。后续若抽出通用修复（如 SHA256 校验、
时间轴钳位），会在此逐条登记 PR 号。

## 二、有意不分叉保留的（本 fork 的私有策略）

| 领域 | 本分支做法 | 与上游的差异 | 保留理由 |
|---|---|---|---|
| 解析内核 | Go（`platform_live_go/`）+ gomobile AAR | 上游为 Python 解析服务 | 去掉服务端部署依赖，侧载即可用 |
| 应用架构 | 单 APK + 运行时设备模式（TV/手机） | 上游分端构建 | 少一半维护面，用户升级只装一个包 |
| UI | Compose 重写（首页/历史/设置/播放器） | 上游为传统 View 体系 | 遥控焦点与 TV 大屏体验需要自绘 |
| 数据层 | DataStore + 多 prefs + 备份/迁移体系 | 上游以 prefs 为主 | 换机可携带、恢复出厂可闭环 |
| 发布 | GitHub Actions 构建三渠道资产 + SHA256 manifest | 上游发布流程不同 | 侧载分发的完整性校验 |
| 合规 | `SECURITY.md` / `PRIVACY.md` / OSS 清单 / 首次运行契约 | 上游无对应文档 | 应用商店与备案问询需要 |

## 三、待回灌 / 待 triage（含 66 commits behind）

> ⚠️ **状态：待逐条核对**。审计基线时本分支 `481 commits ahead / 66 behind`；
> 由于网络与上游 PR 状态随时变化，本表**不写成结论**。每次发版前执行：
>
> ```bash
> git fetch upstream
> git log --oneline main..upstream/main | tee /tmp/upstream-behind.txt
> git log --oneline --left-right --cherry-pick main...upstream/main | head -80
> ```
>
> 对上面每一条 commit，在下方表格补一行，标记为 `已合入等价实现 / 不需要 / 待合`：
>
> | 上游 commit | 摘要 | 结论 | 本分支对应落点 / 理由 |
> |---|---|---|---|
> | _待填_ | _待填_ | 待 triage | _待填_ |
>
> 直到这张表没有空白，本 fork 才算对上游有交代。CI 的 `upstream-sync-check`
> （S2-09，待加）会在 behind 数持续增长时开提醒 issue，不阻断发版。

## 四、身份与命名

- 产品名：**聚合视频 JuheVideo**，`app_name` 已不使用 TVBox；
- 包名 `com.tvbox.app` **有意保留**——用户数据迁移成本高于改名收益，桌面显示名以 `app_name` 为准；
- UA 中仍含 `TVBox-Android/1.3` 标识片段（`UserAgentSetting.Default`）。这是兼容性考量，
  但会让人误以为本分支由上游背书；后续改为读 `BuildConfig.VERSION_NAME`（S2-09 待办）。

## 五、报告问题

- **播放 / 遥控 / 数据 / 合规** → 本仓库 Issues（请用模板，附盒子型号与脱敏日志）；
- **上游原有功能的上游行为** → 上游 Issues；
- **安全漏洞** → 本仓库 `SECURITY.md` 的私密渠道，不要公开贴 0-day。
