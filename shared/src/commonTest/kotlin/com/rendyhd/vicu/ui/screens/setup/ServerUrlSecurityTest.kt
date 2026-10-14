package com.rendyhd.vicu.ui.screens.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerUrlSecurityTest {

    @Test
    fun `plain http to a public host warns`() {
        assertTrue(isCleartextToRemoteHost("http://tasks.example.com"))
        assertTrue(isCleartextToRemoteHost("http://tasks.example.com:3456/"))
        assertTrue(isCleartextToRemoteHost("  HTTP://Tasks.Example.com/vikunja  "))
        assertTrue(isCleartextToRemoteHost("http://8.8.8.8"))
        assertTrue(isCleartextToRemoteHost("http://user:pw@tasks.example.com"))
    }

    @Test
    fun `https and scheme-less addresses do not warn`() {
        assertFalse(isCleartextToRemoteHost("https://tasks.example.com"))
        assertFalse(isCleartextToRemoteHost("tasks.example.com"))
        assertFalse(isCleartextToRemoteHost(""))
        assertFalse(isCleartextToRemoteHost("http://"))
    }

    @Test
    fun `hosts on the local machine or network do not warn`() {
        listOf(
            "http://localhost:3456",
            "http://app.localhost",
            "http://127.0.0.1:3456",
            "http://127.1.2.3",
            "http://10.0.0.5",
            "http://192.168.1.20:3456",
            "http://172.16.0.1",
            "http://172.31.255.255",
            "http://169.254.10.10",
            "http://100.64.0.1",
            "http://100.127.255.255",
            "http://[::1]:3456",
            "http://[fd12:3456::1]",
            "http://[fe80::1]",
            "http://nas",
            "http://vikunja.local",
            "http://vikunja.lan:8080",
            "http://box.home.arpa",
            "http://box.internal",
        ).forEach { assertFalse(isCleartextToRemoteHost(it), it) }
    }

    @Test
    fun `addresses just outside the private ranges still warn`() {
        listOf(
            "http://172.15.0.1",
            "http://172.32.0.1",
            "http://100.63.0.1",
            "http://100.128.0.1",
            "http://11.0.0.1",
            "http://192.169.0.1",
            "http://[2001:db8::1]",
        ).forEach { assertTrue(isCleartextToRemoteHost(it), it) }
    }

    @Test
    fun `the hostname is parsed out of the userinfo port and path`() {
        assertEquals("tasks.example.com", cleartextHostOf("http://me@tasks.example.com:81/x?y=1#z"))
        assertEquals("::1", cleartextHostOf("http://[::1]:3456/api"))
        assertEquals(null, cleartextHostOf("https://tasks.example.com"))
    }
}
