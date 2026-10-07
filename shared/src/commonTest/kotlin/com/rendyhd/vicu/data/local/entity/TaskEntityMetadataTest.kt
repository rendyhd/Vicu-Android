package com.rendyhd.vicu.data.local.entity

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a task is hidden sync metadata is stored with the row, worked out when the row is
 * written (A-UI-19). The list queries filter on the column and the repository checks the flag, so
 * nothing scans descriptions on every emission any more.
 */
class TaskEntityMetadataTest {

    private val mapper = TaskMapper(authTestJson)

    private val routineCarrier = "<!-- vicu-routine:v1:eyJ4IjoxfQ -->"
    private val routineArchive = "<!-- vicu-routine:archive:v1:eyJ4IjoxfQ -->"
    private val customListCarrier = "<!-- vicu-custom-lists:v1:eyJ4IjoxfQ -->"

    @Test
    fun `every kind of marker sets the flag`() {
        assertTrue(TaskEntity(id = 1, description = routineCarrier).isMetadata)
        assertTrue(TaskEntity(id = 2, description = routineArchive).isMetadata)
        assertTrue(TaskEntity(id = 3, description = customListCarrier).isMetadata)
        assertTrue(TaskEntity(id = 4, description = "Notes\n$routineCarrier").isMetadata, "after a body")
        assertTrue(TaskEntity(id = 5, description = "<!--vicu-custom-lists:v1:e30-->").isMetadata, "no spaces")
    }

    @Test
    fun `ordinary descriptions do not`() {
        assertFalse(TaskEntity(id = 1).isMetadata)
        assertFalse(TaskEntity(id = 2, description = "<p>Buy milk</p>").isMetadata)
        assertFalse(TaskEntity(id = 3, description = "mentions vicu-routine: in plain text").isMetadata)
        assertFalse(TaskEntity(id = 4, description = "<!-- an ordinary comment -->").isMetadata)
    }

    @Test
    fun `a task mapped from the server carries the flag`() {
        val carrier = with(mapper) { TaskDto(id = 10, title = "Carrier", description = routineCarrier, done = true).toEntity() }
        val ordinary = with(mapper) { TaskDto(id = 11, title = "Milk", description = "<p>x</p>").toEntity() }

        assertTrue(carrier.isMetadata)
        assertFalse(ordinary.isMetadata)
    }

    @Test
    fun `an optimistic edit recomputes the flag from the new description`() {
        val ordinary = TaskEntity(id = 20, title = "Milk", description = "plain")
        val becomesCarrier = with(mapper) {
            ordinary.withEditedFields(Task(id = 20, title = "Milk", description = customListCarrier))
        }
        val carrier = TaskEntity(id = 21, description = routineCarrier)
        val becomesOrdinary = with(mapper) {
            carrier.withEditedFields(Task(id = 21, title = "Milk", description = "plain again"))
        }

        assertTrue(becomesCarrier.isMetadata)
        assertFalse(becomesOrdinary.isMetadata)
    }

    @Test
    fun `an edit that leaves the description alone keeps the flag`() {
        val carrier = TaskEntity(id = 30, title = "Vitamin D", description = routineCarrier, done = true)

        val edited = with(mapper) { carrier.withEditedFields(Task(id = 30, title = "Vitamin D3", description = routineCarrier, done = true)) }
        val toggled = with(mapper) { carrier.withDoneState(done = false, doneAt = "") }

        assertEquals(true, edited.isMetadata)
        assertEquals(true, toggled.isMetadata)
    }

    @Test
    fun `the flag is part of the row, so an unchanged row compares equal and a refresh does not rewrite it`() {
        val first = with(mapper) { TaskDto(id = 40, title = "Carrier", description = routineCarrier, done = true).toEntity() }
        val second = with(mapper) { TaskDto(id = 40, title = "Carrier", description = routineCarrier, done = true).toEntity() }

        assertEquals(first, second)
    }
}
