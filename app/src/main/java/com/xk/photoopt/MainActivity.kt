package com.xk.photoopt

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xk.photoopt.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent { PhotoOptTheme { PhotoApp() } }
    }
}

@Composable
fun PhotoApp(vm: PhotoViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val batch by vm.batch.collectAsState()
    var access by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var locationAccess by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var review by remember { mutableStateOf(false) }
    var donation by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var limitations by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MediaEntry?>(null) }
    var exportingTask by remember { mutableStateOf<BatchState?>(null) }
    val history by vm.history.collectAsState()
    val busy = vm.scanning || batch.running
    val snackbar = remember { SnackbarHostState() }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addRoot) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addImages(it) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { uri -> vm.export(uri, exportingTask) } }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locationAccess = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    fun requestAccess() {
        try { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) }
        catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) access = Environment.isExternalStorageManager() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(access) { if (access) permissions.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION)) }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (vm.tab == 0) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(Ink), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.FilterHdr, "轻相册", tint = Lime, modifier = Modifier.size(25.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("轻相册", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("PhotoOpt", color = Muted, fontSize = 10.sp)
                }
                IconButton(onClick = { donation = true }) { Icon(Icons.Rounded.LocalCafe, "打赏支持", tint = Teal) }
                IconButton(onClick = { context.startActivity(Intent(context, MaintenanceActivity::class.java)) }) { Icon(Icons.Rounded.Build, "运维工具", tint = Muted) }
                IconButton(onClick = { about = true }) { Icon(Icons.Rounded.Info, "使用说明", tint = Muted) }
            }
        },
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 10.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    if (busy) {
                        ActiveTaskBar(
                            scanning = vm.scanning,
                            scanCount = vm.scanCount, scanTotal = vm.scanTotal,
                            batch = batch,
                            onOpen = { vm.tab = if (vm.scanning) 1 else 2 },
                            onStop = { if (vm.scanning) vm.cancelScan() else vm.stop() }
                        )
                    } else if (vm.tab == 1 && vm.scanned) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (vm.scanned) "已选 ${vm.selected.size} 项" else "准备好，轻装出发", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(if (vm.scanned) "原文件 ${bytes(vm.chosen.sumOf { it.size })}" else "所有处理都在手机上完成", color = Muted, fontSize = 11.sp)
                            }
                            Button(
                                onClick = { if (!access) requestAccess() else if (!locationAccess) permissions.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION)) else if (!vm.scanned) vm.scan() else { review = true } },
                                enabled = !busy && (if (!access || !locationAccess) true else if (!vm.scanned) vm.canScan else vm.selected.isNotEmpty()),
                                shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 15.dp)
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(19.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(if (!access) "允许文件访问" else if (!locationAccess) "允许照片位置信息" else if (!vm.scanned) "扫描文件" else "生成小图", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        listOf(Triple("准备", Icons.Rounded.Dashboard, 0), Triple("文件", Icons.Rounded.PhotoLibrary, 1), Triple("结果", Icons.Rounded.TaskAlt, 2)).forEach { (label, icon, index) ->
                            Surface(onClick = { vm.tab = index }, shape = RoundedCornerShape(14.dp), color = if (vm.tab == index) Color(0xFFEDF3E8) else Color.Transparent) {
                                Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, null, Modifier.size(18.dp), tint = if (vm.tab == index) Teal else Muted)
                                    Spacer(Modifier.width(7.dp)); Text(label, fontSize = 12.sp, color = if (vm.tab == index) Teal else Muted, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (vm.tab) {
                0 -> SetupScreen(vm, access, busy, ::requestAccess,
                    { if (access) directoryPicker.launch(null) else requestAccess() },
                    { if (access) imagePicker.launch(arrayOf("image/*", "video/*")) else requestAccess() })
                1 -> FilesScreen(vm, busy, onPreview = { detail = it }, onLimitations = { limitations = true }, onScan = {
                    if (!access) requestAccess() else if (!locationAccess) permissions.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION)) else vm.scan()
                })
                2 -> HistoryScreen(history, batch, vm::stop, vm::deleteTask, { donation = true }) {
                    exportingTask = it; exportPicker.launch("PhotoOpt-${it.startedAt}.json")
                }
            }

        }
    }

    if (review) AlertDialog(
        onDismissRequest = { review = false },
        icon = { Icon(Icons.Rounded.AutoAwesome, null) },
        title = { Text("生成小图") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${vm.selected.size} 个文件 · 原始大小 ${bytes(vm.chosen.sumOf { it.size })}", fontWeight = FontWeight.Bold)
                Text("${vm.quality.title} · JPEG 质量 ${vm.quality.jpeg}\n${if (vm.quality.edge == 0) "照片保持原分辨率" else "照片长边最多 ${vm.quality.edge} 像素，不放大小图"}\n视频：H.264 / AAC，目标 ${vm.quality.bitrate / 1_000_000} Mbps")
                HorizontalDivider()
                vm.chosen.map { File(it.root).let { root -> "${root.name} → ${vm.prefix}${root.name}" } }.distinct().forEach { Text(it, fontSize = 13.sp) }
                Text("拍摄时间、位置等信息将尽量保留，并写入 PhotoOpt 标记。单文件实况保留视频；vivo 配对实况照片、视频都按所选档位压缩与缩放，保留配对信息。", fontSize = 13.sp)
                Text("原件始终保留。输出已有同名文件、元数据校验失败将跳过或报告失败；小文件、压缩后未变小的文件原样复制。", color = Teal, fontSize = 13.sp)
                if (vm.chosen.any { it.forcedStatic }) Text("将强制提取 ${vm.chosen.count { it.forcedStatic }} 张普通主图；未知动态与附加数据不保留。", fontSize = 12.sp, color = Teal)
                if (vm.chosen.any { it.primaryOnly && !it.stillOnly }) Text("含 ${vm.chosen.count { it.primaryOnly && !it.stillOnly }} 张仅处理主图的 HDR / 多画面 JPEG；小图不保留 HDR 和附加画面。", fontSize = 12.sp, color = Muted)
                Text("请勿把“小图-”目录加入飞牛备份范围。清理手机原件后才会释放空间。", fontSize = 12.sp, color = Muted)
            }
        },
        confirmButton = { TextButton(onClick = {
            review = false
            run {
                if (Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                vm.start()
            }
        }) { Text("开始处理") } },
        dismissButton = { TextButton(onClick = {
            review = false
        }) { Text("返回") } }
    )
    OriginalCleanupDialogs(vm)
    if (donation) DonationDialog { donation = false }
    if (limitations) LimitationsDialog { limitations = false }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("关于轻相册") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("原图存 NAS，回忆随身带。", fontWeight = FontWeight.Medium)
            Text("照片和视频备份后，手机里的原件仍然占空间。轻相册参考 iCloud“优化储存空间”的思路，把已备份的本地照片、视频压缩成分辨率和画质适当降低的小图，方便继续在手机相册里翻看。", fontSize = 13.sp, lineHeight = 21.sp)
            Text("确认备份和小图没问题后，清理手机原件，就能腾出空间。需要高清原图时，再通过 NAS App 查看或下载。轻相册只负责本地压缩，不会自动备份或自动取回原图。", fontSize = 13.sp, lineHeight = 21.sp)
            HorizontalDivider(color = Paper)
            Text("怎么使用", fontWeight = FontWeight.SemiBold)
            Text("1. 先备份\n用飞牛等 NAS App 备份原照片和视频，确认能正常打开。轻相册不会替你备份。", fontSize = 13.sp, lineHeight = 21.sp)
            Text("2. 选文件\n在准备页添加目录或指定文件，再到文件页点“扫描”。扫描不会修改原文件。", fontSize = 13.sp, lineHeight = 21.sp)
            Text("3. 生成小图\n勾选要处理的文件，点“生成小图”。小图会放在旁边的“小图-原目录名”文件夹里，原件仍保留。", fontSize = 13.sp, lineHeight = 21.sp)
            Text("4. 确认后清理\n检查 NAS 备份和小图都正常后，点“清理原件”。查看清单并再次确认才会删除，删除不能撤销。", fontSize = 13.sp, lineHeight = 21.sp)
            Text("清理原件后才会真正释放空间。小图文件夹不要重复加入 NAS 备份范围。", fontSize = 12.sp, color = Teal, lineHeight = 20.sp)
            Text("这里的“小图”也包括压缩后的视频。格式与实况说明请点文件页的问号。", fontSize = 11.sp, color = Muted)
            HorizontalDivider(color = Paper)
            Text("最佳实践：按相册定期整理", fontWeight = FontWeight.SemiBold)
            Text("1. 建几个相册\n在系统相册中创建“备份-日常”“备份-家人”“备份-旅行”等相册，确认它们对应手机里的实际文件夹。", fontSize = 13.sp, lineHeight = 21.sp)
            Text("2. 定期整理并备份\n把需要备份的照片和视频移动到对应相册，借助 NAS App、百度网盘等软件定期备份这些目录，等待备份完成。", fontSize = 13.sp, lineHeight = 21.sp)
            Text("3. 用轻相册生成小图\n选择这几个原件目录进行处理。小图会保存到另一个同级目录，例如“备份-旅行”对应“小图-备份-旅行”，方便继续在系统相册中查看。", fontSize = 13.sp, lineHeight = 21.sp)
            Text("4. 检查后再清理\n确认备份能打开、小图效果满意后，再清理已经处理好的手机原件。以后重复整理、备份、生成小图即可。", fontSize = 13.sp, lineHeight = 21.sp)
            Text("备份软件只选原件目录，避免重复上传小图。清理前也要确认不会同步删除 NAS 或网盘中的备份。", fontSize = 12.sp, color = Teal, lineHeight = 20.sp)
        }
    }, confirmButton = { TextButton(onClick = { about = false }) { Text("知道了") } })
    detail?.let { entry ->
        val companions = remember(vm.entries, entry.source) { mediaGroups(vm.entries).firstOrNull { group -> group.any { it.source == entry.source } } ?: listOf(entry) }
        Dialog(onDismissRequest = { detail = null }) {
            Surface(shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (companions.size > 1) {
                        Text(if (entry.stillOnly) "实况 · 仅生成静态照片" else if (entry.vivoId != null) "vivo 实况 · 照片和视频一起压缩" else "同名照片与视频 · ${companions.size} 个文件", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            companions.forEach { member -> FilterChip(selected = member.source == entry.source,
                                onClick = { detail = member }, label = { Text(member.name, fontSize = 12.sp) }) }
                        }
                    }
                    key(entry.source) {
                        if (File(entry.source).extension.lowercase() in MediaFormats.videos) VideoPreview(entry)
                        else Thumbnail(entry, Modifier.fillMaxWidth().height(240.dp), large = true)
                    }
                    Text(entry.name, fontWeight = FontWeight.Bold)
                    Text("${entry.kind}${if (entry.metadataRead) " · ${entry.width} × ${entry.height}" else ""} · ${bytes(entry.size)}", fontSize = 12.sp, color = Muted)
                    Text(if (!entry.metadataRead) "已直接跳过，未读取拍摄时间和位置。" else "拍摄时间：${entry.taken ?: "未记录"}\n位置：${if (entry.hasGps) "有 GPS 信息，将保留" else "未记录"}", fontSize = 13.sp)
                    SelectionContainer { Text("来源：${entry.source}\n\n输出：${entry.destination(vm.prefix).path}", fontSize = 12.sp) }
                    entry.reason?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    if (entry.primaryOnly) Text("仅生成普通静态小图，不保留 HDR 增益图和附加画面；原件保留。", fontSize = 12.sp, color = Muted)
                    TextButton(onClick = { detail = null }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(vm: PhotoViewModel, access: Boolean, busy: Boolean, grant: () -> Unit, addFolder: () -> Unit, addImages: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Hero() }
        if (!access) item {
            Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFFFFF1D9)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = Color(0xFF9C7637)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("连接你的本地照片", fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("允许文件访问，创建同级小图目录", fontSize = 11.sp, color = Muted) }
                    TextButton(onClick = grant) { Text("允许") }
                }
            }
        }
        item {
            SectionHeading("01", "选择来源", "支持多个目录")
            Spacer(Modifier.height(12.dp))
            WhiteCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = addFolder, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                        Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加目录", fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = addImages, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("指定文件", fontSize = 13.sp)
                    }
                }
                if (vm.roots.isEmpty() && vm.singles.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Paper).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderCopy, null, tint = Muted, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(14.dp))
                        Column { Text("从一个相册目录开始", fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("例如：DCIM、Pictures、备份-时间线", fontSize = 11.sp, color = Muted) }
                    }
                }
                vm.roots.forEach { path ->
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = Teal, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text(File(path).name, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(path, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        IconButton(onClick = { vm.removeRoot(path) }, enabled = !busy) { Icon(Icons.Rounded.Close, "移除目录", Modifier.size(17.dp), tint = Muted) }
                    }
                }
                if (vm.singles.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhotoLibrary, null, tint = Teal); Spacer(Modifier.width(10.dp))
                        Text("单独选择了 ${vm.singles.size} 个文件", modifier = Modifier.weight(1f), fontSize = 13.sp)
                        IconButton(onClick = vm::clearSingles, enabled = !busy) { Icon(Icons.Rounded.Close, "清除指定文件", Modifier.size(17.dp)) }
                    }
                    Text("单独文件输出到其父目录旁的“${vm.prefix}目录名”；属于已添加来源时沿用来源规则。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
                }
            }
        }
        item {
            SectionHeading("02", "输出设置", "原件始终保留")
            Spacer(Modifier.height(12.dp))
            WhiteCard {
                OutlinedTextField(value = vm.prefix, onValueChange = vm::updatePrefix, enabled = !busy, singleLine = true,
                    label = { Text("目录前缀") }, leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                    isError = !validPrefix(vm.prefix), supportingText = { if (!validPrefix(vm.prefix)) Text("使用 1–40 个字符，不含路径符号、首尾空格或前导点") },
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Paper).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SubdirectoryArrowRight, null, Modifier.size(17.dp), tint = Muted); Spacer(Modifier.width(6.dp))
                    Text("示例：旅行 → ${vm.prefix}旅行", color = Teal, fontSize = 12.sp, maxLines = 2)
                }
                Spacer(Modifier.height(10.dp))
                Text("每个来源分别生成对应目录，子目录结构保留。", fontSize = 11.sp, color = Muted)

            }
        }
        item { Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(16.dp), tint = Muted); Spacer(Modifier.width(8.dp))
            Text("小图目录请勿加入飞牛备份。确认 NAS 已保存原件后，由你自行清理手机原件，空间才会释放。", fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
        } }
    }
}

