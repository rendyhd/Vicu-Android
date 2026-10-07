package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.runtime.saveable.Saver

/**
 * The dialog the Settings screen has open, if any. One at a time: every dialog is reached from the
 * list underneath it, and the one that follows another (the review cadence list opening the custom
 * cadence input) replaces it.
 *
 * It holds ids, never the objects, so it can be saved and restored (a rotation or process
 * recreation used to close every open dialog); the screen looks the project, label or list up in
 * its current state when it draws.
 */
internal sealed interface SettingsDialog {
    data object DailySummaryTime : SettingsDialog
    data object AfternoonSummaryTime : SettingsDialog
    data object ReminderOffset : SettingsDialog
    data object ReminderRelativeTo : SettingsDialog
    data object ReviewCadence : SettingsDialog
    data object ReviewCadenceCustom : SettingsDialog
    data object LogbookRetention : SettingsDialog

    /** [projectId] null creates a project. */
    data class ProjectEditor(val projectId: Long?) : SettingsDialog
    data class DeleteProject(val projectId: Long) : SettingsDialog
    data class ArchiveProject(val projectId: Long) : SettingsDialog

    /** [labelId] null creates a label. */
    data class LabelEditor(val labelId: Long?) : SettingsDialog
    data class DeleteLabel(val labelId: Long) : SettingsDialog

    /** [listId] null creates a custom list. */
    data class CustomListEditor(val listId: String?) : SettingsDialog
    data class DeleteCustomList(val listId: String) : SettingsDialog

    data object SignOut : SettingsDialog
    data object ClearCache : SettingsDialog
    data object ClearFailedActions : SettingsDialog
    data object AuthDebugLog : SettingsDialog
    data object InboxPicker : SettingsDialog
    data class BottomBarSlot(val index: Int) : SettingsDialog
}

private const val SEP = ':'

internal fun SettingsDialog.encode(): String = when (this) {
    SettingsDialog.DailySummaryTime -> "daily_time"
    SettingsDialog.AfternoonSummaryTime -> "afternoon_time"
    SettingsDialog.ReminderOffset -> "reminder_offset"
    SettingsDialog.ReminderRelativeTo -> "reminder_relative"
    SettingsDialog.ReviewCadence -> "review_cadence"
    SettingsDialog.ReviewCadenceCustom -> "review_cadence_custom"
    SettingsDialog.LogbookRetention -> "logbook_retention"
    is SettingsDialog.ProjectEditor -> withArg("project_editor", projectId?.toString())
    is SettingsDialog.DeleteProject -> withArg("delete_project", projectId.toString())
    is SettingsDialog.ArchiveProject -> withArg("archive_project", projectId.toString())
    is SettingsDialog.LabelEditor -> withArg("label_editor", labelId?.toString())
    is SettingsDialog.DeleteLabel -> withArg("delete_label", labelId.toString())
    is SettingsDialog.CustomListEditor -> withArg("list_editor", listId)
    is SettingsDialog.DeleteCustomList -> withArg("delete_list", listId)
    SettingsDialog.SignOut -> "sign_out"
    SettingsDialog.ClearCache -> "clear_cache"
    SettingsDialog.ClearFailedActions -> "clear_failed"
    SettingsDialog.AuthDebugLog -> "auth_log"
    SettingsDialog.InboxPicker -> "inbox_picker"
    is SettingsDialog.BottomBarSlot -> withArg("bottom_bar_slot", index.toString())
}

private fun withArg(name: String, arg: String?): String = if (arg == null) name else "$name$SEP$arg"

internal fun encodeSettingsDialog(dialog: SettingsDialog?): String = dialog?.encode().orEmpty()

/** The dialog [encoded] names, or null for nothing open or an encoding that is not understood. */
internal fun decodeSettingsDialog(encoded: String): SettingsDialog? {
    val name = encoded.substringBefore(SEP)
    // Null when there is no argument; the empty string is never a valid one.
    val arg = if (encoded.contains(SEP)) encoded.substringAfter(SEP) else null
    return when (name) {
        "daily_time" -> SettingsDialog.DailySummaryTime
        "afternoon_time" -> SettingsDialog.AfternoonSummaryTime
        "reminder_offset" -> SettingsDialog.ReminderOffset
        "reminder_relative" -> SettingsDialog.ReminderRelativeTo
        "review_cadence" -> SettingsDialog.ReviewCadence
        "review_cadence_custom" -> SettingsDialog.ReviewCadenceCustom
        "logbook_retention" -> SettingsDialog.LogbookRetention
        "project_editor" -> if (arg == null) SettingsDialog.ProjectEditor(null) else arg.toLongOrNull()?.let { SettingsDialog.ProjectEditor(it) }
        "delete_project" -> arg?.toLongOrNull()?.let { SettingsDialog.DeleteProject(it) }
        "archive_project" -> arg?.toLongOrNull()?.let { SettingsDialog.ArchiveProject(it) }
        "label_editor" -> if (arg == null) SettingsDialog.LabelEditor(null) else arg.toLongOrNull()?.let { SettingsDialog.LabelEditor(it) }
        "delete_label" -> arg?.toLongOrNull()?.let { SettingsDialog.DeleteLabel(it) }
        "list_editor" -> SettingsDialog.CustomListEditor(arg?.takeIf { it.isNotEmpty() })
        "delete_list" -> arg?.takeIf { it.isNotEmpty() }?.let { SettingsDialog.DeleteCustomList(it) }
        "sign_out" -> SettingsDialog.SignOut
        "clear_cache" -> SettingsDialog.ClearCache
        "clear_failed" -> SettingsDialog.ClearFailedActions
        "auth_log" -> SettingsDialog.AuthDebugLog
        "inbox_picker" -> SettingsDialog.InboxPicker
        "bottom_bar_slot" -> arg?.toIntOrNull()?.let { SettingsDialog.BottomBarSlot(it) }
        else -> null
    }
}

/** For `rememberSaveable`: the open dialog as a string, "" for none. */
internal val SettingsDialogSaver: Saver<SettingsDialog?, String> = Saver(
    save = { encodeSettingsDialog(it) },
    restore = { decodeSettingsDialog(it) },
)
