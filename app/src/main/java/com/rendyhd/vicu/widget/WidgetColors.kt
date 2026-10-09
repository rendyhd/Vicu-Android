package com.rendyhd.vicu.widget

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as glanceColorProviders
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.ui.theme.VicuDarkColorScheme
import com.rendyhd.vicu.ui.theme.VicuLightColorScheme
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * The widgets' colours: the Vicu scheme, or the wallpaper scheme when Settings has device colours on
 * (Android 12 and later), the same choice as `VicuTheme`.
 *
 * Both are resolved here, in the app, and handed to Glance as fixed day and night values. Glance's
 * default theme instead points at the system palette through resources that the launcher resolves
 * when it draws the widget, which left the widgets on the wallpaper colours after the app moved to
 * the Vicu scheme, and on some launchers produced secondary text, checkbox outlines and a + glyph
 * with no contrast against the widget background.
 */
internal fun widgetColors(context: Context, useDeviceColors: Boolean): ColorProviders =
    if (useDeviceColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        glanceColorProviders(light = dynamicLightColorScheme(context), dark = dynamicDarkColorScheme(context))
    } else {
        glanceColorProviders(light = VicuLightColorScheme, dark = VicuDarkColorScheme)
    }

/** [widgetColors] for the current device-colours setting; the Vicu scheme when the setting cannot be read. */
internal suspend fun loadWidgetColors(context: Context): ColorProviders {
    val useDeviceColors = runCatching {
        GlobalContext.get().get<ThemePrefsStore>().useDeviceColors.first()
    }.getOrDefault(false)
    return widgetColors(context, useDeviceColors)
}
