package com.rendyhd.vicu.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTransitionKindTest {

    @Test
    fun `peer screens cross-fade`() {
        assertEquals(
            NavTransitionKind.CrossFade,
            navTransitionKind(TransitionScreen.Other, TransitionScreen.Other),
        )
        assertEquals(
            NavTransitionKind.CrossFade,
            navTransitionKind(TransitionScreen.Other, TransitionScreen.Project),
        )
        assertEquals(
            NavTransitionKind.CrossFade,
            navTransitionKind(TransitionScreen.Project, TransitionScreen.Other),
        )
    }

    @Test
    fun `a project inside a project is an axis move both ways`() {
        assertEquals(
            NavTransitionKind.SharedAxisX,
            navTransitionKind(TransitionScreen.Project, TransitionScreen.Project, pushed = true),
        )
    }

    @Test
    fun `a project chosen in the bottom bar or the drawer over another project is a peer`() {
        assertEquals(
            NavTransitionKind.CrossFade,
            navTransitionKind(TransitionScreen.Project, TransitionScreen.Project, pushed = false),
        )
    }

    @Test
    fun `search is an axis move in and out`() {
        assertEquals(
            NavTransitionKind.SharedAxisX,
            navTransitionKind(TransitionScreen.Other, TransitionScreen.Search),
        )
        assertEquals(
            NavTransitionKind.SharedAxisX,
            navTransitionKind(TransitionScreen.Search, TransitionScreen.Project),
        )
        assertEquals(
            NavTransitionKind.SharedAxisX,
            navTransitionKind(TransitionScreen.Project, TransitionScreen.Search, pushed = false),
        )
    }

    @Test
    fun `the axis move runs toward the end of the line and mirrors in right-to-left`() {
        val ltr = NavTransitions(axisPx = 30, rtl = false)
        assertEquals(30, ltr.axisOffset(forward = true))
        assertEquals(-30, ltr.axisOffset(forward = false))
        val rtl = NavTransitions(axisPx = 30, rtl = true)
        assertEquals(-30, rtl.axisOffset(forward = true))
        assertEquals(30, rtl.axisOffset(forward = false))
    }
}
