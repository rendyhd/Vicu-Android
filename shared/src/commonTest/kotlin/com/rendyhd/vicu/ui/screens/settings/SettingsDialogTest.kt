package com.rendyhd.vicu.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsDialogTest {

    private val all = listOf(
        SettingsDialog.DailySummaryTime,
        SettingsDialog.AfternoonSummaryTime,
        SettingsDialog.ReminderOffset,
        SettingsDialog.ReminderRelativeTo,
        SettingsDialog.ReviewCadence,
        SettingsDialog.ReviewCadenceCustom,
        SettingsDialog.LogbookRetention,
        SettingsDialog.ExcludedFromReview,
        SettingsDialog.ProjectEditor(null),
        SettingsDialog.ProjectEditor(42L),
        SettingsDialog.SubprojectEditor(42L),
        SettingsDialog.DeleteProject(7L),
        SettingsDialog.ArchiveProject(8L),
        SettingsDialog.LabelEditor(null),
        SettingsDialog.LabelEditor(3L),
        SettingsDialog.DeleteLabel(4L),
        SettingsDialog.CustomListEditor(null),
        SettingsDialog.CustomListEditor("c0ffee-1234"),
        SettingsDialog.DeleteCustomList("a:b:c"),
        SettingsDialog.SignOut,
        SettingsDialog.ClearCache,
        SettingsDialog.ClearFailedActions,
        SettingsDialog.AuthDebugLog,
        SettingsDialog.InboxPicker,
        SettingsDialog.BottomBarSlot(2),
    )

    @Test
    fun `every dialog survives being saved and restored`() {
        all.forEach { dialog ->
            assertEquals(dialog, decodeSettingsDialog(dialog.encode()), dialog.toString())
        }
    }

    @Test
    fun `every dialog has its own encoding`() {
        assertEquals(all.size, all.map { it.encode() }.toSet().size)
    }

    @Test
    fun `nothing open is saved as an empty string and restored as nothing`() {
        assertEquals("", encodeSettingsDialog(null))
        assertNull(decodeSettingsDialog(""))
    }

    @Test
    fun `an encoding from a newer or damaged state restores as nothing instead of failing`() {
        assertNull(decodeSettingsDialog("no_such_dialog"))
        assertNull(decodeSettingsDialog("delete_project:not-a-number"))
        assertNull(decodeSettingsDialog("bottom_bar_slot"))
        assertNull(decodeSettingsDialog("bottom_bar_slot:x"))
        assertNull(decodeSettingsDialog("subproject_editor"), "a new subproject needs its parent")
        assertNull(decodeSettingsDialog("subproject_editor:x"))
    }
}
