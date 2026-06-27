/**
 * 触控捕获器：控制端全屏触摸区的事件处理器
 *
 * 将 Compose 层的触控事件转换为 TouchRelay.Event 并发送
 * 归一化坐标：x,y 除以屏幕尺寸得到 0.0~1.0 的相对坐标
 */
package com.touchsync.touch

import android.util.DisplayMetrics
import com.touchsync.network.TouchRelay

class TouchCapture(
    private val relay: TouchRelay,
    private val displayMetrics: DisplayMetrics
) {
    private var isDown = false

    fun onDown(x: Float, y: Float, pressure: Float = 1.0f) {
        isDown = true
        val xNorm = (x / displayMetrics.widthPixels).coerceIn(0f, 1f)
        val yNorm = (y / displayMetrics.heightPixels).coerceIn(0f, 1f)
        relay.sendEvent(TouchRelay.Event.Down(xNorm, yNorm, pressure.coerceIn(0f, 1f)))
    }

    fun onMove(x: Float, y: Float, pressure: Float = 1.0f) {
        if (!isDown) return
        val xNorm = (x / displayMetrics.widthPixels).coerceIn(0f, 1f)
        val yNorm = (y / displayMetrics.heightPixels).coerceIn(0f, 1f)
        relay.sendEvent(TouchRelay.Event.Move(xNorm, yNorm, pressure.coerceIn(0f, 1f)))
    }

    fun onUp() {
        if (!isDown) return; isDown = false
        relay.sendEvent(TouchRelay.Event.Up(0f, 0f))
    }
}
