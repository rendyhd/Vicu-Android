package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.repository.WeekStartPreferences
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.util.WeekStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * "Week starts on" (Settings, General, Preferences; contract section 2.1): Monday, Sunday or
 * Saturday, a synced setting, so the choice reaches every device of the account. Calendars, the
 * This week window and which week Next week means follow it; repeat rules do not.
 *
 * While the Vikunja account's own week start is one of the three and differs from the one in use,
 * a line under the choices offers it. Vicu only reads that setting, and only here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WeekStartSetting(modifier: Modifier = Modifier) {
    val preferences = koinInject<WeekStartPreferences>()
    val api = koinInject<VikunjaApiService>()
    val scope = rememberCoroutineScope()
    val weekStart by preferences.weekStart.collectAsStateWithLifecycle(LocalClockDay.current.weekStart)
    // Read once while the setting is on screen. No answer (offline, a token without the right): no offer.
    val vikunja by produceState<Int?>(initialValue = null) {
        value = try {
            api.getCurrentUser().settings?.weekStart
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val offer = WeekStart.offer(vikunja, weekStart)

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = "Week starts on", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Calendars, This week and Next week follow it, on all your devices",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            WeekStart.CHOICES.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = weekStart == choice,
                    onClick = { scope.launch { preferences.set(choice) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = WeekStart.CHOICES.size),
                ) {
                    Text(choice.label)
                }
            }
        }
        if (offer != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Vikunja is set to ${offer.label}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { scope.launch { preferences.set(offer) } }) {
                    Text("Use ${offer.label}")
                }
            }
        }
    }
}
