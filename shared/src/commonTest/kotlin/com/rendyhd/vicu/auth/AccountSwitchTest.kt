package com.rendyhd.vicu.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountSwitchTest {

    private val server = "https://tasks.example.com"

    private fun decide(previous: AccountIdentity?, incoming: AccountIdentity) =
        AccountSwitch.decide(previous, incoming)

    @Test
    fun `the same user on the same server keeps everything`() {
        assertEquals(
            LoginDataAction.KEEP,
            decide(AccountIdentity(server, 7), AccountIdentity(server, 7)),
        )
    }

    @Test
    fun `a different user on the same server wipes`() {
        assertEquals(
            LoginDataAction.WIPE_ALL,
            decide(AccountIdentity(server, 7), AccountIdentity(server, 8)),
        )
    }

    @Test
    fun `a different server wipes even for the same user id`() {
        assertEquals(
            LoginDataAction.WIPE_ALL,
            decide(AccountIdentity(server, 7), AccountIdentity("https://other.example.com", 7)),
        )
    }

    @Test
    fun `no account on record wipes`() {
        assertEquals(LoginDataAction.WIPE_ALL, decide(null, AccountIdentity(server, 7)))
        assertEquals(LoginDataAction.WIPE_ALL, decide(AccountIdentity("", 7), AccountIdentity(server, 7)))
    }

    @Test
    fun `a stored session without a user id on the same server counts as the same account`() {
        assertEquals(
            LoginDataAction.KEEP,
            decide(AccountIdentity(server, null), AccountIdentity(server, 7)),
        )
    }

    @Test
    fun `a stored session without a user id on another server wipes`() {
        assertEquals(
            LoginDataAction.WIPE_ALL,
            decide(AccountIdentity(server, null), AccountIdentity("https://other.example.com", 7)),
        )
    }

    @Test
    fun `cosmetic differences in the url do not make a different server`() {
        assertEquals(
            LoginDataAction.KEEP,
            decide(AccountIdentity("HTTPS://Tasks.Example.com/", 7), AccountIdentity(" $server ", 7)),
        )
    }

    @Test
    fun `url normalisation keeps the path case and drops trailing slashes`() {
        assertEquals("https://host.example/Vikunja", AccountSwitch.normalizeUrl("HTTPS://HOST.example/Vikunja//"))
        assertEquals("host.example", AccountSwitch.normalizeUrl("Host.example/"))
        assertEquals("http://192.168.1.5:3456", AccountSwitch.normalizeUrl("http://192.168.1.5:3456/"))
    }

    @Test
    fun `a path makes a different instance`() {
        assertEquals(
            LoginDataAction.WIPE_ALL,
            decide(AccountIdentity("$server/a", 7), AccountIdentity("$server/b", 7)),
        )
    }
}
