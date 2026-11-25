package com.tvbox.app.domain

/**
 * T2-14：集号归一化的唯一入口。
 *
 * 源站集名是野生字符串（`第05集` / `E05` / `EP.5` / `05` / `第5话` / `05(国语)` / `【05】`）。
 * 若各页面各写一套正则，换源后续播会匹配不上、多源合并会重复出格子。
 * 这里把「集号」拆成正交的两维：[num] 是主键，[annot] 是版本维度（国语/粤语/未删减…），
 * 版本不同不应该互相顶掉进度。
 */
data class EpKey(
    val num: Int,
    val annot: EpAnnotation = EpAnnotation.NONE,
)

enum class EpAnnotation {
    NONE,
    MANDARIN,
    CANTONESE,
    UNCUT,
    DIRECTOR,
}

object EpisodeKey {
    private val NUMBER = Regex(
        """(?:第\s*|[Ee][Pp]?\.?\s*)?(\d{1,4})\s*(?:集|话|回|期|集全|完结)?""",
        RegexOption.IGNORE_CASE,
    )
    private val PAREN = Regex("[（(][^）)]*[）)]")
    // 方括号里往往就是集号本身（【05】），只能去掉括号符号，不能连内容一起删。
    private val BRACKET = Regex("[【】\\[\\]]")

    /** 解析失败返回 null：调用方必须显式展示原始集名 + 警示，绝不能猜。 */
    fun parse(raw: String): EpKey? {
        if (raw.isBlank()) return null
        val cleaned = PAREN.replace(raw, " ").let { BRACKET.replace(it, " ") }
        val match = NUMBER.find(cleaned) ?: return null
        val num = match.groupValues[1].toIntOrNull() ?: return null
        if (num <= 0) return null
        return EpKey(num = num, annot = annotationOf(raw))
    }

    /**
     * 续播匹配：同集号且标注不同（国语 ↔ 粤语）时**不视为同一集**，
     * 否则会把另一语种的进度顶掉。未标注可匹配任意标注（兼容老数据）。
     */
    fun matches(resume: EpKey, candidate: EpKey): Boolean =
        resume.num == candidate.num &&
            (resume.annot == EpAnnotation.NONE ||
                candidate.annot == EpAnnotation.NONE ||
                resume.annot == candidate.annot)

    private fun annotationOf(raw: String): EpAnnotation = when {
        raw.contains("未删减", ignoreCase = true) || raw.contains("未剪切") -> EpAnnotation.UNCUT
        raw.contains("导演剪辑", ignoreCase = true) || raw.contains("Director", ignoreCase = true) -> EpAnnotation.DIRECTOR
        raw.contains("粤语", ignoreCase = true) || raw.contains("粵語") || raw.contains("Cantonese", ignoreCase = true) -> EpAnnotation.CANTONESE
        raw.contains("国语", ignoreCase = true) || raw.contains("國語") || raw.contains("普通话") || raw.contains("Mandarin", ignoreCase = true) -> EpAnnotation.MANDARIN
        else -> EpAnnotation.NONE
    }
}
