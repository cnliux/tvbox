# 聚焦与 UI 执行记录 — v1.0.44 聚焦审查批次 1

> 执行日期：2026-11-26 ｜ 基于 `待执行/聚焦与UI设计审查清单_v1.0.44.md`
> 范围：聚焦/UI 审查清单执行顺序建议的「第一批 + 第二批」
> 验证：`:app:compileDebugKotlin` ✅ ｜ `:app:testDebugUnitTest` ✅ 1092 用例 0 失败 ｜ `:app:lintDebug` ✅

---

## 本批次完成的条目

| 条目 | 级别 | 动作 | 关键文件 |
|---|---|---|---|
| F-01 | 🔴 | 焦点 token 契约修复：`focusTokensFor(themeAccent, ringColor, reduceMotion)` 按主题解析；`tvFocusScale` 消费 `ringWidth/ringColor/elevation`；测试重写为「字段被消费」契约断言 + 主题×reduceMotion 矩阵 | `TvFocusTokens.kt`、`Focus.kt`、`Theme.kt`、`TvFocusTokensTest.kt` |
| F-02 | 🔴 | 焦点色统一收敛到 `TvColors.FocusRing`：`Focus.kt` 默认值 `Accent→FocusRing`；散落固定白/薄荷绿/绿焦点色替换；设置分类卡去掉静态渐变边与 `tvFocusScale` 叠加的双边框 | `Focus.kt`、`LiveTvChannelPanel.kt`、`PlatformLiveScreen.kt`、`PlayerChrome.kt`、`SearchScreen.kt`、`SettingsTvSplitScreen.kt` |
| F-04 | 🟠 | 直播面板首焦重试：`rememberTvFocusRequest` 增 `retries`（每帧重试），`LiveTvChannelPanel` 面板首焦改用统一焦点请求（60ms + 2 帧重试） | `FocusRequest.kt`、`LiveTvChannelPanel.kt` |
| F-08 | 🟠 | 直播切台横幅重复绘制删除（两段相同 `switchBanner?.let` 保留一段） | `LiveScreen.kt` |
| M-01 | 🟠 | 手机播放器自动隐藏不抢焦：`PlayerChromeState` 增 `touchMode`，`restoreFocus()` 触摸模式直接返回；四个播放页按各自设备模式传入 | `PlayerChrome.kt`、`PlayerScreen.kt`、`LiveScreen.kt`、`MobileLiveScreen.kt`、`PlatformLiveScreen.kt` |
| M-02 | 🟠 | 首启法务页主题/触摸模式修复：移入 `TVBoxTheme` 并按设备模式提供 `LocalTouchMode`；条款正文 `heightIn(min=260dp)+verticalScroll`；删死代码 FocusRequester | `MainActivity.kt`、`FirstRunScreen.kt` |
| M-03 | 🟠 | 手机触摸热区统一 48dp：历史页搜索入口/清除✕/HistoryChip/建议词、手机搜索页清除✕、手机直播搜索历史 chip | `MobileHistoryScreen.kt`、`SearchScreen.kt`、`MobileLiveScreen.kt` |

## 部分完成 / 需真机确认

- **F-02 余项**：审查清单要求「7 处焦点驱动的 `.border()` 改走 `tvFocusScale`」。本批次完成**焦点色统一**（核心），但保留各列表行（选集/线路/候选）原有的 border 宽度逻辑，**未强行改走 `tvFocusScale`**（避免引入不想要的缩放/阴影）。颜色已统一，行为变更留待真机走查后评估。
- **F-03**：直播面板焦点陷阱 `livePanelFocusTrap` 仅在测试 fixture 使用，生产 `LiveScreen` 未接线 —— 需真机确认后处理。
- **F-05**：`PlayerChrome` picker 下标已有 `getOrNull` 保护（越界风险已缓解）；`SearchScreen` 首焦复用 `rememberTvFocusRequest`，仍待真机验证。
- **M-04**：源管理指纹徽章热区（16dp）与父卡嵌套 clickable —— 修法涉及布局权衡（48dp 会破坏源管理卡），需设计决策后处理。
- **M-05~M-11 / F-06/F-07/F-10**：待后续批次（多数需真机走查或结构重构）。

## 回滚点

- 本批次改动均已提交推送（见 v1.0.45 发布提交）。
