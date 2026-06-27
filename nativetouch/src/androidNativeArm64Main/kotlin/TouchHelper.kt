/**
 * Native 触控助手核心实现（来自用户提供的 TouchHelper.kt，原样集成，仅补充了缺失的
 * kotlin.random.Random 导入）。
 *
 * 编译目标：androidNativeArm64（Kotlin/Native，对接 Bionic libc 的 ioctl / evdev / uinput）
 * 运行方式：由 Main.kt 的 main() 构造为独立可执行文件，由 app 模块通过
 * "su -c <path> <w> <h>" 以 root 身份启动，详见 NativeTouchInjector.kt
 *
 * 原理：EVIOCGRAB 抓取物理触摸设备独占权 → 读取 evdev 原始事件解析多点触控 →
 * 通过 /dev/uinput 创建虚拟触摸设备并回放，实现系统级触控注入/转发
 */
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import platform.linux.*
import kotlin.math.*
import kotlin.random.Random

// 常量定义
const val MAX_FINGERS = 10
const val MAX_EVENTS = 64
const val UNGRAB = 0
const val GRAB = 1

// input_event 结构体映射
@OptIn(ExperimentalForeignApi::class)
class InputEvent(private val ptr: CPointer<platform.linux.input_event>) {
    var type: UShort
        get() = ptr.pointed.type
        set(value) { ptr.pointed.type = value }
    
    var code: UShort
        get() = ptr.pointed.code
        set(value) { ptr.pointed.code = value }
    
    var value: Int
        get() = ptr.pointed.value
        set(value) { ptr.pointed.value = value }
}

data class Vector2(val x: Float, val y: Float)

data class TouchFinger(
    var isDown: Boolean = false,
    var id: Int = -1,
    var pos: Vector2 = Vector2(0f, 0f)
)

data class TouchDevice(
    var fd: Int = -1,
    var absX: input_absinfo = input_absinfo(),
    var absY: input_absinfo = input_absinfo(),
    var s2tx: Float = 1.0f,
    var s2ty: Float = 1.0f,
    val fingers: Array<TouchFinger> = Array(MAX_FINGERS) { TouchFinger() }
)

class TouchHelper {
    private var initialized = false
    private var readOnly = false
    private var orientation = 0
    private var otherTouch = false
    private var screenSize = Vector2(0f, 0f)
    private var touchScale = Vector2(1f, 1f)
    
    private val devices = mutableListOf<TouchDevice>()
    private var virtualFd = -1
    private var job: Job? = null
    
    // 回调函数
    var onTouchEvent: ((List<TouchDevice>) -> Unit)? = null
    
