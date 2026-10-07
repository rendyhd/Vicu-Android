package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.util.TaskLinkParser

private val LINK_TARGET = 32.dp

val ObsidianIcon: ImageVector
    get() = ImageVector.Builder(
        name = "ObsidianIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            lineTo(3f, 9f)
            lineTo(6f, 20f)
            lineTo(12f, 22f)
            lineTo(18f, 20f)
            lineTo(21f, 9f)
            close()
        }
    }.build()

val LinkChainIcon: ImageVector
    get() = ImageVector.Builder(
        name = "LinkChainIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 13f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 7.54f, 0.54f)
            lineToRelative(3f, -3f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, -7.07f, -7.07f)
            lineToRelative(-1.72f, 1.71f)
            moveTo(14f, 11f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, -7.54f, -0.54f)
            lineToRelative(-3f, 3f)
            arcToRelative(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 7.07f, 7.07f)
            lineToRelative(1.71f, -1.71f)
        }
    }.build()

/**
 * Emits 0-2 tappable link icons matching the desktop app's appearance.
 * Intended to be placed inside an existing Row (no wrapper).
 */
@Composable
fun TaskLinkIcons(
    description: String?,
    modifier: Modifier = Modifier,
) {
    val links = remember(description) { TaskLinkParser.extractLinks(description) }
    if (links.isEmpty()) return

    val uriHandler = LocalUriHandler.current

    for (link in links) {
        val (icon, tint, contentDesc) = when (link) {
            is TaskLinkParser.TaskLink.ObsidianNote -> Triple(
                ObsidianIcon,
                Color(0xFFA855F7).copy(alpha = 0.6f),
                "Open \"${link.displayName}\" in Obsidian",
            )
            is TaskLinkParser.TaskLink.BrowserPage -> Triple(
                LinkChainIcon,
                Color(0xFF3B82F6).copy(alpha = 0.6f),
                "Open \"${link.displayName}\"",
            )
        }
        val url = when (link) {
            is TaskLinkParser.TaskLink.ObsidianNote -> link.url
            is TaskLinkParser.TaskLink.BrowserPage -> link.url
        }
        // A 14 dp icon is far too small to hit: the icon is drawn small inside a 32 dp target, the
        // most a crowded task row can give each link.
        Box(
            modifier = modifier
                .size(LINK_TARGET)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) {
                    try {
                        uriHandler.openUri(url)
                    } catch (_: Exception) {
                        // Ignore any failure silently on common level
                    }
                }
                .semantics { contentDescription = contentDesc },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }
    }
}
