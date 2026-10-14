package com.rendyhd.vicu.permission

import com.rendyhd.vicu.data.local.NotificationPermissionStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPermissionCoordinatorTest {

    private class FakePlatform(
        override val needsRuntimePermission: Boolean = true,
        var granted: Boolean = false,
        var hasScreen: Boolean = true,
    ) : NotificationPermissionPlatform {
        var launches = 0
        var settingsOpened = 0
        override fun isGranted() = granted
        override fun launchSystemRequest(): Boolean {
            if (!hasScreen) return false
            launches++
            return true
        }

        override fun openSystemSettings() {
            settingsOpened++
        }
    }

    private fun newStore() = NotificationPermissionStore(InMemoryPreferencesDataStore())

    private fun TestScope.coordinator(platform: FakePlatform, store: NotificationPermissionStore = newStore()) =
        NotificationPermissionCoordinator(platform, store, backgroundScope)

    @Test
    fun `nothing is shown at start`() = runTest(UnconfinedTestDispatcher()) {
        val c = coordinator(FakePlatform())
        assertNull(c.prompt.value)
    }

    @Test
    fun `setup completion shows the rationale, then the system prompt on accept`() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakePlatform()
        val store = newStore()
        val c = coordinator(platform, store)

        c.onSetupCompleted()
        assertEquals(NotificationPermissionStep.SHOW_RATIONALE, c.prompt.value?.step)
        assertEquals(0, platform.launches)

        c.confirm()
        assertNull(c.prompt.value)
        assertEquals(1, platform.launches)
        assertEquals(1, store.requestsMade())
    }

    @Test
    fun `the prompt after setup comes only once even when dismissed`() = runTest(UnconfinedTestDispatcher()) {
        val c = coordinator(FakePlatform())
        c.onSetupCompleted()
        c.dismiss()
        assertNull(c.prompt.value)

        c.onSetupCompleted()
        assertNull(c.prompt.value)
    }

    @Test
    fun `turning a feature on asks again after a dismissed sheet`() = runTest(UnconfinedTestDispatcher()) {
        val c = coordinator(FakePlatform())
        c.onSetupCompleted()
        c.dismiss()

        c.onFeatureEnabled()
        assertEquals(NotificationPermissionStep.SHOW_RATIONALE, c.prompt.value?.step)
    }

    @Test
    fun `no sheet when the permission is already granted`() = runTest(UnconfinedTestDispatcher()) {
        val c = coordinator(FakePlatform(granted = true))
        c.onSetupCompleted()
        c.onFeatureEnabled()
        assertNull(c.prompt.value)
    }

    @Test
    fun `no sheet before Android 13`() = runTest(UnconfinedTestDispatcher()) {
        val c = coordinator(FakePlatform(needsRuntimePermission = false))
        c.onSetupCompleted()
        c.onFeatureEnabled()
        assertNull(c.prompt.value)
    }

    @Test
    fun `after two system prompts a feature opens system settings instead`() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakePlatform()
        val c = coordinator(platform)

        repeat(2) {
            c.onFeatureEnabled()
            c.confirm()
        }
        assertEquals(2, platform.launches)

        c.onFeatureEnabled()
        assertEquals(NotificationPermissionStep.OPEN_SYSTEM_SETTINGS, c.prompt.value?.step)
        c.confirm()
        assertEquals(2, platform.launches)
        assertEquals(1, platform.settingsOpened)
    }

    @Test
    fun `a request that could not be shown is not counted`() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakePlatform(hasScreen = false)
        val store = newStore()
        val c = coordinator(platform, store)

        c.onFeatureEnabled()
        c.confirm()
        assertEquals(0, store.requestsMade())
    }

    @Test
    fun `the Settings button asks directly, then opens settings`() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakePlatform()
        val c = coordinator(platform)

        c.requestFromSettings()
        assertNull(c.prompt.value)
        assertEquals(1, platform.launches)

        c.requestFromSettings()
        assertEquals(2, platform.launches)

        c.requestFromSettings()
        assertEquals(2, platform.launches)
        assertEquals(1, platform.settingsOpened)
    }
}
