package com.xk.photoopt

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xk.photoopt.ui.theme.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files

class MaintenanceViewModel(application: Application) : AndroidViewModel(application) {
    var results by mutableStateOf<List<MarkerFinding>>(emptyList()); private set
    var running by mutableStateOf(false); private set
    var location by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var count by mutableStateOf(0); private set
    private var work: Job? = null
    fun stop() { work?.cancel() }
    fun inspect(uris: List<Uri>) {
        if (running || uris.isEmpty()) return
        running = true; results = emptyList(); error = null; count = 0
        work = viewModelScope.launch {
            val collected = mutableListOf<MarkerFinding>()
            try {
                withContext(Dispatchers.IO) {
                    val paths = uris.map { resolveLocalFile(getApplication(), it) ?: error("请选择手机上的本地目录或文件") }.distinctBy { it.path }
                    withContext(Dispatchers.Main) { location = paths.joinToString("\n") { it.path } }
                    val pending = java.util.ArrayDeque<File>(); paths.forEach(pending::add)
                    val visited = mutableSetOf<String>()
                    var lastUpdate = 0L
                    while (pending.isNotEmpty()) {
                        ensureActive()
                        val file = pending.removeFirst()
                        if (Files.isSymbolicLink(file.toPath()) || !visited.add(file.canonicalPath)) continue
                        if (file.isDirectory) {
                            val children = file.listFiles()
                            if (children == null) collected.add(MarkerFinding(file.path, "读取失败", note = "无法读取目录"))
                            else children.sortedBy { it.name }.forEach { child ->
                                if (child.isDirectory || child.extension.lowercase() in MediaFormats.images + MediaFormats.videos) pending.add(child)
                            }
                        } else collected.add(MarkerInspector.inspect(file))
                        if (System.currentTimeMillis() - lastUpdate >= 150 || pending.isEmpty()) {
                            val snapshot = collected.toList()
                            withContext(Dispatchers.Main) { results = snapshot; count = snapshot.size }
                            lastUpdate = System.currentTimeMillis()
                        }
                    }
                }
            } catch (_: CancellationException) { error = "检查已停止，保留已读取结果" }
            catch (e: Exception) { error = e.message ?: "检查失败" }
            finally { results = collected.toList(); count = results.size; running = false }
        }
    }
}

class MaintenanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotoOptTheme { MaintenanceScreen(back = { finish() }) } }
    }
}

@Composable
private fun MaintenanceScreen(back: () -> Unit, vm: MaintenanceViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var filter by remember { mutableStateOf("全部") }
    var detail by remember { mutableStateOf<MarkerFinding?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { uri -> vm.inspect(listOf(uri)) } }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.inspect(it) }
    val shown = vm.results.filter { filter == "全部" || it.state == filter }
    BackHandler { vm.stop(); back() }
    Scaffold(containerColor = Paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.stop(); back() }) { Icon(Icons.Rounded.ArrowBack, "返回") }
                Text("运维工具", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (vm.running) TextButton(onClick = vm::stop) { Text("停止") }
            }
            Text("检查文件是否由轻相册处理。轻相册压缩或原样复制的图片、视频都会写入 PhotoOpt 标记。此工具只读检查，不修改文件。", fontSize = 12.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folder.launch(null) }, enabled = !vm.running, modifier = Modifier.weight(1f)) { Text("选择目录") }
                OutlinedButton(onClick = { files.launch(arrayOf("image/*", "video/*")) }, enabled = !vm.running, modifier = Modifier.weight(1f)) { Text("选择文件") }
            }
            if (!Environment.isExternalStorageManager()) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
            }) { Text("允许本地文件访问") }
            if (vm.location.isNotBlank()) Text(vm.location, fontSize = 10.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (vm.running) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("已检查 ${vm.count} 项", fontSize = 11.sp, color = Teal) }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "有标记", "未发现标记", "读取失败", "暂不支持").forEach { name ->
                    val count = if (name == "全部") vm.results.size else vm.results.count { it.state == name }
                    FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text("$name $count", fontSize = 11.sp) })
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (vm.results.isEmpty() && !vm.running) item { Text("选择目录可递归检查，也可指定图片或视频。", fontSize = 12.sp, color = Muted) }
                items(shown, key = { it.path }) { result ->
                    Surface(onClick = { detail = result }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(File(result.path).name, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(result.state + if (result.action.isNotEmpty()) " · ${result.action}" else "", fontSize = 12.sp,
                                color = if (result.markers.isNotEmpty()) Teal else Muted)
                            Text(result.path, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    detail?.let { result -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(result.state) }, text = {
        SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(result.path, fontSize = 12.sp)
            result.markers.forEach { Text(it, fontSize = 12.sp) }
            if (result.state == "未发现标记") Text("当前文件中未发现本应用支持识别的标记；不能据此证明它从未被处理，其他软件可能移除了元数据。", fontSize = 12.sp)
            if (result.note.isNotBlank()) Text(result.note, fontSize = 12.sp)
        } }
    }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }) }
}
