package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareIntakeTest {
    private val own = "com.rendyhd.vicu.fileprovider"

    @Test
    fun otherAppsContentAddressesAreAccepted() {
        assertTrue(ShareIntake.isAcceptable("content://media/external/images/media/42", own))
        assertTrue(ShareIntake.isAcceptable("CONTENT://com.other.provider/files/a.pdf", own))
    }

    @Test
    fun fileAndOtherSchemesAreRefused() {
        assertFalse(ShareIntake.isAcceptable("file:///data/data/com.rendyhd.vicu/databases/vicu.db", own))
        assertFalse(ShareIntake.isAcceptable("http://example.com/a.png", own))
        assertFalse(ShareIntake.isAcceptable("/sdcard/a.png", own))
        assertFalse(ShareIntake.isAcceptable("", own))
        assertFalse(ShareIntake.isAcceptable("content://", own))
    }

    @Test
    fun ownFileProviderIsRefusedInAnyCase() {
        assertFalse(ShareIntake.isAcceptable("content://$own/cache/x.png", own))
        assertFalse(ShareIntake.isAcceptable("content://COM.RENDYHD.VICU.FILEPROVIDER/x", own))
        assertFalse(ShareIntake.isAcceptable("content://$own", own))
        assertFalse(ShareIntake.isAcceptable("content://$own?x=1", own))
    }

    @Test
    fun userInfoInTheAuthorityIsRefused() {
        assertFalse(ShareIntake.isAcceptable("content://evil@$own/x", own))
    }

    @Test
    fun aLongerAuthorityThatStartsWithOursIsAccepted() {
        assertTrue(ShareIntake.isAcceptable("content://$own.other/x", own))
    }

    @Test
    fun acceptableKeepsOrderAndDropsTheRest() {
        val kept = ShareIntake.acceptable(
            listOf("content://a/1", "file:///x", "content://$own/y", "content://b/2"),
            own,
        )
        assertEquals(listOf("content://a/1", "content://b/2"), kept)
    }
}
