package com.rendyhd.vicu.permission

import com.rendyhd.vicu.permission.NotificationPermissionStep.NONE
import com.rendyhd.vicu.permission.NotificationPermissionStep.OPEN_SYSTEM_SETTINGS
import com.rendyhd.vicu.permission.NotificationPermissionStep.SHOW_RATIONALE
import com.rendyhd.vicu.permission.NotificationPermissionTrigger.AFTER_SETUP
import com.rendyhd.vicu.permission.NotificationPermissionTrigger.FEATURE_ENABLED
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationPermissionPolicyTest {

    private fun state(
        needs: Boolean = true,
        granted: Boolean = false,
        requests: Int = 0,
        setupHandled: Boolean = false,
    ) = NotificationPermissionState(needs, granted, requests, setupHandled)

    @Test
    fun `nothing to ask where there is no runtime permission or it is granted`() {
        for (trigger in NotificationPermissionTrigger.entries) {
            assertEquals(NONE, NotificationPermissionPolicy.next(state(needs = false), trigger))
            assertEquals(NONE, NotificationPermissionPolicy.next(state(granted = true), trigger))
        }
    }

    @Test
    fun `after setup the rationale is shown once`() {
        assertEquals(SHOW_RATIONALE, NotificationPermissionPolicy.next(state(), AFTER_SETUP))
        assertEquals(NONE, NotificationPermissionPolicy.next(state(setupHandled = true), AFTER_SETUP))
    }

    @Test
    fun `after setup never nags once the system has stopped asking`() {
        assertEquals(NONE, NotificationPermissionPolicy.next(state(requests = 2), AFTER_SETUP))
    }

    @Test
    fun `turning a feature on shows the rationale while requests are left`() {
        assertEquals(SHOW_RATIONALE, NotificationPermissionPolicy.next(state(), FEATURE_ENABLED))
        assertEquals(SHOW_RATIONALE, NotificationPermissionPolicy.next(state(requests = 1), FEATURE_ENABLED))
        // The one-time prompt after setup does not use up the user's own asks.
        assertEquals(SHOW_RATIONALE, NotificationPermissionPolicy.next(state(setupHandled = true), FEATURE_ENABLED))
    }

    @Test
    fun `turning a feature on points to system settings when no request is left`() {
        assertEquals(OPEN_SYSTEM_SETTINGS, NotificationPermissionPolicy.next(state(requests = 2), FEATURE_ENABLED))
        assertEquals(OPEN_SYSTEM_SETTINGS, NotificationPermissionPolicy.next(state(requests = 5), FEATURE_ENABLED))
    }

    @Test
    fun `requests left counts down from two and stops at zero`() {
        assertEquals(2, NotificationPermissionPolicy.requestsLeft(state(requests = 0)))
        assertEquals(1, NotificationPermissionPolicy.requestsLeft(state(requests = 1)))
        assertEquals(0, NotificationPermissionPolicy.requestsLeft(state(requests = 2)))
        assertEquals(0, NotificationPermissionPolicy.requestsLeft(state(requests = 9)))
    }

    @Test
    fun `the Settings button asks while it can and opens system settings after`() {
        assertEquals(SHOW_RATIONALE, NotificationPermissionPolicy.nextFromSettings(state(requests = 1)))
        assertEquals(OPEN_SYSTEM_SETTINGS, NotificationPermissionPolicy.nextFromSettings(state(requests = 2)))
        // Granted but switched off in system settings, or no runtime permission: only the page helps.
        assertEquals(OPEN_SYSTEM_SETTINGS, NotificationPermissionPolicy.nextFromSettings(state(granted = true)))
        assertEquals(OPEN_SYSTEM_SETTINGS, NotificationPermissionPolicy.nextFromSettings(state(needs = false)))
    }
}
