package com.xk.photoopt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.services.core.ServiceSettings
import com.xk.photoopt.ui.theme.Muted
import com.xk.photoopt.ui.theme.Paper
import com.xk.photoopt.ui.theme.PhotoOptTheme
import com.xk.photoopt.ui.theme.Teal
import java.util.Locale

class SavedLocationMapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val latitude = intent.getDoubleExtra("latitude", Double.NaN)
        val longitude = intent.getDoubleExtra("longitude", Double.NaN)
        val note = intent.getStringExtra("note").orEmpty().ifBlank { "已保存的位置" }
        val preferences = getSharedPreferences("location-map", MODE_PRIVATE)
        if (preferences.getBoolean("amap-privacy-agreed", false)) configureAmapPrivacy()
        setContent {
            PhotoOptTheme {
                var agreed by remember { mutableStateOf(preferences.getBoolean("amap-privacy-agreed", false)) }
                if (agreed) SavedLocationMapScreen(latitude, longitude, note, back = { finish() })
                else MapPrivacyScreen(back = { finish() }, agree = {
                    preferences.edit().putBoolean("amap-privacy-agreed", true).apply()
                    configureAmapPrivacy(); agreed = true
                })
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
private fun MapPrivacyScreen(back: () -> Unit, agree: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("使用内置高德地图", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp)); Text("查看已保存位置需要在应用内加载高德地图。只有同意后才会初始化地图 SDK。", color = Muted)
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/"))) }) { Text("查看高德开放平台隐私政策") }
        Button(onClick = agree, modifier = Modifier.fillMaxWidth()) { Text("同意并查看") }
        TextButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

@Composable
private fun SavedLocationMapScreen(latitude: Double, longitude: Double, note: String, back: () -> Unit) {
    val valid = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) { Text(note, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text("内置地图", fontSize = 11.sp, color = Muted) }
        }
        if (!valid) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("位置坐标无效", color = MaterialTheme.colorScheme.error) }
        else {
            val point = wgs84ToGcj02(LatLng(latitude, longitude))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SavedPointMap(point, note)
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).navigationBarsPadding(), shape = MaterialTheme.shapes.large, color = Paper, shadowElevation = 5.dp) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Map, null, tint = Teal); Spacer(Modifier.width(8.dp)); Text(note, fontWeight = FontWeight.SemiBold) }
                        Text(String.format(Locale.US, "%.6f, %.6f", latitude, longitude), fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedPointMap(point: LatLng, note: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    AndroidView(factory = {
        mapView.apply {
            map.uiSettings.isZoomControlsEnabled = false
            map.addMarker(MarkerOptions().position(point).title(note))
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 17f))
        }
    }, modifier = Modifier.fillMaxSize())
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose { lifecycle.removeObserver(observer); mapView.onPause(); mapView.onDestroy() }
    }
}