@Composable
private fun Hero() {
    Box(Modifier.fillMaxWidth().height(228.dp).clip(RoundedCornerShape(28.dp)).background(Ink)) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color(0xFF28453C), radius = size.width * .6f, center = Offset(size.width * 1.02f, size.height * .9f))
            drawCircle(Color(0xFF355444), radius = size.width * .38f, center = Offset(size.width * .98f, size.height * 1.04f))
        }
        Column(Modifier.padding(24.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(color = Color(0xFF355043), shape = RoundedCornerShape(50)) {
                Text("ON DEVICE  /  本地处理", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Lime, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Text("原图存 NAS\n回忆随身带", fontSize = 29.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("为下一次快门，腾出空间。", color = Color(0xFFAFC2B6), fontSize = 11.sp)
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).offset(y = 20.dp).size(100.dp, 125.dp).rotate(11f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFB9C8A4)))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).offset(y = 8.dp).size(100.dp, 125.dp).rotate(-7f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF4F4E7)).padding(6.dp)) {
            Canvas(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(7.dp))) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFB5D5CA), Color(0xFFEBE9BD))))
                drawCircle(Color(0xFFF5F0B7), size.width * .14f, Offset(size.width * .7f, size.height * .25f))
                drawPath(Path().apply { moveTo(0f, size.height * .8f); lineTo(size.width * .5f, size.height * .34f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }, Color(0xFF779D80))
                drawPath(Path().apply { moveTo(0f, size.height); lineTo(size.width * .68f, size.height * .5f); lineTo(size.width, size.height * .8f); lineTo(size.width, size.height); close() }, Color(0xFF355D4D))
            }
            Text("LESS SIZE, MORE LIFE", fontSize = 5.sp, color = Teal, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun FilesScreen(vm: PhotoViewModel, busy: Boolean, onPreview: (MediaEntry) -> Unit, onLimitations: () -> Unit, onScan: () -> Unit) {
    var filter by remember { mutableStateOf("全部") }
    val visible = vm.visibleEntries
    val allGroups = remember(visible) { mediaGroups(visible) }
    val availableGroups = allGroups.filter { group -> group.any { it.eligible } }
    val skippedGroups = allGroups.filter { group -> group.none { it.eligible } }
    val selectedGroups = allGroups.filter { group -> group.any { it.source in vm.selected } }
    val groups = when (filter) {
        "可处理" -> availableGroups
        "已跳过" -> skippedGroups
        "已选择" -> selectedGroups
        else -> allGroups
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("文件清单", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onLimitations) { Icon(Icons.Rounded.HelpOutline, "处理说明", tint = Muted, modifier = Modifier.size(20.dp)) }
                Button(onClick = onScan, enabled = vm.canScan && !busy, shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Icon(Icons.Rounded.ManageSearch, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp))
                    Text(if (vm.scanned) "重新扫描" else "扫描", fontSize = 12.sp)
                }
            }
        }
        if (!vm.scanned && !vm.scanning) item {
            EmptyState(Icons.Rounded.PhotoLibrary, "暂无文件", "添加来源后点击扫描")
        }
        if (vm.scanned) {
            item {
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        Row {
                            OptionCheck("隐藏已处理", vm.hideProcessed, true, vm::updateHideProcessed, Modifier.weight(1f))
                            OptionCheck("兼容模式", vm.forceStatic, !busy, vm::updateForceStatic, Modifier.weight(1f))
                        }
                    }
                }
                if (vm.forceStatic) Text("兼容模式：对于不兼容的图像格式，直接提取主图进行压缩。如果已跳过中依旧有不支持的类型，或不兼容的图像希望兼容，可以联系作者", fontSize = 11.sp, color = Muted, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::prepareOriginalCleanup, enabled = !busy && !vm.cleanupBusy, contentPadding = PaddingValues(horizontal = 0.dp)) { Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("清理原件", fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = vm::selectAll, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("全选", fontSize = 12.sp) }
                    TextButton(onClick = vm::clearSelection, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("清空", fontSize = 12.sp) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("全部" to allGroups.size, "可处理" to availableGroups.size,
                        "已跳过" to skippedGroups.size, "已选择" to selectedGroups.size).forEach { (name, count) ->
                        FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text("$name $count", fontSize = 11.sp) })
                    }
                }
            }
            if (groups.isEmpty()) item { EmptyState(Icons.Rounded.ImageSearch, "这里暂时没有文件", "可以更换筛选条件或重新扫描。") }
            items(groups, key = { it.first().source }) { group ->
                val entry = group.first()
                val paired = group.size > 1
                val groupDescription = if (paired && group.any { it.stillOnly }) "仅处理照片 · 不输出配对视频" else
                    group.mapNotNull { it.reason }.distinct().joinToString("；").takeIf { it.isNotBlank() }
                Surface(onClick = { onPreview(entry) }, shape = RoundedCornerShape(20.dp), color = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumbnail(entry, Modifier.size(70.dp).clickable { onPreview(entry) })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).clickable { onPreview(entry) }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (paired) File(entry.source).nameWithoutExtension + if (entry.stillOnly) " · 实况仅照片" else if (entry.vivoId != null) " · vivo 实况" else " · 照片与视频" else entry.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (paired) "${group.size} 个文件 · ${bytes(group.sumOf { it.size })} · 点击查看" else "${bytes(entry.size)}${if (entry.metadataRead) "  ·  ${entry.width} × ${entry.height}" else "  ·  未读内容"}", color = Muted, fontSize = 10.sp)
                            Text(groupDescription ?: entry.reason ?: if (entry.copyOriginal) "文件已很小 · 原样复制" else "${entry.kind}${if (entry.hasGps) " · GPS" else ""}${if (entry.taken != null) " · 拍摄时间" else ""}", color = if (entry.eligible) Teal else Color(0xFF9D7960), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (!entry.eligible) Icon(Icons.Rounded.ChevronRight, "查看详情", tint = Muted) else Checkbox(checked = entry.source in vm.selected, onCheckedChange = { vm.toggle(entry) }, enabled = entry.eligible && !busy, modifier = Modifier.size(40.dp).semantics { contentDescription = "选择 ${entry.name}" })
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsScreen(batch: BatchState, stop: () -> Unit, export: () -> Unit, back: () -> Unit) {
    var filter by remember(batch.id) { mutableStateOf("全部") }
    var expanded by remember(batch.id) { mutableStateOf<Outcome?>(null) }
    var showInfo by remember(batch.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Rounded.ArrowBack, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text("返回", fontSize = 12.sp)
            }
            Text(taskTime(batch.startedAt).replace(" ", "\n"), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            TextButton(onClick = export, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("导出报告", fontSize = 12.sp) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Surface(color = Ink, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (batch.running) "处理中 ${batch.completed}/${batch.total}" else if (batch.cancelled) "已中断" else if (batch.error != null) "任务异常" else "处理结束", color = Color.White, modifier = Modifier.weight(1f), fontSize = 12.sp)
                        Text("压缩减少 ${bytes(batch.saved)}", color = Lime, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    Text("清理原件后释放 · 小图 ${bytes(batch.outputBytes)}", color = Color(0xFFB5C7BB), fontSize = 10.sp)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val videos = batch.results.count { File(it.source).extension.lowercase() in MediaFormats.videos }
                Text("照片 ${batch.results.size - videos} · 视频 $videos", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
                TextButton(onClick = { showInfo = !showInfo }) { Text(if (showInfo) "收起详情" else "任务详情", fontSize = 11.sp) }
            }
            if (showInfo) {
                Text("${if (batch.endedAt > 0) "结束：${taskTime(batch.endedAt)}" else "结束时间未记录"}\n${runCatching { Quality.valueOf(batch.profile).title }.getOrDefault("历史设置")} · ${batch.prefix}", fontSize = 12.sp, color = Muted)
                if (batch.id == "legacy") Text("旧版时间按报告保存时间展示", fontSize = 11.sp, color = Muted)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "完成", "失败", "跳过", "未处理").forEach { name ->
                    val count = if (name == "全部") batch.total else batch.results.count { it.state == name || (name == "未处理" && it.state == "待处理") }
                    FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text("${if (name == "完成") "成功" else name} $count", fontSize = 11.sp) })
                }
            }
        }
        batch.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }
        if (batch.results.isEmpty()) item { EmptyState(Icons.Rounded.FactCheck, "暂无文件记录", "") }
        items(batch.results.filter { filter == "全部" || it.state == filter || (filter == "未处理" && it.state == "待处理") }, key = { it.source }) { result ->
            Surface(onClick = { expanded = result }, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (result.state == "完成") Icons.Rounded.CheckCircle else if (result.state == "失败") Icons.Rounded.ErrorOutline else Icons.Rounded.SkipNext, null,
                        tint = if (result.state == "完成") Teal else Color(0xFFA87D59), modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(File(result.source).name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (result.state == "完成") "${if (result.detail.startsWith("原样复制")) "原样复制 · " else ""}${bytes(result.before)} → ${bytes(result.after)}" else result.detail, fontSize = 11.sp, color = Muted, maxLines = 2)
                    }
                    Text(result.state, fontSize = 11.sp, color = Teal)
                }
            }
        }
    }
    }
    expanded?.let { result -> OutcomePreviewDialog(result) { expanded = null } }

}

