package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.rendyhd.vicu.ui.theme.VicuColorRole

/**
 * The colours of the swipe on a task row (docs/design-system-v1.md, section 7 and the swipe card of
 * the design review). While the row is dragged the whole row is tinted with the action's colour, a
 * little deeper the further it goes ([TINT_START] to [TINT_END] of the colour over the screen
 * background); at the commit point ([COMMIT]) the strip the row has uncovered turns to the full
 * colour and the word beside the icon switches to the colour's own `on` colour.
 */
object SwipeTint {
    /** The fraction of the row's width at which the swipe commits (the colour goes full, the icon pops). */
    const val COMMIT = 0.5f

    /** Opacity of the action colour over the row at the start of the drag. */
    const val TINT_START = 0.12f

    /** Opacity of the action colour just before the commit point. */
    const val TINT_END = 0.35f

    /** The row's tint opacity at [fraction] of the width dragged: 12 % rising to 35 % at the commit point. */
    fun tintAlpha(fraction: Float): Float {
        val t = (fraction / COMMIT).coerceIn(0f, 1f)
        return TINT_START + (TINT_END - TINT_START) * t
    }

    /** The row's tinted background at [fraction], as the opaque colour the eye sees over [surface]. */
    fun background(role: VicuColorRole, surface: Color, fraction: Float): Color =
        role.color.copy(alpha = tintAlpha(fraction)).compositeOver(surface)

    /** The colour behind the word in the uncovered strip: the tint before the commit, the full colour from it. */
    fun labelBackground(role: VicuColorRole, surface: Color, fraction: Float): Color =
        if (fraction >= COMMIT) role.color else background(role, surface, fraction)

    /**
     * The colour of the word and icon at [fraction]. From the commit point it is the role's `on`
     * colour on the full colour; before it, the first of the screen's text colour and the role's
     * container text colour that reads at 4.5:1 on the tint (the better one when neither does).
     */
    fun labelColor(role: VicuColorRole, surface: Color, onSurface: Color, fraction: Float): Color {
        if (fraction >= COMMIT) return role.onColor
        val bg = background(role, surface, fraction)
        val candidates = listOf(onSurface, role.onContainer)
        return candidates.firstOrNull { contrastRatio(it, bg) >= MIN_CONTRAST }
            ?: candidates.maxBy { contrastRatio(it, bg) }
    }

    const val MIN_CONTRAST = 4.5

    /** WCAG contrast ratio of two opaque colours. */
    fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
