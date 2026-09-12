package com.xk.photoopt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Local files only. All writes go to a separate sibling directory; originals are never opened for writing. */
class MediaEngine(private val context: Context) {
    private val siblingNames = ConcurrentHashMap<String, Set<String>>()
    private val imageExtensions = MediaFormats.images
    private val videoExtensions = MediaFormats.videos

    suspend fun scan(roots: List<String>, singles: List<String>, prefix: String,
                     onProgress: (Int, Int, Int) -> Unit): List<MediaEntry> = withContext(Dispatchers.IO) {
        InspectionCache(context).use { cache ->
            val reused = AtomicInteger(0)
            val found = linkedMapOf<String, Pair<File, File>>()
            val sortedRoots = roots.map { File(it).canonicalFile }.sortedBy { it.path.length }
            val outputs = sortedRoots.map { File(it.parentFile, prefix + it.name).canonicalFile }
            fun inside(file: File, parent: File) = file == parent || file.path.startsWith(parent.path + File.separator)
            suspend fun visit(directory: File, root: File) {
                coroutineContext.ensureActive()
                check(directory.canRead()) { "无法读取目录：${directory.path}" }
                val children = directory.listFiles() ?: error("无法列出目录：${directory.path}")
                siblingNames[directory.path] = children.filter { it.isFile }.map { it.name.lowercase() }.toSet()
                for (file in children.sortedBy { it.name }) {
                    coroutineContext.ensureActive()
                    if (Files.isSymbolicLink(file.toPath())) continue
                    if (file.isDirectory) {
                        if (file.name.startsWith(".") || file.name.startsWith(prefix) || outputs.any { inside(file, it) }) continue
                        visit(file, root)
                    } else if (file.extension.lowercase() in imageExtensions + videoExtensions) {
                        found.putIfAbsent(file.canonicalPath, file to root)
                        if (found.size % 30 == 0) onProgress(found.size, 0, 0)
                    }
                }
            }
            sortedRoots.forEach { root ->
                check(!outputs.any { inside(root, it) }) { "来源目录不能位于输出目录中" }
                if (sortedRoots.none { it != root && inside(root, it) }) visit(root, root)
            }
            singles.forEach { path ->
                val file = File(path).canonicalFile
                if (file.isFile) found.putIfAbsent(file.path, file to (sortedRoots.firstOrNull { inside(file, it) } ?: file.parentFile!!))
            }
            val incomplete = VivoPairProcessor.incompleteOutputs(context)
            val candidates = found.values.groupBy { (file, _) -> file.parent to file.nameWithoutExtension.lowercase() }
                .values.filter { group -> group.size == 2 && group.count { it.first.extension.lowercase() in setOf("jpg", "jpeg") } == 1 && group.count { it.first.extension.lowercase() == "mp4" } == 1 }
            val pairs = candidates.flatMap { group -> group.map { it.first.path to group } }.toMap()
            val pairResults = ConcurrentHashMap<String, List<MediaEntry>>()
            val files = found.values.toList()
            val results = arrayOfNulls<MediaEntry>(files.size)
            val next = AtomicInteger(0)
            val completed = AtomicInteger(0)
            onProgress(0, files.size, 0)
            coroutineScope {
                repeat(minOf(4, files.size)) {
                    launch {
                        while (true) {
                            ensureActive()
                            val index = next.getAndIncrement()
                            if (index >= files.size) break
                            val (file, root) = files[index]
                            var basic = MediaEntry(file.path, root.path, file.relativeTo(root).path,
                                file.length(), file.lastModified(), kind = if (file.extension.lowercase() in videoExtensions) "视频" else "图片",
                                metadataRead = false)
                            results[index] = try {
                                if (file.path in pairs) {
                                    val group = pairs.getValue(file.path)
                                    pairResults.computeIfAbsent(group.first().first.path) { inspectVivoPair(group, prefix, incomplete, cache) }
                                        .first { it.source == file.path }
                                } else if (listOf(basic.destination(prefix), basic.copy(copyOriginal = true).destination(prefix)).any { Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) })
                                    basic.copy(reason = "同名输出已存在，直接跳过，不读取原件内容", outputExists = true)
                                else {
                                    val actual = MediaHeader.imageFormat(file)
                                    basic = basic.copy(sourceFormat = actual, kind = if (actual != null) "图片" else basic.kind)
                                    if (Files.exists(basic.copy(copyOriginal = true).destination(prefix).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                                        basic.copy(reason = "同名输出已存在，直接跳过，不读取原件内容", outputExists = true)
                                    else if (hasLivePartner(file)) basic.copy(kind = if (basic.kind == "视频") "疑似实况视频" else "疑似实况图", reason = "同目录同名照片/视频，疑似配对；两者均跳过")
                                else if (basic.kind != "视频" && (basic.sourceFormat ?: file.extension.lowercase()) !in setOf("jpg", "jpeg", "png", "webp"))
                                    basic.copy(reason = "${(basic.sourceFormat ?: file.extension).uppercase()} 暂不转码，保留原件")
                                else if (basic.kind != "视频" && basic.size in 1 until 180 * 1024)
                                    if (file.readBytes().toString(Charsets.ISO_8859_1).contains(MARKER)) basic.copy(reason = "已有 PhotoOpt 标记") else basic.copy(copyOriginal = true)
                                else {
                                    val cached = cache.get(basic)
                                    if (cached != null) { reused.incrementAndGet(); cached }
                                    else inspect(file, root).also { result ->
                                        if (file.length() == basic.size && file.lastModified() == basic.modified)
                                            cache.put(result)
                                    }
                                }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { basic.copy(reason = "读取失败：${e.message}") }
                            synchronized(completed) {
                                val count = completed.incrementAndGet()
                                if (count % 10 == 0 || count == files.size) onProgress(count, files.size, reused.get())
                            }
                        }
                    }
                }
            }
            ensureActive()
            cache.flush()
            results.filterNotNull()
        }
    }

    private fun inspectVivoPair(files: List<Pair<File, File>>, prefix: String, incomplete: Set<String>, cache: InspectionCache): List<MediaEntry> {
        val basics = files.map { (file, root) -> MediaEntry(file.path, root.path, file.relativeTo(root).path, file.length(), file.lastModified(),
            kind = if (file.extension.lowercase() == "mp4") "视频" else "vivo 实况", metadataRead = false) }
        fun skip(reason: String) = basics.map { it.copy(reason = reason) }
        if (basics.any { it.destination(prefix).canonicalPath !in incomplete && Files.exists(it.destination(prefix).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) })
            return basics.map { it.copy(reason = "配对文件已有输出，整组跳过，不覆盖", outputExists = true) }
        val cached = basics.map { cache.get(it) }
        if (cached.all { it?.vivoId != null } && cached[0]!!.vivoId == cached[1]!!.vivoId && cached[0]!!.vivoPartner == basics[1].source && cached[1]!!.vivoPartner == basics[0].source)
            return cached.filterNotNull()
        try {
            val photo = basics.first { it.kind != "视频" }; val video = basics.first { it.kind == "视频" }
            val id = VivoMedia.photoId(File(photo.source)) ?: return skip("同名配对未识别为支持的 vivo 实况，整组保留")
            if (VivoMedia.videoId(File(video.source)) != id) return skip("实况照片与视频关联标识不一致，整组保留")
            val layout = jpegLayout(File(photo.source))
            if (layout.segments.any { it.toString(Charsets.ISO_8859_1).contains(MARKER) }) return skip("已有 PhotoOpt 标记，实况整组跳过")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo.source, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "照片无法解码" }
            val exif = ExifInterface(photo.source)
            val picture = photo.copy(width = bounds.outWidth, height = bounds.outHeight, taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                hasGps = exif.latLong != null, metadataRead = true, vivoId = id, vivoPartner = video.source)
            val movie = inspectVideo(video).copy(vivoId = id, vivoPartner = photo.source, metadataRead = true)
            if (!movie.eligible) return skip("实况视频：${movie.reason}")
            val result = listOf(picture, movie)
            if (basics.all { File(it.source).length() == it.size && File(it.source).lastModified() == it.modified }) result.forEach(cache::put)
            return result
        } catch (e: Exception) { return skip("实况检查失败：${e.message}") }
    }

    private fun inspect(file: File, root: File): MediaEntry {
        val ext = MediaHeader.imageFormat(file) ?: file.extension.lowercase()
        var entry = MediaEntry(file.path, root.path, file.relativeTo(root).path, file.length(), file.lastModified(), sourceFormat = ext)
        if (ext in videoExtensions) return inspectVideo(entry)
        if (ext !in setOf("jpg", "jpeg", "png", "webp")) return entry.copy(reason = "${ext.uppercase()} 暂不转码，保留原件")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
        val exif = ExifInterface(file)
        entry = entry.copy(width = bounds.outWidth, height = bounds.outHeight,
            taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), hasGps = exif.latLong != null)
        if (exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.contains(MARKER) == true) return entry.copy(reason = "已有 PhotoOpt 标记")
        if (ext in setOf("png", "webp")) {
            if (isAnimated(file, ext)) return entry.copy(kind = "动图", reason = "动态 PNG / WebP 暂不转码")
            return entry
        }
        val layout = jpegLayout(file)
        val metadata = layout.segments.joinToString("") { it.toString(Charsets.ISO_8859_1) }
        if (metadata.contains(MARKER)) return entry.copy(reason = "已有 PhotoOpt 标记")
        // A validated JPEG primary image can be extracted without carrying timing metadata.
        // Keep the default conservative; the explicit force-static option enables extraction.
        if (metadata.contains("com.android.capture.fps") || hasSlowMotionMetadata(metadata)) {
            return entry.copy(reason = "含特殊拍摄速率元数据，可勾选兼容模式", forceStaticAllowed = true)
        }
        if (metadata.contains("MPF\u0000") || metadata.contains("hdrgm:") || metadata.contains("GainMap")) {
            if (metadata.contains("MotionPhoto") || metadata.contains("MicroVideo"))
                return entry.copy(kind = "实况图", reason = "HDR 与动态共用未知结构，可强制转普通主图", forceStaticAllowed = true)
            return entry.copy(kind = "HDR / 多画面 · 仅主图", primaryOnly = true)
        }
        val motion = metadata.contains("MotionPhoto") || metadata.contains("MicroVideo")
        if (layout.end < file.length() || motion) {
            val tail = file.length() - layout.end
            val xmp = exif.getAttribute(ExifInterface.TAG_XMP).orEmpty()
            val legacy = Regex("(?:MicroVideoOffset)[=\\s]*[\"'](\\d+)[\"']").find(xmp)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("<[^>]*MicroVideoOffset>(\\d+)</").find(xmp)?.groupValues?.get(1)?.toLongOrNull()
            val modern = Regex("Item:Semantic=[\"']MotionPhoto[\"']").containsMatchIn(xmp) &&
                Regex("Item:Length=[\"']$tail[\"']").containsMatchIn(xmp)
            val mp4 = tail > 12 && RandomAccessFile(file, "r").use { r ->
                r.seek(layout.end + 4); val b = ByteArray(4); r.readFully(b); String(b, Charsets.US_ASCII) == "ftyp"
            }
            // The video's bytes and end-relative offset stay identical. Nonstandard trailers are never guessed.
            val primaryHasSize = Regex("Item:Semantic=[\"']Primary[\"'][^>]*Item:Length=[\"'][1-9]").containsMatchIn(xmp) ||
                Regex("Item:Length=[\"'][1-9][0-9]*[\"'][^>]*Item:Semantic=[\"']Primary[\"']").containsMatchIn(xmp)
            if (motion && mp4 && (legacy == tail || modern) && !primaryHasSize && !metadata.contains("Samsung")) {
                return entry.copy(kind = "实况图", motionOffset = layout.end)
            }
            return entry.copy(kind = "实况图", reason = "未支持的实况图或附加数据结构，保留原件", forceStaticAllowed = true)
        }
        return entry
    }

    private fun inspectVideo(entry: MediaEntry): MediaEntry {
        val file = File(entry.source)
        if (file.extension.lowercase() !in setOf("mp4", "mov", "m4v")) return entry.copy(kind = "视频", reason = "当前仅转码 MP4 / MOV / M4V")
        if (CopyMarker.videoMarked(file)) return entry.copy(kind = "视频", reason = "已有 PhotoOpt 标记")
        val movieData = movieMetadata(file)
        val metadata = movieData.toString(Charsets.ISO_8859_1)
        if (metadata.contains(MARKER)) return entry.copy(kind = "视频", reason = "已有 PhotoOpt 标记")
        if (metadata.contains("com.apple.quicktime.content.identifier") || metadata.contains("com.apple.quicktime.still-image-time"))
            return entry.copy(kind = "实况视频", reason = "Apple Live Photo 视频暂不改动")
        if (hasSlowMotionMetadata(metadata))
            return entry.copy(kind = "视频", reason = "含慢动作元数据，暂不转码")
        return MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.path)
            if (metadata.contains("com.android.capture.fps") && !hasNormalCaptureRate(file, retriever, movieData)) {
                return@use entry.copy(kind = "视频", reason = "拍摄与播放帧率不一致或无法确认，暂不转码")
            }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val hdr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull() in setOf(6, 7)
            entry.copy(kind = "视频", width = width, height = height,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                taken = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                hasGps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) != null || readVideoLocation(movieData) != null,
                reason = when { width == 0 || height == 0 -> "没有可解码的视频轨道"; hdr -> "HDR 视频暂不转码，保留原色彩"; else -> null })
        }
    }

    suspend fun process(entry: MediaEntry, prefix: String, quality: Quality): Outcome = withContext(Dispatchers.IO) {
        val source = File(entry.source)
        var output = entry.destination(prefix)
        var copied = entry.copyOriginal
        var removedHdr = false
        fun result(state: String, detail: String, after: Long = 0, existing: Boolean = false) = Outcome(source.path, output.path, state, detail, entry.size, after, existing)
        if (!entry.eligible) return@withContext result("跳过", entry.reason.orEmpty())
        if (source.length() != entry.size || source.lastModified() != entry.modified) return@withContext result("跳过", "原文件已变化，请重新扫描")
        if (listOf(output, entry.copy(copyOriginal = true).destination(prefix)).any { Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) })
            return@withContext result("跳过", "同名输出已存在，直接跳过，不覆盖", existing = true)
        require(output.canonicalFile != source.canonicalFile) { "输出不能是原文件" }
        val temp = File(context.cacheDir, "photoopt-${UUID.randomUUID()}.${if (entry.kind == "视频") "mp4" else "jpg"}")
        var staged: File? = null
        try {
            if (entry.copyOriginal) source.copyTo(temp, overwrite = true)
            else if (entry.kind == "视频") VideoProcessor(context).compress(entry, temp, quality)
            else compressImage(entry, temp, quality)
            coroutineContext.ensureActive()
            check(temp.length() > 0) { "输出文件为空" }
            if (!copied && !entry.stillOnly && temp.length() >= entry.size) {
                copied = true
                output = entry.copy(copyOriginal = true).destination(prefix)
                source.copyTo(temp, overwrite = true)
            }
            if (copied) {
                if ((entry.sourceFormat ?: source.extension.lowercase()) in setOf("jpg", "jpeg")) removedHdr = HdrGainMap.removeFromFile(temp)
                CopyMarker.write(temp, entry.sourceFormat ?: source.extension, if (entry.copyOriginal) "already-small" else "not-smaller")
            }
            check(source.length() == entry.size && source.lastModified() == entry.modified) { "处理期间原文件发生变化" }
            check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs()) { "无法创建输出目录" }
            // Stage on the destination volume, then move without REPLACE_EXISTING. Never overwrite user files.
            staged = File(output.parentFile, ".photoopt-${UUID.randomUUID()}.part")
            temp.inputStream().use { input -> staged.outputStream().use { out -> input.copyTo(out) } }
            java.io.FileOutputStream(staged, true).use { it.fd.sync() }
            coroutineContext.ensureActive()
            check(staged.setLastModified(entry.modified)) { "无法保留文件修改时间" }
            if (listOf(output, entry.copy(copyOriginal = true).destination(prefix)).any { Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) })
                return@withContext result("跳过", "处理期间出现同名输出，已保留，不覆盖", existing = true)
            // Publish only after encoding, validation and the complete staged write have succeeded.
            coroutineContext.ensureActive()
            Files.move(staged.toPath(), output.toPath())
            MediaScannerConnection.scanFile(context, arrayOf(output.path), null, null)
            result("完成", if (removedHdr) "移除 HDR 增益图 · 主图未重新编码 · 已写入处理标记" else if (copied) "原样复制 · ${if (entry.copyOriginal) "文件已很小" else "压缩后没有更小"} · 已写入复制标记" else if (entry.forcedStatic) "兼容模式 · 已移除未知动态和附加数据" else if (entry.stillOnly) "仅静态照片 · 实况动态已移除${if (temp.length() >= entry.size) " · 体积未减少" else ""}" else if (entry.primaryOnly) "仅主图 · 普通 JPEG · 未保留 HDR 与附加画面" else if (entry.motionOffset > 0) "静态部分已压缩 · 动态视频原样保留" else "保留拍摄信息 · 已写入 PhotoOpt 标记", output.length())
        } finally {
            temp.delete()
            staged?.delete()
        }
    }

    private suspend fun compressImage(entry: MediaEntry, target: File, quality: Quality) {
        val source = File(entry.source)
        val maxEdge = if (quality.edge == 0) max(entry.width, entry.height) else quality.edge
        val ratio = minOf(1.0, maxEdge.toDouble() / max(entry.width, entry.height))
        val desiredW = (entry.width * ratio).roundToInt().coerceAtLeast(1)
        val desiredH = (entry.height * ratio).roundToInt().coerceAtLeast(1)
        var sample = 1
        while (entry.width / (sample * 2) >= desiredW && entry.height / (sample * 2) >= desiredH) sample *= 2
        // Bound full-resolution allocations, instead of letting a large panorama kill the entire batch.
        require(entry.width.toLong() / sample * (entry.height / sample) <= minOf(40_000_000L, Runtime.getRuntime().maxMemory() / 12)) { "图片像素过大，请选均衡或更省空间" }
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; if (entry.primaryOnly) inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB) })
            ?: error("图片解码失败")
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) bitmap.setGainmap(null)
            if (bitmap.hasAlpha()) {
                val row = IntArray(bitmap.width)
                for (y in 0 until bitmap.height) {
                    coroutineContext.ensureActive()
                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    require(row.all { (it ushr 24) == 255 }) { "包含透明像素，暂不转为 JPEG" }
                }
                bitmap.setHasAlpha(false)
            }
            val scaled = if (bitmap.width != desiredW || bitmap.height != desiredH) Bitmap.createScaledBitmap(bitmap, desiredW, desiredH, true) else bitmap
            try {
                target.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, it)) { "JPEG 编码失败" } }
            } finally { if (scaled !== bitmap) scaled.recycle() }
        } finally { bitmap.recycle() }
        coroutineContext.ensureActive()
        val sourceExif = ExifInterface(source)
        // Preserve JPEG EXIF, XMP, ICC and IPTC blocks before updating dimensions and adding our marker.
        if (!entry.primaryOnly && (entry.sourceFormat ?: source.extension.lowercase()) in setOf("jpg", "jpeg")) {
            val segments = jpegLayout(source).segments
            val imageOnly = File(context.cacheDir, "photoopt-${UUID.randomUUID()}.jpg")
            try {
                target.copyTo(imageOnly)
                target.outputStream().use { out ->
                    out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
                    segments.forEach { out.write(it) }
                    imageOnly.inputStream().use { input -> check(input.skip(2) == 2L); input.copyTo(out) }
                }
            } finally { imageOnly.delete() }
        }
        val destExif = ExifInterface(target)
        if (entry.primaryOnly) {
            // Do not carry offsets, MPF, maker notes or XMP that still describe removed images.
            val names = setOf("TAG_DATETIME", "TAG_DATETIME_ORIGINAL", "TAG_DATETIME_DIGITIZED", "TAG_OFFSET_TIME", "TAG_OFFSET_TIME_ORIGINAL", "TAG_OFFSET_TIME_DIGITIZED",
                "TAG_SUBSEC_TIME", "TAG_SUBSEC_TIME_ORIGINAL", "TAG_SUBSEC_TIME_DIGITIZED", "TAG_ORIENTATION", "TAG_MAKE", "TAG_MODEL", "TAG_SOFTWARE", "TAG_ARTIST", "TAG_COPYRIGHT", "TAG_IMAGE_DESCRIPTION",
                "TAG_EXPOSURE_TIME", "TAG_F_NUMBER", "TAG_PHOTOGRAPHIC_SENSITIVITY", "TAG_FOCAL_LENGTH", "TAG_FOCAL_LENGTH_IN_35MM_FILM", "TAG_EXPOSURE_BIAS_VALUE", "TAG_EXPOSURE_PROGRAM", "TAG_EXPOSURE_MODE", "TAG_WHITE_BALANCE", "TAG_FLASH", "TAG_METERING_MODE", "TAG_LENS_MAKE", "TAG_LENS_MODEL")
            ExifInterface::class.java.fields.filter { (it.name in names || it.name.startsWith("TAG_GPS_")) && it.type == String::class.java }.forEach { field ->
                val tag = field.get(null) as String
                sourceExif.getAttribute(tag)?.let { destExif.setAttribute(tag, it) }
            }
            destExif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
        } else if ((entry.sourceFormat ?: source.extension.lowercase()) !in setOf("jpg", "jpeg")) {
            ExifInterface::class.java.fields.filter { it.name.startsWith("TAG_") && it.type == String::class.java }.forEach { field ->
                val tag = field.get(null) as String
                sourceExif.getAttribute(tag)?.let { value -> destExif.setAttribute(tag, value) }
            }
        }
        destExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, desiredW.toString())
        destExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, desiredH.toString())
        destExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, desiredW.toString())
        destExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, desiredH.toString())
        val previousComment = sourceExif.getAttribute(ExifInterface.TAG_USER_COMMENT).orEmpty()
        destExif.setAttribute(ExifInterface.TAG_USER_COMMENT, listOf(previousComment, "$MARKER; profile=${quality.name}; originalBytes=${entry.size}${if (entry.primaryOnly) "; primaryOnly=true; omitted=HDR,auxiliary-images${if (entry.stillOnly) "; liveStillOnly=true; motionRemoved=true${if (entry.forcedStatic) "; forcedStatic=true" else ""}" else ""}" else ""}").filter { it.isNotBlank() }.joinToString("\n"))
        destExif.saveAttributes()
        if (entry.motionOffset > 0) {
            FileInputStream(source).use { input ->
                input.channel.position(entry.motionOffset)
                java.io.FileOutputStream(target, true).use { out -> input.copyTo(out) }
            }
        }
        verifyImage(sourceExif, target, desiredW, desiredH, preserveXmp = !entry.primaryOnly)
        if (entry.primaryOnly) {
            val layout = jpegLayout(target)
            check(layout.end == target.length()) { "静态小图仍含附加数据" }
            check(layout.segments.none { it.toString(Charsets.ISO_8859_1).contains("MPF\u0000") }) { "静态小图仍含 MPF" }
            check(ExifInterface(target).getAttribute(ExifInterface.TAG_XMP) == null) { "静态小图仍含原 XMP" }
        }
        if (entry.motionOffset > 0) {
            val outOffset = jpegLayout(target).end
            check(target.length() - outOffset == source.length() - entry.motionOffset) { "实况视频长度校验失败" }
            fun tailHash(file: File, offset: Long): ByteArray {
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { input ->
                    input.channel.position(offset)
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                }
                return hash.digest()
            }
            check(tailHash(source, entry.motionOffset).contentEquals(tailHash(target, outOffset))) { "实况视频内容校验失败" }
        }
    }

    private fun verifyImage(original: ExifInterface, target: File, width: Int, height: Int, preserveXmp: Boolean = true) {
        val written = ExifInterface(target)
        val tags = listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_ORIENTATION)
        for (tag in tags) check(original.getAttribute(tag) == written.getAttribute(tag)) { "元数据校验不一致：$tag" }
        check(written.getAttribute(ExifInterface.TAG_USER_COMMENT)?.contains(MARKER) == true) { "标记写入失败" }
        if (preserveXmp && original.getAttribute(ExifInterface.TAG_XMP) != null) check(original.getAttribute(ExifInterface.TAG_XMP) == written.getAttribute(ExifInterface.TAG_XMP)) { "XMP 校验不一致" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, options)
        check(options.outWidth == width && options.outHeight == height) { "输出尺寸校验失败" }
    }

    private fun hasLivePartner(file: File): Boolean {
        val wanted = when (file.extension.lowercase()) {
            in MediaFormats.pairedVideos -> MediaFormats.pairedImages
            in MediaFormats.pairedImages -> MediaFormats.pairedVideos
            else -> return false
        }
        val parent = file.parentFile ?: return false
        val names = siblingNames.computeIfAbsent(parent.path) { parent.listFiles()?.filter { it.isFile }?.map { it.name.lowercase() }?.toSet().orEmpty() }
        return wanted.any { "${file.nameWithoutExtension.lowercase()}.$it" in names }
    }

    private fun isAnimated(file: File, format: String): Boolean = RandomAccessFile(file, "r").use { r ->
        if (format == "webp") {
            if (r.length() < 21) return@use true
            r.seek(12); val id = ByteArray(4); r.readFully(id)
            if (String(id) == "VP8X") { r.seek(20); return@use r.readUnsignedByte() and 2 != 0 }
            return@use false
        }
        r.seek(8)
        while (r.filePointer + 12 < r.length()) {
            val length = r.readInt().toLong() and 0xffffffffL
            val id = ByteArray(4); r.readFully(id)
            if (String(id) == "acTL") return@use true
            if (String(id) == "IEND") break
            r.seek(r.filePointer + length + 4)
        }
        false
    }
}

