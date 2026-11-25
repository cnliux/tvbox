package com.tvbox.app.domain

/**
 * T5-36：Go 侧长生命周期循环的定时器/文件描述符门禁（静态启发式）。
 *
 * `for { select { case <-time.After(3s): } }` 每次循环堆一个 3s 定时器不回收；
 * `for _, x := range xs { defer f.Close() }` 把 fd 攒到函数返回。
 * 二者在弱盒上的症状都是「用一小时后全站失败」，且会与 T1-01 连接池症状撞脸。
 * 这里只做纯文本扫描，供 CI/单测直接判定，不修改 Go 行为。
 */
object GoTimerAudit {

    data class Finding(val line: Int, val kind: Kind, val snippet: String)
    enum class Kind { TimeAfterInLoop, DeferInLoop }

    /**
     * 逐行扫描，维护 `for` 的大括号深度：在 for 块内遇到 `time.After(` 或 `defer `
     * 即记一条。跨行 select 的 `case <-time.After` 也在同一层，能被覆盖。
     */
    fun scan(source: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        var loopDepth = -1
        var braceDepth = 0
        source.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            val opensFor = Regex("""\bfor\b[^@]*\{""").containsMatchIn(line)
            val before = braceDepth
            braceDepth += line.count { it == '{' } - line.count { it == '}' }
            if (opensFor && loopDepth < 0) loopDepth = before + 1
            if (loopDepth >= 0 && braceDepth < loopDepth) loopDepth = -1
            if (loopDepth < 0) return@forEachIndexed

            if (line.contains("time.After(")) {
                findings += Finding(index + 1, Kind.TimeAfterInLoop, line.take(120))
            } else if (Regex("""\bdefer\s+""").containsMatchIn(line)) {
                findings += Finding(index + 1, Kind.DeferInLoop, line.take(120))
            }
        }
        return findings
    }

    fun hasDefect(source: String): Boolean = scan(source).isNotEmpty()
}