    @OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)
    fun init(screenWidth: Int, screenHeight: Int, pReadOnly: Boolean = false): Boolean {
        close()
        devices.clear()
        readOnly = pReadOnly
        
        screenSize = if (screenWidth > screenHeight) {
            Vector2(screenWidth.toFloat(), screenHeight.toFloat())
        } else {
            Vector2(screenHeight.toFloat(), screenWidth.toFloat())
        }
        
        // 扫描触摸设备
        val dir = opendir("/dev/input/")
        if (dir == null) {
            println("无法打开 /dev/input/")
            return false
        }
        
        try {
            var dirent: dirent? = readdir(dir)
            val eventFiles = mutableListOf<String>()
            
            while (dirent != null) {
                val name = dirent.pointed.d_name.toKString()
                if (name.startsWith("event")) {
                    eventFiles.add(name)
                }
                dirent = readdir(dir)
            }
            
            for (eventFile in eventFiles) {
                val path = "/dev/input/$eventFile"
                val fd = open(path, O_RDWR)
                if (fd < 0) continue
                
                if (checkDeviceIsTouch(fd)) {
                    val device = TouchDevice()
                    device.fd = fd
                    
                    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), device.absX.ptr) == 0 &&
                        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), device.absY.ptr) == 0) {
                        
                        if (!readOnly) {
                            ioctl(fd, EVIOCGRAB, GRAB)
                        }
                        devices.add(device)
                    }
                } else {
                    close(fd)
                }
            }
        } finally {
            closedir(dir)
        }
        
        if (devices.isEmpty()) {
            println("未找到触摸设备")
            return false
        }
        
        // 创建虚拟设备
        if (!readOnly) {
            if (!createVirtualDevice()) {
                return false
            }
        }
        
        // 计算缩放因子
        val screenX = devices[0].absX.maximum
        val screenY = devices[0].absY.maximum
        
        for (device in devices) {
            device.s2tx = screenX.toFloat() / device.absX.maximum.toFloat()
            device.s2ty = screenY.toFloat() / device.absY.maximum.toFloat()
        }
        
        touchScale = Vector2(
            screenX.toFloat() / screenWidth.toFloat(),
            screenY.toFloat() / screenHeight.toFloat()
        )
        
        initialized = true
        
        // 启动事件读取线程
        job = GlobalScope.launch(Dispatchers.IO) {
            readEvents()
        }
        
        return true
    }
    
    @OptIn(ExperimentalForeignApi::class)
    private fun checkDeviceIsTouch(fd: Int): Boolean {
        var hasSlot = false
        var hasX = false
        var hasY = false
        
        // 获取 ABS 事件位图
        val bits = ByteArray(1024)
        val res = ioctl(fd, EVIOCGBIT(EV_ABS, bits.size), bits)
        
        if (res > 0) {
            for (i in 0 until res) {
                val byte = bits[i].toInt() and 0xFF
                for (j in 0..7) {
                    if ((byte and (1 shl j)) != 0) {
                        val code = i * 8 + j
                        when (code) {
                            ABS_MT_SLOT -> hasSlot = true
                            ABS_MT_POSITION_X -> hasX = true
                            ABS_MT_POSITION_Y -> hasY = true
                        }
                    }
                }
            }
        }
        
        return hasSlot && hasX && hasY
    }
    
    @OptIn(ExperimentalForeignApi::class)
    private fun createVirtualDevice(): Boolean {
        virtualFd = open("/dev/uinput", O_WRONLY or O_NONBLOCK)
        if (virtualFd < 0) {
            println("无法打开 /dev/uinput")
            return false
        }
        
        memScoped {
            val uiDev = alloc<uinput_user_dev>()
            memset(uiDev.ptr, 0, sizeOf<uinput_user_dev>())
            
            // 随机设备名
            val name = "VirtualTouch_${Random.nextInt(1000, 9999)}"
            name.toByteArray().copyInto(uiDev.name, 0, 0, min(name.length, UINPUT_MAX_NAME_SIZE - 1))
            
            uiDev.id.bustype = 0
            uiDev.id.vendor = 0x1234.toUShort()
            uiDev.id.product = 0x5678.toUShort()
            uiDev.id.version = 1.toUShort()
            
            // 设置事件类型
            ioctl(virtualFd, UI_SET_PROPBIT, INPUT_PROP_DIRECT)
            
            ioctl(virtualFd, UI_SET_EVBIT, EV_ABS)
            ioctl(virtualFd, UI_SET_ABSBIT, ABS_X)
            ioctl(virtualFd, UI_SET_ABSBIT, ABS_Y)
            ioctl(virtualFd, UI_SET_ABSBIT, ABS_MT_POSITION_X)
            ioctl(virtualFd, UI_SET_ABSBIT, ABS_MT_POSITION_Y)
            ioctl(virtualFd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID)
            
            ioctl(virtualFd, UI_SET_EVBIT, EV_SYN)
            ioctl(virtualFd, UI_SET_EVBIT, EV_KEY)
            ioctl(virtualFd, UI_SET_KEYBIT, BTN_TOOL_FINGER)
            ioctl(virtualFd, UI_SET_KEYBIT, BTN_TOUCH)
            
            // 从物理设备复制按键支持
            val firstFd = devices[0].fd
            val bits = ByteArray(1024)
            val res = ioctl(firstFd, EVIOCGBIT(EV_KEY, bits.size), bits)
            
            for (i in 0 until res) {
                val byte = bits[i].toInt() and 0xFF
                for (j in 0..7) {
                    if ((byte and (1 shl j)) != 0) {
                        val code = i * 8 + j
                        if (code != BTN_TOUCH && code != BTN_TOOL_FINGER) {
                            ioctl(virtualFd, UI_SET_KEYBIT, code)
                        }
                    }
                }
            }
            
            // 设置坐标范围
            val screenX = devices[0].absX.maximum
            val screenY = devices[0].absY.maximum
            
            uiDev.absmin[ABS_MT_POSITION_X] = 0
            uiDev.absmax[ABS_MT_POSITION_X] = screenX
            uiDev.absmin[ABS_MT_POSITION_Y] = 0
            uiDev.absmax[ABS_MT_POSITION_Y] = screenY
            uiDev.absmin[ABS_X] = 0
            uiDev.absmax[ABS_X] = screenX
            uiDev.absmin[ABS_Y] = 0
            uiDev.absmax[ABS_Y] = screenY
            uiDev.absmin[ABS_MT_TRACKING_ID] = 0
            uiDev.absmax[ABS_MT_TRACKING_ID] = 65535
            
            // 写入设备配置
            write(virtualFd, uiDev.ptr, sizeOf<uinput_user_dev>())
            
            if (ioctl(virtualFd, UI_DEV_CREATE) != 0) {
                println("创建虚拟设备失败")
                return false
            }
        }
        
        return true
    }
    
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun readEvents() {
        val eventBuffer = ByteArray(sizeOf<input_event>() * MAX_EVENTS)
        
        while (initialized) {
            val deviceIndex = 0
            val device = devices.getOrNull(deviceIndex) ?: break
            
            val readSize = read(device.fd, eventBuffer.refTo(0), eventBuffer.size)
            
            if (readSize <= 0 || readSize % sizeOf<input_event>() != 0) {
                delay(1)
                continue
            }
            
            val eventCount = readSize / sizeOf<input_event>()
            var latestSlot = 0
            
            for (i in 0 until eventCount) {
                memScoped {
                    val eventPtr = eventBuffer.refTo(i * sizeOf<input_event>()).reinterpret<input_event>()
                    val event = InputEvent(eventPtr)
                    
                    when (event.type.toInt()) {
                        EV_ABS -> {
                            when (event.code.toInt()) {
                                ABS_MT_SLOT -> {
                                    latestSlot = event.value
                                }
                                ABS_MT_TRACKING_ID -> {
                                    if (event.value == -1) {
                                        device.fingers[latestSlot].isDown = false
                                    } else {
                                        device.fingers[latestSlot].id = (deviceIndex * 2 + 1) * 10 + latestSlot
                                        device.fingers[latestSlot].isDown = true
                                    }
                                }
                                ABS_MT_POSITION_X -> {
                                    device.fingers[latestSlot].id = (deviceIndex * 2 + 1) * 10 + latestSlot
                                    device.fingers[latestSlot].pos = Vector2(
                                        event.value.toFloat() * device.s2tx,
                                        device.fingers[latestSlot].pos.y
                                    )
                                }
                                ABS_MT_POSITION_Y -> {
                                    device.fingers[latestSlot].id = (deviceIndex * 2 + 1) * 10 + latestSlot
                                    device.fingers[latestSlot].pos = Vector2(
                                        device.fingers[latestSlot].pos.x,
                                        event.value.toFloat() * device.s2ty
                                    )
                                }
                            }
                        }
                        EV_SYN -> {
                            if (event.code.toInt() == SYN_REPORT) {
                                // 回调处理
                                onTouchEvent?.invoke(devices)
                                
                                if (!readOnly) {
                                    uploadEvents()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    @OptIn(ExperimentalForeignApi::class)
    private fun uploadEvents() {
        if (virtualFd < 0) return
        
        memScoped {
            val events = allocArray<input_event>(MAX_EVENTS)
            var eventCount = 0
            var hasTouch = false
            
            for (device in devices) {
                for (finger in device.fingers) {
                    if (finger.isDown) {
                        if (eventCount + 6 >= MAX_EVENTS) break
                        
                        // X坐标
                        events[eventCount].type = EV_ABS.toUShort()
                        events[eventCount].code = ABS_X.toUShort()
                        events[eventCount].value = finger.pos.x.toInt()
                        eventCount++
                        
                        // Y坐标
                        events[eventCount].type = EV_ABS.toUShort()
                        events[eventCount].code = ABS_Y.toUShort()
                        events[eventCount].value = finger.pos.y.toInt()
                        eventCount++
                        
                        // MT X
                        events[eventCount].type = EV_ABS.toUShort()
                        events[eventCount].code = ABS_MT_POSITION_X.toUShort()
                        events[eventCount].value = finger.pos.x.toInt()
                        eventCount++
                        
                        // MT Y
                        events[eventCount].type = EV_ABS.toUShort()
                        events[eventCount].code = ABS_MT_POSITION_Y.toUShort()
                        events[eventCount].value = finger.pos.y.toInt()
                        eventCount++
                        
                        // Tracking ID
                        events[eventCount].type = EV_ABS.toUShort()
                        events[eventCount].code = ABS_MT_TRACKING_ID.toUShort()
                        events[eventCount].value = finger.id
                        eventCount++
                        
                        // SYN_MT_REPORT
                        events[eventCount].type = EV_SYN.toUShort()
                        events[eventCount].code = SYN_MT_REPORT.toUShort()
                        events[eventCount].value = 0
                        eventCount++
                        
                        hasTouch = true
                    }
                }
            }
            
            if (!hasTouch) {
                // 发送抬起事件
                events[eventCount].type = EV_SYN.toUShort()
                events[eventCount].code = SYN_MT_REPORT.toUShort()
                events[eventCount].value = 0
                eventCount++
                
                events[eventCount].type = EV_KEY.toUShort()
                events[eventCount].code = BTN_TOUCH.toUShort()
                events[eventCount].value = 0
                eventCount++
                
                events[eventCount].type = EV_KEY.toUShort()
                events[eventCount].code = BTN_TOOL_FINGER.toUShort()
                events[eventCount].value = 0
                eventCount++
            }
            
            // SYN_REPORT
            events[eventCount].type = EV_SYN.toUShort()
            events[eventCount].code = SYN_REPORT.toUShort()
            events[eventCount].value = 0
            eventCount++
            
            write(virtualFd, events, (eventCount * sizeOf<input_event>()).toLong())
        }
    }
    
    fun down(x: Float, y: Float) {
        if (devices.isEmpty()) return
        
        val finger = devices[0].fingers[9]
        finger.id = 19
        finger.pos = Vector2(x * touchScale.x, y * touchScale.y)
        finger.isDown = true
        
        if (!readOnly) {
            uploadEvents()
        }
    }
    
    fun move(x: Float, y: Float) {
        if (devices.isEmpty()) return
        
        val finger = devices[0].fingers[9]
        finger.pos = Vector2(x * touchScale.x, y * touchScale.y)
        
        if (!readOnly) {
            uploadEvents()
        }
    }
    
    fun up() {
        if (devices.isEmpty()) return
        
        val finger = devices[0].fingers[9]
        finger.isDown = false
        
        if (!readOnly) {
            uploadEvents()
        }
    }
    
    @OptIn(ExperimentalForeignApi::class)
    fun close() {
        initialized = false
        job?.cancel()
        
        for (device in devices) {
            if (!readOnly) {
                ioctl(device.fd, EVIOCGRAB, UNGRAB)
            }
            close(device.fd)
        }
        devices.clear()
        
        if (virtualFd > 0) {
            ioctl(virtualFd, UI_DEV_DESTROY)
            close(virtualFd)
            virtualFd = -1
        }
    }
    
    fun setOrientation(o: Int) {
        orientation = o
    }
    
    fun setOtherTouch(enable: Boolean) {
        otherTouch = enable
    }
    
    fun getScale(): Vector2 = touchScale
    
    fun touchToScreen(coord: Vector2): Vector2 {
        var x = coord.x / touchScale.x
        var y = coord.y / touchScale.y
        
        if (otherTouch) {
            when (orientation) {
                1 -> { /* 保持 */ }
                2 -> {
                    y = y
                    x = screenSize.y - x
                }
                3 -> {
                    x = screenSize.y - x
                    y = screenSize.x - y
                }
                else -> {
                    y = x
                    x = screenSize.y - y
                }
            }
        } else {
            when (orientation) {
                1 -> {
                    x = y
                    y = screenSize.y - x
                }
                2 -> {
                    x = screenSize.y - x
                    y = screenSize.x - y
                }
                3 -> {
                    y = x
                    x = screenSize.x - y
                }
                else -> { /* 保持 */ }
            }
        }
        
        return Vector2(x, y)
    }
}