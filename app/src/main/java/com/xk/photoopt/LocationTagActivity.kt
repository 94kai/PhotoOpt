package com.xk.photoopt

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.xk.photoopt.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationPhoto(val uri: Uri, val name: String, val hasGps: Boolean)
data class LocationWriteResult(val name: String, val success: Boolean, val detail: String)
data class PlaceResult(val name: String, val latitude: Double, val longitude: Double)

class LocationTagViewModel(application: Application) : AndroidViewModel(application) {
    var photos by mutableStateOf<List<LocationPhoto>>(emptyList()); private set
    var selectedUris by mutableStateOf<Set<Uri>>(emptySet()); private set
    var directoryName by mutableStateOf<String?>(null); private set
    var directorySelected by mutableStateOf(false); private set
    var loadingDirectory by mutableStateOf(false); private set
    var directoryError by mutableStateOf<String?>(null); private set
    var writing by mutableStateOf(false); private set
    var progress by mutableIntStateOf(0); private set
    var results by mutableStateOf<List<LocationWriteResult>>(emptyList()); private set

    fun loadDirectory(treeUri: Uri) {
        if (writing) return
        val resolver = getApplication<Application>().contentResolver
        directorySelected = true
        directoryName = runCatching { queryDisplayName(treeUri) }.getOrNull()
            ?: Uri.decode(DocumentsContract.getTreeDocumentId(treeUri)).substringAfterLast('/').substringAfterLast(':').ifBlank { "已选目录" }
        loadingDirectory = true; directoryError = null; photos = emptyList(); selectedUris = emptySet(); results = emptyList()
        viewModelScope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    val entries = mutableListOf<LocationPhoto>()
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                        val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        check(idIndex >= 0) { "系统文件管理器没有返回文件标识" }
                        while (cursor.moveToNext()) {
                            val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "照片"
                            val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                            if (!mime.startsWith("image/") && !name.substringAfterLast('.', "").lowercase().let { it in setOf("jpg", "jpeg", "png", "webp", "heic", "heif") }) continue
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                            val hasGps = runCatching {
                                resolver.openFileDescriptor(uri, "r")?.use { descriptor -> hasValidGps(ExifInterface(descriptor.fileDescriptor)) } == true
                            }.getOrDefault(false)
                            entries += LocationPhoto(uri, name.ifBlank { "照片" }, hasGps)
                        }
                    } ?: error("无法读取目录")
                    entries.sortedBy { it.name.lowercase() }
                }
            }
            photos = loaded.getOrElse { directoryError = "目录读取失败：${it.message ?: it.javaClass.simpleName}"; emptyList() }
            loadingDirectory = false
        }
    }

    private fun queryDisplayName(uri: Uri): String? = getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    fun toggle(uri: Uri) { selectedUris = if (uri in selectedUris) selectedUris - uri else selectedUris + uri }
    fun selectAll(items: List<LocationPhoto>) { selectedUris = selectedUris + items.map { it.uri } }
    fun clearSelection() { selectedUris = emptySet() }
    fun clearResults() { results = emptyList() }
    fun directorySelectionFailed() { directoryError = "没有收到目录授权，请重新选择并点击“允许”" }

    fun write(latitude: Double, longitude: Double) {
        val selectedPhotos = photos.filter { it.uri in selectedUris }
        if (writing || selectedPhotos.isEmpty()) return
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            results = selectedPhotos.map { LocationWriteResult(it.name, false, "坐标无效，未写入") }
            return
        }
        writing = true; progress = 0; results = emptyList()
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val completed = mutableListOf<LocationWriteResult>()
            withContext(Dispatchers.IO) {
                val succeededUris = mutableSetOf<Uri>()
                selectedPhotos.forEach { photo ->
                    val outcome = runCatching {
                        resolver.openFileDescriptor(photo.uri, "rw")?.use { descriptor ->
                            ExifInterface(descriptor.fileDescriptor).apply {
                                // 部分相册会留下值为 0/0 的损坏 GPS 标签，先清除再重建标准 GPS 目录。
                                setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
                                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
                                setLatLong(latitude, longitude)
                                setAttribute(ExifInterface.TAG_GPS_VERSION_ID, "2.3.0.0")
                                setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "MANUAL")
                                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                                setAttribute(ExifInterface.TAG_GPS_DATESTAMP, String.format(Locale.US, "%04d:%02d:%02d", utc.get(Calendar.YEAR), utc.get(Calendar.MONTH) + 1, utc.get(Calendar.DAY_OF_MONTH)))
                                setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, String.format(Locale.US, "%02d:%02d:%02d", utc.get(Calendar.HOUR_OF_DAY), utc.get(Calendar.MINUTE), utc.get(Calendar.SECOND)))
                                saveAttributes()
                            }
                        } ?: error("无法打开文件")
                        val verification = resolver.openFileDescriptor(photo.uri, "r")?.use { descriptor ->
                            val exif = ExifInterface(descriptor.fileDescriptor)
                            val actual = exif.latLong ?: error("GPS 回读为空")
                            val latitudeValue = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE).orEmpty()
                            val longitudeValue = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE).orEmpty()
                            val latitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF).orEmpty()
                            val longitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF).orEmpty()
                            check(actual.size == 2 && actual.all { it.isFinite() }) { "GPS 回读值无效" }
                            check(latitudeValue.isNotBlank() && longitudeValue.isNotBlank() && "/0" !in latitudeValue && "/0" !in longitudeValue) { "GPS 标签格式无效" }
                            check(latitudeRef in setOf("N", "S") && longitudeRef in setOf("E", "W")) { "GPS 方向标记无效" }
                            actual
                        } ?: error("无法回读文件")
                        check(abs(verification[0] - latitude) < 0.00001 && abs(verification[1] - longitude) < 0.00001) { "GPS 回读校验失败" }
                        resolver.notifyChange(photo.uri, null)
                        succeededUris += photo.uri
                        LocationWriteResult(photo.name, true, String.format(Locale.US, "已写入 %.6f, %.6f", verification[0], verification[1]))
                    }.getOrElse { error -> LocationWriteResult(photo.name, false, error.message ?: "写入失败") }
                    completed += outcome
                    withContext(Dispatchers.Main) { results = completed.toList(); progress = completed.size }
                }
                withContext(Dispatchers.Main) {
                    photos = photos.map { if (it.uri in succeededUris) it.copy(hasGps = true) else it }
                    selectedUris = selectedUris - succeededUris
                }
            }
            writing = false
        }
    }
}

