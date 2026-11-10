package com.rendyhd.vicu.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.SavedFilterRef
import com.rendyhd.vicu.data.sync.SavedFilterImporter
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.ui.components.shared.RuleEditing
import com.rendyhd.vicu.util.SavedFilterImport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the review sheet; [rule] is the filter's rule with label and project names. */
data class SavedFilterRow(
    val preview: SavedFilterImporter.Preview,
    val checked: Boolean,
    val rule: String = preview.rule,
) {
    val importable: Boolean get() = preview.result.list != null
}

/** The review sheet: loading, its rows, or why it could not read the filters. */
data class SavedFilterSheet(
    val loading: Boolean = true,
    val rows: List<SavedFilterRow> = emptyList(),
    val error: String? = null,
) {
    val checkedCount: Int get() = rows.count { it.checked && it.importable }
}

/**
 * Settings, Custom Lists: the offer of saved filters that were never imported, the "Import saved
 * filters" row and the review sheet (cross-app semantics 3.7).
 */
class SavedFilterImportViewModel(
    private val importer: SavedFilterImporter,
    private val labelRepository: LabelRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    /** Filters never imported and not put off on this device. */
    val offer: StateFlow<List<SavedFilterRef>> =
        importer.offer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every saved filter on the server. */
    val known: StateFlow<List<SavedFilterRef>> =
        importer.known.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sheet = MutableStateFlow<SavedFilterSheet?>(null)
    val sheet: StateFlow<SavedFilterSheet?> = _sheet.asStateFlow()

    /** Opens the sheet on every filter the server has; the ones never imported start ticked. */
    fun open() {
        val filters = known.value
        _sheet.value = SavedFilterSheet(loading = true)
        viewModelScope.launch {
            val previews = importer.previews(filters)
            // The rule as the list editor shows it: names instead of label and project ids.
            val labels = labelRepository.getAll().first().map { RuleEditing.Named(it.id, it.title) }
            val projects = projectRepository.getAll().first().map { RuleEditing.Named(it.id, it.title) }
            _sheet.update { current ->
                if (current == null) return@update null
                if (previews == null) {
                    SavedFilterSheet(loading = false, error = "Vicu couldn't read the saved filters. Check the connection and try again.")
                } else {
                    SavedFilterSheet(
                        loading = false,
                        rows = previews.map {
                            SavedFilterRow(
                                it,
                                checked = !it.importedBefore && it.result.list != null,
                                rule = RuleEditing.toDisplay(it.rule, labels, projects).ifBlank { it.rule },
                            )
                        },
                    )
                }
            }
        }
    }

    fun toggle(filterId: Long) {
        _sheet.update { sheet ->
            sheet?.copy(rows = sheet.rows.map { if (it.preview.filter.id == filterId && it.importable) it.copy(checked = !it.checked) else it })
        }
    }

    fun close() {
        _sheet.value = null
    }

    fun importChecked() {
        val rows = _sheet.value?.rows ?: return
        _sheet.value = null
        viewModelScope.launch { importer.import(rows.filter { it.checked && it.importable }.map { it.preview }) }
    }

    /** Not now on the offer: hidden on this device until another filter appears. */
    fun notNow() {
        val filters = offer.value
        viewModelScope.launch { importer.dismiss(filters) }
    }
}

/** What an import will do, in words for the sheet. */
internal fun importStatusText(row: SavedFilterRow): String {
    val result = row.preview.result
    if (result.list == null) return "Can't import: Vicu can't read this filter yet"
    if (row.preview.importedBefore && !row.checked) return "Imported before"
    if (result.status == SavedFilterImport.Status.EXACT) return "Imports exactly"
    val parts = result.reasons.map {
        when (it) {
            SavedFilterImport.Reason.KANBAN_COLUMN -> "the kanban column"
            SavedFilterImport.Reason.OTHER_FIELD -> "a condition Vicu can't check"
            SavedFilterImport.Reason.MANUAL_ORDER -> "the manual order"
            SavedFilterImport.Reason.SORT_KEY -> "a sort order"
        }
    }
    val joined = if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    return "Leaves out $joined"
}