@Composable
private fun Thumbnail(entry: MediaEntry, modifier: Modifier, large: Boolean = false) {
    var loaded by remember(entry.source, entry.modified, entry.size, large) { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, entry.source, entry.modified, entry.size, large) {
        // Preview loading is independent of scan metadata: skipped files can still be viewed.
        value = withContext(Dispatchers.IO) {
            runCatching {
                val limit = if (large) 900 else 240
                if (entry.kind.contains("视频")) {
                    MediaMetadataRetriever().use { r -> r.setDataSource(entry.source); r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, limit, limit) }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(entry.source, options)
                    var sample = 1
                    while (max(options.outWidth, options.outHeight) / sample > limit * 2) sample *= 2
                    val raw = BitmapFactory.decodeFile(entry.source, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
                    val exif = runCatching { ExifInterface(entry.source) }.getOrNull()
                    val degrees = exif?.rotationDegrees ?: 0
                    if (degrees != 0 || exif?.isFlipped == true) {
                        val matrix = Matrix().apply { if (exif?.isFlipped == true) postScale(-1f, 1f); postRotate(degrees.toFloat()) }
                        android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
                    } else raw
                }
            }.getOrNull()
        }
        loaded = true
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFE5EDE1)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), entry.name, Modifier.fillMaxSize(), contentScale = if (large) ContentScale.Fit else ContentScale.Crop) }
            ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (entry.kind.contains("视频")) Icons.Rounded.PlayCircle else Icons.Rounded.Landscape, entry.name, tint = Muted)
                if (large) Text(if (loaded) "此文件无法预览" else "加载预览…", fontSize = 12.sp, color = Muted)
            }
        if (entry.kind == "实况图") Surface(Modifier.align(Alignment.BottomStart).padding(3.dp), color = Ink.copy(alpha = .8f), shape = RoundedCornerShape(4.dp)) {
            Text("LIVE", Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = Color.White, fontSize = 7.sp)
        }
    }
}

