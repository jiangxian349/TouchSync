plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.touchsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.touchsync"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// ============================================================
// 把 nativetouch 模块编译出的 androidNativeArm64 可执行文件
// 拷贝进 app/src/main/assets/，打包进 APK，运行时由
// NativeTouchInjector 释放到 filesDir 并以 su 启动。
//
// 注意：Kotlin Gradle Plugin 不同版本对应的产物子目录名可能不同
// （例如 "touchhelperReleaseExecutable" 还是 "releaseExecutable"），
// 如果该任务报找不到文件，请执行一次：
//     ./gradlew :nativetouch:linkReleaseExecutableAndroidNativeArm64
// 然后查看 nativetouch/build/bin/androidNativeArm64/ 下实际生成的目录名，
// 据此调整下面 from(...) 里的路径。
// ============================================================
val copyNativeTouchHelper by tasks.registering(Copy::class) {
    dependsOn(":nativetouch:linkReleaseExecutableAndroidNativeArm64")
    from(project(":nativetouch").layout.buildDirectory.dir("bin/androidNativeArm64/touchhelperReleaseExecutable"))
    include("touchhelper.kexe")
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "touchhelper_arm64" }
}

afterEvaluate {
    tasks.findByName("mergeDebugAssets")?.dependsOn(copyNativeTouchHelper)
    tasks.findByName("mergeReleaseAssets")?.dependsOn(copyNativeTouchHelper)
}