private fun hasValidGps(exif: ExifInterface): Boolean {
    val point = exif.latLong ?: return false
    if (point.size != 2 || point.any { !it.isFinite() }) return false
    val latitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE).orEmpty()
    val longitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE).orEmpty()
    val latitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF).orEmpty()
    val longitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF).orEmpty()
    return latitude.isNotBlank() && longitude.isNotBlank() && "/0" !in latitude && "/0" !in longitude && latitudeRef in setOf("N", "S") && longitudeRef in setOf("E", "W")
}

class LocationTagActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("location-map", MODE_PRIVATE)
        if (BuildConfig.AMAP_API_KEY.isNotBlank() && preferences.getBoolean("amap-privacy-agreed", false)) configureAmapPrivacy()
        setContent {
            PhotoOptTheme {
                var privacyAgreed by remember { mutableStateOf(preferences.getBoolean("amap-privacy-agreed", false)) }
                when {
                    BuildConfig.AMAP_API_KEY.isBlank() -> MissingAmapKeyScreen(back = { finish() })
                    privacyAgreed -> LocationTagScreen(back = { finish() })
                    else -> AmapPrivacyScreen(back = { finish() }, agree = {
                        preferences.edit().putBoolean("amap-privacy-agreed", true).apply()
                        configureAmapPrivacy()
                        privacyAgreed = true
                    })
                }
            }
        }
    }

    private fun configureAmapPrivacy() {
        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        ServiceSettings.updatePrivacyShow(applicationContext, true, true)
        ServiceSettings.updatePrivacyAgree(applicationContext, true)
    }
}

@Composable
private fun MissingAmapKeyScreen(back: () -> Unit) {
    Scaffold(containerColor = Paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Map, null, tint = Teal, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(14.dp)); Text("尚未配置高德地图 Key", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); Text("提供 Key 后重新打包即可使用地图、地点搜索和位置写入。", color = Muted)
            Spacer(Modifier.height(18.dp)); Button(onClick = back) { Text("返回") }
        }
    }
}

