@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.xk.photoopt

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

class VideoProcessor(private val context: Context) {
    suspend fun compress(entry: MediaEntry, output: File, quality: Quality, preserveDimensions: Boolean = false) {
        val source = File(entry.source)
        val timestamp = readMovieTimestamp(source) ?: error("无法读取视频容器时间，暂不转码")
        var location: String? = null
        var date: String? = null
        var rotation = 0
        MediaMetadataRetriever().use { r ->
            r.setDataSource(source.path)
            location = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: readVideoLocation(source)
            date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        }
        val gps = location?.let { Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(it) }
        require(location == null || gps != null) { "视频位置格式无法安全保留" }
        val isRotated = rotation == 90 || rotation == 270
        val displayW = if (isRotated) entry.height else entry.width
        val displayH = if (isRotated) entry.width else entry.height
        val limit = if (preserveDimensions || quality == Quality.ORIGINAL) max(displayW, displayH) else quality.videoEdge
        val scale = minOf(1.0, limit.toDouble() / max(displayW, displayH))
        val height = ((displayH * scale).toInt() / 2 * 2).coerceAtLeast(2)
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val muxer = InAppMuxer.Factory.Builder().setMetadataProvider { metadata ->
                    // Transformer collects supported source metadata. Replace only the values we explicitly preserve.
                    metadata.removeAll { it is Mp4TimestampData }
                    metadata.add(Mp4TimestampData(timestamp.first, timestamp.second))
                    if (gps != null) {
                        metadata.removeAll { it is Mp4LocationData }
                        metadata.add(Mp4LocationData(gps.groupValues[1].toFloat(), gps.groupValues[2].toFloat()))
                    }
                    metadata.add(MdtaMetadataEntry("com.xk.photoopt.marker", "$MARKER; profile=${quality.name}".toByteArray(), MdtaMetadataEntry.TYPE_INDICATOR_STRING))
                    date?.let { metadata.add(MdtaMetadataEntry("com.xk.photoopt.original-date", it.toByteArray(), MdtaMetadataEntry.TYPE_INDICATOR_STRING)) }
                    location?.let { metadata.add(MdtaMetadataEntry("com.xk.photoopt.original-location", it.toByteArray(), MdtaMetadataEntry.TYPE_INDICATOR_STRING)) }
                }.build()
                val encoder = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(quality.bitrate).build())
                    .setEnableFallback(true).build()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoder)
                    .setMuxerFactory(muxer)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }).build()
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
                try {
                    // A presentation effect forces a video encode even when the codec and dimensions match.
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(height))))
                        .build()
                    transformer.start(edited, output.path)
                } catch (e: Exception) {
                    transformer.cancel()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        }
        check(containsVideoMarker(output)) { "视频标记校验失败" }
        check(readMovieTimestamp(output) == timestamp) { "视频创建时间校验失败" }
        MediaMetadataRetriever().use { r ->
            r.setDataSource(output.path)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            check(duration > 0 && abs(duration - entry.durationMs) <= 500) { "视频时长校验失败" }
            if (gps != null) {
                val actual = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: readVideoLocation(output)).orEmpty()
                val parts = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(actual)
                check(parts != null && (1..2).all { abs(parts.groupValues[it].toDouble() - gps.groupValues[it].toDouble()) < 0.0001 }) { "视频位置校验失败" }
            }
        }
    }
}

/** Inspect only movie metadata, never load an entire video into memory. */
fun movieMetadata(file: File): ByteArray = RandomAccessFile(file, "r").use { r ->
    while (r.filePointer + 8 <= r.length()) {
        val start = r.filePointer
        var size = r.readInt().toLong() and 0xffffffffL
        val type = ByteArray(4); r.readFully(type)
        var header = 8L
        if (size == 1L) { size = r.readLong(); header = 16 }
        if (size == 0L) size = r.length() - start
        check(size >= header && size <= r.length() - start) { "视频容器结构无效" }
        if (String(type, Charsets.US_ASCII) == "moov") {
            check(size - header <= 32 * 1024 * 1024) { "视频元数据过大" }
            return@use ByteArray((size - header).toInt()).also { r.readFully(it) }
        }
        r.seek(start + size)
    }
    byteArrayOf()
}

fun containsVideoMarker(file: File): Boolean = movieMetadata(file).toString(Charsets.ISO_8859_1).contains(MARKER)

