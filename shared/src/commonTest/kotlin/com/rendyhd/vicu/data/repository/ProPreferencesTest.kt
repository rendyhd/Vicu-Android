package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.util.pro.ProjectMode
import com.rendyhd.vicu.util.pro.RepeatRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pro's synced keys (cross-app semantics 11.3 and 21), as the desktop's `use-pro-prefs.ts` writes them. */
class ProPreferencesTest {

    @Test
    fun `the keys are named as on the desktop`() {
        assertEquals("pro.area", SyncedPrefKeys.PRO_AREA)
        assertEquals("project_mode.12", SyncedPrefKeys.projectModeOf(12))
        assertEquals("project_status.12", SyncedPrefKeys.projectStatusOf(12))
        assertEquals("label_status.4", SyncedPrefKeys.labelStatusOf(4))
        assertEquals("label_kind.4", SyncedPrefKeys.labelKindOf(4))
        assertEquals("repeat_rule.99", SyncedPrefKeys.repeatRuleOf(99))
        assertTrue(SyncedPrefKeys.isProKey("label_kind.4"))
        assertFalse(SyncedPrefKeys.isProKey("label_kind.x"))
        assertFalse(SyncedPrefKeys.isProKey("task_order"))
    }

    @Test
    fun `only the values the registry allows are writable`() {
        assertTrue(SyncedPrefKeys.isWritableProValue("project_mode.1", JsonPrimitive("sequential")))
        assertTrue(SyncedPrefKeys.isWritableProValue("project_mode.1", JsonPrimitive("single")))
        assertFalse(SyncedPrefKeys.isWritableProValue("project_mode.1", JsonPrimitive("parallel")))
        assertTrue(SyncedPrefKeys.isWritableProValue("project_status.1", JsonPrimitive("on_hold")))
        assertFalse(SyncedPrefKeys.isWritableProValue("label_status.1", JsonPrimitive("held")))
        assertTrue(SyncedPrefKeys.isWritableProValue("label_kind.1", JsonPrimitive("person")))
        assertTrue(SyncedPrefKeys.isWritableProValue("repeat_rule.1", JsonObject(mapOf("count" to JsonPrimitive(3)))))
        assertFalse(SyncedPrefKeys.isWritableProValue("repeat_rule.1", JsonObject(emptyMap())))
        assertTrue(SyncedPrefKeys.isWritableProValue("pro.area", JsonArray(listOf(JsonPrimitive("c1")))))
        assertFalse(SyncedPrefKeys.isWritableProValue("pro.area", JsonArray(listOf(JsonPrimitive(3)))))
        // null resets any Pro key; nothing else is a Pro key.
        assertTrue(SyncedPrefKeys.isWritableProValue("label_kind.1", null))
        assertFalse(SyncedPrefKeys.isWritableProValue("task_order", null))
    }

    @Test
    fun `setters write the value, and the default as null`() = runTest {
        val synced = InMemorySyncedEntries(mapOf("task_order" to JsonPrimitive("due_date"), "later.key" to JsonPrimitive(1)))
        val pro = ProPreferences(synced)

        assertTrue(pro.setProjectMode(12, ProjectMode.SEQUENTIAL))
        assertTrue(pro.setProjectOnHold(13, true))
        assertTrue(pro.setLabelOnHold(4, true))
        assertTrue(pro.setPerson(5, true))
        assertTrue(pro.setRepeatRule(99, RepeatRule(count = 3, catchUp = true)))
        assertTrue(pro.setArea(listOf("c1", "c1", "c2")))

        val snapshot = pro.snapshot.first()
        assertEquals(ProjectMode.SEQUENTIAL, snapshot.projectMode(12))
        assertTrue(snapshot.projectOnHold(13))
        assertEquals(setOf(4L), snapshot.heldLabels)
        assertEquals(setOf(5L), snapshot.people)
        assertEquals(RepeatRule(count = 3, catchUp = true), snapshot.repeatRule(99))
        assertEquals(listOf("c1", "c2"), snapshot.area)
        // The snapshot holds Pro's keys only.
        assertFalse("task_order" in snapshot.values)

        pro.setProjectMode(12, ProjectMode.PARALLEL)
        pro.setPerson(5, false)
        pro.setRepeatRule(99, null)
        pro.setArea(emptyList())
        val entries = synced.entries.first()
        assertTrue(entries.containsKey("project_mode.12"))
        assertNull(entries["project_mode.12"])
        assertNull(entries["label_kind.5"])
        assertNull(entries["repeat_rule.99"])
        assertNull(entries["pro.area"])
        // Keys this version does not know are left alone.
        assertEquals(JsonPrimitive(1), entries["later.key"])
    }

    @Test
    fun `a rule with nothing in it is no rule`() = runTest {
        val synced = InMemorySyncedEntries()
        val pro = ProPreferences(synced)

        pro.setRepeatRule(1, RepeatRule())

        assertFalse(synced.entries.first().containsKey("repeat_rule.1"))
    }
}
