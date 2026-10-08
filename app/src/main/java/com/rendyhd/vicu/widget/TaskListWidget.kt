package com.rendyhd.vicu.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.LocalContext
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
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
import com.rendyhd.vicu.putViewTarget
import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.ui.theme.VicuDarkColorScheme
import com.rendyhd.vicu.ui.theme.VicuDarkColors
import com.rendyhd.vicu.ui.theme.VicuLightColorScheme
import com.rendyhd.vicu.ui.theme.VicuLightColors
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

// Status and priority colours come from the Vicu roles (not the Material You theming of the widget).
private val overdueColor = ColorProvider(
    day = VicuLightColorScheme.error,
    night = VicuDarkColorScheme.error,
)

/** The vector drawable of a priority mark (docs/design-system-v1.md), or null when none is shown. */
private fun priorityMarkDrawable(priority: Int): Int? = when (priority) {
    1 -> R.drawable.ic_priority_bars_1
    2 -> R.drawable.ic_priority_bars_2
    3 -> R.drawable.ic_priority_bars_3
    4, 5 -> R.drawable.ic_priority_urgent
    else -> null
}

private fun priorityMarkDescription(priority: Int): String? = when (priority) {
    1 -> "Low priority"
    2 -> "Medium priority"
    3 -> "High priority"
    4 -> "Urgent priority"
    5 -> "Do now priority"
    else -> null
}

private fun priorityColor(priority: Int) = ColorProvider(
    day = VicuLightColors.priority(priority) ?: VicuLightColors.priorityLow,
    night = VicuDarkColors.priority(priority) ?: VicuDarkColors.priorityLow,
)

// Action callbacks for deep linking
class OpenTaskEntryAction : ActionCallback {
    companion object {
        val DefaultProjectIdKey = ActionParameters.Key<Long>("default_project_id")
    }
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHOW_TASK_ENTRY, true)
            val projectId = parameters[DefaultProjectIdKey] ?: 0L
            if (projectId != 0L) {
                putExtra(MainActivity.EXTRA_DEFAULT_PROJECT_ID, projectId)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}

class OpenWidgetViewAction : ActionCallback {
    companion object {
        val ViewTypeKey = ActionParameters.Key<String>("view_type")
        val ViewIdKey = ActionParameters.Key<String>("view_id")
    }
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(context, MainActivity::class.java).apply {
            // The action parameters can only carry strings; the intent carries the typed target.
            ViewTarget.parse(parameters[ViewTypeKey], parameters[ViewIdKey])?.let { putViewTarget(it) }
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

/**
 * Resolves which project the + button should add tasks to, based on the widget state.
 * - PROJECT widget → that project's ID
 * - CUSTOM_LIST widget → the list's addToProjectId (0 = inbox)
 * - Everything else → 0 (inbox)
 */
private fun resolveAddProjectId(state: TaskWidgetState): Long {
    if (!state.smartAdd) return 0L
    return when (state.viewType) {
        WidgetViewType.PROJECT -> state.viewId.toLongOrNull() ?: 0L
        WidgetViewType.CUSTOM_LIST -> state.addToProjectId
        else -> 0L
    }
}

/**
 * Builds the click action for the widget title.
 * When contextNav is enabled, navigates to the matching screen.
 * Otherwise, just opens the app.
 */
@Composable
private fun titleClickAction(state: TaskWidgetState): Action {
    val target = if (state.contextNav) state.viewType.toViewTarget(state.viewId) else null
    return if (target != null) {
        actionRunCallback<OpenWidgetViewAction>(
            actionParametersOf(
                OpenWidgetViewAction.ViewTypeKey to target.typeName,
                OpenWidgetViewAction.ViewIdKey to target.idOrEmpty,
            )
        )
    } else {
        actionStartActivity<MainActivity>()
    }
}

@Composable
private fun CompactWidget(state: TaskWidgetState) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(titleClickAction(state))
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
            AddButton(state)
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
        if (state.error != null) {
            Box(
                modifier = GlanceModifier.fillMaxSize()
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            }
        } else if (state.tasks.isEmpty()) {
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
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(titleClickAction(state)),
        )
        AddButton(state)
    }
}

@Composable
private fun AddButton(state: TaskWidgetState) {
    val projectId = resolveAddProjectId(state)
    val action = if (projectId != 0L) {
        actionRunCallback<OpenTaskEntryAction>(
            actionParametersOf(OpenTaskEntryAction.DefaultProjectIdKey to projectId)
        )
    } else {
        actionRunCallback<OpenTaskEntryAction>()
    }
    Box(
        modifier = GlanceModifier
            .height(36.dp)
            .width(46.dp)
            .cornerRadius(18.dp)
            .background(GlanceTheme.colors.primary)
            .clickable(action),
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
                .cornerRadius(18.dp)
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
            // The time shows only when the due date has an explicit one, in the device's 12/24 hour style.
            val dateLabel = DateUtils.formatDueDate(
                task.dueDate,
                is24Hour = DateFormat.is24HourFormat(LocalContext.current),
            )
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
        // Priority mark: the same shapes as the app (bars for low to high, a square for urgent), tinted by the priority role.
        priorityMarkDrawable(task.priority)?.let { drawable ->
            Image(
                provider = ImageProvider(drawable),
                contentDescription = priorityMarkDescription(task.priority),
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(priorityColor(task.priority)),
            )
        }
    }
}
