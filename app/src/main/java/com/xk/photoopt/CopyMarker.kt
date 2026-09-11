package com.xk.photoopt

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32

/** Metadata-only writes: encoded pixels, audio and video samples are not re-encoded. */
object CopyMarker {
    fun write(file: File, sourceExtension: String, reason: String) {
        val marker = "$MARKER; action=copy; reason=$reason"
        when (sourceExtension.lowercase()) {
            "jpg", "jpeg" -> VivoMedia.markCopiedJpeg(file, marker)
            "png" -> markPng(file, marker)
            "webp" -> ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_USER_COMMENT, listOf(getAttribute(ExifInterface.TAG_USER_COMMENT).orEmpty(), marker).filter { it.isNotBlank() }.joinToString("\n"))
                saveAttributes()
                check(ExifInterface(file).getAttribute(ExifInterface.TAG_USER_COMMENT)?.contains(marker) == true)
            }
            "mp4", "mov", "m4v" -> {
                // Top-level ISO meta box: appending leaves every existing sample offset unchanged.
                val key = "com.xk.photoopt.marker".toByteArray()
                val handler = box("hdlr", ByteArray(8) + "mdta".toByteArray() + ByteArray(13))
                val keys = box("keys", ByteArray(4) + int(1) + int(key.size + 8) + "mdta".toByteArray() + key)
                val item = boxBytes(int(1), box("data", int(1) + int(0) + marker.toByteArray()))
                file.appendBytes(box("meta", ByteArray(4) + handler + keys + box("ilst", item)))
                check(videoMarked(file)) { "原样视频标记校验失败" }
            }
            else -> error("此格式暂不支持写入复制标记")
        }
    }
    private fun int(n: Int) = ByteBuffer.allocate(4).putInt(n).array()
    private fun box(tag: String, data: ByteArray) = boxBytes(tag.toByteArray(Charsets.ISO_8859_1), data)
    private fun boxBytes(tag: ByteArray, data: ByteArray) = int(data.size + 8) + tag + data
    fun videoMarked(file: File): Boolean = RandomAccessFile(file, "r").use { r ->
        while (r.filePointer + 8 <= r.length()) {
            val start = r.filePointer
            var length = r.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4); r.readFully(type)
            var header = 8
            if (length == 1L) { length = r.readLong(); header = 16 }
            if (length == 0L) length = r.length() - start
            require(length >= header && start + length <= r.length())
            if (String(type) == "meta" && length < 1024 * 1024) {
                val data = ByteArray((length - header).toInt()); r.readFully(data)
                if (data.toString(Charsets.UTF_8).contains(MARKER)) return@use true
            }
            r.seek(start + length)
        }
        false
    }
    private fun markPng(file: File, marker: String) {
        val data = "PhotoOpt\u0000$marker".toByteArray()
        val type = "tEXt".toByteArray()
        val crc = CRC32().apply { update(type); update(data) }.value.toInt()
        val chunk = int(data.size) + type + data + int(crc)
        val offset = RandomAccessFile(file, "r").use { r ->
            r.seek(8)
            var found = -1L
            while (r.filePointer + 12 <= r.length()) {
                val start = r.filePointer; val length = r.readInt().toLong() and 0xffffffffL
                val tag = ByteArray(4); r.readFully(tag)
                require(start + length + 12 <= r.length())
                if (String(tag) == "IEND") { found = start; break }
                r.seek(start + length + 12)
            }
            require(found >= 0) { "PNG 结构无效" }; found
        }
        val temp = File(file.parentFile, "photoopt-${UUID.randomUUID()}.jpg")
        try {
            file.inputStream().use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(65536); var left = offset
                while (left > 0) { val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); check(n > 0); output.write(buffer, 0, n); left -= n }
                output.write(chunk); input.copyTo(output)
            } }
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}
