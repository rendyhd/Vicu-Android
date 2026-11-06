package com.rendyhd.vicu.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLinkTest {

    private val links = listOf(
        AppLink.OpenTask(42),
        AppLink.OpenView(ViewTarget.Today),
        AppLink.OpenView(ViewTarget.Inbox),
        AppLink.OpenView(ViewTarget.Upcoming),
        AppLink.OpenView(ViewTarget.Anytime),
        AppLink.OpenView(ViewTarget.Routines),
        AppLink.OpenView(ViewTarget.Plan),
        AppLink.OpenView(ViewTarget.Project(7)),
        AppLink.OpenView(ViewTarget.CustomList("list a/b ü")),
        AppLink.AddTask(),
        AppLink.AddTask(projectId = 7),
        AppLink.Dictate,
    )

    @Test
    fun `every link survives a round trip through its URL`() {
        for (link in links) {
            assertEquals(link, AppLink.parse(link.toUrl("vicu")), link.toUrl("vicu"))
            assertEquals(link, AppLink.parse(link.toUrl("vicu-debug")))
        }
    }

    @Test
    fun `the URLs are readable`() {
        assertEquals("vicu://task/42", AppLink.OpenTask(42).toUrl("vicu"))
        assertEquals("vicu://view/TODAY", AppLink.OpenView(ViewTarget.Today).toUrl("vicu"))
        assertEquals("vicu://view/PROJECT/7", AppLink.OpenView(ViewTarget.Project(7)).toUrl("vicu"))
        assertEquals("vicu://add?project=7", AppLink.AddTask(7).toUrl("vicu"))
        assertEquals("vicu://dictate", AppLink.Dictate.toUrl("vicu"))
    }

    @Test
    fun `links that name nothing are refused`() {
        assertNull(AppLink.parse("vicu://"))
        assertNull(AppLink.parse("not a url"))
        assertNull(AppLink.parse("vicu://task/abc"))
        assertNull(AppLink.parse("vicu://task/0"))
        assertNull(AppLink.parse("vicu://view/NOWHERE"))
        assertNull(AppLink.parse("vicu://view/PROJECT"))
        assertNull(AppLink.parse("vicu://view/CUSTOM_LIST/"))
        assertNull(AppLink.parse("vicu://settings"))
    }

    @Test
    fun `an add link without a usable project adds to the default project`() {
        assertEquals(AppLink.AddTask(null), AppLink.parse("vicu://add?project=x"))
        assertEquals(AppLink.AddTask(null), AppLink.parse("vicu://add/"))
    }
}
