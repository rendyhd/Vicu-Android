package com.rendyhd.vicu.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.color.ColorProvider
import com.rendyhd.vicu.R
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.util.DateUtils

class TaskListWidget : GlanceAppWidget() {

    companion object {
        private val COMPACT = DpSize(120.dp, 48.dp)
        private val MEDIUM = DpSize(200.dp, 100.dp)
        private val LARGE = DpSize(250.dp, 200.dp)
    }

    override val stateDefinition = TaskWidgetStateDefinition

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            val state = TaskWidgetStateDefinition.parseState(prefs)

            GlanceTheme {
                val size = androidx.glance.LocalSize.current
                when {
                    size.width < 200.dp || size.height < 100.dp -> CompactWidget(state)
                    else -> ScrollableWidget(state)
                }
            }
        }
    }
}

// Semantic color constants (not part of Material You theming)
private val overdueColor = ColorProvider(
    day = Color(0xFFEF4444),
    night = Color(0xFFF87171),
)

private val highPriorityColor = ColorProvider(
    day = Color(0xFFEF4444),
    night = Color(0xFFF87171),
)

private val medPriorityColor = ColorProvider(
    day = Color(0xFFF59E0B),
    night = Color(0xFFFBBF24),
)

// Action callbacks for deep linking
class OpenTaskEntryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("show_task_entry", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}

class OpenTaskDetailAction : ActionCallback {
    companion object {
        val TaskIdKey = ActionParameters.Key<Long>("task_id")
    }
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TaskIdKey] ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("task_id", taskId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}

@Composable
private fun CompactWidget(state: TaskWidgetState) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(actionStartActivity<MainActivity>())
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.viewName,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            AddButton()
        }
    }
}

@Composable
private fun ScrollableWidget(state: TaskWidgetState) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(16.dp),
    ) {
        WidgetHeader(state)
        Spacer(modifier = GlanceModifier.height(8.dp))
        if (state.tasks.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.lastUpdated.isEmpty()) "Loading..." else "No tasks",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(state.tasks, itemId = { it.id }) { task ->
                    WidgetTaskRow(task)
                }
            }
        }
    }
}

@Composable
private fun WidgetHeader(state: TaskWidgetState) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.viewName,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        AddButton()
    }
}

@Composable
private fun AddButton() {
    Box(
        modifier = GlanceModifier
            .height(36.dp)
            .width(46.dp)
            .cornerRadius(18.dp)
            .background(GlanceTheme.colors.primary)
            .clickable(actionRunCallback<OpenTaskEntryAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_add),
            contentDescription = "Add task",
            modifier = GlanceModifier.size(36.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
        )
    }
}

@Composable
private fun WidgetTaskRow(task: WidgetTaskItem) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                actionRunCallback<OpenTaskDetailAction>(
                    actionParametersOf(OpenTaskDetailAction.TaskIdKey to task.id)
                )
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(36.dp)
                .clickable(
                    actionRunCallback<ToggleTaskCallback>(
                        actionParametersOf(ToggleTaskCallback.TaskIdKey to task.id)
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(
                    if (task.done) R.drawable.ic_widget_circle_checked
                    else R.drawable.ic_widget_circle_unchecked
                ),
                contentDescription = if (task.done) "Completed" else "Mark complete",
                modifier = GlanceModifier.size(22.dp),
                colorFilter = if (task.done) null else ColorFilter.tint(GlanceTheme.colors.outline),
            )
        }
        Spacer(modifier = GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = task.title,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                ),
                maxLines = 1,
            )
            val dateLabel = DateUtils.formatRelativeDate(task.dueDate)
            if (dateLabel.isNotEmpty()) {
                Text(
                    text = dateLabel,
                    style = TextStyle(
                        color = if (DateUtils.isOverdue(task.dueDate)) overdueColor else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                    ),
                )
            }
        }
        // Priority indicator
        if (task.priority >= 3) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .cornerRadius(4.dp)
                    .background(highPriorityColor),
            ) {}
        } else if (task.priority == 2) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .cornerRadius(4.dp)
                    .background(medPriorityColor),
            ) {}
        }
    }
}
