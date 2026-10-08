package com.rendyhd.vicu.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.ui.theme.VicuTheme
import org.koin.android.ext.android.inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    projectDao: ProjectDao,
    customListStore: CustomListStore,
    onSelect: (WidgetConfig) -> Unit,
) {
    val identity = LocalVicuColors.current.identity
    var projects by remember { mutableStateOf<List<ProjectEntity>>(emptyList()) }
    var customLists by remember { mutableStateOf<List<CustomList>>(emptyList()) }

    LaunchedEffect(Unit) {
        projects = projectDao.getAllSync().filter { it.parentProjectId == 0L }
        customLists = customListStore.getAll().first()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Configure Widget") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("Smart Lists")
            ConfigOption(
                icon = SmartListIdentity.TODAY.icon,
                iconTint = SmartListIdentity.TODAY.color(identity),
                label = "Today",
                onClick = {
                    onSelect(WidgetConfig(WidgetViewType.TODAY, viewName = "Today"))
                },
            )
            ConfigOption(
                icon = SmartListIdentity.INBOX.icon,
                iconTint = SmartListIdentity.INBOX.color(identity),
                label = "Inbox",
                onClick = {
                    onSelect(WidgetConfig(WidgetViewType.INBOX, viewName = "Inbox"))
                },
            )
            ConfigOption(
                icon = SmartListIdentity.UPCOMING.icon,
                iconTint = SmartListIdentity.UPCOMING.color(identity),
                label = "Upcoming",
                onClick = {
                    onSelect(WidgetConfig(WidgetViewType.UPCOMING, viewName = "Upcoming"))
                },
            )
            ConfigOption(
                icon = SmartListIdentity.ANYTIME.icon,
                iconTint = SmartListIdentity.ANYTIME.color(identity),
                label = "Anytime",
                onClick = {
                    onSelect(WidgetConfig(WidgetViewType.ANYTIME, viewName = "Anytime"))
                },
            )

            if (projects.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionLabel("Projects")
                projects.forEach { project ->
                    ConfigOption(
                        icon = Icons.Outlined.Folder,
                        label = project.title,
                        onClick = {
                            onSelect(
                                WidgetConfig(
                                    viewType = WidgetViewType.PROJECT,
                                    viewId = project.id.toString(),
                                    viewName = project.title,
                                )
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
                        onClick = {
                            onSelect(
                                WidgetConfig(
                                    viewType = WidgetViewType.CUSTOM_LIST,
                                    viewId = list.id,
                                    viewName = list.name,
                                )
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
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            tint = iconTint,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
