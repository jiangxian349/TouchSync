/**
 * 前台 Service：管理触控同步的完整生命周期
 *
 * 双模式架构：
 * - 接收端（MODE_RECEIVER）：启动 TCP 服务器 + UDP 广播 + Native 触控注入器（evdev grab + uinput）
 * - 控制端（MODE_CONTROLLER）：启动 UDP 设备发现，等待用户选择配对
 *
 * 核心流程（接收端）：
 * 1. startReceiver() → 启动 TCP 服务器 + UDP 广播 → 在 appScope 后台协程中初始化
 *    NativeTouchInjector（释放 assets 中的 native 可执行文件并以 su 启动，避免阻塞主线程）
 * 2. relay.startServer() 在 IO 线程监听 TCP 连接
 * 3. discovery.startAdvertising() 每 2 秒广播自身信息
 * 4. 当控制端连接并发送触控事件时，onEvent 回调触发 injector.inject()（写入 native 进程 stdin）
 *
 * 核心流程（控制端）：
 * 1. startController() → discovery.startDiscovery() 监听广播
 * 2. UI 显示设备列表，用户点击"配对" → relay.connectToServer()
 * 3. 用户在触控区滑动 → TouchCapture → relay.sendEvent()
 *
 * 注：不再使用 bindService 获取 relay/discovery 引用，改为通过 TouchSyncApplication 单例直接访问
 */
package com.touchsync.service

import android.app.Notification; import android.app.NotificationChannel; import android.app.NotificationManager
import android.app.PendingIntent; import android.app.Service; import android.content.Intent; import android.os.IBinder
import android.util.Log
import com.touchsync.MainActivity; import com.touchsync.TouchSyncApplication
import com.touchsync.touch.NativeTouchInjector
import kotlinx.coroutines.*

class TouchSyncService : Service() {

    companion object {
        const val TAG = "TouchSyncService"
        const val CHANNEL_ID = "touchsync_channel"; const val NOTIFICATION_ID = 1
        const val EXTRA_MODE = "mode"
        const val MODE_CONTROLLER = "controller"; const val MODE_RECEIVER = "receiver"
    }

    private var injector: NativeTouchInjector? = null
    private var mode: String = MODE_RECEIVER
    private var state: String = "\u7a7a\u95f2"

    override fun onCreate() { super.onCreate(); startForeground(NOTIFICATION_ID, createNotification()) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_MODE)?.let { mode = it }
        when (mode) { MODE_RECEIVER -> startReceiver(); MODE_CONTROLLER -> startController() }
        return START_STICKY
    }

    /** 启动接收端：注册回调 → 启动 TCP 服务器和 UDP 广播 → 后台初始化注入器 */
    private fun startReceiver() {
        state = "\u63a2\u6d4b\u4e2d"; updateState()
        val app = application as TouchSyncApplication
        // 注册触控事件回调：每次收到事件时注入到系统，并记录触摸轨迹
        // 注：injector 在下方协程中异步初始化完成前为 null/未就绪，inject() 内部会自行判断 isReady，
        // 初始化完成前到达的事件会被安全地丢弃，不会崩溃
        app.relay.onEvent = { event -> injector?.inject(event); app.addTouchDot(event.xNorm, event.yNorm) }
        app.relay.startServer(); app.discovery.startAdvertising()
        app.appScope.launch {
            app.relay.connected.collect { connected -> state = if (connected) "\u5df2\u8fde\u63a5" else "\u7b49\u5f85\u8fde\u63a5"; updateState() }
        }
        // NativeTouchInjector.initialize() 内部会释放 assets、fork su 进程并阻塞读取其首行输出
        // （等待 root 授权弹窗 + native 进程完成 evdev grab / uinput 创建），放到 appScope（IO 调度器）
        // 后台协程执行，避免阻塞 Service 所在的主线程导致 ANR
        app.appScope.launch {
            val inj = NativeTouchInjector(this@TouchSyncService, resources.displayMetrics)
            val ok = inj.initialize()
            injector = inj
            state = if (ok) "\u6ce8\u5165\u5668\u5c31\u7eea (native evdev/uinput)" else "\u6ce8\u5165\u5668\u5931\u8d25: ${inj.lastError}"
            updateState()
        }
        Log.i(TAG, "Receiver mode active")
    }

    /** 启动控制端：开始设备发现，等待用户手动配对 */
    private fun startController() {
        state = "\u63a2\u6d4b\u4e2d"; updateState()
        val app = application as TouchSyncApplication; app.discovery.startDiscovery()
        app.appScope.launch {
            app.relay.connected.collect { connected -> state = if (connected) "\u5df2\u8fde\u63a5" else "\u7b49\u5f85\u8fde\u63a5"; updateState() }
        }
        Log.i(TAG, "Controller mode active")
    }

    private fun updateState() { (application as TouchSyncApplication).setServiceState(state) }

    override fun onDestroy() {
        val app = application as TouchSyncApplication
        app.discovery.stopAdvertising(); app.discovery.stopDiscovery(); app.relay.stopServer(); app.relay.disconnectClient()
        injector?.shutdown(); super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 创建前台通知，保持 Service 存活 */
    private fun createNotification(): Notification {
        val ch = NotificationChannel(CHANNEL_ID, "\u89e6\u63a7\u540c\u6b65", NotificationManager.IMPORTANCE_LOW)
        ch.description = "\u670d\u52a1\u8fd0\u884c\u4e2d"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID).setContentTitle("\u89e6\u63a7\u540c\u6b65").setContentText("\u670d\u52a1\u8fd0\u884c\u4e2d").setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pi).setOngoing(true).build()
    }
}
