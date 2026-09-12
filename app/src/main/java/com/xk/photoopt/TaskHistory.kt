package com.xk.photoopt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One atomically replaced report per task; deleting a report never touches media. */
object TaskHistory {
    private val mutable = MutableStateFlow<List<BatchState>>(emptyList())
    val state = mutable.asStateFlow()
    private var loaded = false
    private fun directory(context: Context) = File(context.filesDir, "task-history").apply { mkdirs() }
    @Synchronized fun load(context: Context) {
        if (loaded) return
        val dir = directory(context)
        dir.listFiles().orEmpty().filter { it.extension == "tmp" && it.nameWithoutExtension.matches(Regex("(?:legacy|[0-9a-fA-F-]{36})")) }.forEach { it.delete() }
        val old = File(context.filesDir, "last-report.json")
        val migration = File(dir, "legacy-migrated")
        if (!migration.exists()) {
            val migrated = !old.exists() || runCatching {
                val legacy = parseReport(JSONObject(old.readText())).copy(id = "legacy", startedAt = old.lastModified(), endedAt = old.lastModified())
                write(context, legacy)
            }.isSuccess
            if (migrated) migration.writeText("1")
        }
        val reports = dir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching {
                val report = parseReport(JSONObject(file.readText()))
                if (report.running && report.id != BatchProgress.state.value.takeIf { it.running }?.id) {
                    val stopped = report.copy(running = false, cancelled = true, error = "任务被中断，已完成小图保留。重新扫描即可处理剩余文件。",
                        results = report.results.map { if (it.state == "待处理") it.copy(state = "未处理", detail = "任务中断，尚未记录完成") else it })
                    write(context, stopped)
                    stopped
                } else report
            }.getOrNull()
        }
        mutable.value = reports.sortedByDescending { it.startedAt }
        loaded = true
    }
    @Synchronized fun save(context: Context, state: BatchState) {
        load(context)
        if (state.id.isBlank()) return
        write(context, state)
        mutable.value = (mutable.value.filterNot { it.id == state.id } + state).sortedByDescending { it.startedAt }
    }
    private fun write(context: Context, state: BatchState) {
        require(state.id.matches(Regex("[a-zA-Z0-9-]+")))
        val dir = directory(context)
        val tmp = File(dir, "${state.id}.tmp")
        tmp.outputStream().use { stream -> stream.write(reportJson(state).toString().toByteArray()); stream.fd.sync() }
        Files.move(tmp.toPath(), File(dir, "${state.id}.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    @Synchronized fun delete(context: Context, id: String) {
        load(context)
        check(!(BatchProgress.state.value.running && BatchProgress.state.value.id == id)) { "进行中的任务不能删除" }
        val record = mutable.value.firstOrNull { it.id == id } ?: return
        check(File(directory(context), "${record.id}.json").let { !it.exists() || it.delete() }) { "无法删除记录" }
        mutable.value = mutable.value.filterNot { it.id == id }
    }
}

fun parseReport(o: JSONObject): BatchState {
    val list = o.optJSONArray("results")
    return BatchState(running = o.optBoolean("running"), completed = o.optInt("completed"), total = o.optInt("total"),
        cancelled = o.optBoolean("cancelled"), error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
        cleanupNote = o.optString("cleanupNote").takeIf { it.isNotBlank() && it != "null" },
        id = o.optString("id"), startedAt = o.optLong("startedAt"), endedAt = o.optLong("endedAt"), profile = o.optString("profile"), prefix = o.optString("prefix"),
        results = (0 until (list?.length() ?: 0)).map { i -> list!!.getJSONObject(i).let { r ->
            Outcome(r.getString("source"), r.getString("output"), r.getString("state"), r.getString("detail"), r.getLong("originalBytes"), r.optLong("outputBytes"))
        } })
}