@Composable
private fun AmapPrivacyScreen(back: () -> Unit, agree: () -> Unit) {
    val context = LocalContext.current
    Scaffold(containerColor = Paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("使用高德地图服务", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("位置选择将使用高德地图 SDK 加载地图和搜索地点。使用过程中，高德可能按照其隐私政策处理设备、网络与位置信息。只有你同意后才会初始化高德 SDK。", color = Muted, lineHeight = 22.sp)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/"))) }) { Text("查看高德开放平台隐私政策") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = agree, modifier = Modifier.fillMaxWidth()) { Text("同意并使用地图") }
            TextButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("不同意，返回") }
        }
    }
}

@Composable
private fun LocationTagScreen(back: () -> Unit, vm: LocationTagViewModel = viewModel()) {
    val context = LocalContext.current
    var recentDirectories by remember { mutableStateOf(RecentDirectoryStore.load(context)) }
    var map by remember { mutableStateOf<AMap?>(null) }
    var selectedPoint by remember { mutableStateOf(LatLng(39.908823, 116.397470)) }
    var selectedName by remember { mutableStateOf("地图中心位置") }
    var pendingName by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var poiResults by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var lastSearchAt by remember { mutableLongStateOf(0L) }
    var locating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var confirmWrite by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    var onlyWithoutGps by remember { mutableStateOf(false) }
    val savedLocations = remember { SavedLocationStore.load(context) }
    var savedLocationMenu by remember { mutableStateOf(false) }

    fun moveTo(point: LatLng, zoom: Double = 17.0, name: String = "地图中心位置") {
        pendingName = name
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, zoom.toFloat()))
    }

    fun locateCurrent() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        locating = true; locationError = null
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) { locating = false; locationError = "请先开启系统定位服务"; return }
        val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        if (last != null) moveToGps(last, ::moveTo)
        manager.getCurrentLocation(provider, CancellationSignal(), ContextCompat.getMainExecutor(context)) { location: Location? ->
            locating = false
            if (location == null) locationError = "暂时无法获取当前位置，请稍后重试"
            else moveToGps(location, ::moveTo)
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locateCurrent() else locationError = "需要定位权限才能使用当前位置"
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.loadDirectory(uri)
            val directoryName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull() ?: Uri.decode(DocumentsContract.getTreeDocumentId(uri)).substringAfterLast('/').substringAfterLast(':').ifBlank { "照片目录" }
            recentDirectories = RecentDirectoryStore.add(context, uri.toString(), directoryName)
            showMap = false
        } else vm.directorySelectionFailed()
    }

    fun searchPoi() {
        val keyword = search.trim()
        if (keyword.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastSearchAt < 1_000) { searchError = "搜索过于频繁，请稍后再试"; return }
        lastSearchAt = now
        searching = true; searchError = null; poiResults = emptyList()
        runCatching {
            Inputtips(context, InputtipsQuery(keyword, "")).apply {
                setInputtipsListener { tips, code ->
                    searching = false
                    if (code != 1000) { searchError = "地点搜索失败（$code）"; return@setInputtipsListener }
                    poiResults = tips.orEmpty().mapNotNull { tip ->
                        tip.point?.let { point -> PlaceResult(listOf(tip.name, tip.district).filter { it.isNotBlank() }.joinToString(" · "), point.latitude, point.longitude) }
                    }.distinctBy { "${it.latitude},${it.longitude}" }
                    if (poiResults.isEmpty()) searchError = "没有找到相关地点"
                }
                requestInputtipsAsyn()
            }
        }.onFailure { searching = false; searchError = it.message ?: "地点搜索失败" }
    }

    BackHandler { if (!vm.writing) { if (showMap) showMap = false else back() } }
    Scaffold(containerColor = Paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (showMap) showMap = false else back() }, enabled = !vm.writing) { Icon(Icons.Rounded.ArrowBack, "返回") }
                Column(Modifier.weight(1f)) {
                    Text("批量添加位置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (showMap) "选定地点后写入已选照片" else "选择目录并勾选需要修改的照片", fontSize = 11.sp, color = Muted)
                }
                if (!showMap) TextButton(onClick = { directoryPicker.launch(null) }, enabled = !vm.writing && !vm.loadingDirectory) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (vm.directoryName == null) "选目录" else "换目录")
                }
            }
            if (!showMap) {
                DirectoryPhotoSelector(
                    vm = vm,
                    onlyWithoutGps = onlyWithoutGps,
                    onFilterChange = { onlyWithoutGps = it },
                    chooseDirectory = { directoryPicker.launch(null) },
                    recentDirectories = recentDirectories,
                    openDirectory = { directory ->
                        vm.loadDirectory(Uri.parse(directory.uri))
                        recentDirectories = RecentDirectoryStore.touch(context, directory.uri)
                    },
                    deleteDirectory = { directory ->
                        recentDirectories = RecentDirectoryStore.delete(context, directory.uri)
                        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(directory.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    },
                    next = { showMap = true }
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AMapContent(onReady = { ready ->
                        map = ready
                        ready.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                            override fun onCameraChange(position: com.amap.api.maps.model.CameraPosition?) = Unit
                            override fun onCameraChangeFinish(position: com.amap.api.maps.model.CameraPosition?) {
                                position?.target?.let { point ->
                                selectedPoint = point
                                selectedName = pendingName ?: "地图中心位置"
                                pendingName = null
                            }
                            }
                        })
                    })
                    Icon(Icons.Rounded.LocationOn, null, tint = Color(0xFFE6513E), modifier = Modifier.align(Alignment.Center).size(40.dp).offset(y = (-20).dp))
                    Column(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                        Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 5.dp, color = Color.White) {
                            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Search, null, tint = Muted)
                                OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("搜索地点、道路或建筑") },
                                    singleLine = true, modifier = Modifier.weight(1f), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { searchPoi() }))
                                IconButton(onClick = { searchPoi() }) { if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Search, "搜索") }
                            }
                        }
                        searchError?.let { Text(it, Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                        if (poiResults.isNotEmpty()) Surface(Modifier.fillMaxWidth().heightIn(max = 240.dp), shape = RoundedCornerShape(16.dp), shadowElevation = 5.dp) {
                            LazyColumn {
                                items(poiResults, key = { "${it.latitude},${it.longitude},${it.name}" }) { poi ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        poiResults = emptyList(); search = poi.name
                                        moveTo(LatLng(poi.latitude, poi.longitude), name = poi.name)
                                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Place, null, tint = Teal); Spacer(Modifier.width(10.dp))
                                        Text(poi.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    FloatingActionButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locateCurrent()
                        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = Color.White) {
                        if (locating) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.MyLocation, "当前位置", tint = Teal)
                    }
                }
                Surface(color = Color.White, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (savedLocations.isNotEmpty()) Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { savedLocationMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Bookmark, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("使用已保存的位置")
                            }
                            DropdownMenu(expanded = savedLocationMenu, onDismissRequest = { savedLocationMenu = false }, modifier = Modifier.fillMaxWidth(.9f)) {
                                savedLocations.forEach { saved ->
                                    DropdownMenuItem(text = {
                                        Column { Text(saved.note); Text(String.format(Locale.US, "%.6f, %.6f", saved.latitude, saved.longitude), fontSize = 10.sp, color = Muted) }
                                    }, onClick = {
                                        savedLocationMenu = false
                                        moveTo(wgs84ToGcj02(LatLng(saved.latitude, saved.longitude)), name = saved.note)
                                    }, leadingIcon = { Icon(Icons.Rounded.Place, null, tint = Teal) })
                                }
                            }
                        }
                        Text(selectedName, fontWeight = FontWeight.SemiBold)
                        val wgsPoint = gcj02ToWgs84(selectedPoint)
                        Text("${"%.6f".format(wgsPoint.latitude)}, ${"%.6f".format(wgsPoint.longitude)} · 已选 ${vm.selectedUris.size} 张照片", fontSize = 11.sp, color = Muted)
                        Text("地图与搜索服务由高德提供", fontSize = 10.sp, color = Teal)
                        locationError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                        if (vm.writing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在写入 ${vm.progress} / ${vm.selectedUris.size}", fontSize = 12.sp) }
                        Button(onClick = { confirmWrite = true }, enabled = !vm.writing && vm.selectedUris.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.AddLocationAlt, null)
                            Spacer(Modifier.width(8.dp)); Text("给 ${vm.selectedUris.size} 张照片添加此位置")
                        }
                    }
                }
            }
        }
    }

    if (confirmWrite) AlertDialog(onDismissRequest = { confirmWrite = false }, title = { Text("确认修改原照片？") }, text = {
        Text("将把“$selectedName”的 GPS 坐标写入 ${vm.selectedUris.size} 张照片，已有位置会被替换。此操作直接修改所选原文件，建议先确认已有备份。")
    }, confirmButton = { TextButton(onClick = { confirmWrite = false; val point = gcj02ToWgs84(selectedPoint); vm.write(point.latitude, point.longitude) }) { Text("确认写入") } }, dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text("返回") } })

    if (!vm.writing && vm.results.isNotEmpty()) {
        val succeeded = vm.results.count { it.success }
        AlertDialog(onDismissRequest = { vm.clearResults(); showMap = false }, title = { Text("位置写入完成") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("成功 $succeeded 张，失败 ${vm.results.size - succeeded} 张")
                vm.results.filter { it.success }.take(8).forEach { Text("${it.name}：${it.detail}", fontSize = 12.sp, color = Teal) }
                vm.results.filterNot { it.success }.take(8).forEach { Text("${it.name}：${it.detail}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { vm.clearResults(); showMap = false }) { Text("完成") } })
    }
}

@Composable
private fun DirectoryPhotoSelector(
    vm: LocationTagViewModel,
    onlyWithoutGps: Boolean,
    onFilterChange: (Boolean) -> Unit,
    chooseDirectory: () -> Unit,
    recentDirectories: List<RecentDirectory>,
    openDirectory: (RecentDirectory) -> Unit,
    deleteDirectory: (RecentDirectory) -> Unit,
    next: () -> Unit
) {
    val visible = remember(vm.photos, onlyWithoutGps) { if (onlyWithoutGps) vm.photos.filterNot { it.hasGps } else vm.photos }
    Column(Modifier.fillMaxSize()) {
        if (!vm.directorySelected && !vm.loadingDirectory) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Teal, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp)); Text("先选择照片目录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp)); Text("会读取目录内的图片和 GPS 状态，不会修改文件。", color = Muted)
                Spacer(Modifier.height(16.dp)); Button(onClick = chooseDirectory, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(7.dp)); Text("选择新目录") }
                if (recentDirectories.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp)); Text("历史目录", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                    Text("点击快速打开；删除只移除记录，不会删除照片或文件夹。", modifier = Modifier.fillMaxWidth(), fontSize = 10.sp, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentDirectories, key = { it.uri }) { directory ->
                            Surface(onClick = { openDirectory(directory) }, shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
                                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Folder, null, tint = Teal); Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(directory.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text(Uri.decode(directory.uri).substringAfterLast('/').substringAfterLast(':'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = Muted)
                                    }
                                    IconButton(onClick = { deleteDirectory(directory) }) { Icon(Icons.Rounded.DeleteOutline, "删除目录记录", tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
                vm.directoryError?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
            return
        }
        if (vm.loadingDirectory) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("正在读取目录中的照片和位置信息…", color = Muted)
            }
            return
        }
        vm.directoryError?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.directoryName ?: "已选目录", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("共 ${vm.photos.size} 张 · 无位置 ${vm.photos.count { !it.hasGps }} 张 · 已选 ${vm.selectedUris.size} 张", fontSize = 12.sp, color = Muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = onlyWithoutGps, onClick = { onFilterChange(!onlyWithoutGps) }, label = { Text("只看无位置") }, leadingIcon = if (onlyWithoutGps) {{ Icon(Icons.Rounded.FilterAlt, null, Modifier.size(18.dp)) }} else null)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.selectAll(visible) }, enabled = visible.isNotEmpty()) { Text("全选当前") }
                TextButton(onClick = vm::clearSelection, enabled = vm.selectedUris.isNotEmpty()) { Text("清空") }
            }
        }
        HorizontalDivider()
        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (onlyWithoutGps && vm.photos.isNotEmpty()) "这个目录中的照片都有位置信息" else "目录中没有可读取的照片", color = Muted)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.uri.toString() }) { photo ->
                    val selected = photo.uri in vm.selectedUris
                    Row(Modifier.fillMaxWidth().clickable { vm.toggle(photo.uri) }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhotoThumbnail(photo.uri)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(photo.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            Text(if (photo.hasGps) "已有位置信息" else "无位置信息", fontSize = 11.sp, color = if (photo.hasGps) Teal else Muted)
                        }
                        Checkbox(checked = selected, onCheckedChange = null)
                    }
                    HorizontalDivider(Modifier.padding(start = 78.dp), color = Color(0xFFEAE8E1))
                }
            }
        }
        Surface(color = Color.White, shadowElevation = 8.dp) {
            Button(onClick = next, enabled = vm.selectedUris.isNotEmpty(), modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                Text(if (vm.selectedUris.isEmpty()) "请选择照片" else "下一步：给 ${vm.selectedUris.size} 张照片选位置")
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(uri: Uri) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, Size(144, 144), null) }.getOrNull() }
    }
    Surface(Modifier.size(56.dp), shape = RoundedCornerShape(8.dp), color = Color(0xFFE8E6DF)) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Image, null, tint = Muted) }
    }
}

