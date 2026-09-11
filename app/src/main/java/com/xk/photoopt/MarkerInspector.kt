package com.xk.photoopt

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile

/** Reads media metadata directly; task history, filenames and scan caches are not evidence of a marker. */
data class MarkerFinding(val path: String, val state: String, val markers: List<String> = emptyList(), val note: String = "") {
    val action: String get() = markers.joinToString(" ").let {
        when {
            it.contains("action=copy") -> "原样复制"
            it.contains("forcedStatic=true") -> "强制转普通图片"
            it.contains("motionRemoved=true") -> "实况转静态照片"
            it.contains("primaryOnly=true") -> "仅主图"
            it.contains("vivo-pair=B") || it.contains("experimental=vivo-pair") -> "vivo 实况处理"
            it.contains("profile=") -> "压缩"
            markers.isNotEmpty() -> "已处理"
            else -> ""
        }
    }
}

object MarkerInspector {
    private const val MAX_METADATA = 32L * 1024 * 1024
    private fun extract(text: String, location: String): List<String> = Regex("PhotoOpt:v[0-9]+[^\\u0000-\\u001f<>\"]{0,700}")
        .findAll(text).map { "$location：${it.value.trim()}" }.toList()

    fun inspect(file: File): MarkerFinding {
        val markers = mutableListOf<String>()
        val errors = mutableListOf<String>()
        fun read(location: String, block: () -> Unit) {
            runCatching(block).onFailure { errors.add("$location：${it.message ?: "读取失败"}") }
        }
        if (!file.isFile || !file.canRead()) return MarkerFinding(file.path, "读取失败", note = "文件不存在或无法读取")
        when (file.extension.lowercase()) {
            "jpg", "jpeg", "webp", "heic", "heif", "dng" -> {
                read("EXIF") { ExifInterface(file).let { exif ->
                    markers += extract(exif.getAttribute(ExifInterface.TAG_USER_COMMENT).orEmpty(), "EXIF UserComment")
                    markers += extract(exif.getAttribute(ExifInterface.TAG_XMP).orEmpty(), "XMP")
                } }
                if (file.extension.lowercase() in setOf("jpg", "jpeg")) read("JPEG 元数据") {
                    RandomAccessFile(file, "r").use { r ->
                        require(r.readUnsignedShort() == 0xffd8) { "不是 JPEG" }
                        while (r.filePointer + 2 <= r.length()) {
                            require(r.readUnsignedByte() == 255) { "JPEG 标记损坏" }
                            var tag = r.readUnsignedByte()
                            while (tag == 255) tag = r.readUnsignedByte()
                            if (tag == 218 || tag == 217) break
                            if (tag == 1 || tag in 208..215) continue
                            val length = r.readUnsignedShort() - 2
                            require(length >= 0 && length <= r.length() - r.filePointer) { "JPEG 段长度无效" }
                            if (tag == 254 || tag in 225..239) {
                                val data = ByteArray(length); r.readFully(data)
                                markers += extract(data.toString(Charsets.UTF_8), if (tag == 254) "JPEG Comment" else "JPEG APP${tag - 224}")
                            } else r.seek(r.filePointer + length)
                        }
                    }
                }
            }
            "png" -> read("PNG 元数据") {
                RandomAccessFile(file, "r").use { r ->
                    require(r.readLong() == 0x89504e470d0a1a0aUL.toLong()) { "不是 PNG" }
                    while (r.filePointer + 12 <= r.length()) {
                        val length = r.readInt().toLong() and 0xffffffffL
                        val tag = ByteArray(4); r.readFully(tag)
                        val name = tag.toString(Charsets.US_ASCII)
                        require(length + 4 <= r.length() - r.filePointer) { "PNG 段长度无效" }
                        if (name in setOf("tEXt", "iTXt", "eXIf")) {
                            require(length <= MAX_METADATA) { "元数据过大" }
                            val data = ByteArray(length.toInt()); r.readFully(data)
                            markers += extract(data.toString(Charsets.UTF_8), "PNG $name")
                            r.skipBytes(4)
                        } else r.seek(r.filePointer + length + 4)
                        if (name == "IEND") break
                    }
                }
            }
            "mp4", "mov", "m4v" -> read("视频元数据") {
                RandomAccessFile(file, "r").use { r ->
                    while (r.filePointer + 8 <= r.length()) {
                        val start = r.filePointer
                        var length = r.readInt().toLong() and 0xffffffffL
                        val type = ByteArray(4); r.readFully(type)
                        var header = 8
                        if (length == 1L) { length = r.readLong(); header = 16 }
                        if (length == 0L) length = r.length() - start
                        require(length >= header && length <= r.length() - start) { "视频容器结构无效" }
                        val name = type.toString(Charsets.US_ASCII)
                        if (name in setOf("moov", "meta", "uuid")) {
                            require(length - header <= MAX_METADATA) { "视频元数据过大" }
                            val data = ByteArray((length - header).toInt()); r.readFully(data)
                            markers += extract(data.toString(Charsets.UTF_8), "MP4 $name")
                        }
                        r.seek(start + length)
                    }
                }
            }
            else -> return MarkerFinding(file.path, "暂不支持", note = "暂不支持读取此格式的标记，不能据此判定未处理")
        }
        return MarkerFinding(file.path, when {
            markers.isNotEmpty() -> "有标记"
            errors.isNotEmpty() -> "读取失败"
            else -> "未发现标记"
        }, markers.distinct(), errors.joinToString("\n"))
    }
}
