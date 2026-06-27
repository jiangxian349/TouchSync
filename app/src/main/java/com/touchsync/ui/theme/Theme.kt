/**
 * Material 3 主题：HyperOS 风格配色
 *
 * - 支持 Monet 动态取色（Android 12+），自动适配系统壁纸色调
 * - 降级时使用内置 HyperOS Blue 色板（主色 #3482FF）
 * - 同时提供浅色/深色两套自定义配色方案
 */
package com.touchsync.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// HyperOS 风格色板
private val HyperBlue = Color(0xFF3482FF); private val HyperRed = Color(0xFFE64A19); private val HyperGreen = Color(0xFF4CAF50)

private val LightColorScheme = lightColorScheme(
    primary = HyperBlue, onPrimary = Color.White, primaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF535F70), secondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFF6B5778), tertiaryContainer = Color(0xFFF2DAFF),
    error = HyperRed, background = Color(0xFFFDFBFF), surface = Color.White,
    onBackground = Color(0xFF1A1C1E), onSurface = Color(0xFF1A1C1E),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4D94FF), secondary = Color(0xFFBBC7DB), tertiary = Color(0xFFD6BEE4),
    error = Color(0xFFFF8A65), background = Color(0xFF1A1C1E), surface = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE3E2E6), onSurface = Color(0xFFE3E2E6),
)

@Composable
fun TouchSyncTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColorScheme; else -> LightColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