@Composable
private fun WhiteCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White) { Column(Modifier.fillMaxWidth().padding(18.dp), content = content) }
}
@Composable
private fun SectionHeading(number: String, title: String, caption: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(9.dp)); Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.weight(1f)); Text(caption, fontSize = 10.sp, color = Muted)
    }
}
@Composable
private fun ToggleRow(icon: ImageVector, title: String, caption: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = Teal); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp); if (caption.isNotBlank()) Text(caption, fontSize = 10.sp, color = Muted) }
        Switch(checked = checked, onCheckedChange = change, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}
@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(label, fontSize = 11.sp, color = Muted)
        }
    }
}
@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(74.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFFE5EDDD)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(32.dp), tint = Teal) }
        Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        Text(subtitle, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun LimitationsButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Icon(Icons.Rounded.HelpOutline, null, Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("处理说明", fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp))
    }
}

@Composable
private fun LimitationsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        containerColor = Color.White,
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, null) },
        title = { Text("处理说明") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                processingLimitations.forEach { group ->
                    if (group.title.isNotBlank()) item { Text(group.title, color = Teal, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    items(group.items) { limitation ->
                        Surface(shape = RoundedCornerShape(14.dp), color = Paper) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(limitation.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Ink)
                                SelectionContainer { Text(limitation.explanation, fontSize = 12.sp, lineHeight = 20.sp, color = Ink.copy(alpha = .8f)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

/** Lives in Scaffold's bottom bar, independently of the selected page and its scroll position. */
@Composable
private fun ActiveTaskBar(
    scanning: Boolean,
    scanCount: Int,
    scanTotal: Int,
    batch: BatchState,
    onOpen: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = Ink
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Lime, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            if (scanning) if (scanTotal == 0) "列出文件 · $scanCount" else "扫描 · $scanCount / $scanTotal" else "生成中 · ${batch.completed} / ${batch.total}",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (scanning) "点击查看文件清单" else batch.current.ifBlank { "正在准备处理" },
                            fontSize = 10.sp, color = Color(0xFFB9C9BC), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, if (scanning) "查看扫描" else "查看处理进度", Modifier.size(17.dp), tint = Lime)
                }
                TextButton(onClick = onStop) { Text("停止", fontSize = 12.sp, color = Lime) }
            }
            if (!scanning && batch.total > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (batch.completed.toFloat() / batch.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = Lime, trackColor = Color(0xFF3C5749)
                )
            }
        }
    }
}

private fun taskTime(time: Long): String = if (time <= 0) "历史任务" else
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(time))

@Composable
private fun HistoryScreen(history: List<BatchState>, active: BatchState, stop: () -> Unit, delete: (String) -> Unit, donate: () -> Unit, export: (BatchState) -> Unit) {
    var selectedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<BatchState?>(null) }
    val tasks = if (active.running && active.id.isNotBlank())
        (history.filterNot { it.id == active.id } + active).sortedByDescending { it.startedAt } else history
    val selected = tasks.firstOrNull { it.id == selectedId }
    androidx.activity.compose.BackHandler(selected != null) { selectedId = null }
    if (selected != null) {
        ResultsScreen(selected, stop, { export(selected) }, { selectedId = null })
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("任务记录", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("${tasks.size} 次", color = Muted, fontSize = 12.sp)
                }
            }
            item {
                Surface(color = Ink, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("累计压缩减少", color = Color.White, fontSize = 12.sp)
                                Text("清理原件后释放 · 仅统计保留记录", color = Color(0xFFB5C7BB), fontSize = 10.sp)
                            }
                            Text(bytes(tasks.sumOf { it.saved }), color = Lime, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = .12f), modifier = Modifier.padding(horizontal = 16.dp))
                        Surface(onClick = donate, color = Color.Transparent) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.LocalCafe, null, Modifier.size(18.dp), tint = Lime)
                                Spacer(Modifier.width(8.dp))
                                Text(if (tasks.sumOf { it.saved } > 0) "省下空间，请我喝杯咖啡" else "喜欢轻相册？请我喝杯咖啡", Modifier.weight(1f), color = Color.White, fontSize = 11.sp)
                                Text("打赏", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = Lime)
                            }
                        }
                    }
                }
            }
            if (tasks.isEmpty()) item { EmptyState(Icons.Rounded.History, "还没有处理任务", "扫描并生成小图后，每次任务都会保存在这里。") }
            items(tasks, key = { it.id }) { task ->
                Surface(onClick = { selectedId = task.id }, color = Color.White, shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.FolderCopy, null, tint = Teal, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(taskTime(task.startedAt), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(if (task.running) "正在处理 · ${task.completed} / ${task.total}" else if (task.cancelled) "已中断" else if (task.error != null) "任务异常" else "处理结束", color = Muted, fontSize = 11.sp)
                            }
                            IconButton(onClick = { deleting = task }, enabled = !task.running) { Icon(Icons.Rounded.DeleteOutline, "删除任务记录", tint = Muted) }
                        }
                        Text("共 ${task.total} · 成功 ${task.results.count { it.state == "完成" }} · 失败 ${task.results.count { it.state == "失败" }} · 跳过 ${task.results.count { it.state == "跳过" }}", fontSize = 11.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("压缩减少 ${bytes(task.saved)}", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (task.results.any { it.state == "未处理" || it.state == "待处理" }) Text("未处理 ${task.results.count { it.state == "未处理" || it.state == "待处理" }}", fontSize = 11.sp, color = Muted); Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
                        }
                    }
                }
            }
        }
    }
    deleting?.let { task -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这次任务记录？") },
        text = { Text("${taskTime(task.startedAt)}\n只删除记录，原件和生成的照片、视频都会保留。") },
        confirmButton = { TextButton(onClick = { delete(task.id); deleting = null }) { Text("删除记录") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

/** Pair by both directory and stem, using the same conservative formats as detection. */
private fun mediaGroups(entries: List<MediaEntry>): List<List<MediaEntry>> {
    val candidates = entries.groupBy { File(it.source).let { f -> f.parent.orEmpty() to f.nameWithoutExtension.lowercase() } }
    return candidates.values.flatMap { group ->
        val images = group.filter { File(it.source).extension.lowercase() in MediaFormats.pairedImages }
        val videos = group.filter { File(it.source).extension.lowercase() in MediaFormats.pairedVideos }
        if (images.isNotEmpty() && videos.isNotEmpty()) {
            val pair = images + videos
            listOf(pair) + group.filterNot { it in pair }.map { listOf(it) }
        } else group.map { listOf(it) }
    }
}

@Composable
private fun VideoPreview(entry: MediaEntry) {
    var playing by remember(entry.source) { mutableStateOf(false) }
    var error by remember(entry.source) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val player = remember(entry.source) { android.widget.VideoView(context) }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); player.stopPlayback() }
    }
    Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (!playing) {
            Thumbnail(entry, Modifier.fillMaxSize(), large = true)
            FilledTonalButton(onClick = { playing = true }) { Icon(Icons.Rounded.PlayArrow, null); Text("播放视频") }
        } else {
            androidx.compose.ui.viewinterop.AndroidView(factory = {
                player.apply {
                    setMediaController(android.widget.MediaController(context).apply { setAnchorView(player) })
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, _, _ -> error = "此视频无法由系统播放器解码，原文件不受影响。"; true }
                    setVideoPath(entry.source)
                }
            }, modifier = Modifier.fillMaxSize())
            error?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.background(Color.Black).padding(16.dp)) }
        }
    }
}