data class JpegLayout(val end: Long, val segments: List<ByteArray>)

/** Parses marker lengths and entropy stuffing, so an FF D9 inside EXIF isn't mistaken for the image end. */
fun jpegLayout(file: File): JpegLayout = file.inputStream().use { input ->
    var position = 0L
    val buffer = ByteArray(64 * 1024)
    var cursor = 0
    var available = 0
    fun read(): Int {
        if (cursor == available) {
            available = input.read(buffer); cursor = 0
            check(available > 0) { "JPEG 意外结束" }
        }
        position++
        return buffer[cursor++].toInt() and 255
    }
    check(read() == 255 && read() == 216) { "不是 JPEG" }
    val segments = mutableListOf<ByteArray>()
    var metadataSize = 0
    var pending = -1
    while (true) {
        var marker = pending
        pending = -1
        if (marker < 0) { check(read() == 255) { "JPEG 标记损坏" }; do { marker = read() } while (marker == 255) }
        if (marker == 217) return@use JpegLayout(position, segments)
        if (marker == 1 || marker in 208..215) continue
        val high = read(); val low = read(); val size = high * 256 + low
        check(size >= 2) { "JPEG 段长度错误" }
        val keep = marker in setOf(225, 226, 237, 254)
        val data = if (keep) ByteArray(size + 2).also { it[0] = 255.toByte(); it[1] = marker.toByte(); it[2] = high.toByte(); it[3] = low.toByte() } else null
        for (i in 0 until size - 2) { val b = read(); data?.set(i + 4, b.toByte()) }
        if (data != null) { metadataSize += data.size; check(metadataSize <= 16 * 1024 * 1024) { "元数据过大" }; segments.add(data) }
        if (marker == 218) {
            while (true) {
                if (read() != 255) continue
                var b: Int
                do { b = read() } while (b == 255)
                if (b == 0 || b in 208..215) continue
                pending = b
                break
            }
        }
    }
    @Suppress("UNREACHABLE_CODE") error("JPEG 无结束标记")
}


