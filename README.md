<div align="center">

# TouchSync 触控同步

局域网内，把一台 Android 设备变成另一台设备的远程触控板 —— 在控制端的屏幕上滑动，
触控事件实时同步到接收端，并在系统级注入，对接收端上的任意前台 App 生效。

![platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84)
![kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF)
![root](https://img.shields.io/badge/接收端-需要%20Root-orange)
![license](https://img.shields.io/badge/license-MIT-blue)

</div>

---

## 这是什么

TouchSync 由两个角色组成，运行在同一局域网内的两台设备上：

- **控制端**：全屏黑色触控面，手指在上面滑动产生的坐标会被归一化后通过 TCP 发给接收端
- **接收端**：在 root 权限下，把收到的坐标转换成系统级触控事件注入进去，效果等同于
  有人在这台设备的屏幕上直接操作 —— 切到任意 App 后依然生效

典型场景：旧手机闲置时当一块无线触摸板，远程操作放在支架/夹具上不方便用手碰的设备，
多设备调试时统一控制等。

## 工作原理

```
┌──────────────┐   UDP 广播 (29528)    ┌──────────────┐
│   接收端 App   │ ───────────────────▶ │   控制端 App   │
│ DiscoveryService│  "TOUCHSYNC:id:name" │ DiscoveryService│
└──────┬───────┘                       └──────┬───────┘
       │                                      │ 用户在设备列表中点击"配对"
       │              TCP (29527)             │
       │ ◀──────────────────────────────────  │
       │     6 字节二进制帧：                  │
       │     [action][x_norm][y_norm][pressure]│
       ▼                                      ▼
┌──────────────────┐                  ┌──────────────────┐
│ NativeTouchInjector│                 │   TouchCapture    │
│  su 启动 native 进程│                 │ Compose 手势 → 归一化坐标│
└──────┬────────────┘                 └──────────────────┘
       │ stdin 文本协议："DOWN x y" / "MOVE x y" / "UP"
       ▼
┌────────────────────────────┐
│  nativetouch（Kotlin/Native）│
│  EVIOCGRAB 抓取物理触摸设备   │
│  /dev/uinput 创建虚拟触摸设备 │
└────────────────────────────┘
```

- **设备发现**：接收端每 2 秒通过 UDP 广播自身信息（设备 ID + 名称），控制端监听并展示可配对设备列表
- **触控传输**：控制端与接收端之间用一条 TCP 连接传输触控事件，二进制帧仅 6 字节，
  `tcpNoDelay` 关闭 Nagle 算法以降低延迟
- **系统级注入**：接收端不依赖任何无障碍服务或 `input` shell 命令，而是用一个独立编译的
  native 守护进程，直接 `EVIOCGRAB` 抓取物理触摸设备的独占权，并创建一个 `/dev/uinput`
  虚拟触摸设备来回放事件，支持真正的多点触控协议

## 项目结构

```
TouchSync/
├── app/                            # Android 应用主模块
│   └── src/main/java/com/touchsync/
│       ├── MainActivity.kt         # 入口 Activity + 权限申请
│       ├── TouchSyncApplication.kt # 全局单例：TouchRelay / DiscoveryService
│       ├── network/
│       │   ├── DiscoveryService.kt # UDP 局域网设备发现
│       │   └── TouchRelay.kt       # TCP 触控事件中继
│       ├── touch/
│       │   ├── TouchCapture.kt        # 控制端：手势 → 归一化坐标
│       │   └── NativeTouchInjector.kt # 接收端：启动并对接 native 注入进程
│       ├── service/
│       │   └── TouchSyncService.kt # 前台 Service，串联以上所有组件
│       └── ui/                     # Compose UI（Material 3）
└── nativetouch/                    # Kotlin/Native 模块（androidNativeArm64）
    └── src/androidNativeArm64Main/kotlin/
        ├── TouchHelper.kt          # evdev grab + uinput 核心实现
        └── Main.kt                 # 可执行文件入口，实现 stdin 文本协议
```

## 环境要求

| | 控制端 | 接收端 |
|---|---|---|
| 系统版本 | Android 8.0 (API 26) 及以上 | Android 8.0 (API 26) 及以上 |
| Root | 不需要 | **需要**（Magisk 等，用于 `EVIOCGRAB` / `/dev/uinput`） |
| 网络 | 与接收端处于同一局域网 | 与控制端处于同一局域网 |

构建本项目需要：

- Android Studio（建议较新版本，支持 Kotlin 2.0 / Kotlin Multiplatform 插件）
- Android NDK（`nativetouch` 模块编译 androidNativeArm64 可执行文件需要）
- 联网环境（首次构建 `androidNativeArm64` target 时，Gradle 会自动下载对应的
  Kotlin/Native 编译器发行版）

## 构建与运行

```bash
git clone <your-repo-url>
cd TouchSync
./gradlew :app:assembleDebug
```

`app/build.gradle.kts` 中的 `copyNativeTouchHelper` 任务会自动：

1. 触发 `:nativetouch:linkReleaseExecutableAndroidNativeArm64`，编译出 native 可执行文件
2. 把产物拷贝进 `app/src/main/assets/touchhelper_arm64`，随 APK 一起打包

安装到两台设备后：

1. 在用作**接收端**的设备上打开 App，选择"接收端"模式，授予 root 权限
2. 在用作**控制端**的设备上打开 App，选择"控制端"模式，设备列表中会自动出现
   同一局域网内广播的接收端，点击"配对"
3. 配对成功后控制端进入全屏触控面，在上面滑动即可远程操控接收端

## 已知限制

- 仅提供了 `androidNativeArm64` 编译目标；32 位机型或 x86 模拟器需要在
  `nativetouch/build.gradle.kts` 中追加对应 target
- `TouchHelper` 以 `devices[0]`（第一个识别到的触摸设备）作为坐标换算基准，
  多屏/折叠屏机型上的行为需要按实际机型验证
- 该机制依赖 root 权限，部分系统（如部分定制 ROM 的 SELinux 策略）可能限制
  对 `/dev/uinput` 的访问，需要按机型单独适配

## 使用须知

本项目用到的 `EVIOCGRAB` + `/dev/uinput` 是系统级触控注入技术，请仅在你自己拥有或
已获得明确授权的设备上使用，不要用于未经授权访问、操控他人设备等场景。

## License

[MIT](LICENSE)
