package com.xk.photoopt

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PhotoViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("photoopt", Context.MODE_PRIVATE)
    var roots by mutableStateOf(preferences.getStringSet("roots", emptySet())!!.sorted()); private set
    var singles by mutableStateOf<List<String>>(emptyList()); private set
    var prefix by mutableStateOf(preferences.getString("prefix", "小图-") ?: "小图-"); private set
    var quality by mutableStateOf(if (!preferences.getBoolean("compact-default-v1", false)) Quality.COMPACT else runCatching { Quality.valueOf(preferences.getString("quality", "COMPACT")!!) }.getOrDefault(Quality.COMPACT)); private set
    var hideProcessed by mutableStateOf(true); private set
    fun updateHideProcessed(value: Boolean) { hideProcessed = value }
    val visibleEntries get() = if (hideProcessed) entries.filterNot { it.outputExists } else entries
    var forceStatic by mutableStateOf(true); private set
    var liveStillOnly by mutableStateOf(true); private set
    private var scannedEntries: List<MediaEntry> = emptyList()
    var includeVideos by mutableStateOf(true); private set
    var entries by mutableStateOf<List<MediaEntry>>(emptyList()); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var scanning by mutableStateOf(false); private set
    var scanReused by mutableStateOf(0); private set
    var scanTotal by mutableStateOf(0); private set
    var scanCount by mutableStateOf(0); private set
    var cleanupNote by mutableStateOf<String?>(null); private set
    var scanned by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var tab by mutableStateOf(0)
    var lastPlan by mutableStateOf<String?>(null); private set
    private var scanJob: Job? = null
    val batch = BatchProgress.state
    val chosen get() = entries.filter { it.source in selected }
    val canScan get() = (roots.isNotEmpty() || singles.isNotEmpty()) && validPrefix(prefix)

    val history = TaskHistory.state
    init {
        preferences.edit().putBoolean("compact-default-v1", true).putString("quality", quality.name).apply()
        viewModelScope.launch(Dispatchers.IO) { TaskHistory.load(getApplication()) }
    }
    fun deleteTask(id: String) { viewModelScope.launch(Dispatchers.IO) {
        runCatching { TaskHistory.delete(getApplication(), id) }.onFailure { withContext(Dispatchers.Main) { message = "删除记录失败：${it.message}" } }
    } }

    fun updateLiveStillOnly(value: Boolean) {
        if (scanning || batch.value.running) return
        liveStillOnly = value
        applyOptionsToSelection()
    }
    fun updateForceStatic(value: Boolean) {
        if (scanning || batch.value.running) return
        forceStatic = value
        applyOptionsToSelection()
    }
    private fun applyOptionsToSelection() {
        val previous = selected
        entries = applyLiveMode(scannedEntries)
        val wanted = entries.filter { it.source in previous }.flatMap { listOfNotNull(it.source, it.vivoPartner) }.toSet()
        selected = entries.filter { it.eligible && it.source in wanted }.map { it.source }.toSet()
    }
    private fun applyLiveMode(source: List<MediaEntry>): List<MediaEntry> = source.map { entry ->
        when {
            forceStatic && entry.forceStaticAllowed && !entry.outputExists -> entry.copy(reason = null, kind = "强制转普通图片", primaryOnly = true,
                stillOnly = true, forcedStatic = true, motionOffset = 0, copyOriginal = false, vivoId = null, vivoPartner = null)
            !liveStillOnly || !entry.eligible -> entry
            entry.vivoId != null && entry.kind == "视频" -> entry.copy(reason = "仅保留实况照片，不输出配对视频", vivoId = null, vivoPartner = null, stillOnly = true)
            entry.vivoId != null || entry.motionOffset > 0 -> entry.copy(kind = "实况 · 仅静态照片", vivoId = null, vivoPartner = null,
                motionOffset = 0, copyOriginal = false, primaryOnly = true, stillOnly = true)
            else -> entry
        }
    }

    fun updatePrefix(value: String) { prefix = value; invalidate(); if (validPrefix(value)) preferences.edit().putString("prefix", value).apply() }
    fun updateQuality(value: Quality) { quality = value; preferences.edit().putString("quality", value.name).apply(); lastPlan = null }
    fun setVideos(value: Boolean) { includeVideos = value; selected = if (value) selected + entries.filter { it.eligible && it.kind == "视频" && it.vivoId == null }.map { it.source } else selected - entries.filter { it.kind == "视频" && it.vivoId == null }.map { it.source }.toSet() }
    private fun invalidate() { scanJob?.cancel(); scanning = false; scanned = false; scannedEntries = emptyList(); entries = emptyList(); selected = emptySet(); lastPlan = null }
    fun addRoot(uri: Uri) {
        runCatching {
            val file = resolveLocalFile(getApplication(), uri) ?: error("请选择手机或 SD 卡上的本地目录")
            check(file.isDirectory && file.canRead()) { "无法读取目录，请先允许文件访问" }
            check(file.parentFile != null && file.name.isNotBlank() && file.canonicalFile != Environment.getExternalStorageDirectory().canonicalFile && file.parentFile?.path != "/storage") { "请选择具体照片目录" }
            val path = file.canonicalPath
            roots = (roots + path).distinct()
            preferences.edit().putStringSet("roots", roots.toSet()).apply()
            invalidate()
        }.onFailure { message = it.message }
    }
    fun removeRoot(path: String) { roots = roots - path; preferences.edit().putStringSet("roots", roots.toSet()).apply(); invalidate() }
    fun clearSingles() { singles = emptyList(); invalidate() }
    fun addImages(uris: List<Uri>) {
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { resolveLocalFile(getApplication(), it)?.takeIf { file -> file.isFile }?.canonicalPath }.getOrNull() } }
            singles = (singles + resolved).distinct()
            invalidate()
            if (resolved.size < uris.size) message = "已添加 ${resolved.size} 项。部分来源不是可访问的本地文件，请改用添加目录。"
        }
    }
    fun scan() {
        if (!canScan || scanning || batch.value.running) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.ACCESS_MEDIA_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) { message = "需要允许照片位置信息访问，以避免 GPS 被系统隐藏。请在准备页授权。"; return }
        if (!Environment.isExternalStorageManager()) { message = "请先允许文件访问，以便生成同级输出目录"; return }
        scanning = true; scanCount = 0; scanTotal = 0; scanReused = 0; scanned = false; lastPlan = null; cleanupNote = null
        val scanRoots = roots.toList()
        val scanSingles = singles.toList()
        val scanPrefix = prefix
        scanJob = viewModelScope.launch {
            try {
                entries = MediaWorkGate.mutex.withLock {
                    withContext(Dispatchers.IO) {
                        val found = MediaEngine(getApplication()).scan(scanRoots, scanSingles, scanPrefix) { count, total, reused ->
                            viewModelScope.launch { scanCount = count; scanTotal = total; scanReused = reused }
                        }
                        val incomplete = VivoPairProcessor.incompleteOutputs(getApplication())
                        val collisions = found.filter { it.eligible || it.forceStaticAllowed }.flatMap { entry ->
                            listOf(entry.destination(scanPrefix).canonicalPath, entry.copy(copyOriginal = true).destination(scanPrefix).canonicalPath).distinct().map { it to entry.source }
                        }.groupBy({ it.first }, { it.second }).filterValues { it.distinct().size > 1 }.keys
                        val checked = found.map { entry ->
                            ensureActive()
                            when {
                                !entry.eligible && !entry.forceStaticAllowed -> entry
                                listOf(entry.destination(scanPrefix).canonicalPath, entry.copy(copyOriginal = true).destination(scanPrefix).canonicalPath).any { it in collisions } -> entry.copy(reason = "多个源文件将输出到同一文件，请分别处理或调整命名", forceStaticAllowed = false)
                                listOf(entry.destination(scanPrefix), entry.copy(copyOriginal = true).destination(scanPrefix)).any { it.canonicalPath !in incomplete && java.nio.file.Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) } -> entry.copy(reason = "同名输出已存在，直接跳过，不覆盖", outputExists = true)
                                else -> entry
                            }
                        }
                        checked.map { entry ->
                            if (entry.vivoPartner != null && checked.any { it.source == entry.vivoPartner && !it.eligible }) entry.copy(reason = "实况配对文件不可处理，整组跳过", outputExists = entry.outputExists || checked.any { it.source == entry.vivoPartner && it.outputExists }) else entry
                        }
                    }
                }
                scannedEntries = entries
                entries = applyLiveMode(scannedEntries)
                selected = entries.filter { it.eligible && (includeVideos || it.kind != "视频" || it.vivoId != null) }.map { it.source }.toSet()
                scanned = true; tab = 1
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "扫描失败" }
            finally { scanning = false }
        }
    }
    fun cancelScan() { scanJob?.cancel(); scanning = false }
    fun toggle(entry: MediaEntry) {
        if (!entry.eligible) return
        val group = setOfNotNull(entry.source, entry.vivoPartner)
        selected = if (entry.source in selected) selected - group else selected + group
    }
    fun selectAll() { selected = entries.filter { it.eligible && (includeVideos || it.kind != "视频" || it.vivoId != null) }.map { it.source }.toSet() }
    fun clearSelection() { selected = emptySet() }
    fun preparePlan(): String {
        val plan = JSONObject().apply {
            put("cleanupNote", cleanupNote); put("app", "PhotoOpt"); put("dryRun", true); put("originalsRetained", true)
            put("prefix", prefix); put("profile", quality.name); put("originalBytes", chosen.sumOf { it.size })
            put("note", "仅检查，不编码、不创建输出目录、不修改媒体文件。压缩后大小需要实际编码确定。")
            put("entries", JSONArray().apply { entries.forEach { entry -> put(mediaToJson(entry).apply {
                put("selected", entry.source in selected); put("output", entry.destination(prefix).path)
                put("action", if (entry.source in selected) if (entry.kind == "视频") "转为 H.264 MP4" else if (entry.motionOffset > 0) "压缩静态图，原样保留视频" else "JPEG quality=${quality.jpeg}, maxEdge=${quality.edge}" else "跳过")
            }) } })
        }.toString(2)
        lastPlan = plan
        return plan
    }
    fun start() {
        if (chosen.isEmpty() || scanning || batch.value.running || !validPrefix(prefix)) return
        val snapshot = entries.filterNot { it.outputExists }.map { if (it.source in selected || !it.eligible) it else it.copy(reason = "未勾选，不处理") }
        val prefixSnapshot = prefix
        val qualitySnapshot = quality
        BatchProgress.mutable.value = BatchState(running = true, total = snapshot.size, current = "正在准备", id = java.util.UUID.randomUUID().toString(), startedAt = System.currentTimeMillis(), profile = qualitySnapshot.name, prefix = prefixSnapshot)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = JSONObject().apply {
                        put("id", batch.value.id); put("startedAt", batch.value.startedAt)
                        put("prefix", prefixSnapshot); put("quality", qualitySnapshot.name)
                        put("entries", JSONArray().apply { snapshot.forEach { put(mediaToJson(it)) } })
                    }
                    File(getApplication<Application>().filesDir, "batch-request.json").writeText(request.toString())
                }
                ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), ProcessingService::class.java).putExtra("request", "batch-request.json"))
                tab = 2
            } catch (e: Exception) {
                BatchProgress.mutable.value = BatchState(error = e.message)
                message = "无法启动处理：${e.message}"
            }
        }
    }
    fun stop() { getApplication<Application>().startService(Intent(getApplication(), ProcessingService::class.java).setAction("cancel")) }
    fun export(uri: Uri, task: BatchState?) {
        viewModelScope.launch {
            try {
                val content = task?.let { reportJson(it).toString(2) } ?: preparePlan()
                withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(content) } ?: error("无法写入报告") }
                message = "报告已导出"
            } catch (e: Exception) { message = "导出失败：${e.message}" }
        }
    }

}

/** The app intentionally declines cloud providers: sibling-directory output needs a real local source path. */
fun resolveLocalFile(context: Context, uri: Uri): File? {
    if (uri.scheme == "file") return uri.path?.let(::File)
    if (uri.authority == "com.android.externalstorage.documents") {
        val id = if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
        val parts = id.split(":", limit = 2)
        if (parts.size != 2) return null
        val base = if (parts[0].equals("primary", true)) Environment.getExternalStorageDirectory() else File("/storage/${parts[0]}")
        val file = File(base, parts[1]).canonicalFile
        if (file != base.canonicalFile && !file.path.startsWith(base.canonicalPath + "/")) return null
        return file
    }
    var queryUri = uri
    if (uri.authority == "com.android.providers.media.documents") {
        val id = DocumentsContract.getDocumentId(uri).split(":", limit = 2)
        if (id.size == 2) queryUri = ContentUris.withAppendedId(if (id[0] == "video") MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id[1].toLong())
    }
    return runCatching {
        context.contentResolver.query(queryUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let(::File) else null
        }
    }.getOrNull()
}
