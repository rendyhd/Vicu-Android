package com.rendyhd.vicu.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `noteLinks` vectors of test-fixtures/description-format-v1.json (description format, "Task
 * links"). The desktop runs the same file in src/shared/__tests__/note-links.test.ts.
 */
class NoteLinksTest {

    @Serializable
    private data class Vector(val name: String, val url: String, val offered: Boolean)

    @Serializable
    private data class Fixture(val noteLinks: List<Vector>)

    private val vectors: List<Vector> by lazy {
        Json { ignoreUnknownKeys = true }
            .decodeFromString(Fixture.serializer(), CrossAppFixture.readTestFixture("description-format-v1.json"))
            .noteLinks
    }

    private fun pair(url: String, name: String = "Runbook"): String {
        val escaped = url.replace("&", "&amp;")
        return "<p>Body</p><!-- notelink:$escaped --><p><a href=\"$escaped\">📎 $name</a></p>"
    }

    @Test
    fun everyVectorIsOfferedOrNotAsTheFixtureSays() {
        assertTrue(vectors.size >= 35, "vectors: ${vectors.size}")
        assertTrue(vectors.any { it.offered } && vectors.any { !it.offered })
        val failures = vectors.filter { NoteLinks.isAllowed(it.url) != it.offered }.map { "${it.name}: expected ${it.offered}" }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun stopsAtTheLengthLimit() {
        val base = "obsidian://open?vault=V&file="
        assertTrue(NoteLinks.isAllowed(base + "a".repeat(NoteLinks.MAX_CHARS - base.length)))
        assertFalse(NoteLinks.isAllowed(base + "a".repeat(NoteLinks.MAX_CHARS - base.length + 1)))
        assertFalse(NoteLinks.isAllowed(null))
    }

    @Test
    fun aTaskOffersTheTwoLinksVicuWrites() {
        val open = TaskLinkParser.extractLinks(pair("obsidian://open?vault=Personal&file=Tasks%2FRunbook"))
        assertEquals(
            listOf<TaskLinkParser.TaskLink>(
                TaskLinkParser.TaskLink.ObsidianNote("obsidian://open?vault=Personal&file=Tasks%2FRunbook", "Runbook"),
            ),
            open,
        )
        val uid = TaskLinkParser.extractLinks(pair("obsidian://advanced-uri?vault=Personal&uid=42"))
        assertEquals("obsidian://advanced-uri?vault=Personal&uid=42", (uid.single() as TaskLinkParser.TaskLink.ObsidianNote).url)
    }

    @Test
    fun aTaskOffersNoLinkForAnAddressThatDoesMoreThanOpenANote() {
        val refused = listOf(
            "obsidian://advanced-uri?vault=Personal&commandid=shell-commands%3Arun",
            "obsidian://advanced-uri?vault=Personal&uid=42&commandid=app%3Adelete-file",
            "obsidian://advanced-uri?vault=Personal&filepath=a.md&data=x&mode=overwrite",
            "obsidian://new?vault=Personal&file=Inbox&content=x&overwrite=true",
            "obsidian://open?vault=Ops",
        )
        for (url in refused) {
            assertEquals(emptyList(), TaskLinkParser.extractLinks(pair(url, "Meeting notes")), url)
        }
    }

    @Test
    fun aRefusedMarkerStaysInTheDescription() {
        val refused = pair("obsidian://advanced-uri?vault=Personal&commandid=x")
        assertTrue(TaskLinkParser.extractLinkHtml(refused).contains("notelink:obsidian://advanced-uri"))
        assertEquals("<p>Body</p>", TaskLinkParser.stripLinks(refused))
    }

    @Test
    fun thePageLinkOfATaskIsStillOfferedWhenItsNoteLinkIsRefused() {
        val both = pair("obsidian://new?vault=V&file=a") +
            "<!-- pagelink:https://example.com/a --><p><a href=\"https://example.com/a\">🔗 A page</a></p>"
        assertEquals(
            listOf<TaskLinkParser.TaskLink>(TaskLinkParser.TaskLink.BrowserPage("https://example.com/a", "A page")),
            TaskLinkParser.extractLinks(both),
        )
    }
}
