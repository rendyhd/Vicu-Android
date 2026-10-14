package com.rendyhd.vicu.ui.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenRevealTest {

    @Test
    fun `a screen arriving by a cross-fade is told so once, by its first composition`() {
        val reveal = ScreenReveal()
        reveal.arriving("today")

        assertTrue(reveal.takeArrival("today"))
        assertFalse(reveal.takeArrival("today"))
    }

    @Test
    fun `a screen that did not arrive by a cross-fade (a pop, a slide, the first screen) shows at once`() {
        assertFalse(ScreenReveal().takeArrival("inbox"))
    }

    @Test
    fun `the screen being left is drawn until the screen over it is fully in`() {
        val reveal = ScreenReveal()
        reveal.arriving("today")
        reveal.leaving("inbox", coveredBy = "today")

        assertFalse(reveal.isCovered("inbox"))
        reveal.revealedIn("today")
        assertTrue(reveal.isCovered("inbox"))
    }

    @Test
    fun `a screen that is not being left is never treated as covered`() {
        val reveal = ScreenReveal()
        reveal.revealedIn("today")

        assertFalse(reveal.isCovered("inbox"))
    }

    @Test
    fun `a screen that leaves the composition is forgotten, so a later visit starts afresh`() {
        val reveal = ScreenReveal()
        reveal.arriving("today")
        reveal.leaving("inbox", coveredBy = "today")
        reveal.revealedIn("today")

        reveal.forget("today")

        assertFalse(reveal.takeArrival("today"))
        assertFalse(reveal.isCovered("inbox"))
    }
}
