package com.rendyhd.vicu.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Corner radii of docs/design-system-v1.md, section 4 (test-fixtures/design-tokens-v1.json,
// "radius", dp column): control 8 = small, popover 12 = medium, card 12 = large,
// sheet 28 = extraLarge. extraSmall keeps the Material 3 default. DesignTokensTest compares them.
val VicuShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** A label chip is a full pill; the chip applies this itself, it is not a slot of [VicuShapes]. */
val VicuChipShape = RoundedCornerShape(percent = 50)