/** Some editors write slowMotion:"none" even for ordinary videos. */
private fun hasSlowMotionMetadata(metadata: String): Boolean {
    if (!metadata.contains("slowmotion", ignoreCase = true)) return false
    val values = Regex("\"slowmotion\"\\s*:\\s*(?:\"([^\"]*)\"|([^,}\\s]+))", RegexOption.IGNORE_CASE)
        .findAll(metadata).map { it.groupValues[1].ifBlank { it.groupValues[2] }.lowercase() }.toList()
    return values.isEmpty() || values.any { it !in setOf("none", "false", "0", "null", "off", "") }
}

/** A capture-rate tag alone does not imply slow motion (ordinary Android videos carry it too). */
private fun hasNormalCaptureRate(file: File, retriever: MediaMetadataRetriever, movieData: ByteArray): Boolean {
    val captureRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
        ?: readCaptureFrameRate(movieData) ?: return false
    if (!captureRate.isFinite() || captureRate <= 0) return false
    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()
    val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()
    val playbackRate = if (durationMs != null && durationMs > 0 && frames != null && frames > 0) {
        frames * 1000 / durationMs
    } else {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            if (format == null || !format.containsKey(MediaFormat.KEY_FRAME_RATE)) return false
            format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() ?: return false
        } finally { extractor.release() }
    }
    // Allow rounding and normal variable-frame-rate variation, but not slow/fast-motion ratios.
    return playbackRate.isFinite() && playbackRate > 0 && kotlin.math.abs(captureRate - playbackRate) / captureRate <= 0.05
}
