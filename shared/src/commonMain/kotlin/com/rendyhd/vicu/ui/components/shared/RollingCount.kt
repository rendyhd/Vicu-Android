package com.rendyhd.vicu.ui.components.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.rendyhd.vicu.ui.theme.VicuMotion

/** The way a count rolls: up when it grew (the new number comes in from below), down when it shrank. */
enum class RollDirection { UP, DOWN }

/** Which way a count moves from [previous] to [next]; null when it did not change. Same rule as the desktop `rollDirection`. */
fun rollDirection(previous: Int, next: Int): RollDirection? = when {
    next == previous -> null
    next > previous -> RollDirection.UP
    else -> RollDirection.DOWN
}

/** How far the old and the new number travel, as a share of the number's own height (the desktop uses 60 percent). */
internal const val ROLL_TRAVEL = 0.6f

/**
 * A count that rolls when it changes (card 4.11b): the old number leaves and the new one comes in,
 * up when the count grew and down when it shrank, over `fade.base`. It does not animate on first
 * composition. Every spec follows the system animator scale: with animations off (scale 0) the new
 * number is simply there, which is the reduced variant. A screen reader gets whatever the
 * enclosing element says (the section header names its count itself).
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RollingCount(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    AnimatedContent(
        targetState = value,
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            val up = rollDirection(initialState, targetState) != RollDirection.DOWN
            val sign = if (up) 1 else -1
            val enter = slideInVertically(tween(VicuMotion.fadeBaseMs)) { height -> (sign * height * ROLL_TRAVEL).toInt() } +
                fadeIn(tween(VicuMotion.fadeBaseMs))
            val leave = slideOutVertically(tween(VicuMotion.fadeBaseMs)) { height -> (-sign * height * ROLL_TRAVEL).toInt() } +
                fadeOut(tween(VicuMotion.fadeBaseMs))
            (enter togetherWith leave).using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "rollingCount",
    ) { count ->
        Text(text = "$count", style = style, color = color, maxLines = 1)
    }
}
