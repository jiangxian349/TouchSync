/**
 * 主 Activity：入口界面 + 权限管理 + 双模式 UI 切换
 *
 * 架构：
 * - 不通过 bindService 获取服务引用，改为直接从 TouchSyncApplication 取 relay/discovery
 * - 连接状态通过 relay.connected StateFlow 驱动 UI 切换
 * - 三种权限：悬浮窗（控制端全屏触摸需要）、通知（前台 Service）、root（接收端注入需要）
 *
 * UI 状态机：
 * - 未连接 + 控制端 → 显示设备列表，点击"配对"建立 TCP 连接
 * - 未连接 + 接收端 → 显示等待配对界面
 * - 已连接 + 控制端 → 全屏纯黑 OLED 省电触摸面 + 沉浸模式
 * - 已连接 + 接收端 → 全屏暗色背景 + 白色触控轨迹 + 注入状态文字
 */
package com.touchsync

import android.Manifest; import android.content.Intent; import android.content.pm.PackageManager
import android.net.Uri; import android.os.Build; import android.os.Bundle; import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity; import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue; import androidx.compose.runtime.mutableStateOf; import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.touchsync.service.TouchSyncService
import com.touchsync.ui.screens.MainScreen; import com.touchsync.ui.theme.TouchSyncTheme

class MainActivity : ComponentActivity() {

    private var serviceMode by mutableStateOf(TouchSyncService.MODE_RECEIVER)

    // 悬浮窗权限回调
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startAndShow()
        else Toast.makeText(this, "\u9700\u8981\u60ac\u6d6e\u7a97\u6743\u9650\u4ee5\u6355\u83b7\u89e6\u6478", Toast.LENGTH_LONG).show()
    }
    // 通知权限回调（Android 13+）
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAndShow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); requestPermissions()
    }

    /** 依次检查通知权限和悬浮窗权限 */
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return }
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:com.touchsync"))); return
        }
        startAndShow()
    }

    /** 启动前台 Service + 渲染 Compose UI */
    private fun startAndShow() {
        ContextCompat.startForegroundService(this, Intent(this, TouchSyncService::class.java).apply { putExtra(TouchSyncService.EXTRA_MODE, serviceMode) })
        val app = application as TouchSyncApplication
        setContent {
            TouchSyncTheme {
                MainScreen(
                    mode = serviceMode,
                    onModeChange = { newMode -> serviceMode = newMode; stopService(Intent(this, TouchSyncService::class.java)); startAndShow() },
                    relay = app.relay, discovery = app.discovery, displayMetrics = resources.displayMetrics
                )
            }
        }
    }
}
