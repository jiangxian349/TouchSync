/**
 * TCP 触控事件中继
 *
 * 架构：Channel 消息队列 + IO 协程独占 Socket
 * - 发送端（控制端）：sendEvent() 将 TouchRelay.Event 投递到 Channel
 * - IO 协程：从 Channel 消费事件，序列化为 6 字节二进制帧写入 Socket
 * - 接收端（被控端）：ServerSocket.accept() 接受连接，DataInputStream.readFully 读取帧
 * - 解析后通过 onEvent 回调通知 TouchSyncService
 *
 * 二进制帧格式（6 字节，大端序）：
 *   [action:1B] [x_norm:2B] [y_norm:2B] [pressure:1B]
 *   action: 0=DOWN, 1=MOVE, 2=UP
 *   x_norm, y_norm: uint16, 0-65535 映射到 0.0-1.0 归一化坐标
 *
 * Channel 架构解决了之前多线程竞态导致的 Socket 提前关闭问题：
 * - connectToServer 创建的 Socket 只在 IO 协程中访问
 * - sendEvent 非阻塞，仅投递消息
 */
package com.touchsync.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TouchRelay(private val scope: CoroutineScope) {
    companion object {
        const val TAG = "TouchRelay"
        const val PORT = 29527  // TCP 端口
    }

    /** 触控事件密封类，归一化坐标 xNorm/yNorm 范围 0.0-1.0 */
    sealed class Event(val xNorm: Float, val yNorm: Float) {
        data class Down(val x: Float, val y: Float, val pressure: Float) : Event(x, y)
        data class Move(val x: Float, val y: Float, val pressure: Float) : Event(x, y)
        data class Up(val x: Float, val y: Float) : Event(x, y)
    }

    /** 连接状态，UI 通过 collectAsState 订阅 */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // ==================== 服务端（接收端） ====================

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    /** 收到触摸事件时的回调，由 TouchSyncService 注册 */
    var onEvent: ((Event) -> Unit)? = null

    /** 启动 TCP 服务器，在 IO 线程上阻塞监听 */
    fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(PORT).also { it.reuseAddress = true }
                Log.i(TAG, "TCP server listening on $PORT")
                while (isActive) {
                    val client = serverSocket!!.accept()
                    Log.i(TAG, "Client connected: ${client.inetAddress}")
                    _connected.value = true
                    handleClient(client)  // 阻塞处理单个客户端
                    _connected.value = false
                }
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "Server error", e)
            }
        }
    }

    fun stopServer() {
        serverJob?.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null; _connected.value = false
    }

    // ==================== 客户端（控制端）= Channel 消息队列 ====================

    /** 发送队列，sendEvent 投递，IO 协程消费 */
    private val sendChannel = Channel<Event>(Channel.UNLIMITED)
    private var clientJob: Job? = null

    /** 连接到接收端，启动 IO 协程消费 Channel 并写入 Socket */
    fun connectToServer(address: InetAddress) {
        disconnectClient()
        clientJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(address, PORT).also {
                    it.tcpNoDelay = true   // 禁用 Nagle 算法，降低延迟
                    it.soTimeout = 0       // 无限超时
                    it.keepAlive = true    // TCP KeepAlive
                }
                val output = DataOutputStream(socket.getOutputStream())
                _connected.value = true
                Log.i(TAG, "Connected to receiver: $address")
                // 持续从 Channel 消费事件并写入 Socket
                for (event in sendChannel) {
                    output.write(buildBuffer(event))
                    output.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client connection ended: ${e.message}")
            } finally { _connected.value = false }
        }
    }

    /** 非阻塞投递触控事件到发送队列 */
    fun sendEvent(event: Event) { sendChannel.trySend(event) }

    /** 断开客户端连接，取消协程并清空队列 */
    fun disconnectClient() {
        clientJob?.cancel()
        while (sendChannel.tryReceive().isSuccess) { /* 排空 Channel */ }
        _connected.value = false
    }

    /** 将 Event 序列化为 6 字节大端序二进制帧 */
    private fun buildBuffer(event: Event): ByteArray {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        when (event) {
            is Event.Down -> {
                buf.put(0); buf.putShort(normToShort(event.xNorm)); buf.putShort(normToShort(event.yNorm)); buf.put(prToByte(event.pressure))
            }
            is Event.Move -> {
                buf.put(1); buf.putShort(normToShort(event.xNorm)); buf.putShort(normToShort(event.yNorm)); buf.put(prToByte(event.pressure))
            }
            is Event.Up -> {
                buf.put(2); buf.putShort(normToShort(event.xNorm)); buf.putShort(normToShort(event.yNorm)); buf.put(0)
            }
        }
        return buf.array()
    }

    private fun normToShort(v: Float) = (v * 65535f).toInt().coerceIn(0, 65535).toShort()
    private fun prToByte(v: Float) = (v * 255f).toInt().coerceIn(0, 255).toByte()

    // ==================== 内部：客户端数据处理 ====================

    /** 处理单个客户端连接，循环读取 6 字节帧并回调 onEvent */
    private fun handleClient(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            val buf = ByteArray(6)
            while (true) {
                input.readFully(buf)  // 阻塞读取，确保完整 6 字节
                val event = parseEvent(buf)
                if (event != null) {
                    try { onEvent?.invoke(event) }
                    catch (e: Exception) { Log.e(TAG, "onEvent crashed", e) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client disconnected: ${e.message}")
        } finally { try { socket.close() } catch (_: Exception) {} }
    }

    /** 从 6 字节二进制数据解析 Event */
    private fun parseEvent(data: ByteArray): Event? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val action = buf.get().toInt() and 0xFF
        val xn = ((buf.getShort().toInt() and 0xFFFF) / 65535f)
        val yn = ((buf.getShort().toInt() and 0xFFFF) / 65535f)
        val pr = (buf.get().toInt() and 0xFF) / 255f
        return when (action) { 0 -> Event.Down(xn, yn, pr); 1 -> Event.Move(xn, yn, pr); 2 -> Event.Up(xn, yn); else -> null }
    }
}
