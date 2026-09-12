package com.xk.photoopt

import android.content.Context
import android.media.MediaScannerConnection
import android.system.Os
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** Two staged files and a durable ownership journal prevent an interrupted pair being mistaken for complete. */
class VivoPairProcessor(private val context: Context) {
    suspend fun process(pair: List<MediaEntry>, prefix: String, quality: Quality): List<Outcome> = withContext(Dispatchers.IO) {
        require(pair.size == 2 && pair.all { it.eligible && it.vivoId != null }) { "实况需要完整选择照片和视频" }
        val photo = pair.first { it.kind != "视频" }; val video = pair.first { it.kind == "视频" }
        val ordered = listOf(photo, video)
        fun outcomes(state: String, detail: String) = pair.map { Outcome(it.source, it.destination(prefix).path, state, detail, it.size,
            if (state == "完成") it.destination(prefix).length() else 0) }
        if (pair.any { Files.exists(it.destination(prefix).toPath(), LinkOption.NOFOLLOW_LINKS) }) return@withContext outcomes("跳过", "实况输出已有同名文件，整组不覆盖").map { it.copy(alreadyExists = Files.exists(File(it.output).toPath(), LinkOption.NOFOLLOW_LINKS)) }
        if (pair.any { File(it.source).length() != it.size || File(it.source).lastModified() != it.modified }) return@withContext outcomes("跳过", "实况原文件已变化，请重新扫描")
        require(VivoMedia.photoId(File(photo.source)) == photo.vivoId && VivoMedia.videoId(File(video.source)) == photo.vivoId) { "实况配对信息已变化" }
        val id = VivoMedia.newId()
        val temps = listOf("jpg", "mp4").map { File(context.cacheDir, "photoopt-${UUID.randomUUID()}.$it") }
        val staged = mutableListOf<File>()
        var journal: File? = null
        var copied = false
        var copyRemovedHdr = false
        try {
            VivoMedia.compressPhoto(context, photo, temps[0], quality, photo.vivoId!!, id)
            VideoProcessor(context).compress(video, temps[1], quality)
            VivoMedia.appendVideoMetadata(File(video.source), temps[1], photo.vivoId, id)
            ensureActive()
            check(temps.none { it.length() == 0L }) { "实况输出为空" }
            if (temps.sumOf { it.length() } >= pair.sumOf { it.size }) {
                ordered.forEachIndexed { index, entry -> File(entry.source).copyTo(temps[index], overwrite = true); if (index == 0) copyRemovedHdr = HdrGainMap.removeFromFile(temps[index]); CopyMarker.write(temps[index], File(entry.source).extension, "vivo-pair-not-smaller") }
                copied = true
            }
            require(pair.all { File(it.source).length() == it.size && File(it.source).lastModified() == it.modified }) { "处理期间实况原文件已变化" }
            val records = JSONArray()
            ordered.forEachIndexed { index, entry ->
                val output = entry.destination(prefix).canonicalFile
                require(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs()) { "无法创建输出目录" }
                val part = File(output.parentFile, ".photoopt-${UUID.randomUUID()}.part")
                staged.add(part)
                temps[index].inputStream().use { input -> part.outputStream().use { out -> input.copyTo(out); out.fd.sync() } }
                check(part.setLastModified(entry.modified)) { "无法保留修改时间" }
                val stat = Os.lstat(part.path)
                records.put(JSONObject().put("output", output.path).put("staged", part.path).put("inode", stat.st_ino).put("device", stat.st_dev))
            }
            ensureActive()
            require(ordered.none { Files.exists(it.destination(prefix).toPath(), LinkOption.NOFOLLOW_LINKS) }) { "处理期间出现同名输出" }
            val dir = File(context.filesDir, "vivo-pending").apply { mkdirs() }
            journal = File(dir, "${UUID.randomUUID()}.json")
            val tempJournal = File(dir, journal!!.name + ".tmp")
            tempJournal.outputStream().use { it.write(JSONObject().put("files", records).toString().toByteArray()); it.fd.sync() }
            Files.move(tempJournal.toPath(), journal!!.toPath())
            // No suspension between publishing the two files. A process kill is handled by the journal.
            ordered.forEachIndexed { index, entry -> Files.move(staged[index].toPath(), entry.destination(prefix).canonicalFile.toPath()) }
            check(journal!!.delete()) { "无法完成实况提交记录" }
            journal = null
            MediaScannerConnection.scanFile(context, ordered.map { it.destination(prefix).path }.toTypedArray(), null, null)
            outcomes("完成", if (copyRemovedHdr) "移除 HDR 增益图 · 主图与视频未重新编码" else if (copied) "原样复制 · 实况整组压缩后没有更小 · 已写入复制标记" else "vivo 实况 B · 已压缩并移除 HDR 增益 · 保留动态与配对")
        } finally {
            temps.forEach { it.delete() }
            if (journal != null) recover(context)
            staged.forEach { it.delete() }
        }
    }

    companion object {
        private fun journals(context: Context) = File(context.filesDir, "vivo-pending").listFiles().orEmpty().filter { it.extension == "json" }
        private fun records(file: File): List<JSONObject> {
            val array = JSONObject(file.readText()).getJSONArray("files")
            require(array.length() == 2)
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        private fun owns(path: String, record: JSONObject): Boolean = runCatching {
            val file = File(path)
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalPath != file.absolutePath) false else {
                val stat = Os.lstat(path)
                stat.st_ino == record.getLong("inode") && stat.st_dev == record.getLong("device")
            }
        }.getOrDefault(false)
        /** Read-only, used by scans to recognize a half-published pair that may be safely regenerated. */
        fun incompleteOutputs(context: Context): Set<String> = journals(context).flatMap { file -> runCatching {
            val rows = records(file)
            if (rows.all { owns(it.getString("output"), it) }) emptyList() else rows.filter { owns(it.getString("output"), it) }.map { it.getString("output") }
        }.getOrDefault(emptyList()) }.toSet()
        /** Called under MediaWorkGate before ordinary temporary-file cleanup. */
        fun recover(context: Context) {
            journals(context).forEach { journal ->
                val rows = records(journal)
                val complete = rows.all { owns(it.getString("output"), it) }
                rows.forEach { row ->
                    if (!complete && owns(row.getString("output"), row)) check(File(row.getString("output")).delete()) { "无法清理未完成实况输出" }
                    if (owns(row.getString("staged"), row)) check(File(row.getString("staged")).delete()) { "无法清理实况临时文件" }
                }
                check(journal.delete()) { "无法清理实况提交记录" }
            }
        }
    }
}
