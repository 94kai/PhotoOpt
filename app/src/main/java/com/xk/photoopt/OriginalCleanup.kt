package com.xk.photoopt

import android.content.Context
import android.media.MediaScannerConnection
import android.system.Os
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** A cleanup plan is a snapshot of exact files, never a directory-wide delete. */
data class CleanupStamp(val path: String, val size: Long, val modified: Long, val inode: Long, val device: Long)
data class CleanupOriginal(val source: CleanupStamp, val output: CleanupStamp)
data class CleanupGroup(val files: List<CleanupOriginal>)
data class CleanupNote(val path: String, val state: String, val detail: String, val bytes: Long = 0)
data class OriginalCleanupPlan(val groups: List<CleanupGroup>, val skipped: List<CleanupNote>) {
    val files get() = groups.flatMap { it.files }
    val size get() = files.sumOf { it.source.size }
}

class OriginalCleanup(private val context: Context) {
    private fun stamp(path: String): CleanupStamp? = runCatching {
        val file = File(path)
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || !file.canRead() || file.absolutePath != file.canonicalPath) return null
        val stat = Os.lstat(path)
        if (stat.st_size <= 0) return null
        CleanupStamp(path, stat.st_size, file.lastModified(), stat.st_ino, stat.st_dev)
    }.getOrNull()
    private fun unchanged(expected: CleanupStamp) = stamp(expected.path) == expected

    suspend fun prepare(entries: List<MediaEntry>, prefix: String): OriginalCleanupPlan {
        require(validPrefix(prefix)) { "目录前缀无效" }
        val pending = VivoPairProcessor.incompleteOutputs(context)
        val skipped = mutableListOf<CleanupNote>()
        val checked = linkedMapOf<String, CleanupOriginal>()
        for (entry in entries.distinctBy { it.source }) {
            coroutineContext.ensureActive()
            val original = stamp(entry.source)
            if (original == null || original.size != entry.size || original.modified != entry.modified) {
                skipped += CleanupNote(entry.source, "跳过", "原件不可访问或扫描后已变化，请重新扫描")
                continue
            }
            val actual = runCatching { MediaHeader.imageFormat(File(entry.source)) }.getOrNull()
            val resolved = entry.copy(sourceFormat = actual ?: entry.sourceFormat)
            val outputs = listOf(resolved.destination(prefix), resolved.copy(copyOriginal = true).destination(prefix)).distinctBy { it.path }
            val output = outputs.filter { it.absolutePath !in pending }.mapNotNull { stamp(it.absolutePath) }.firstOrNull {
                it.path != original.path && !(it.device == original.device && it.inode == original.inode)
            }
            if (output == null) skipped += CleanupNote(entry.source, "跳过", "未找到可用小图，或实况输出尚未完整提交")
            else checked[entry.source] = CleanupOriginal(original, output)
        }
        // Two sources claiming the same output cannot both be treated as safely copied.
        val ambiguous = checked.values.groupBy { it.output.path }.filterValues { it.size > 1 }.keys
        val protectedPaths = checked.values.map { it.output.path }.toSet()
        checked.values.toList().filter { it.output.path in ambiguous || it.source.path in protectedPaths }.forEach {
            checked.remove(it.source.path)
            skipped += CleanupNote(it.source.path, "跳过", "输出对应关系冲突，保留原件")
        }
        val groups = mutableListOf<CleanupGroup>()
        val siblingNames = mutableMapOf<String, Set<String>>()
        val sourceGroups = entries.distinctBy { it.source }.groupBy { File(it.source).let { f -> f.parent to f.nameWithoutExtension.lowercase() } }
        for (members in sourceGroups.values) {
            coroutineContext.ensureActive()
            val paired = members.any { File(it.source).extension.lowercase() in MediaFormats.pairedImages } && members.any { File(it.source).extension.lowercase() in MediaFormats.pairedVideos }
            if (paired) {
                if (members.all { it.source in checked }) groups += CleanupGroup(members.map { checked.getValue(it.source) })
                else members.filter { it.source in checked }.forEach { skipped += CleanupNote(it.source, "跳过", "配对照片或视频缺少小图，整组保留") }
            } else members.forEach { member ->
                val file = File(member.source)
                // A separately selected photo/video must not silently leave a live partner without a copy.
                val extensions = if (file.extension.lowercase() in MediaFormats.pairedImages) MediaFormats.pairedVideos else if (file.extension.lowercase() in MediaFormats.pairedVideos) MediaFormats.pairedImages else emptySet()
                val names = siblingNames.getOrPut(file.parent.orEmpty()) { file.parentFile?.listFiles().orEmpty().filter { it.isFile }.map { it.name.lowercase() }.toSet() }
                val partner = extensions.any { file.nameWithoutExtension.lowercase() + "." + it in names }
                checked[member.source]?.let {
                    if (partner) skipped += CleanupNote(member.source, "跳过", "存在未纳入清单的配对文件，请添加整个目录后检查")
                    else groups += CleanupGroup(listOf(it))
                }
            }
        }
        return OriginalCleanupPlan(groups, skipped)
    }

    suspend fun deleteConfirmed(plan: OriginalCleanupPlan, progress: suspend (List<CleanupNote>, Int) -> Unit): List<CleanupNote> {
        val notes = mutableListOf<CleanupNote>()
        val id = UUID.randomUUID().toString()
        // Persist the user's confirmed scope before deleting anything.
        saveReceipt(id, plan, notes)
        try {
            for (group in plan.groups) {
                coroutineContext.ensureActive()
                if (group.files.any { !unchanged(it.source) || !unchanged(it.output) }) {
                    notes += group.files.map { CleanupNote(it.source.path, "跳过", "确认后原件或小图发生变化，整组保留") }
                } else {
                    for (file in group.files) {
                        coroutineContext.ensureActive()
                        val safe = unchanged(file.source) && group.files.all { unchanged(it.output) }
                        val note = if (!safe) CleanupNote(file.source.path, "跳过", "原件或小图发生变化，保留原件")
                        else try {
                            if (Files.deleteIfExists(File(file.source.path).toPath())) CleanupNote(file.source.path, "已删除", "已确认备份并保留小图", file.source.size)
                            else CleanupNote(file.source.path, "跳过", "原件已不存在")
                        } catch (e: Exception) { CleanupNote(file.source.path, "失败", e.message ?: "删除失败") }
                        notes += note
                        withContext(NonCancellable) {
                            try { saveReceipt(id, plan, notes) } finally { progress(notes.toList(), plan.files.size) }
                        }
                    }
                }
                progress(notes.toList(), plan.files.size)
                saveReceipt(id, plan, notes)
            }
        } finally {
            MediaScannerConnection.scanFile(context, notes.filter { it.state == "已删除" }.map { it.path }.toTypedArray(), null, null)
        }
        return notes
    }
    private fun saveReceipt(id: String, plan: OriginalCleanupPlan, notes: List<CleanupNote>) {
        val dir = File(context.filesDir, "original-cleanup").apply { mkdirs() }
        val data = JSONObject().put("confirmedAtOrUpdatedAt", System.currentTimeMillis()).put("files", JSONArray().apply {
            plan.files.forEach { file -> put(JSONObject().put("source", file.source.path).put("output", file.output.path)
                .put("state", notes.firstOrNull { it.path == file.source.path }?.state ?: "未记录完成")) }
        })
        val tmp = File(dir, "$id.tmp")
        tmp.outputStream().use { it.write(data.toString().toByteArray()); it.fd.sync() }
        Files.move(tmp.toPath(), File(dir, "$id.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
