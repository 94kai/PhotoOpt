package com.xk.photoopt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BatchProgress {
    internal val mutable = MutableStateFlow(BatchState())
    val state = mutable.asStateFlow()
}

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { work?.cancel(); return START_NOT_STICKY }
        if (work?.isActive == true) return START_NOT_STICKY
        val fileName = intent?.getStringExtra("request") ?: run { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("processing", "压缩进度", NotificationManager.IMPORTANCE_LOW))
        startForeground(7, notification("正在准备压缩"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoOpt:batch").apply { acquire(6 * 60 * 60 * 1000L) }
        work = scope.launch {
            MediaWorkGate.mutex.withLock {
                try {
                    val requestFile = File(filesDir, fileName)
                    val request = withContext(Dispatchers.IO) { JSONObject(requestFile.readText()).also { requestFile.delete() } }
                    val prefix = request.getString("prefix")
                    require(validPrefix(prefix)) { "无效的目录前缀" }
                    val quality = Quality.valueOf(request.getString("quality"))
                    val array = request.getJSONArray("entries")
                    val entries = (0 until array.length()).map { mediaFromJson(array.getJSONObject(it)) }.filterNot { it.outputExists }
                    val engine = MediaEngine(this@ProcessingService)
                    BatchProgress.mutable.value = BatchState(running = true, total = entries.size, completed = entries.count { !it.eligible }, current = "正在清理临时残留",
                        id = request.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        startedAt = request.optLong("startedAt", System.currentTimeMillis()), profile = quality.name, prefix = prefix,
                        results = entries.map { Outcome(it.source, it.destination(prefix).path, if (it.eligible) "待处理" else "跳过", it.reason ?: "等待处理", it.size) })
                    saveReport()
                    val cleanup = withContext(Dispatchers.IO) {
                        VivoPairProcessor.recover(this@ProcessingService)
                        val cleaner = ResidualCleaner(this@ProcessingService)
                        val outputRoots = entries.map { outputRoot(it.root, prefix) }.distinct()
                        cleaner.registerOutputs(outputRoots)
                        cleaner.clean(outputRoots, dryRun = false)
                    }
                    BatchProgress.mutable.value = BatchProgress.mutable.value.copy(cleanupNote = cleanup.message)
                    saveReport()
                    val handled = mutableSetOf<String>()
                    for ((index, entry) in entries.withIndex()) {
                        ensureActive()
                        if (!entry.eligible || entry.source in handled) continue
                        if (entry.vivoId != null) {
                            val pair = listOfNotNull(entry, entries.firstOrNull { it.source == entry.vivoPartner })
                            BatchProgress.mutable.value = BatchProgress.mutable.value.copy(current = "vivo 实况 · ${entry.name}")
                            val results = try { VivoPairProcessor(this@ProcessingService).process(pair, prefix, quality) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { pair.map { Outcome(it.source, it.destination(prefix).path, "失败", e.message ?: "实况处理失败", it.size) } }
                            handled.addAll(pair.map { it.source })
                            BatchProgress.mutable.value = BatchProgress.mutable.value.let { state -> state.copy(completed = state.completed + results.size,
                                results = state.results.map { old -> results.firstOrNull { it.source == old.source } ?: old }) }
                            saveReport()
                            continue
                        }
                        BatchProgress.mutable.value = BatchProgress.mutable.value.copy(current = entry.name)
                        manager.notify(7, notification("${BatchProgress.state.value.completed + 1} / ${BatchProgress.state.value.total} · ${entry.name}"))
                        val result = try { engine.process(entry, prefix, quality) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Outcome(entry.source, entry.destination(prefix).path, "失败", e.message ?: "处理失败", entry.size) }
                        BatchProgress.mutable.value = BatchProgress.mutable.value.let { it.copy(completed = it.completed + 1, results = it.results.map { old -> if (old.source == result.source) result else old }) }
                        saveReport()
                    }
                } catch (e: CancellationException) {
                    BatchProgress.mutable.value = BatchProgress.mutable.value.copy(cancelled = true)
                } catch (e: Exception) {
                    BatchProgress.mutable.value = BatchProgress.mutable.value.copy(error = e.message ?: "任务失败")
                } finally {
                    BatchProgress.mutable.value = BatchProgress.mutable.value.let { state -> state.copy(running = false, current = "", endedAt = System.currentTimeMillis(),
                        results = state.results.map { if (it.state == "待处理") it.copy(state = "未处理", detail = "任务已结束，未记录完成；重新扫描可确认已有输出") else it }) }
                    withContext(NonCancellable) { saveReport() }
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun saveReport() = withContext(Dispatchers.IO) {
        runCatching { TaskHistory.save(this@ProcessingService, BatchProgress.state.value) }.onFailure {
            BatchProgress.mutable.value = BatchProgress.mutable.value.copy(error = "任务记录保存失败：${it.message}")
        }
    }

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, ProcessingService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "processing")
            .setSmallIcon(R.drawable.ic_stat_photo).setContentTitle("轻相册 · 正在生成副本")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "停止", cancel).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        BatchProgress.mutable.value = BatchProgress.mutable.value.copy(error = "已达到系统后台处理时限，请重新扫描后继续")
        work?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}

fun validPrefix(value: String) = value.isNotBlank() && value.length <= 40 &&
    value.none { it in "/\\:*?\"<>|" || it.code < 32 } && !value.startsWith(".") && value == value.trim()

fun mediaToJson(entry: MediaEntry) = JSONObject().apply {
    put("source", entry.source); put("root", entry.root); put("relative", entry.relative)
    put("size", entry.size); put("modified", entry.modified); put("width", entry.width); put("height", entry.height)
    put("kind", entry.kind); put("reason", entry.reason); put("taken", entry.taken); put("hasGps", entry.hasGps)
    put("forceStaticAllowed", entry.forceStaticAllowed); put("forcedStatic", entry.forcedStatic); put("outputExists", entry.outputExists); put("stillOnly", entry.stillOnly); put("primaryOnly", entry.primaryOnly); put("copyOriginal", entry.copyOriginal); put("vivoId", entry.vivoId); put("vivoPartner", entry.vivoPartner); put("metadataRead", entry.metadataRead); put("motionOffset", entry.motionOffset); put("durationMs", entry.durationMs)
}
fun mediaFromJson(o: JSONObject) = MediaEntry(o.getString("source"), o.getString("root"), o.getString("relative"),
    o.getLong("size"), o.getLong("modified"), o.optInt("width"), o.optInt("height"), o.optString("kind", "图片"),
    if (o.has("reason") && !o.isNull("reason")) o.getString("reason") else null,
    if (o.has("taken") && !o.isNull("taken")) o.getString("taken") else null,
    o.optBoolean("hasGps"), o.optLong("motionOffset"), o.optLong("durationMs"), o.optBoolean("metadataRead", true), o.optString("vivoId").takeIf { it.isNotBlank() && it != "null" }, o.optString("vivoPartner").takeIf { it.isNotBlank() && it != "null" }, o.optBoolean("copyOriginal"), o.optBoolean("primaryOnly"), o.optBoolean("stillOnly"), o.optBoolean("outputExists"), o.optBoolean("forceStaticAllowed"), o.optBoolean("forcedStatic"))

fun reportJson(state: BatchState) = JSONObject().apply {
    put("id", state.id); put("startedAt", state.startedAt); put("endedAt", state.endedAt); put("profile", state.profile); put("prefix", state.prefix)
    put("app", "PhotoOpt"); put("dryRun", false); put("originalsRetained", true)
    put("cleanupNote", state.cleanupNote); put("running", state.running); put("cancelled", state.cancelled); put("error", state.error)
    put("completed", state.completed); put("total", state.total); put("potentialSavingAfterCleanup", state.saved)
    put("results", JSONArray().apply { state.results.forEach { result -> put(JSONObject().apply {
        put("source", result.source); put("output", result.output); put("state", result.state)
        put("detail", result.detail); put("originalBytes", result.before); put("outputBytes", result.after)
    }) } })
}
