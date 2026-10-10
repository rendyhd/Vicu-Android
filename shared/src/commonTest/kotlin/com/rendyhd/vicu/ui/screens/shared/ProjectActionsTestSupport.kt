package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.repository.RecordingRepositoryHooks
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/** An [AuthManager] with nothing behind it: it holds the Inbox id and never goes to the network. */
fun offlineAuthManager(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
): AuthManager = AuthManager(
    platformAuthHooks = RecordingAuthHooks(),
    tokenStorage = InMemoryTokenStorage(),
    apiServiceProvider = { error("no network in this test") },
    appScope = scope,
    networkMonitor = FakeNetworkMonitor(),
)

/** [ProjectActions] over [projects], for view model tests. Today is 2026-10-10 in UTC unless [now] says otherwise. */
fun testProjectActions(
    projects: ProjectRepository,
    auth: AuthManager = offlineAuthManager(),
    hooks: PlatformRepositoryHooks = RecordingRepositoryHooks(),
    now: Instant = Instant.parse("2026-10-10T10:00:00Z"),
): ProjectActions = ProjectActions(
    projectRepository = projects,
    authManager = auth,
    repositoryHooks = hooks,
    dayClock = DayClock(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        time = FixedTimeSource(now, TimeZone.UTC),
        ticking = false,
    ),
)
