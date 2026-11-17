package com.tvbox.app.data.live

import java.util.ArrayDeque

/**
 * FLV 标签的纯解析/改写工具。
 *
 * 只做字节层面的判定，不碰 IO，便于单元测试。字段语义遵循 FLV 规范与
 * Enhanced-FLV（E-RTMP）扩展：
 *  - 基础标签头 11 字节 + 标签体 + 4 字节 PreviousTagSize；
 *  - 视频标签体首字节高 4 位是 frameType（1 = 关键帧），低 4 位是 codecId（7 = AVC）；
 *  - codecId 为 7/12 时，第 2 字节是 AVCPacketType（0 = 序列头/config）；
 *  - 首字节最高位为 1 表示 Enhanced-FLV：第 2~5 字节是 FourCC（hvc1/av01…），
 *    低 4 位是 packetType（0 = 序列头）。
 */
object FlvTags {
    const val TYPE_AUDIO = 8
    const val TYPE_VIDEO = 9
    const val TYPE_SCRIPT = 18

    const val TAG_HEADER_SIZE = 11
    const val PREVIOUS_TAG_SIZE_LEN = 4
    const val FILE_HEADER_SIZE = 9

    /** 单个标签的硬上限，防止上游坏数据把内存吃光（正常几十 KB）。 */
    const val MAX_TAG_SIZE = 8 shl 20

    fun type(tag: ByteArray, offset: Int = 0): Int = tag[offset].toInt() and 0x1f

    fun dataSize(tag: ByteArray, offset: Int = 0): Int =
        ((tag[offset + 1].toInt() and 0xff) shl 16) or
            ((tag[offset + 2].toInt() and 0xff) shl 8) or
            (tag[offset + 3].toInt() and 0xff)

    fun timestamp(tag: ByteArray, offset: Int = 0): Long {
        val low = ((tag[offset + 4].toInt() and 0xff) shl 16) or
            ((tag[offset + 5].toInt() and 0xff) shl 8) or
            (tag[offset + 6].toInt() and 0xff)
        val high = tag[offset + 7].toInt() and 0xff
        return (high.toLong() shl 24) or low.toLong()
    }

    /** 标签体（不含 11 字节头与尾部 4 字节）。 */
    fun body(tag: ByteArray, offset: Int = 0): ByteArray {
        val size = dataSize(tag, offset)
        val start = offset + TAG_HEADER_SIZE
        return tag.copyOfRange(start, minOf(start + size, tag.size))
    }

    fun isVideoConfig(tag: ByteArray, offset: Int = 0): Boolean {
        if (type(tag, offset) != TYPE_VIDEO) return false
        val bodySize = dataSize(tag, offset)
        if (bodySize < 2) return false
        val b0 = tag[offset + TAG_HEADER_SIZE].toInt() and 0xff
        return if (b0 and 0x80 != 0) {
            // Enhanced-FLV：packetType 0 表示序列头（需有 FourCC）。
            bodySize >= 5 && (b0 and 0x0f) == 0
        } else {
            val codec = b0 and 0x0f
            (codec == 7 || codec == 12) && (tag[offset + TAG_HEADER_SIZE + 1].toInt() and 0xff) == 0
        }
    }

    fun isAudioConfig(tag: ByteArray, offset: Int = 0): Boolean {
        if (type(tag, offset) != TYPE_AUDIO) return false
        val bodySize = dataSize(tag, offset)
        if (bodySize < 2) return false
        val b0 = tag[offset + TAG_HEADER_SIZE].toInt() and 0xff
        // soundFormat 10 = AAC，后继字节为 AACPacketType（0 = sequence header）。
        return (b0 ushr 4) == 10 && (tag[offset + TAG_HEADER_SIZE + 1].toInt() and 0xff) == 0
    }

    fun isKeyframe(tag: ByteArray, offset: Int = 0): Boolean {
        if (type(tag, offset) != TYPE_VIDEO) return false
        if (dataSize(tag, offset) < 1) return false
        val b0 = tag[offset + TAG_HEADER_SIZE].toInt() and 0xff
        return if (b0 and 0x80 != 0) {
            // Enhanced-FLV 的 coded-frames：frameType 仍在 bit6~4（1 = keyframe）。
            ((b0 ushr 4) and 0x07) == 1
        } else {
            ((b0 ushr 4) and 0x0f) == 1
        }
    }

