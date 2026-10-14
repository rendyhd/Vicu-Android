package com.rendyhd.vicu.ui.components.collab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.util.Collaboration

/**
 * The comment icon with the unread dot (cross-app semantics 9.4): a [dotSize] dot in `primary` with a
 * 1.5 dp ring of [ringColor] at the icon's top right while [unread].
 */
@Composable
fun CommentIcon(
    unread: Boolean,
    iconSize: Dp,
    tint: Color,
    ringColor: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 7.dp,
) {
    Box(modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
        if (unread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = dotSize / 3, y = -dotSize / 3)
                    .size(dotSize + 3.dp)
                    .background(ringColor, CircleShape)
                    .padding(1.5.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

/**
 * The comment count on a row's meta line (design-system-v1.md section 4): the icon and the number in
 * the meta style, `primary` and bold while unread. Its own 48 dp target opens the Comments screen.
 */
@Composable
fun RowCommentCount(
    count: Int,
    unread: Boolean,
    onOpen: () -> Unit,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val label = Collaboration.commentCountLabel(count, unread)
    Row(
        modifier = modifier
            .clickable(onClickLabel = "Open comments", role = Role.Button, onClick = onOpen)
            // A 48 dp target whose content starts where the meta line does (not centred in it).
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label = "Open comments") {
                    onOpen()
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CommentIcon(unread = unread, iconSize = 14.dp, tint = color, ringColor = ringColor)
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = if (unread) FontWeight.Bold else null,
            maxLines = 1,
        )
    }
}
