package com.rendyhd.vicu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.rendyhd.vicu.util.CrossAppFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `test-fixtures/design-tokens-v1.json` (identical in the desktop repo), parsed once. */
object DesignTokensFixture {
    val root: JsonObject by lazy {
        Json.parseToJsonElement(CrossAppFixture.readTestFixture("design-tokens-v1.json")).jsonObject
    }

    val android: JsonObject get() = root.getValue("android").jsonObject

    /** `#RRGGBB` as an opaque colour. */
    fun parseHex(hex: String): Color {
        require(Regex("#[0-9A-Fa-f]{6}").matches(hex)) { "not #RRGGBB: $hex" }
        return Color(
            red = hex.substring(1, 3).toInt(16),
            green = hex.substring(3, 5).toInt(16),
            blue = hex.substring(5, 7).toInt(16),
        )
    }

    /** The Compose colour scheme roles of one theme in the fixture ("light" or "dark"), by role name. */
    fun schemeFor(theme: String): Map<String, Color> =
        android.getValue("colorScheme").jsonObject.getValue(theme).jsonObject
            .mapValues { parseHex(it.value.jsonPrimitive.content) }
}

/** Every role of a Compose [ColorScheme] by its property name. */
fun ColorScheme.roles(): Map<String, Color> = mapOf(
    "primary" to primary,
    "onPrimary" to onPrimary,
    "primaryContainer" to primaryContainer,
    "onPrimaryContainer" to onPrimaryContainer,
    "inversePrimary" to inversePrimary,
    "secondary" to secondary,
    "onSecondary" to onSecondary,
    "secondaryContainer" to secondaryContainer,
    "onSecondaryContainer" to onSecondaryContainer,
    "tertiary" to tertiary,
    "onTertiary" to onTertiary,
    "tertiaryContainer" to tertiaryContainer,
    "onTertiaryContainer" to onTertiaryContainer,
    "error" to error,
    "onError" to onError,
    "errorContainer" to errorContainer,
    "onErrorContainer" to onErrorContainer,
    "background" to background,
    "onBackground" to onBackground,
    "surface" to surface,
    "onSurface" to onSurface,
    "surfaceVariant" to surfaceVariant,
    "onSurfaceVariant" to onSurfaceVariant,
    "surfaceTint" to surfaceTint,
    "inverseSurface" to inverseSurface,
    "inverseOnSurface" to inverseOnSurface,
    "outline" to outline,
    "outlineVariant" to outlineVariant,
    "scrim" to scrim,
    "surfaceBright" to surfaceBright,
    "surfaceDim" to surfaceDim,
    "surfaceContainerLowest" to surfaceContainerLowest,
    "surfaceContainerLow" to surfaceContainerLow,
    "surfaceContainer" to surfaceContainer,
    "surfaceContainerHigh" to surfaceContainerHigh,
    "surfaceContainerHighest" to surfaceContainerHighest,
    "primaryFixed" to primaryFixed,
    "primaryFixedDim" to primaryFixedDim,
    "onPrimaryFixed" to onPrimaryFixed,
    "onPrimaryFixedVariant" to onPrimaryFixedVariant,
    "secondaryFixed" to secondaryFixed,
    "secondaryFixedDim" to secondaryFixedDim,
    "onSecondaryFixed" to onSecondaryFixed,
    "onSecondaryFixedVariant" to onSecondaryFixedVariant,
    "tertiaryFixed" to tertiaryFixed,
    "tertiaryFixedDim" to tertiaryFixedDim,
    "onTertiaryFixed" to onTertiaryFixed,
    "onTertiaryFixedVariant" to onTertiaryFixedVariant,
)
