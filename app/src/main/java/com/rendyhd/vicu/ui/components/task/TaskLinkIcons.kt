package com.rendyhd.vicu.ui.components.task

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.util.TaskLinkParser

/** Obsidian logo icon — matches the desktop SVG exactly. */
private val ObsidianIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Obsidian",
        defaultWidth = 14.dp,
        defaultHeight = 14.dp,
        viewportWidth = 100f,
        viewportHeight = 100f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(68.6f, 2.2f)
        lineTo(32.8f, 19.8f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2.2f, 2.7f)
        lineTo(18.2f, 80.1f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1f, 3.7f)
        lineToRelative(16.7f, 16f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 3.6f, 1.1f)
        lineToRelative(42f, -9.6f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2.8f, -2.3f)
        lineTo(97.7f, 46f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, -0.5f, -3.8f)
        lineTo(72.3f, 3f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, -3.7f, -1.8f)
        close()
    }.build()
}

/** Link chain icon — matches the desktop's Lucide "link" SVG exactly. */
private val LinkChainIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LinkChain",
        defaultWidth = 14.dp,
        defaultHeight = 14.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
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
    }.build()
}

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

    val context = LocalContext.current

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
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            modifier = modifier
                .size(14.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show()
                    }
                },
            tint = tint,
        )
    }
}
