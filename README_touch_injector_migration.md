# TouchSync — 触控注入逻辑替换说明

## 本次改动概述

把接收端的触控注入方式从 `ShellTouchInjector`（通过 `su` 持续 shell 下发
`input motionevent DOWN/MOVE/UP` 命令）替换为你提供的 `TouchHelper.kt`
（Kotlin/Native，直接 `EVIOCGRAB` 抓取物理触摸设备 + 创建 `/dev/uinput`
虚拟设备来回放触控事件）。

`TouchHelper.kt` 用的是 Kotlin/Native cinterop（`platform.linux`、ioctl 等），
不能跑在 Android 应用进程的 JVM 里，所以做了如下集成方式：

```
[控制端 App] --TCP--> [接收端 App: TouchSyncService]
                              │
                              │ NativeTouchInjector
                              │ 1. 把 assets/touchhelper_arm64 释放到 filesDir
                              │ 2. su -c "<path> <屏幕宽> <屏幕高>" 启动子进程
                              │ 3. 通过子进程 stdin 下发一行一条的文本命令
                              ▼
                  [nativetouch 模块编译出的可执行文件]
                  TouchHelper（EVIOCGRAB + /dev/uinput）
```

stdin 文本协议（坐标为屏幕物理像素，与原来 ShellTouchInjector 的坐标体系一致）：

```
DOWN <x> <y>
MOVE <x> <y>
UP
EXIT
```

## 改动文件清单

新增：
- `nativetouch/`：新的 Kotlin Multiplatform 模块（target = androidNativeArm64）
  - `src/androidNativeArm64Main/kotlin/TouchHelper.kt`：你给的文件，原样集成，
    只补了一处缺失的 `import kotlin.random.Random`
  - `src/androidNativeArm64Main/kotlin/Main.kt`：新写的入口，实现上面的 stdin 协议
  - `build.gradle.kts`：配置该 target 编译为可执行文件
- `app/src/main/java/com/touchsync/touch/NativeTouchInjector.kt`：取代
  `ShellTouchInjector`，负责释放/启动 native 进程并转发触控事件

删除：
- `ShellTouchInjector.kt`（原 `input motionevent` 方案，已不再使用）

修改：
- `TouchSyncService.kt`：接收端改用 `NativeTouchInjector`；并把注入器初始化
  放进后台协程（避免等待 su 授权弹窗时阻塞主线程造成 ANR）
- `MainActivity.kt`：补了三个缺失的 import
  （`androidx.compose.runtime.{getValue,mutableStateOf,setValue}`），
  原文件里 `by mutableStateOf(...)` 这几个 import 没写全，编译不过
- 根 `build.gradle.kts` / `settings.gradle.kts`：加入 Kotlin Multiplatform
  插件和 `:nativetouch` 模块
- 新增 `app/build.gradle.kts`：原 zip 里没有这个文件（只给了根目录的），
  按现有源码用到的依赖（Compose、Material3、coroutines）补全了一份

## 构建步骤

1. 用 Android Studio（建议较新版本，支持 Kotlin 2.0 + Kotlin Multiplatform 插件）
   打开本项目根目录
2. 确保已安装 NDK，并且本机能联网下载 Kotlin/Native 的 androidNativeArm64
   编译器发行版（首次构建该 target 时 Gradle 会自动下载）
3. 直接运行 `:app` 的 assemble 任务即可：`app/build.gradle.kts` 里的
   `copyNativeTouchHelper` 任务会先触发
   `:nativetouch:linkReleaseExecutableAndroidNativeArm64`，再把产物拷贝进
   `app/src/main/assets/touchhelper_arm64`

## 已知限制 / 需要你在本地核实的地方

这部分代码涉及内核级 ioctl、Kotlin/Native cinterop 和真机 root 环境，
**在我这边的沙盒里没有 Kotlin/Native 编译器、NDK，也没有网络权限下载它们**，
所以这次只做了源码层面的拼装和走查，没有也无法实际编译验证。建议你在
Android Studio 里构建一次，重点核实：

1. **`nativetouch` 产物路径**：`app/build.gradle.kts` 里
   `copyNativeTouchHelper` 任务假设产物在
   `nativetouch/build/bin/androidNativeArm64/touchhelperReleaseExecutable/touchhelper.kexe`，
   不同 Kotlin Gradle Plugin 版本这个子目录名可能不一样，第一次构建报错的话
   按实际路径改一下 `from(...)`
2. **`TouchHelper.kt` 里几个 cinterop 细节**我没有改动逐字保留：比如
   `input_absinfo()` 这种 C struct 的零参构造、`uiDev.absmin[...]` 数组式赋值
   等写法，是否和你本地的 Kotlin/Native 版本 cinterop 生成的 API 完全对得上，
   需要实际编译一次才能确认（不同 Kotlin 版本对 uinput.h / input.h 的
   cinterop 绑定生成方式偶尔会有差异）
3. **机型适配**：`TouchHelper.init()` 里 `devices[0]` 被当作坐标换算的基准
   设备，多触摸屏机型/分屏机型上是否符合预期需要实机验证
4. 只配置了 `androidNativeArm64` 一个 target；如果你需要在 32 位机型或
   x86 模拟器上跑接收端，需要在 `nativetouch/build.gradle.kts` 里再加对应
   target，并在 `NativeTouchInjector.extractBinary()` 里按 ABI 选择对应 asset