fun readMovieTimestamp(file: File): Pair<Long, Long>? {
    val data = movieMetadata(file)
    val b = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN)
    while (b.remaining() >= 8) {
        val start = b.position()
        val size = b.int.toLong() and 0xffffffffL
        val type = ByteArray(4); b.get(type)
        if (size < 8 || size > data.size - start) return null
        if (String(type, Charsets.US_ASCII) == "mvhd") {
            if (size < 20) return null
            val version = b.get().toInt(); b.position(b.position() + 3)
            return when (version) {
                0 -> (b.int.toLong() and 0xffffffffL) to (b.int.toLong() and 0xffffffffL)
                1 -> if (size >= 28) b.long to b.long else null
                else -> null
            }
        }
        b.position(start + size.toInt())
    }
    return null
}

/** Android's retriever misses the 3GPP `loci` atom emitted by some MP4 writers. */
fun readVideoLocation(file: File): String? = readVideoLocation(movieMetadata(file))

fun readVideoLocation(data: ByteArray): String? {
    fun parse(start: Int, end: Int): String? {
        var position = start
        while (position + 8 <= end) {
            val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN)
            buffer.position(position)
            val size = buffer.int.toLong() and 0xffffffffL
            val type = ByteArray(4); buffer.get(type)
            if (size < 8 || size > end - position) break
            val atomEnd = position + size.toInt()
            when (String(type, Charsets.ISO_8859_1)) {
                "udta" -> parse(position + 8, atomEnd)?.let { return it }
                "©xyz" -> {
                    if (size > 12) {
                        val value = String(data, position + 12, size.toInt() - 12, Charsets.UTF_8).trimEnd('\u0000')
                        if (Regex("[+-]\\d+(?:\\.\\d+)?[+-]\\d+(?:\\.\\d+)?(?:[+-]\\d+(?:\\.\\d+)?)?/").matches(value)) return value
                    }
                }
                "loci" -> {
                    if (size >= 27) {
                        buffer.position(position + 14) // full-box header and language
                        while (buffer.position() < atomEnd && buffer.get().toInt() != 0) { }
                        if (buffer.position() + 13 <= atomEnd) {
                            buffer.get() // role
                            val longitude = buffer.int / 65536.0
                            val latitude = buffer.int / 65536.0
                            val altitude = buffer.int / 65536.0
                            if (latitude in -90.0..90.0 && longitude in -180.0..180.0)
                                return String.format(java.util.Locale.US, "%+.6f%+.6f%+.2f/", latitude, longitude, altitude)
                        }
                    }
                }
            }
            position = atomEnd
        }
        return null
    }
    return parse(0, data.size)
}

/** Read standard mdta values, including text written by remuxers that Android does not expose. */
fun readCaptureFrameRate(data: ByteArray): Double? = runCatching {
    val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN)
    data class Atom(val body: Int, val end: Int, val type: Int)
    fun atoms(start: Int, end: Int): List<Atom> {
        val result = mutableListOf<Atom>()
        var p = start
        while (p + 8 <= end) {
            val size = buffer.getInt(p).toLong() and 0xffffffffL
            require(size >= 8 && size <= end - p) { "Invalid metadata atom" }
            result.add(Atom(p + 8, p + size.toInt(), buffer.getInt(p + 4)))
            p += size.toInt()
        }
        return result
    }
    fun type(value: String) = java.nio.ByteBuffer.wrap(value.toByteArray(Charsets.US_ASCII)).int
    fun readMeta(meta: Atom): Double? {
        val children = atoms(meta.body + 4, meta.end)
        val keys = children.firstOrNull { it.type == type("keys") } ?: return null
        if (keys.end - keys.body < 8) return null
        val entries = atoms(keys.body + 8, keys.end)
        if (buffer.getInt(keys.body + 4) != entries.size) return null
        val index = entries.indexOfFirst { it.type == type("mdta") &&
            String(data, it.body, it.end - it.body, Charsets.UTF_8) == "com.android.capture.fps" }
        if (index < 0) return null
        val list = children.firstOrNull { it.type == type("ilst") } ?: return null
        val item = atoms(list.body, list.end).firstOrNull { it.type == index + 1 } ?: return null
        val value = atoms(item.body, item.end).firstOrNull { it.type == type("data") } ?: return null
        if (value.end - value.body < 8) return null
        val start = value.body + 8
        val length = value.end - start
        return when (buffer.getInt(value.body) and 0xffffff) {
            1 -> String(data, start, length, Charsets.UTF_8).trim().toDoubleOrNull()
            23 -> if (length == 4) buffer.getFloat(start).toDouble() else null
            24 -> if (length == 8) buffer.getDouble(start) else null
            else -> null
        }
    }
    fun visit(start: Int, end: Int): Double? {
        for (atom in atoms(start, end)) {
            when (atom.type) {
                type("meta") -> readMeta(atom)?.let { return it }
                type("udta") -> visit(atom.body, atom.end)?.let { return it }
            }
        }
        return null
    }
    visit(0, data.size)
}.getOrNull()
