package com.rendyhd.vicu.auth

import com.rendyhd.vicu.auth.AuthTransitionAction.ENSURE_SUMMARIES
import com.rendyhd.vicu.auth.AuthTransitionAction.RESCHEDULE_ACCOUNT_WORK
import com.rendyhd.vicu.auth.AuthTransitionAction.SYNC_NOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthTransitionsTest {

    @Test
    fun `the first sign-in of a run ensures the summaries once`() {
        val rules = AuthTransitions()
        assertEquals(emptySet(), rules.on(AuthState.Loading))
        assertEquals(setOf(ENSURE_SUMMARIES), rules.on(AuthState.Authenticated))
        assertEquals(emptySet(), rules.on(AuthState.Authenticated))
    }

    @Test
    fun `signing in again after the session expired syncs at once`() {
        val rules = AuthTransitions()
        rules.on(AuthState.Authenticated)
        assertEquals(emptySet(), rules.on(AuthState.NeedsReAuth))
        // The sign-in may be another account, whose wipe cancelled the account work.
        assertEquals(setOf(SYNC_NOW, RESCHEDULE_ACCOUNT_WORK), rules.on(AuthState.Authenticated))
        assertEquals(emptySet(), rules.on(AuthState.Authenticated))
    }

    @Test
    fun `a sign-out that passed through sign in again still schedules the account work`() {
        val rules = AuthTransitions()
        rules.on(AuthState.Authenticated)
        rules.on(AuthState.Unauthenticated)
        rules.on(AuthState.NeedsReAuth)
        assertTrue(RESCHEDULE_ACCOUNT_WORK in rules.on(AuthState.Authenticated))
    }

    @Test
    fun `signing in after a sign-out schedules the account work again`() {
        val rules = AuthTransitions()
        rules.on(AuthState.Loading)
        rules.on(AuthState.Unauthenticated)
        assertEquals(setOf(ENSURE_SUMMARIES, RESCHEDULE_ACCOUNT_WORK), rules.on(AuthState.Authenticated))
        rules.on(AuthState.Unauthenticated)
        assertEquals(setOf(RESCHEDULE_ACCOUNT_WORK), rules.on(AuthState.Authenticated))
    }
}