private data class RecordPreview(val entry: MediaEntry?, val label: String)

@Composable
private fun OutcomePreviewDialog(result: Outcome, dismiss: () -> Unit) {
    val preview by produceState<RecordPreview?>(null, result.source, result.output) {
        value = withContext(Dispatchers.IO) {
            val source = File(result.source)
            val output = result.output.takeIf { it.isNotBlank() }?.let(::File)
            val file = listOfNotNull(source, output).firstOrNull { runCatching { it.isFile && it.canRead() }.getOrDefault(false) }
            if (file == null) RecordPreview(null, "原文件和对应输出均不存在或无法访问，记录仍然保留。")
            else RecordPreview(MediaEntry(file.path, file.parent.orEmpty(), file.name, file.length(), file.lastModified(),
                kind = if (file.extension.lowercase() in MediaFormats.videos) "视频" else "图片", metadataRead = false),
                if (file == source) "原文件预览" else "原文件不可用，显示记录中的输出路径文件")
        }
    }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(File(result.source).name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("${result.state} · ${result.detail}", color = if (result.state == "完成") Teal else MaterialTheme.colorScheme.error, fontSize = 13.sp)
                if (preview == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                preview?.let { found ->
                    Text(found.label, color = Muted, fontSize = 12.sp)
                    found.entry?.let { entry -> key(entry.source) {
                        if (entry.kind == "视频") VideoPreview(entry)
                        else Thumbnail(entry, Modifier.fillMaxWidth().height(240.dp), large = true)
                    } }
                }
                SelectionContainer { Text("来源：${result.source}\n\n输出路径：${result.output}\n\n原大小：${bytes(result.before)}${if (result.state == "完成") "\n小图大小：${bytes(result.after)}" else ""}", fontSize = 12.sp) }
                TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}


@Composable
private fun OptionCheck(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).toggleable(value = checked, enabled = enabled,
        role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = change).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = if (enabled) Ink else Muted)
    }
}


