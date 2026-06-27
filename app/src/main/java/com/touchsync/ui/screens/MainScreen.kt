/**
 * 主界面：全屏双模式 UI
 *
 * 四种显示状态：
 * 1. 控制端未连接 → Material 3 设备列表（蓝牙配对风格） + 模式切换
 * 2. 控制端已连接 → 全屏纯黑 OLED 触摸面 + 沉浸模式（隐藏状态栏/导航栏）
 * 3. 接收端未连接 → 等待配对界面
 * 4. 接收端已连接 → 全屏暗色背景 + Canvas 白色触控轨迹（2 秒渐隐）+ 注入状态文字
 *
 * 注：接收端已连接时，用户按 Home 键切换到其他应用，注入的触控事件会作用于前台应用
 */
package com.touchsync.ui.screens

import android.app.Activity; import android.util.DisplayMetrics; import android.view.WindowManager
import androidx.compose.foundation.Canvas; import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*; import androidx.compose.foundation.lazy.LazyColumn; import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons; import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*; import androidx.compose.runtime.*; import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier; import androidx.compose.ui.geometry.Offset; import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput; import androidx.compose.ui.platform.LocalContext; import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight; import androidx.compose.ui.unit.dp; import androidx.compose.ui.unit.sp
import com.touchsync.TouchSyncApplication; import com.touchsync.network.DiscoveryService; import com.touchsync.network.TouchRelay
import com.touchsync.service.TouchSyncService; import com.touchsync.touch.TouchCapture
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mode: String,
    onModeChange: (String) -> Unit,
    relay: TouchRelay,
    discovery: DiscoveryService,
    displayMetrics: DisplayMetrics
) {
    val context = LocalContext.current; val app = context.applicationContext as TouchSyncApplication
    val connected by relay.connected.collectAsState()
    val serviceState by app.serviceState.collectAsState()
    val devices by discovery.devices.collectAsState()

    val isController = mode == TouchSyncService.MODE_CONTROLLER
    val touchCapture = remember(relay, displayMetrics) { TouchCapture(relay, displayMetrics) }

    // 动画帧定时器（用于触控轨迹渐隐）
    var frame by remember { mutableStateOf(0L) }; val dots by app.touchDots.collectAsState()
    LaunchedEffect(Unit) { while (true) { frame = System.currentTimeMillis(); delay(16) } }

    // ==================== 控制端已连接：全屏纯黑 OLED 触摸面 ====================
    if (isController && connected) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            val w = (context as Activity).window; w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 沉浸模式：隐藏状态栏和导航栏
            view.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset -> touchCapture.onDown(offset.x, offset.y) },
                onDrag = { change, _ -> touchCapture.onMove(change.position.x, change.position.y) },
                onDragEnd = { touchCapture.onUp() }, onDragCancel = { touchCapture.onUp() }
            )
        })
        return
    }

    // ==================== 接收端已连接：全屏暗色背景 + 轨迹 ====================
    if (!isController && connected) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000))) {
            val now = frame
            Canvas(Modifier.fillMaxSize()) {
                for (dot in dots) {
                    val age = (now - dot.timestamp).coerceAtLeast(0)
                    val a = ((2000f - age) / 2000f).coerceIn(0f, 1f)
                    if (a > 0f) drawCircle(Color.White.copy(alpha = a * 0.7f), 14f, Offset(dot.x * size.width, dot.y * size.height))
                }
            }
            // 顶部状态文字
            Text(serviceState, color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            // 底部提示
            Text("\u6309Home\u5207\u6362\u5230\u5176\u4ed6\u5e94\u7528\u4ee5\u5168\u5c40\u63a7\u5236", color = Color.White.copy(alpha = 0.25f), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        }
        return
    }

    // ==================== 未连接：Material 3 配对界面 ====================
    Scaffold(topBar = { TopAppBar(title = { Text("\u89e6\u63a7\u540c\u6b65", fontWeight = FontWeight.SemiBold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 模式切换卡片
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("\u8fd0\u884c\u6a21\u5f0f", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ir = mode == TouchSyncService.MODE_RECEIVER
                        FilterChip(selected = ir, onClick = { onModeChange(TouchSyncService.MODE_RECEIVER) }, label = { Text("\u63a5\u6536\u7aef") }, leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp)) }, modifier = Modifier.weight(1f))
                        FilterChip(selected = isController, onClick = { onModeChange(TouchSyncService.MODE_CONTROLLER) }, label = { Text("\u63a7\u5236\u7aef") }, leadingIcon = { Icon(Icons.Rounded.TouchApp, null, Modifier.size(18.dp)) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            // 状态卡片
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) { Text("\u8fde\u63a5\u72b6\u6001", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(8.dp)); Text(serviceState, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
            }
            // 设备列表 / 等待界面
            Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                if (isController) {
                    if (devices.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.Search, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)); Spacer(Modifier.height(8.dp)); Text("\u641c\u7d22\u8bbe\u5907\u4e2d...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                        }
                    } else {
                        Column(Modifier.padding(12.dp)) {
                            Text("\u53ef\u7528\u8bbe\u5907", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(8.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(devices, key = { it.deviceId }) { device ->
                                    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
                                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) { Text(device.deviceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium); Text(device.address.hostAddress ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            Button(onClick = { relay.connectToServer(device.address) }) { Text("\u914d\u5bf9") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.Bluetooth, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)); Spacer(Modifier.height(12.dp)); Text("\u7b49\u5f85\u63a7\u5236\u7aef\u914d\u5bf9...", fontSize = 16.sp, fontWeight = FontWeight.Medium); Text("\u8bf7\u5728\u63a7\u5236\u7aef\u9009\u62e9\u672c\u8bbe\u5907\u8fdb\u884c\u914d\u5bf9", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
        }
    }
}
