package com.tvbox.app.data.live

import java.io.EOFException
import java.io.InputStream

/**
 * 逐条读取 HTTP-FLV 的上游标签。
 *
 * 会话中途被 CDN 掐断时，`readFully` 会抛 [EOFException]（含读到一半的标签）；
 * 调用方把它当作「本段正常结束」，交给续流逻辑，而不是当成硬失败。
 */
internal class FlvTagReader(private val input: InputStream) {

    /** 读 FLV 文件头（9 字节 + 首个 PreviousTagSize）。返回 False 表示上游不是 FLV。 */
    fun readFileHeader(): Boolean {
        val header = ByteArray(FlvTags.FILE_HEADER_SIZE)
        readFully(header)
        if (header[0] != 'F'.code.toByte() || header[1] != 'L'.code.toByte() || header[2] != 'V'.code.toByte()) {
            return false
        }
        // dataOffset 之后可能还有扩展字段，按声明值跳过。
        val dataOffset = ((header[5].toInt() and 0xff) shl 24) or
            ((header[6].toInt() and 0xff) shl 16) or
            ((header[7].toInt() and 0xff) shl 8) or
            (header[8].toInt() and 0xff)
        var skip = dataOffset.coerceAtLeast(FlvTags.FILE_HEADER_SIZE) - FlvTags.FILE_HEADER_SIZE
        skip += FlvTags.PREVIOUS_TAG_SIZE_LEN
        while (skip > 0) {
            val step = minOf(skip, 4096)
            readFully(ByteArray(step))
            skip -= step
        }
        return true
    }

    /** 下一条完整标签（11 字节头 + 标签体 + 4 字节 PreviousTagSize）；流尾返回 null。 */
    fun nextTag(): ByteArray? {
        val header = ByteArray(FlvTags.TAG_HEADER_SIZE)
        val read = input.read(header)
        if (read < 0) return null
        if (read < FlvTags.TAG_HEADER_SIZE) {
            readFully(header, read)
        }
        val size = FlvTags.dataSize(header)
        if (size <= 0 || size > FlvTags.MAX_TAG_SIZE) {
            throw MalformedTagException(size)
        }
        val tag = ByteArray(FlvTags.TAG_HEADER_SIZE + size + FlvTags.PREVIOUS_TAG_SIZE_LEN)
        System.arraycopy(header, 0, tag, 0, FlvTags.TAG_HEADER_SIZE)
        readFully(tag, FlvTags.TAG_HEADER_SIZE, size + FlvTags.PREVIOUS_TAG_SIZE_LEN)
        return tag
    }

    private fun readFully(target: ByteArray, offset: Int = 0, length: Int = target.size - offset) {
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val read = input.read(target, position, remaining)
            if (read < 0) throw EOFException("upstream closed")
            position += read
            remaining -= read
        }
    }
}

/** 标签长度非法（实测虎牙上游偶发 14~16MB 的伪长度）。 */
internal class MalformedTagException(val size: Int) : Exception("malformed flv tag size=$size")