@Composable
private fun DonationDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    val qr by produceState<android.graphics.Bitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("donation_qr.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    Dialog(onDismissRequest = dismiss) {
        Surface(color = Paper, shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.LocalCafe, null, tint = Teal, modifier = Modifier.size(32.dp))
                Text("请我喝杯咖啡", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("如果轻相册帮到了你，欢迎随心打赏。", fontSize = 12.sp, color = Muted)
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(16.dp).fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
                        qr?.let { Image(it.asImageBitmap(), "打赏二维码", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                            ?: Text("打赏二维码待添加", color = Muted, fontSize = 13.sp)
                    }
                }
                SelectionContainer { Text("微信：xk3440395", fontSize = 14.sp, color = Teal) }
                Text("感谢支持，让轻相册继续变得更好。", fontSize = 11.sp, color = Muted)
                TextButton(onClick = dismiss) { Text("关闭") }
            }
        }
    }
}


@Composable
private fun OriginalCleanupDialogs(vm: PhotoViewModel) {
    if (vm.cleanupBusy) AlertDialog(onDismissRequest = {}, title = { Text("清理原件") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(vm.cleanupPhase); LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }, confirmButton = {})
    vm.originalCleanupPlan?.let { plan ->
        var confirmed by remember(plan) { mutableStateOf(false) }
        var showSkipped by remember(plan) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = vm::dismissCleanup, title = { Text(if (plan.files.isEmpty()) "暂无可清理原件" else "确认删除 ${plan.files.size} 个原文件？") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("原件共 ${bytes(plan.size)}。检查范围是本次扫描的全部来源，不受文件勾选或隐藏状态影响。仅删除本机原件，保留小图和目录；删除不会进入相册回收站。", fontSize = 13.sp)
                Text("应用只检查对应小图是否存在，无法确认 NAS 备份状态。若 NAS 开启了同步删除，请先确认不会连带删除备份。", fontSize = 12.sp, color = Muted)
                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    plan.files.forEach { file -> SelectionContainer { Text("原件：${file.source.path}\n小图：${file.output.path}", fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp)) } }
                }
                if (plan.skipped.isNotEmpty()) {
                    TextButton(onClick = { showSkipped = !showSkipped }) { Text("${plan.skipped.size} 项保留原件 · ${if (showSkipped) "收起" else "查看原因"}", fontSize = 12.sp) }
                    if (showSkipped) Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                        plan.skipped.forEach { Text("${File(it.path).name}：${it.detail}", fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                }
                if (plan.files.isNotEmpty()) Row(Modifier.fillMaxWidth().toggleable(confirmed, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { confirmed = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmed, onCheckedChange = null)
                    Text("我已确认 NAS 备份完成，并检查过小图可正常使用", fontSize = 12.sp)
                }
            }
        }, confirmButton = {
            if (plan.files.isNotEmpty()) TextButton(onClick = { vm.deleteConfirmedOriginals(confirmed) }, enabled = confirmed) { Text("删除原件", color = if (confirmed) MaterialTheme.colorScheme.error else Muted) }
            else TextButton(onClick = vm::dismissCleanup) { Text("知道了") }
        }, dismissButton = { if (plan.files.isNotEmpty()) TextButton(onClick = vm::dismissCleanup) { Text("取消") } })
    }
    if (!vm.cleanupBusy) vm.originalCleanupResults?.let { notes ->
        AlertDialog(onDismissRequest = vm::dismissCleanup, title = { Text("原件清理结果") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已删除 ${notes.count { it.state == "已删除" }} 个 · ${bytes(notes.filter { it.state == "已删除" }.sumOf { it.bytes })}")
                Text("跳过 ${notes.count { it.state == "跳过" }} 个 · 失败 ${notes.count { it.state == "失败" }} 个", fontSize = 12.sp)
                notes.forEach { Text("${File(it.path).name} · ${it.state}\n${it.detail}", fontSize = 11.sp) }
            }
        }, confirmButton = { TextButton(onClick = vm::dismissCleanup) { Text("完成") } })
    }
}
