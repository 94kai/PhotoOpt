package com.xk.photoopt

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Removes a verified JPEG gain-map image while retaining the primary bitstream and trailing live-photo data. */
object HdrGainMap {
    data class Result(val data: ByteArray, val primaryEnd: Int, val removed: Boolean)
    private data class Segment(val tag: Int, val start: Int, val end: Int)
    private fun segments(data: ByteArray, start: Int, end: Int): List<Segment> {
        require(start + 2 <= end && data[start] == 0xff.toByte() && data[start + 1] == 0xd8.toByte()) { "JPEG 头无效" }
        val result = mutableListOf<Segment>(); var p = start + 2
        while (p + 2 <= end) {
            val begin = p
            require(data[p++].toInt() and 255 == 255) { "JPEG 标记无效" }
            while (p < end && data[p].toInt() and 255 == 255) p++
            require(p < end)
            val tag = data[p++].toInt() and 255
            if (tag == 218 || tag == 217) return result
            if (tag == 1 || tag in 208..215) continue
            require(p + 2 <= end)
            val length = ((data[p].toInt() and 255) shl 8) + (data[p + 1].toInt() and 255)
            require(length >= 2 && length <= end - p) { "JPEG 段长度无效" }
            result.add(Segment(tag, begin, p + length)); p += length
        }
        error("JPEG 扫描头缺失")
    }
    private fun text(data: ByteArray, segment: Segment) = data.copyOfRange(segment.start, segment.end).toString(Charsets.ISO_8859_1)
    private fun isGainMap(text: String) = text.contains("hdrgm:") || text.contains("HDRGainMap") || text.contains("hdr-gain-map") || text.contains("Semantic=\"GainMap\"")

    fun remove(data: ByteArray, primaryEnd: Int): Result {
        val headers = segments(data, 0, primaryEnd)
        val mpf = headers.firstOrNull { it.tag == 226 && text(data, it).contains("MPF\u0000") }
        if (mpf == null) {
            require(headers.none { it.tag == 225 && isGainMap(text(data, it)) }) { "未知 HDR 增益存储结构，保留原件" }
            return Result(data, primaryEnd, false)
        }
        val base = mpf.start + 8
        require(data.copyOfRange(mpf.start + 4, base).contentEquals(byteArrayOf(77, 80, 70, 0))) { "未知 MPF 头" }
        val order = when (data.copyOfRange(base, base + 2).toString(Charsets.US_ASCII)) {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> error("未知 MPF 字节序")
        }
        val buffer = ByteBuffer.wrap(data).order(order)
        val ifd = base + buffer.getInt(base + 4)
        require(ifd >= base && ifd + 2 <= mpf.end)
        var range: IntRange? = null
        repeat(buffer.getShort(ifd).toInt() and 65535) { index ->
            val t = ifd + 2 + index * 12
            require(t + 12 <= mpf.end)
            if (buffer.getShort(t).toInt() and 65535 == 0xb002) {
                require(buffer.getInt(t + 4) == 32) { "未知多画面结构，不能仅移除 HDR 增益" }
                val entries = base + buffer.getInt(t + 8)
                require(entries >= base && entries + 32 <= mpf.end)
                val start = base + buffer.getInt(entries + 24)
                val length = buffer.getInt(entries + 20)
                require(start == primaryEnd && length > 0 && length <= data.size - start) { "增益图范围无效" }
                val auxiliaryHeaders = segments(data, start, start + length)
                require(auxiliaryHeaders.any { it.tag == 225 && isGainMap(text(data, it)) }) { "附加图未确认为 HDR 增益图，保留原件" }
                range = start until start + length
            }
        }
        val gainMap = range ?: error("MPF 缺少图像索引")
        val removeHeaders = headers.filter { segment ->
            if (segment == mpf) true else if (segment.tag == 225 && isGainMap(text(data, segment))) {
                val metadata = text(data, segment)
                require(!metadata.contains("MotionPhoto") && !metadata.contains("MicroVideo")) { "HDR 与动态共用未知索引，暂不处理" }
                true
            } else false
        }
        val out = java.io.ByteArrayOutputStream()
        var cursor = 0
        removeHeaders.forEach { out.write(data, cursor, it.start - cursor); cursor = it.end }
        out.write(data, cursor, primaryEnd - cursor)
        val newEnd = out.size()
        out.write(data, gainMap.last + 1, data.size - gainMap.last - 1)
        return Result(out.toByteArray(), newEnd, true)
    }
    fun removeFromFile(file: File): Boolean {
        require(file.length() <= Runtime.getRuntime().maxMemory() / 6) { "文件过大，无法安全移除 HDR 增益" }
        val removed = remove(file.readBytes(), jpegLayout(file).end.toInt())
        if (!removed.removed) return false
        file.writeBytes(removed.data)
        VivoMedia.markCopiedJpeg(file, "$MARKER; hdrGainMapRemoved=true; primaryPixelsReencoded=false")
        check(jpegLayout(file).segments.none { it.toString(Charsets.ISO_8859_1).contains("MPF\u0000") }) { "HDR 索引未移除" }
        return true
    }
}
