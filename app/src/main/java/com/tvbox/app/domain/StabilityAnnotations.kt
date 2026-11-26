package com.tvbox.app.domain

/**
 * S5-26：Compose 稳定性注解的**约定真源**（本文件只承载规则，不引入 Compose 依赖）。
 *
 * 背景：核实报告显示全仓 `@Stable` / `@Immutable` 为 0 个，组合函数参数里的
 * `MutableList` / 可变状态会让 Compose 无法跳过重组，TV 端列表滚动时反复重绘海报。
 *
 * 约定（domain 层保持纯 Kotlin，不把 androidx.compose 泄漏进来）：
 * 1. 所有字段 `val`、集合只读、无可变引用的数据模型 → 在 **ui/components 边界**用
 *    `androidx.compose.runtime.Immutable` 标记；典型如尺寸档位、配置枚举、不可变卡片模型。
 * 2. 字段可变但会正确通知 Compose（mutableStateOf / State）的模型 → 用
 *    `androidx.compose.runtime.Stable` 标记；不要在 `@Immutable` 类里藏可变集合。
 * 3. 可组合函数的lambda 参数若每次重组都新建，优先用 `remember` 包裹调用方，
 *    再在参数上标注 `@Stable` 表明该回调语义稳定，避免整棵子树参与重组。
 * 4. 禁止用 `@Stable` / `@Immutable` 掩盖真实的可变状态：标注前必须确认类的公开契约
 *    满足「同输入同输出」，否则比不标注更危险（错误跳过重组会导致界面不更新）。
 *
 * 实际标注落点见：`ui/components/PosterImage.kt`（档位枚举）、
 * `ui/components/TvFocusableSurface.kt`（焦点面骨架）、
 * `domain/Models.kt`（点播/直播/平台直播数据模型，S5-26）、
 * `domain/PlaybackUiState.kt` / `domain/UiStateSlices.kt` / `ui/UpdateUiState.kt` / `ui/SourceManagerUiState.kt`（UI 状态切片，S5-26）。
 * 新增核心组件请沿用同一规则。
 */
object StabilityAnnotations
