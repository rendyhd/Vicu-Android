package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The numbers of a drawer progress ring: the same rule as the desktop's `project-progress.ts`. */
class ProjectProgressTest {

    @Test
    fun `done out of all the tasks of the project`() {
        val progress = projectProgress(done = 3, open = 5)

        assertEquals(ProjectProgress(done = 3, total = 8), progress)
        assertEquals(0.375f, progress!!.fraction)
        assertEquals("3 of 8 done", progress.label)
    }

    @Test
    fun `a project with only done tasks is a full ring`() {
        assertEquals(1f, projectProgress(done = 4, open = 0)!!.fraction)
    }

    @Test
    fun `a project with only open tasks is an empty ring`() {
        assertEquals(0f, projectProgress(done = 0, open = 4)!!.fraction)
    }

    @Test
    fun `no ring while a count is unknown`() {
        assertNull(projectProgress(done = null, open = 3))
        assertNull(projectProgress(done = 3, open = null))
    }

    @Test
    fun `no ring for a project without tasks`() {
        assertNull(projectProgress(done = 0, open = 0))
    }

    @Test
    fun `no ring for a count that cannot be right`() {
        assertNull(projectProgress(done = -1, open = 3))
        assertNull(projectProgress(done = 3, open = -1))
    }
}
