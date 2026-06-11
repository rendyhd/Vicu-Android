package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Project
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectTreeTest {

    private fun p(id: Long, parent: Long = 0L): Project =
        Project(id = id, title = "P$id", parentProjectId = parent)

    @Test
    fun `children follow their parent depth-first`() {
        val tree = buildProjectTree(listOf(p(1), p(2), p(10, parent = 1)))
        assertEquals(listOf(1L, 10L, 2L), tree.map { it.first.id })
        assertEquals(listOf(0, 1, 0), tree.map { it.second })
    }

    @Test
    fun `nested children increase depth`() {
        val tree = buildProjectTree(listOf(p(1), p(2, parent = 1), p(3, parent = 2)))
        assertEquals(listOf(1L, 2L, 3L), tree.map { it.first.id })
        assertEquals(listOf(0, 1, 2), tree.map { it.second })
    }

    @Test
    fun `orphans land at root level`() {
        val tree = buildProjectTree(listOf(p(1), p(5, parent = 99)))
        assertEquals(listOf(1L, 5L), tree.map { it.first.id })
        assertEquals(listOf(0, 0), tree.map { it.second })
    }

    @Test
    fun `cyclic parents terminate`() {
        // A->B->A: neither reachable from root; both land via the orphan pass.
        // Set equality (not list) because the orphan emission order between the two
        // cycle members is arbitrary/unspecified.
        val tree = buildProjectTree(listOf(p(1, parent = 2), p(2, parent = 1)))
        assertEquals(setOf(1L, 2L), tree.map { it.first.id }.toSet())
    }

    @Test
    fun `sibling input order is preserved`() {
        val tree = buildProjectTree(listOf(p(3), p(1), p(2)))
        assertEquals(listOf(3L, 1L, 2L), tree.map { it.first.id })
    }
}
