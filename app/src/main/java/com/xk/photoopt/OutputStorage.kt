package com.xk.photoopt

import android.content.Context
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.coroutineContext

/** Serializes maintenance with processing. No active encoder's temporary files may be swept. */
object MediaWorkGate { val mutex = Mutex() }

data class CleanupSummary(val found: Int = 0, val removed: Int = 0, val released: Long = 0, val failed: Int = 0, val dryRun: Boolean = false) {
    val message: String get() = if (dryRun) "发现 $found 个临时残留；演练模式未删除，正式处理前会自动清理"
        else "已清理 $removed 个临时残留，释放 ${bytes(released)}" + if (failed > 0) "；$failed 项无法清理，将在下次重试" else ""
}

/** Own temporary files only, in app cache or registered/selected output trees. Never follows symlinks. */
class ResidualCleaner(private val context: Context) {
    private val preferences = context.getSharedPreferences("photoopt-maintenance", Context.MODE_PRIVATE)
    private val uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private val cacheName = Regex("photoopt-$uuid\\.(jpg|mp4)")
    private val stagedName = Regex("\\.photoopt-$uuid\\.part")

    fun registerOutputs(roots: Collection<File>) {
        val paths = preferences.getStringSet("outputRoots", emptySet()).orEmpty() + roots.map { it.canonicalPath }
        // Persist before any output directory or staged file is created, including if the process is killed.
        check(preferences.edit().putStringSet("outputRoots", paths).commit()) { "无法保存临时文件清理范围" }
    }

    suspend fun clean(extraRoots: Collection<File>, dryRun: Boolean): CleanupSummary {
        var found = 0; var removed = 0; var released = 0L; var failed = 0
        suspend fun remove(file: File) {
            coroutineContext.ensureActive()
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile) return
            found++
            if (!dryRun) {
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) { removed++; released += size } else failed++
            }
        }
        context.cacheDir.listFiles()?.filter { cacheName.matches(it.name) }?.forEach { remove(it) }
        // A killed report write may leave its app-private staging file. Keep the final report.
        remove(File(context.filesDir, "last-report.tmp"))
        val visited = mutableSetOf<String>()
        suspend fun visit(directory: File) {
            coroutineContext.ensureActive()
            if (Files.isSymbolicLink(directory.toPath()) || !directory.exists()) return
            // All registered paths are canonical. Reject replacement symlinks in any ancestor as well.
            if (directory.canonicalPath != directory.absolutePath || !visited.add(directory.path)) return
            if (!directory.isDirectory) return
            val children = directory.listFiles()
            if (children == null) { failed++; return }
            for (file in children) {
                if (Files.isSymbolicLink(file.toPath())) continue
                if (file.isDirectory) visit(file)
                else if (stagedName.matches(file.name)) remove(file)
            }
        }
        val paths = preferences.getStringSet("outputRoots", emptySet()).orEmpty() + extraRoots.map { it.absolutePath }
        paths.forEach { visit(File(it)) }
        return CleanupSummary(found, removed, released, failed, dryRun)
    }
}

fun outputRoot(sourceRoot: String, prefix: String): File = File(sourceRoot).let { File(it.parentFile, prefix + it.name) }
