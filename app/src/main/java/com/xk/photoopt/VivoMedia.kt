package com.xk.photoopt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Narrow support for the separate JPEG/MP4 format verified on the user's vivo device. */
object VivoMedia {
    private val idPattern = Regex("\"com.android.camera.livephoto\"\\s*:\\s*\"([0-9a-fA-F]{28})\"")
    fun photoId(file: File): String? = runCatching {
        if (file.extension.lowercase() !in setOf("jpg", "jpeg")) return null
        RandomAccessFile(file, "r").use { r ->
            val data = ByteArray(minOf(65536L, r.length()).toInt())
            r.seek(r.length() - data.size); r.readFully(data)
            idPattern.find(data.toString(Charsets.ISO_8859_1))?.groupValues?.get(1)
        }
    }.getOrNull()

    fun videoBlock(file: File): ByteArray? = RandomAccessFile(file, "r").use { r ->
        while (r.filePointer + 8 <= r.length()) {
            val start = r.filePointer
            var size = r.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4); r.readFully(type)
            var header = 8L
            if (size == 1L) { size = r.readLong(); header = 16 }
            if (size == 0L) size = r.length() - start
            require(size >= header && size <= r.length() - start) { "视频结构无效" }
            if (String(type, Charsets.US_ASCII) == "uuid" && size <= 1024 * 1024) {
                r.seek(start)
                val data = ByteArray(size.toInt()); r.readFully(data)
                if (data.toString(Charsets.ISO_8859_1).contains("vivoMediaExtInfo")) return@use data
            }
            r.seek(start + size)
        }
        null
    }
    fun videoId(file: File): String? = runCatching { videoBlock(file)?.let { idPattern.find(it.toString(Charsets.ISO_8859_1))?.groupValues?.get(1) } }.getOrNull()
    fun newId() = System.currentTimeMillis().toString().takeLast(13).padStart(13, '0') + UUID.randomUUID().toString().replace("-", "").take(15)
    fun replaceId(data: ByteArray, old: String, new: String): ByteArray {
        require(old.length == new.length)
        val from = old.toByteArray(); val to = new.toByteArray(); var count = 0
        for (i in 0..data.size - from.size) {
            if (from.indices.all { data[i + it] == from[it] }) { to.copyInto(data, i); count++ }
        }
        require(count > 0) { "实况配对标识缺失" }
        return data
    }
    fun appendVideoMetadata(source: File, target: File, old: String, new: String) {
        val block = videoBlock(source) ?: error("vivo 视频关联信息缺失")
        target.appendBytes(replaceId(block, old, new))
        check(videoId(target) == new) { "视频配对标识校验失败" }
    }

    suspend fun compressPhoto(context: Context, entry: MediaEntry, target: File, quality: Quality, oldId: String, newId: String) = withContext(Dispatchers.IO) {
        val source = File(entry.source)
        require(source.length() <= 40L * 1024 * 1024) { "实况照片文件过大，暂不处理" }
        val ratio = if (quality.edge == 0) 1.0 else minOf(1.0, quality.edge.toDouble() / maxOf(entry.width, entry.height))
        val width = (entry.width * ratio).toInt().coerceAtLeast(1)
        val height = (entry.height * ratio).toInt().coerceAtLeast(1)
        var sample = 1
        while (entry.width / (sample * 2) >= width && entry.height / (sample * 2) >= height) sample *= 2
        require(entry.width.toLong() / sample * (entry.height / sample) <= minOf(40_000_000L, Runtime.getRuntime().maxMemory() / 12)) { "实况像素超出内存预算" }
        val encoded = File(context.cacheDir, "photoopt-${UUID.randomUUID()}.jpg")
        try {
            val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: error("实况照片解码失败")
            if (android.os.Build.VERSION.SDK_INT >= 34) bitmap.setGainmap(null)
            try {
                val scaled = if (bitmap.width != width || bitmap.height != height) Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap
                try { encoded.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, it)) } }
                finally { if (scaled !== bitmap) scaled.recycle() }
            }
            finally { bitmap.recycle() }
            coroutineContext.ensureActive()
            val original = source.readBytes()
            val layout = jpegLayout(source)
            val oldEnd = layout.end.toInt()
            val marker = "$MARKER; vivo-pair=B; profile=${quality.name}; maxEdge=${quality.edge}; hdrGainMapRemoved=true; originalBytes=${entry.size}".toByteArray()
            val prefix = java.io.ByteArrayOutputStream().apply {
                write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
                layout.segments.forEach { write(it) }
                write(byteArrayOf(0xff.toByte(), 0xfe.toByte(), ((marker.size + 2) shr 8).toByte(), (marker.size + 2).toByte()))
                write(marker)
            }.toByteArray()
            val primary = prefix + encoded.readBytes().let { it.copyOfRange(2, it.size) }
            fixMpf(original, primary, oldEnd)
            val stripped = HdrGainMap.remove(primary + original.copyOfRange(oldEnd, original.size), primary.size)
            val result = stripped.data
            replaceId(result, oldId, newId)
            val keptTail = result.copyOfRange(stripped.primaryEnd, result.size)
            target.writeBytes(result.copyOfRange(0, stripped.primaryEnd))
            ExifInterface(target).apply {
                setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
                setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
                setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
                setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
                saveAttributes()
            }
            target.appendBytes(keptTail)
            check(photoId(target) == newId) { "照片配对标识校验失败" }
            val afterLayout = jpegLayout(target)
            check(target.length() - afterLayout.end == keptTail.size.toLong()) { "JPEG 结构校验失败" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            check(bounds.outWidth == width && bounds.outHeight == height) { "实况输出尺寸校验失败" }
            val originalSdr = HdrGainMap.remove(original, oldEnd)
            val expectedTail = originalSdr.data.copyOfRange(originalSdr.primaryEnd, originalSdr.data.size)
            replaceId(expectedTail, oldId, newId)
            check(RandomAccessFile(target, "r").use { r -> r.seek(afterLayout.end); ByteArray(expectedTail.size).also { r.readFully(it) } }.contentEquals(expectedTail)) { "实况照片附加数据校验失败" }
            val before = ExifInterface(source); val after = ExifInterface(target)
            listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_ORIENTATION).forEach {
                check(before.getAttribute(it) == after.getAttribute(it)) { "实况照片元数据校验失败：$it" }
            }
        } finally { encoded.delete() }
    }

    fun markCopiedJpeg(file: File, marker: String) {
        require(file.length() <= Runtime.getRuntime().maxMemory() / 6) { "文件过大，暂无法安全写入复制标记" }
        val data = file.readBytes()
        val end = jpegLayout(file).end.toInt()
        val comment = marker.toByteArray()
        val segment = byteArrayOf(0xff.toByte(), 0xfe.toByte(), ((comment.size + 2) shr 8).toByte(), (comment.size + 2).toByte()) + comment
        val primary = data.copyOfRange(0, 2) + segment + data.copyOfRange(2, end)
        fixMpf(data, primary, end, maxImages = 64)
        file.writeBytes(primary + data.copyOfRange(end, data.size))
        check(jpegLayout(file).segments.any { it.toString(Charsets.UTF_8).contains(marker) }) { "原样照片标记校验失败" }
    }

    /** TIFF offsets in MPF are relative to its own APP2 header, not the JPEG file. */
    private fun mpfTiff(data: ByteArray): Int? {
        var p = 2
        while (p + 4 <= data.size) {
            require(data[p].toInt() and 255 == 255)
            val marker = data[p + 1].toInt() and 255
            if (marker == 218 || marker == 217) return null
            val size = ((data[p + 2].toInt() and 255) shl 8) + (data[p + 3].toInt() and 255)
            require(size >= 2 && p + 2 + size <= data.size)
            if (marker == 226 && size >= 6 && data.copyOfRange(p + 4, p + 8).contentEquals(byteArrayOf(77, 80, 70, 0))) return p + 8
            p += size + 2
        }
        return null
    }
    private fun fixMpf(original: ByteArray, primary: ByteArray, oldEnd: Int, maxImages: Int = 2) {
        val oldBase = mpfTiff(original) ?: return
        val base = mpfTiff(primary) ?: error("MPF 元数据缺失")
        val order = if (primary[base] == 73.toByte() && primary[base + 1] == 73.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer = ByteBuffer.wrap(primary).order(order)
        val ifd = base + buffer.getInt(base + 4)
        val count = buffer.getShort(ifd).toInt() and 65535
        var patched = false
        repeat(count) { i ->
            val tag = ifd + 2 + i * 12
            if (buffer.getShort(tag).toInt() and 65535 == 0xb002) {
                val length = buffer.getInt(tag + 4)
                require(length % 16 == 0 && length / 16 in 2..maxImages) { "未支持的 MPF 图像数量" }
                val entries = base + buffer.getInt(tag + 8)
                buffer.putInt(entries + 4, primary.size)
                for (image in 1 until length / 16) {
                    val record = entries + image * 16
                    val oldAbsolute = oldBase + buffer.getInt(record + 8)
                    val auxiliarySize = buffer.getInt(record + 4)
                    require(oldAbsolute >= oldEnd && auxiliarySize > 0 && oldAbsolute.toLong() + auxiliarySize <= original.size) { "MPF 附加图像范围无效" }
                    buffer.putInt(record + 8, primary.size + oldAbsolute - oldEnd - base)
                }
                patched = true
            }
        }
        require(patched) { "未知 MPF 结构" }
    }
}
