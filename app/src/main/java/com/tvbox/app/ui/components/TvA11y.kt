package com.tvbox.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * S6-32：全站统一的 TalkBack 语义文案与 modifier 助手。
 *
 * 背景：核实报告显示 `contentDescription` 约 95 处、可聚焦控件约 300 处；
 * 大量遥控器按钮/选项 chips 只有 Text 标签或只有选中态，读屏时只念「按钮」。
 * 这里把中文语义拼接规则集中到一处，纯字符串函数不依赖 Compose，可由 JVM 单测覆盖。
 */
object TvA11y {

    /** 拼接非空片段：中文逗号分隔，自动 trim，避免尾随标点。 */
    fun label(vararg parts: String?): String = parts
        .mapNotNull { it?.takeIf(String::isNotBlank)?.trim() }
        .joinToString("，")

    /** 选项类控件：状态并进标签，读屏一次读完「选项名 + 是否已选」。 */
    fun choice(text: String, selected: Boolean): String =
        label(text, if (selected) "已选中" else "未选中")

    /** 动作类控件：动作名后置，符合中文口语顺序，如「搜索，打开」。 */
    fun action(text: String, action: String): String = label(text, action)

    /** 剧集上下文：第 N 集 / 共 M 集（总数 <= 0 时不播报总数）。 */
    fun episode(index: Int, total: Int, title: String): String = buildString {
        append("第 ").append(index.coerceAtLeast(1)).append(" 集")
        if (total > 0) append("，共 ").append(total).append(" 集")
        if (title.isNotBlank()) append("，").append(title.trim())
    }

    /** 播放进度：续看卡与播放器共用，百分比收敛到 0..100。 */
    fun progress(percent: Int): String = "已观看 ${percent.coerceIn(0, 100)}%"

    /**
     * 开关类控件：名称 + 当前开关状态。
     * 手机设置页大量 `Switch` 只有旁边一行标题 Text，读屏单独聚焦开关时只念「开关」，
     * 这里把标题并进标签，一次读完「自动下一集，已开启」。
     */
    fun toggle(text: String, checked: Boolean): String =
        label(text, if (checked) "已开启" else "已关闭")

    /**
     * 输入框：只播报输入用途（不含内容），内容由可编辑文本自身播报，
     * 避免读屏把提示语和已输入内容重复念两遍。
     */
    fun input(text: String): String = label(text)

    /** 清除类图标按钮：作用对象 + 动作，如「搜索内容，清除」，避免只念「按钮」。 */
    fun clear(target: String): String = label(target, "清除")
}

/**
 * 给可点击/可聚焦控件统一挂语义标签与角色。
 * 调用方应传入 [TvA11y] 生成的中文标签，禁止直接传空串或仅英文技术名。
 */
fun Modifier.tvSemantics(label: String, semanticRole: Role? = null): Modifier = semantics {
    contentDescription = label
    if (semanticRole != null) role = semanticRole
}