    /** 时间戳不同的同一标签（只改头，不动标签体）。 */
    fun withTimestamp(tag: ByteArray, ts: Long, offset: Int = 0): ByteArray {
        val out = tag.copyOf()
        val value = ts.coerceAtLeast(0)
        out[offset + 4] = ((value ushr 16) and 0xff).toByte()
        out[offset + 5] = ((value ushr 8) and 0xff).toByte()
        out[offset + 6] = (value and 0xff).toByte()
        out[offset + 7] = ((value ushr 24) and 0xff).toByte()
        return out
    }

    /** FNV-1a 内容指纹：用于识别重连后 CDN 回放的旧帧。 */
    fun fingerprint(tag: ByteArray, offset: Int = 0): Long {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        hash = hash xor type(tag, offset).toLong()
        hash *= 0x100000001b3L
        val start = offset + TAG_HEADER_SIZE
        val end = minOf(start + dataSize(tag, offset), tag.size)
        for (i in start until end) {
            hash = hash xor (tag[i].toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash
    }
}

/** 一条待写出的标签（输出时间轴）。 */
class OutTag(val type: Int, val timestampMs: Long, val bytes: ByteArray)

/**
 * 连续 FLV 的续接状态机（纯逻辑，参考 pure_live 的 `FlvSplicer` 与 livepurer 的单流下游）。
 *
 * 职责：把多条上游会话的标签缝成一条**时间戳严格单调**的下游流，并处理三件事：
 *  1. **回放去重**：CDN 重连后常先回吐数秒旧缓冲。因为输出时间戳被强制顺延，
 *     靠时间戳看不出回放，只能比对帧内容指纹；
 *  2. **时间轴平移**：新会话时间戳归零时整体平移，避免播放器倒退或跳变；
 *  3. **等关键帧黑窗**：换段后到首个关键帧之前的音视频一起丢弃，
 *     只丢视频会让声音先跑，造成音画不同步。
 *
 * 音轨刻意**不做指纹去重**：AAC 静音帧内容完全相同，按指纹丢会误删正常音频，
 * 反而造成声音断续；音频只做单轨时间戳单调约束（必须严格递增）。
 */
class FlvSplicer(
    private val replayWindowMs: Long = 12_000,
    private val replayGuardMaxFrames: Int = 7_200,
    private val spliceStepMs: Long = 33,
    private val alignmentWindowMs: Long = 60_000,
    /** 允许新段首帧比已交付位置略微回退（两轨交错/抖动），超过则视为另一条时间轴。 */
    private val alignmentBackToleranceMs: Long = 1_000,
) {
    private class Seen(val fingerprint: Long, val outTs: Long)

    private val seen = ArrayDeque<Seen>()
    private var segments = 0
    private var pendingAlignment = true

    var lastVideoTs: Long? = null
        private set
    var lastAudioTs: Long? = null
        private set

    /** 当前段的输出时间偏移（毫秒）。 */
    var offset: Long = 0
        private set

    /** 是否处于「等新段关键帧」阶段。 */
    var awaitingKeyframe: Boolean = false
        private set

    var switches: Int = 0
        private set

    /** 已发过的序列头**标签体**（不含标签头，便于忽略时间戳比较）。 */
    private var videoConfig: ByteArray? = null
    private var audioConfig: ByteArray? = null

    /** 开始一段新的上游会话：下一帧视频决定时间轴是否需要对平移。 */
    fun startSegment() {
        segments++
        pendingAlignment = true
        awaitingKeyframe = true
        if (segments > 1) switches++
    }

    /**
     * 处理一条上游标签，返回需要写出的标签（0~2 条）。
     * 返回空表示这条标签被丢弃（回放帧 / 时间戳倒退 / 等关键帧阶段）。
     */
    fun accept(tag: ByteArray): List<OutTag> {
        val type = FlvTags.type(tag)
        if (type == FlvTags.TYPE_SCRIPT) {
            // 只在首段转发 onMetaData；后续段重复发会让部分播放器重置解析器。
            return if (segments <= 1) listOf(OutTag(type, FlvTags.timestamp(tag), tag)) else emptyList()
        }
        if (type != FlvTags.TYPE_VIDEO && type != FlvTags.TYPE_AUDIO) return emptyList()

        val rawTs = FlvTags.timestamp(tag)

        if (FlvTags.isVideoConfig(tag)) {
            alignIfPending(rawTs)
            // 只比较标签体：同一份 config 在不同段的标签头时间戳不同，
            // 按整条标签比较会误判为「内容变了」而每段重发，导致解码器反复重建。
            val body = FlvTags.body(tag)
            val previous = videoConfig
            videoConfig = body
            if (previous != null && previous.contentEquals(body)) return emptyList()
            val ts = (rawTs + offset).coerceAtLeast(0)
            return listOf(OutTag(type, ts, FlvTags.withTimestamp(tag, ts)))
        }
        if (FlvTags.isAudioConfig(tag)) {
            // 等关键帧阶段不写音频序列头，否则播放器会先按音频时间轴走。
            if (awaitingKeyframe) return emptyList()
            val body = FlvTags.body(tag)
            val previous = audioConfig
            audioConfig = body
            if (previous != null && previous.contentEquals(body)) return emptyList()
            val ts = (rawTs + offset).coerceAtLeast(0)
            return listOf(OutTag(type, ts, FlvTags.withTimestamp(tag, ts)))
        }

        alignIfPending(rawTs)

        if (awaitingKeyframe) {
            if (type == FlvTags.TYPE_VIDEO && FlvTags.isKeyframe(tag)) {
                awaitingKeyframe = false
            } else {
                // 关键帧之前到达的音视频一律丢弃：只放视频会让音频领先。
                return emptyList()
            }
        }

        val outTs = rawTs + offset

        if (type == FlvTags.TYPE_VIDEO) {
            val last = lastVideoTs
            if (last != null && outTs < last) return emptyList()
            val fp = FlvTags.fingerprint(tag)
            if (isReplay(fp)) return emptyList()
            remember(fp, outTs)
            lastVideoTs = outTs
            return listOf(OutTag(type, outTs, FlvTags.withTimestamp(tag, outTs)))
        }

        val last = lastAudioTs
        if (last != null && outTs <= last) return emptyList()
        lastAudioTs = outTs
        return listOf(OutTag(type, outTs, FlvTags.withTimestamp(tag, outTs)))
    }

    /** 首段 offset=0；后续段按与已交付位置的距离判断是同轴还是归零重启。 */
    private fun alignIfPending(rawTs: Long) {
        if (!pendingAlignment) return
        pendingAlignment = false
        if (segments <= 1) {
            offset = 0
            return
        }
        val last = lastVideoTs
        val alignedOffset = offset
        // 同轴：与已交付位置接近**且没有明显倒退**。
        // 只按距离判断会误判「时间戳归零重启」为同轴（若已交付位置本身很小），
        // 随后整段被单调性检查逐帧丢弃，表现为换段后长时间黑屏。
        val candidate = rawTs + alignedOffset
        val aligned = last == null ||
            (kotlin.math.abs(candidate - last) <= alignmentWindowMs && candidate >= last - alignmentBackToleranceMs)
        offset = if (aligned) {
            alignedOffset
        } else if (last == null) {
            -rawTs
        } else {
            last + spliceStepMs - rawTs
        }
    }

    private fun isReplay(fingerprint: Long): Boolean {
        for (item in seen) if (item.fingerprint == fingerprint) return true
        return false
    }

    private fun remember(fingerprint: Long, outTs: Long) {
        seen.addLast(Seen(fingerprint, outTs))
        // 双重上限：时间窗（覆盖 CDN 最长回放）与帧数硬顶（防异常流不退出）。
        while (seen.isNotEmpty() && outTs - seen.first.outTs > replayWindowMs) seen.removeFirst()
        while (seen.size > replayGuardMaxFrames) seen.removeFirst()
    }
}