@Composable
private fun AMapContent(onReady: (AMap) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    AndroidView(factory = {
        mapView.apply {
            map.uiSettings.isZoomControlsEnabled = false
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.908823, 116.397470), 10f))
            onReady(map)
        }
    }, modifier = Modifier.fillMaxSize())
    DisposableEffect(lifecycle, mapView) {
        var resumed = false
        var destroyed = false
        fun resume() { if (!resumed && !destroyed) { mapView.onResume(); resumed = true } }
        fun pause() { if (resumed && !destroyed) { mapView.onPause(); resumed = false } }
        fun destroy() { if (!destroyed) { pause(); mapView.onDestroy(); destroyed = true } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_DESTROY -> destroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // Compose 进入时 Activity 通常已经处于 resumed，需主动补齐此前的生命周期事件。
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose { lifecycle.removeObserver(observer); destroy() }
    }
}

private fun moveToGps(location: Location, move: (LatLng, Double, String) -> Unit) {
    move(wgs84ToGcj02(LatLng(location.latitude, location.longitude)), 17.0, "当前位置")
}

internal fun wgs84ToGcj02(point: LatLng): LatLng {
    if (outsideChina(point.latitude, point.longitude)) return point
    var latitudeOffset = transformLatitude(point.longitude - 105.0, point.latitude - 35.0)
    var longitudeOffset = transformLongitude(point.longitude - 105.0, point.latitude - 35.0)
    val radians = point.latitude / 180.0 * Math.PI
    var magic = sin(radians)
    magic = 1 - 0.006693421622965943 * magic * magic
    val root = sqrt(magic)
    latitudeOffset = latitudeOffset * 180.0 / ((6378245.0 * (1 - 0.006693421622965943)) / (magic * root) * Math.PI)
    longitudeOffset = longitudeOffset * 180.0 / (6378245.0 / root * cos(radians) * Math.PI)
    return LatLng(point.latitude + latitudeOffset, point.longitude + longitudeOffset)
}

private fun gcj02ToWgs84(point: LatLng): LatLng {
    if (outsideChina(point.latitude, point.longitude)) return point
    var latitude = point.latitude
    var longitude = point.longitude
    repeat(4) {
        val projected = wgs84ToGcj02(LatLng(latitude, longitude))
        latitude -= projected.latitude - point.latitude
        longitude -= projected.longitude - point.longitude
    }
    return LatLng(latitude, longitude)
}

private fun outsideChina(latitude: Double, longitude: Double) = longitude !in 72.004..137.8347 || latitude !in 0.8293..55.8271

private fun transformLatitude(x: Double, y: Double): Double {
    var value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
    value += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    value += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
    value += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
    return value
}

private fun transformLongitude(x: Double, y: Double): Double {
    var value = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
    value += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    value += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
    value += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
    return value
}
