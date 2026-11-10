package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.util.SavedFilterImport

/** The notice for saved filters that were never imported (cross-app semantics 3.7). */
@Composable
internal fun SavedFilterOffer(viewModel: SavedFilterImportViewModel) {
    val offer by viewModel.offer.collectAsStateWithLifecycle()
    if (offer.isEmpty()) return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(end = 8.dp)) {
                Icon(
                    Icons.Outlined.FilterList,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (offer.size == 1) "1 new saved filter on your server" else "${offer.size} new saved filters on your server",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (offer.size == 1) "Import it as a custom list to use it in Vicu." else "Import them as custom lists to use them in Vicu.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::notNow) { Text("Not now") }
                TextButton(onClick = viewModel::open) { Text("Review") }
            }
        }
    }
}

/** The row under the lists that imports any saved filter, shown while the server has some. */
@Composable
internal fun ImportSavedFiltersRow(viewModel: SavedFilterImportViewModel) {
    val known by viewModel.known.collectAsStateWithLifecycle()
    if (known.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Import saved filters", role = Role.Button, onClick = viewModel::open)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Import saved filters", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The review sheet: each filter, what importing it does, and Import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedFilterSheetHost(viewModel: SavedFilterImportViewModel) {
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val current = sheet ?: return
    ModalBottomSheet(
        onDismissRequest = viewModel::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Import saved filters",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                "Each becomes a custom list in Vicu. Changes you make to these lists won't update the saved filters on your server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            when {
                current.loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
                current.error != null -> Text(
                    current.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(current.rows, key = { it.preview.filter.id }) { row -> SavedFilterSheetRow(row, viewModel) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = viewModel::close) { Text("Cancel") }
                Button(onClick = viewModel::importChecked, enabled = current.checkedCount > 0) {
                    Text(if (current.checkedCount == 0) "Import" else "Import ${current.checkedCount}")
                }
            }
        }
    }
}

@Composable
private fun SavedFilterSheetRow(row: SavedFilterRow, viewModel: SavedFilterImportViewModel) {
    val result = row.preview.result
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = row.checked,
                enabled = row.importable,
                role = Role.Checkbox,
                onValueChange = { viewModel.toggle(row.preview.filter.id) },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = row.checked, onCheckedChange = null, enabled = row.importable, modifier = Modifier.padding(12.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
            Text(row.preview.filter.title, style = MaterialTheme.typography.bodyLarge)
            if (row.rule.isNotBlank()) {
                Text(
                    row.rule,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                importStatusText(row),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    result.list == null -> MaterialTheme.colorScheme.error
                    row.preview.importedBefore && !row.checked -> MaterialTheme.colorScheme.onSurfaceVariant
                    result.status == SavedFilterImport.Status.EXACT -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
