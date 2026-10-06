package com.rendyhd.vicu.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.ui.theme.VicuTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class WidgetConfigActivity : ComponentActivity() {

    private val projectDao: ProjectDao by inject()
    private val customListStore: CustomListStore by inject()

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default result is CANCELED — back out = no widget placed
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            VicuTheme {
                ConfigScreen(
                    appWidgetId = appWidgetId,
                    projectDao = projectDao,
                    customListStore = customListStore,
                    onSelect = { config -> saveConfigAndFinish(config) },
                )
            }
        }
    }

    private fun saveConfigAndFinish(config: WidgetConfig) {
        lifecycleScope.launch {
            WidgetConfigStore.saveConfig(this@WidgetConfigActivity, appWidgetId, config)
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@WidgetConfigActivity, TaskWidgetStateDefinition, glanceId) { prefs ->
                val state = TaskWidgetStateDefinition.parseState(prefs).withConfig(config)
                prefs.toMutablePreferences().apply {
                    this[TaskWidgetStateDefinition.KEY_STATE] = TaskWidgetStateDefinition.encodeState(state)
                }
            }
            TaskListWidget().update(this@WidgetConfigActivity, glanceId)
            WidgetUpdateScheduler.enqueueImmediateUpdate(this@WidgetConfigActivity, appWidgetId)
            WidgetUpdateScheduler.schedulePeriodicRefresh(this@WidgetConfigActivity)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    appWidgetId: Int,
    projectDao: ProjectDao,
    customListStore: CustomListStore,
    onSelect: (WidgetConfig) -> Unit,
) {
    val context = LocalContext.current
    var projects by remember { mutableStateOf<List<ProjectEntity>>(emptyList()) }
    var customLists by remember { mutableStateOf<List<CustomList>>(emptyList()) }
    var selectedConfig by remember { mutableStateOf(WidgetConfig()) }
    var configLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(appWidgetId) {
        selectedConfig = WidgetConfigStore.getConfig(context, appWidgetId) ?: WidgetConfig()
        configLoaded = true
        projects = projectDao.getAllSync().filter { it.parentProjectId == 0L }
        customLists = customListStore.getAll().first()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Configure Widget") })
        },
        bottomBar = {
            Button(
                onClick = { onSelect(selectedConfig) },
                enabled = configLoaded,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text("Save widget")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("Appearance")
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        selectedConfig = selectedConfig.copy(
                            transparentBackground = !selectedConfig.transparentBackground,
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Transparent background", modifier = Modifier.weight(1f))
                Switch(
                    checked = selectedConfig.transparentBackground,
                    onCheckedChange = {
                        selectedConfig = selectedConfig.copy(transparentBackground = it)
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel("Smart Lists")
            ConfigOption(
                icon = Icons.Outlined.WbSunny,
                label = "Today",
                selected = selectedConfig.viewType == WidgetViewType.TODAY,
                onClick = {
                    selectedConfig = selectedConfig.copy(viewType = WidgetViewType.TODAY, viewId = "", viewName = "Today")
                },
            )
            ConfigOption(
                icon = Icons.Outlined.MoveToInbox,
                label = "Inbox",
                selected = selectedConfig.viewType == WidgetViewType.INBOX,
                onClick = {
                    selectedConfig = selectedConfig.copy(viewType = WidgetViewType.INBOX, viewId = "", viewName = "Inbox")
                },
            )
            ConfigOption(
                icon = Icons.Outlined.CalendarMonth,
                label = "Upcoming",
                selected = selectedConfig.viewType == WidgetViewType.UPCOMING,
                onClick = {
                    selectedConfig = selectedConfig.copy(viewType = WidgetViewType.UPCOMING, viewId = "", viewName = "Upcoming")
                },
            )
            ConfigOption(
                icon = Icons.Outlined.AllInclusive,
                label = "Anytime",
                selected = selectedConfig.viewType == WidgetViewType.ANYTIME,
                onClick = {
                    selectedConfig = selectedConfig.copy(viewType = WidgetViewType.ANYTIME, viewId = "", viewName = "Anytime")
                },
            )

            if (projects.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionLabel("Projects")
                projects.forEach { project ->
                    ConfigOption(
                        icon = Icons.Outlined.Folder,
                        label = project.title,
                        selected = selectedConfig.viewType == WidgetViewType.PROJECT &&
                            selectedConfig.viewId == project.id.toString(),
                        onClick = {
                            selectedConfig = selectedConfig.copy(
                                viewType = WidgetViewType.PROJECT,
                                viewId = project.id.toString(),
                                viewName = project.title,
                            )
                        },
                    )
                }
            }

            if (customLists.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionLabel("Custom Lists")
                customLists.forEach { list ->
                    ConfigOption(
                        icon = Icons.AutoMirrored.Outlined.List,
                        label = list.name,
                        selected = selectedConfig.viewType == WidgetViewType.CUSTOM_LIST &&
                            selectedConfig.viewId == list.id,
                        onClick = {
                            selectedConfig = selectedConfig.copy(
                                viewType = WidgetViewType.CUSTOM_LIST,
                                viewId = list.id,
                                viewName = list.name,
                            )
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ConfigOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        RadioButton(selected = selected, onClick = onClick)
    }
}
