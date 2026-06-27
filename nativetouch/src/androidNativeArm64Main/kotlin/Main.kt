/**
 * nativetouch 可执行文件入口
 *
 * 用法：touchhelper <screenWidth> <screenHeight>
 * （screenWidth/screenHeight 与 app 侧 DisplayMetrics.widthPixels/heightPixels 一致）
 *
 * 启动成功后向 stdout 打印一行 "READY"，随后阻塞读取 stdin，按行解析文本协议：
 *   DOWN <x> <y>   按下，x/y 为屏幕物理像素坐标
 *   MOVE <x> <y>   移动
 *   UP             抬起
 *   EXIT           主动退出（释放 grab、销毁虚拟设备）
 *
 * stdin 被关闭（EOF）时同样会退出并清理资源。
 *
 * 该协议由 app 模块的 NativeTouchInjector.kt 通过 "su -c <本程序> <w> <h>" 启动并对接，
 * 进程的 stdin 由 ProcessBuilder 持有，每个触控事件以一行命令写入并 flush。
 */

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("\u7528\u6cd5: touchhelper <screenWidth> <screenHeight>")
        return
    }
    val screenWidth = args[0].toIntOrNull()
    val screenHeight = args[1].toIntOrNull()
    if (screenWidth == null || screenHeight == null) {
        println("\u53c2\u6570\u9519\u8bef\uff1ascreenWidth/screenHeight \u5fc5\u987b\u4e3a\u6574\u6570")
        return
    }

    val helper = TouchHelper()
    if (!helper.init(screenWidth, screenHeight, pReadOnly = false)) {
        // init() 内部已经打印了具体失败原因（无法打开 /dev/input、找不到触摸设备、
        // 创建虚拟设备失败等），这里只需要让父进程（NativeTouchInjector）能读到
        // 一行非 READY 的输出从而判定初始化失败
        println("INIT_FAILED")
        return
    }

    // 通知宿主 App：已经就绪，可以开始下发触控事件
    println("READY")

    try {
        while (true) {
            val line = readLine() ?: break  // stdin 被关闭（父进程退出/管道断开）→ EOF，退出循环
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(" ")
            when (parts[0]) {
                "DOWN" -> if (parts.size >= 3) {
                    val x = parts[1].toFloatOrNull(); val y = parts[2].toFloatOrNull()
                    if (x != null && y != null) helper.down(x, y)
                }
                "MOVE" -> if (parts.size >= 3) {
                    val x = parts[1].toFloatOrNull(); val y = parts[2].toFloatOrNull()
                    if (x != null && y != null) helper.move(x, y)
                }
                "UP" -> helper.up()
                "EXIT" -> break
                else -> { /* 忽略未知命令，保持协议向后兼容 */ }
            }
        }
    } finally {
        // 无论是正常 EXIT、stdin EOF 还是异常，都要释放物理设备的 EVIOCGRAB
        // 并销毁 /dev/uinput 虚拟设备，否则物理触摸屏会在进程退出后失去响应
        helper.close()
    }
}
