package com.xk.photoopt

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xk.photoopt.ui.theme.Muted
import com.xk.photoopt.ui.theme.Paper
import com.xk.photoopt.ui.theme.PhotoOptTheme
import com.xk.photoopt.ui.theme.Teal
import java.util.Locale

class QuickLocationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotoOptTheme { QuickLocationScreen(back = { finish() }) } }
    }
}

@Composable
private fun QuickLocationScreen(back: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var location by remember { mutableStateOf<Location?>(null) }
    var note by remember { mutableStateOf("") }
    var locating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var savedLocations by remember { mutableStateOf(SavedLocationStore.load(context)) }

    fun locate() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) { message = "请先开启系统定位服务"; return }
        locating = true; message = null
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { location = it }
        manager.getCurrentLocation(provider, CancellationSignal(), ContextCompat.getMainExecutor(context)) { result ->
            locating = false
            if (result == null) message = "暂时无法获取当前位置，请稍后重试" else location = result
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate() else message = "需要定位权限才能记录当前位置"
    }
    fun requestLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Scaffold(containerColor = Paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, "返回") }
                Column { Text("记录当前位置", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("保存备注，添加照片位置时直接复用", fontSize = 11.sp, color = Muted) }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::requestLocation, enabled = !locating, modifier = Modifier.fillMaxWidth()) {
                    if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.MyLocation, null)
                    Spacer(Modifier.width(8.dp)); Text(if (locating) "正在定位…" else "快速获取当前位置")
                }
                location?.let { current ->
                    Text(String.format(Locale.US, "%.6f, %.6f · 精度约 %.0f 米", current.latitude, current.longitude, current.accuracy), color = Teal, fontSize = 13.sp)
                    OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("位置备注") }, placeholder = { Text("例如：家、公司、海边停车场") }, singleLine = true)
                    Button(onClick = {
                        SavedLocationStore.add(context, note, current.latitude, current.longitude)
                        savedLocations = SavedLocationStore.load(context); note = ""; message = "位置已保存"
                    }, enabled = note.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(8.dp)); Text("保存这个位置")
                    }
                }
                message?.let { Text(it, color = if (it == "位置已保存") Teal else MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                if (savedLocations.isNotEmpty()) {
                    HorizontalDivider(); Text("已保存的位置", fontWeight = FontWeight.SemiBold)
                    savedLocations.forEach { saved ->
                        ListItem(headlineContent = { Text(saved.note) }, supportingContent = { Text(String.format(Locale.US, "%.6f, %.6f", saved.latitude, saved.longitude)) }, trailingContent = {
                            TextButton(onClick = { SavedLocationStore.delete(context, saved.id); savedLocations = SavedLocationStore.load(context) }) { Text("删除") }
                        }, colors = ListItemDefaults.colors(containerColor = Paper))
                    }
                }
            }
        }
    }
}
