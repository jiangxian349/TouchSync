/**
 * 原生触控注入器：通过 root shell 启动 native 守护进程（nativetouch 模块编译产物）
 *
 * 原理：
 * - app 启动时把内置的可执行文件从 assets 释放到 filesDir 并赋予执行权限
 * - 通过 "su -c <path> <screenWidth> <screenHeight>" 以 root 身份启动该进程
 *   （进程内部会 EVIOCGRAB 抓取物理触摸设备，并创建一个 /dev/uinput 虚拟触摸设备）
 * - 通过进程的 stdin 用一行一条命令的文本协议下发触控事件：
 *       "DOWN <x> <y>\n"  "MOVE <x> <y>\n"  "UP\n"  "EXIT\n"
 *   x、y 为屏幕物理像素坐标（与 ShellTouchInjector 保持一致的坐标体系）
 *
 * 相比旧版 ShellTouchInjector（input motionevent）的优势：
 * - 直接操作 evdev / uinput，支持真正的多点触控协议，不依赖 input 命令的单点模拟
 * - 注入延迟更低，不需要每次注入都 fork 一个 input 子进程
 *
 * 前置条件：设备已 root，su 可用；nativetouch 模块已编译出 androidNativeArm64 可执行文件
 * 并打包进 assets/touchhelper_arm64（见 app/build.gradle.kts 中的 copyNativeTouchHelper 任务）
 */
package com.touchsync.touch

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import com.touchsync.network.TouchRelay
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class NativeTouchInjector(
    private val context: Context,
    private val displayMetrics: DisplayMetrics  // 用于归一化坐标→绝对像素坐标转换
) {
    companion object {
        private const val TAG = "NativeTouchInjector"
        private const val ASSET_NAME = "touchhelper_arm64"
        private const val BIN_NAME = "touchhelper"
    }

    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null

    var isReady: Boolean = false; private set
    var lastError: String = ""; private set

    /** 把 assets 中的可执行文件释放到 filesDir，并赋予可执行权限 */
    private fun extractBinary(): File {
        val outFile = File(context.filesDir, BIN_NAME)
        // 每次启动都重新释放一遍，避免旧版本残留（文件很小，开销可忽略）
        context.assets.open(ASSET_NAME).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        outFile.setExecutable(true, false)
        outFile.setReadable(true, false)
        return outFile
    }

    /** 释放并以 root 身份启动 native 注入进程，初始化成功返回 true */
    fun initialize(): Boolean = try {
        val bin = extractBinary()
        val w = displayMetrics.widthPixels
        val h = displayMetrics.heightPixels
        shellProcess = ProcessBuilder("su", "-c", "${bin.absolutePath} $w $h")
            .redirectErrorStream(true)
            .start()
        shellWriter = BufferedWriter(OutputStreamWriter(shellProcess!!.outputStream))

        // 读取一行启动状态（native 进程成功 init() 后会打印 "READY"），避免对着一个已经
        // 初始化失败（例如未找到触摸设备 / 创建虚拟设备失败）的进程持续写命令
        val reader = BufferedReader(InputStreamReader(shellProcess!!.inputStream))
        val firstLine = reader.readLine()
        if (firstLine == null || !firstLine.contains("READY")) {
            lastError = "native\u8fdb\u7a0b\u672a\u5c31\u7eea: ${firstLine ?: "\u8fdb\u7a0b\u65e0\u8f93\u51fa\uff08\u53ef\u80fd\u662f su \u88ab\u62d2\uff09"}"
            Log.e(TAG, lastError)
            return false
        }

        isReady = true
        Log.i(TAG, "Native injector ready (evdev grab + uinput)")
        true
    } catch (e: Exception) {
        lastError = "\u542f\u52a8 native \u6ce8\u5165\u5668\u5931\u8d25: ${e.message}"
        Log.e(TAG, lastError, e)
        false
    }

    /** 实时注入触控事件，坐标体系与旧版一致：归一化坐标 × 屏幕像素 = 绝对像素坐标 */
    fun inject(event: TouchRelay.Event) {
        if (!isReady) return
        val absX = (event.xNorm * displayMetrics.widthPixels).toInt()
        val absY = (event.yNorm * displayMetrics.heightPixels).toInt()
        val cmd = when (event) {
            is TouchRelay.Event.Down -> "DOWN $absX $absY\n"
            is TouchRelay.Event.Move -> "MOVE $absX $absY\n"
            is TouchRelay.Event.Up -> "UP\n"
        }
        try { shellWriter?.apply { write(cmd); flush() } }
        catch (e: Exception) { Log.w(TAG, "Inject failed", e); isReady = false }
    }

    /** 通知 native 进程退出（释放 evdev grab、销毁虚拟设备），再回收进程 */
    fun shutdown() {
        try { shellWriter?.apply { write("EXIT\n"); flush() } } catch (_: Exception) {}
        try { shellWriter?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
        isReady = false
    }
}
