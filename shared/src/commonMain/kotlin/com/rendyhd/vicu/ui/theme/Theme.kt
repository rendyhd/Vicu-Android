package com.rendyhd.vicu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.rendyhd.vicu.data.local.ThemeMode

@Composable
fun VicuTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }

    val useDeviceColors = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useDeviceColors -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> VicuDarkColorScheme
        else -> VicuLightColorScheme
    }

    // With device colours the status and swipe colours are shifted towards the wallpaper primary.
    val vicuColors = remember(colorScheme, darkTheme, useDeviceColors) {
        val base = if (darkTheme) VicuDarkColors else VicuLightColors
        if (useDeviceColors) base.harmonizedWith(colorScheme.primary) else base
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = VicuShapes,
    ) {
        CompositionLocalProvider(
            LocalVicuColors provides vicuColors,
            content = content,
        )
    }
}
