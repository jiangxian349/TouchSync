/**
 * nativetouch 模块：Kotlin/Native 可执行文件，编译为 androidNativeArm64 二进制
 *
 * 产物路径（Release）大致为：
 *   nativetouch/build/bin/androidNativeArm64/touchhelperReleaseExecutable/touchhelper.kexe
 * （具体子目录名随 Kotlin Gradle Plugin 版本可能略有差异，
 *  若 app 模块的 copyNativeTouchHelper 任务找不到产物，请以实际 build 输出路径为准调整）
 *
 * 注意：本机/CI 上需要安装支持 androidNativeArm64 的 Kotlin/Native 编译器发行版，
 * Gradle 首次构建该 target 时会自动下载对应的 konan 工具链（需要网络）。
 */
plugins {
    kotlin("multiplatform")
}

kotlin {
    androidNativeArm64 {
        binaries {
            executable("touchhelper") {
                entryPoint = "main"
            }
        }
    }

    sourceSets {
        val androidNativeArm64Main by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
    }
}
