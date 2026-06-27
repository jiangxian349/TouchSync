/**
 * 局域网设备发现服务（UDP 广播）
 *
 * 协议：
 * - 接收端每 2 秒向 255.255.255.255:29528 发送广播 "TOUCHSYNC:<deviceId>:<deviceName>"
 * - 控制端监听 29528 端口，解析收到的广播，过滤掉自己的广播
 * - 设备 ID 为 8 位 UUID，首次启动时生成并存储到 SharedPreferences
 * - 设备名称默认为 Build.MODEL，可自定义
 *
 * 多设备支持：维护 discoveredDevices Map，UI 通过 devices StateFlow 获取完整列表
 */
package com.touchsync.network

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

class DiscoveryService(
    private val scope: CoroutineScope,
    private val context: Context
) {
    companion object {
        const val TAG = "DiscoveryService"
        const val DISCOVERY_PORT = 29528  // UDP 发现端口
        const val TCP_PORT = 29527         // 关联的 TCP 端口，通知控制端连接
        private const val PREF_NAME = "touchsync_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
    }

    /** 设备信息，包含唯一 ID、显示名称和 IP 地址 */
    data class DiscoveredDevice(
        val deviceId: String,
        val deviceName: String,
        val address: InetAddress,
        val port: Int = TCP_PORT
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 本机唯一 ID，首次启动生成，持久化存储 */
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: run {
        val id = UUID.randomUUID().toString().take(8); prefs.edit().putString(KEY_DEVICE_ID, id).apply(); id
    }

    /** 设备显示名称，默认 Build.MODEL */
    val deviceName: String = prefs.getString(KEY_DEVICE_NAME, null) ?: run {
        val name = Build.MODEL ?: "Android"; prefs.edit().putString(KEY_DEVICE_NAME, name).apply(); name
    }

    fun setDeviceName(name: String) { prefs.edit().putString(KEY_DEVICE_NAME, name).apply() }

    /** 所有已发现的设备列表 */
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    /** 最先发现的设备（用于向后兼容的状态显示） */
    private val _discoveredDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val discoveredDevice: StateFlow<DiscoveredDevice?> = _discoveredDevice.asStateFlow()

    private fun buildBroadcastMsg(): String = "TOUCHSYNC:$deviceId:$deviceName"

    // ==================== 接收端：广播自身 ====================

    private var broadcastJob: Job? = null

    /** 每 2 秒向局域网广播一次自身信息 */
    fun startAdvertising() {
        broadcastJob?.cancel()
        broadcastJob = scope.launch(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val data = buildBroadcastMsg().toByteArray()
                val addr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(data, data.size, addr, DISCOVERY_PORT)
                while (isActive) {
                    try { socket.send(packet) } catch (e: Exception) { Log.w(TAG, "Broadcast failed", e) }
                    delay(2000L)
                }
            }
        }
    }

    fun stopAdvertising() { broadcastJob?.cancel(); broadcastJob = null }

    // ==================== 控制端：监听广播 ====================

    private var discoverJob: Job? = null
    private val discoveredDevices = mutableMapOf<String, DiscoveredDevice>()

    /** 监听 UDP 广播，解析设备信息并更新列表 */
    fun startDiscovery() {
        discoverJob?.cancel()
        discoveredDevices.clear(); _devices.value = emptyList()
        discoverJob = scope.launch(Dispatchers.IO) {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(DISCOVERY_PORT))
                socket.soTimeout = 3000
                val buf = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive) {
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        // 解析 "TOUCHSYNC:deviceId:deviceName" 格式
                        if (msg.startsWith("TOUCHSYNC:")) {
                            val parts = msg.split(":")
                            if (parts.size >= 3) {
                                val remoteId = parts[1]; val remoteName = parts[2]
                                if (remoteId != deviceId) {  // 过滤自己的广播
                                    val device = DiscoveredDevice(remoteId, remoteName, packet.address)
                                    discoveredDevices[remoteId] = device
                                    _devices.value = discoveredDevices.values.toList()
                                    if (_discoveredDevice.value == null) _discoveredDevice.value = device
                                }
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) { /* 超时继续监听 */ }
                    catch (e: Exception) { if (isActive) Log.w(TAG, "Discovery error", e) }
                }
            }
        }
    }

    fun stopDiscovery() {
        discoverJob?.cancel(); discoverJob = null
        discoveredDevices.clear(); _devices.value = emptyList(); _discoveredDevice.value = null
    }
}
