/**
 * 触控同步 - Application 类
 *
 * 职责：
 * 1. 持有全局单例的 TouchRelay（TCP 中继）和 DiscoveryService（设备发现）
 * 2. 持有全局协程作用域 appScope，供服务层和网络层复用
 * 3. 提供 serviceState 和 touchDots 两个 StateFlow，供 UI 层订阅
 * 4. 检测 root 权限
 *
 * 架构说明：
 * - TouchRelay 和 DiscoveryService 在 Application.onCreate 中创建，生命周期跟随进程
 * - Activity 和 Service 通过 (application as TouchSyncApplication) 获取这两个实例
 * - 避免了 Service 绑定异步时序问题
 */
package com.touchsync

import android.app.Application
import android.content.Context
import android.util.Log
import com.touchsync.network.DiscoveryService
import com.touchsync.network.TouchRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 触摸轨迹数据点，用于接收端可视化反馈 */
data class TouchDot(
    val x: Float,
    val y: Float,
    val timestamp: Long = System.currentTimeMillis()
)

class TouchSyncApplication : Application() {

    /** 全局协程作用域，IO 调度器，SupervisorJob 保证子协程异常不影响其他协程 */
    val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** TCP 触控中继，同一实例在 Activity（UI）和 Service（网络）间共享 */
    val relay = TouchRelay(appScope)

    /** UDP 设备发现，需要 Context 用于 SharedPreferences 存储设备 ID */
    lateinit var discovery: DiscoveryService

    /** 服务状态文本，UI 通过 collectAsState 订阅 */
    private val _serviceState = MutableStateFlow("\u7a7a\u95f2")
    val serviceState: StateFlow<String> = _serviceState.asStateFlow()

    /** 接收端触摸轨迹点列表，UI 通过 Canvas 绘制 */
    private val _touchDots = MutableStateFlow<List<TouchDot>>(emptyList())
    val touchDots: StateFlow<List<TouchDot>> = _touchDots.asStateFlow()

    /** 由 TouchSyncService 调用，更新服务状态文本 */
    fun setServiceState(state: String) { _serviceState.value = state }

    /** 由 TouchRelay.onEvent 回调调用，记录触摸点用于轨迹显示 */
    fun addTouchDot(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        // 保留最近 2 秒内的点，最多 100 个
        val recent = _touchDots.value
            .filter { now - it.timestamp < 2000 }
            .takeLast(99) + TouchDot(x, y, now)
        _touchDots.value = recent
    }

    /** 是否检测到 root 权限 */
    var hasRoot: Boolean = false; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // DiscoveryService 需要 Context 访问 SharedPreferences，在 onCreate 中创建
        discovery = DiscoveryService(appScope, this)
        checkRootAccess()
        Log.i(TAG, "App started, root=$hasRoot")
    }

    /** 通过执行 su -c id 检测 root 权限 */
    private fun checkRootAccess() {
        hasRoot = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            exitCode == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "Root check failed", e); false
        }
    }

    companion object {
        private const val TAG = "TouchSync"
        lateinit var instance: TouchSyncApplication; private set
    }
}
