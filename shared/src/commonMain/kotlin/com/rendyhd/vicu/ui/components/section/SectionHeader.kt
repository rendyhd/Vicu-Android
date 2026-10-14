package com.rendyhd.vicu.ui.components.section

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.RollingCount
import com.rendyhd.vicu.ui.theme.VicuMotion

/** The two levels of a grouped list (docs/design-system-v1.md, section headers). */
enum class SectionLevel {
    /** Overdue, Today, a day, or a project at the top of a list: titleSmall, sentence case. */
    ONE,

    /** A project inside a level 1 group: labelMedium semibold with the project dot. */
    TWO,
}

/** Overdue is drawn in the error role; every other group in the plain text colour. */
enum class SectionTone { DEFAULT, OVERDUE }

/**
 * The header of a group of tasks (design review A-12). [count] is the number of open tasks in the
 * group and is always shown, so it drops when one is completed. With [onToggle] and [isExpanded]
 * the header is a button that collapses its group and says "Expanded" or "Collapsed"; without them
 * it is a plain heading.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    level: SectionLevel = SectionLevel.ONE,
    count: Int? = null,
    /** The project colour as an 8 dp dot before the title. */
    dotColor: Color? = null,
    tone: SectionTone = SectionTone.DEFAULT,
    isExpanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val collapsible = onToggle != null && isExpanded != null
    val titleColor = when {
        tone == SectionTone.OVERDUE -> scheme.error
        level == SectionLevel.ONE -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }
    val textStyle = if (level == SectionLevel.ONE) {
        MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    }
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded == true) 90f else 0f,
        animationSpec = tween(durationMillis = VicuMotion.fadeFastMs),
        label = "chevronRotation",
    )

    val base = if (collapsible) {
        modifier
            .clickable(
                onClickLabel = if (isExpanded == true) "Collapse" else "Expand",
                role = Role.Button,
                onClick = onToggle!!,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    } else {
        modifier
            .heightIn(min = 40.dp)
            .padding(start = 16.dp, end = 16.dp, top = if (level == SectionLevel.ONE) 12.dp else 4.dp, bottom = 4.dp)
    }

    Row(
        modifier = base.semantics(mergeDescendants = true) {
            heading()
            // The name and the open-task count in one phrase, and the state as its own announcement.
            contentDescription = sectionDescription(title, count)
            if (isExpanded != null && collapsible) stateDescription = sectionStateDescription(isExpanded)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsible) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                // Decorative: the row announces Expanded or Collapsed itself.
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
                tint = scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (dotColor != null) {
            // Decorative: the project is named by the title next to it.
            Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = title,
            color = titleColor,
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (count != null) {
            Spacer(modifier = Modifier.width(8.dp))
            // The count rolls by one when a task is completed or added (card 4.11b).
            RollingCount(
                value = count,
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** What a screen reader says for a header: its name, and how many open tasks the group has. */
internal fun sectionDescription(title: String, taskCount: Int?): String =
    if (taskCount != null && taskCount > 0) {
        "$title, ${if (taskCount == 1) "1 task" else "$taskCount tasks"}"
    } else {
        title
    }

/** The state a screen reader announces for a collapsible header. */
internal fun sectionStateDescription(isExpanded: Boolean): String = if (isExpanded) "Expanded" else "Collapsed"
