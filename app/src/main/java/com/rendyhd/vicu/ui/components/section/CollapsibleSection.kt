package com.rendyhd.vicu.ui.components.section

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CollapsibleSection(
    title: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    taskCount: Int = 0,
    isExpanded: Boolean = true,
    onToggle: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "chevronRotation",
    )

    Row(
        modifier = modifier
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier
                .size(18.dp)
                .rotate(rotation),
            tint = color,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AnimatedContent(
            targetState = if (!isExpanded && taskCount > 0) taskCount else null,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            label = "taskCount",
        ) { count ->
            if (count != null) {
                Text(
                    text = "$count",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}
