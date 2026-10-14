package com.rendyhd.vicu.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `collaboration` vectors of test-fixtures/cross-app-semantics-v1.json (contract section 9).
 * The desktop runs the same file in src/shared/__tests__/collaboration.test.ts.
 */
class CollaborationFixtureTest {

    @Serializable
    private data class Person(val id: Long, val username: String, val name: String? = null) {
        fun user() = CollabUser(id, username, name)
    }

    @Serializable
    private data class SharedVector(val name: String, val me: Long, val members: List<Long>, val expect: Boolean)

    @Serializable
    private data class Node(val id: Long, val parent: Long, val owner: Long)

    @Serializable
    private data class Share(val user: Long, val permission: Int)

    @Serializable
    private data class Team(val permission: Int, val members: List<Long>)

    @Serializable
    private data class PermissionVector(val name: String, val project: Long, val user: Long, val expect: Int? = null)

    @Serializable
    private data class MemberPermission(
        val projects: List<Node>,
        val shares: Map<String, List<Share>>,
        val teams: Map<String, List<Team>>,
        val vectors: List<PermissionVector>,
    )

    @Serializable
    private data class NamesExpect(val list: String, val visibleTo: String, val assigned: String, val chip: String)

    @Serializable
    private data class NamesVector(val name: String, val me: Long, val people: List<Person>, val expect: NamesExpect)

    @Serializable
    private data class CountVector(val count: Int, val unread: Boolean, val expect: String)

    @Serializable
    private data class EncodeVector(val name: String, val text: String, val expect: String)

    @Serializable
    private data class Encode(val members: List<Person>, val vectors: List<EncodeVector>)

    @Serializable
    private data class DecodeVector(val name: String, val html: String, val segments: List<JsonObject>, val plain: Boolean, val editText: String? = null)

    @Serializable
    private data class Decode(val members: List<Person>, val vectors: List<DecodeVector>)

    @Serializable
    private data class MentionVector(
        val name: String,
        val text: String,
        val caret: Int,
        val query: String? = null,
        val suggestions: List<String>,
        val pick: String? = null,
        val pickedText: String? = null,
        val pickedCaret: Int? = null,
    )

    @Serializable
    private data class Mention(val me: Long, val members: List<Person>, val vectors: List<MentionVector>)

    @Serializable
    private data class EditedVector(val name: String, val created: String, val updated: String, val expect: Boolean)

    @Serializable
    private data class Block(
        val shared: List<SharedVector>,
        val memberPermission: MemberPermission,
        val names: List<NamesVector>,
        val countLabel: List<CountVector>,
        val encode: Encode,
        val decode: Decode,
        val mention: Mention,
        val edited: List<EditedVector>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val c: Block by lazy {
        val root = json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json")).jsonObject
        json.decodeFromJsonElement(Block.serializer(), root["collaboration"] as JsonObject)
    }

    @Test
    fun `shared projects`() {
        for (v in c.shared) assertEquals(v.expect, Collaboration.isSharedProject(v.members, v.me), v.name)
        assertFalse(Collaboration.isSharedProject(listOf(1, 2), null))
    }

    @Test
    fun `member permission`() {
        val m = c.memberPermission
        val graph = ShareGraph(
            projects = m.projects.map { ProjectNode(it.id, it.parent, it.owner) },
            shares = m.shares.mapKeys { it.key.toLong() }.mapValues { (_, list) -> list.map { UserShareEntry(it.user, it.permission) } },
            teams = m.teams.mapKeys { it.key.toLong() }.mapValues { (_, list) -> list.map { TeamShareEntry(it.permission, it.members) } },
        )
        for (v in m.vectors) assertEquals(v.expect, Collaboration.memberPermission(v.project, v.user, graph), v.name)
        val cyclic = ShareGraph(
            projects = listOf(ProjectNode(1, 2, 9), ProjectNode(2, 1, 9)),
            shares = mapOf(2L to listOf(UserShareEntry(5, 1))),
            teams = emptyMap(),
        )
        assertEquals(1, Collaboration.memberPermission(1, 5, cyclic))
    }

    @Test
    fun names() {
        for (v in c.names) {
            val people = v.people.map { it.user() }
            assertEquals(v.expect.list, Collaboration.nameList(people, v.me), v.name)
            assertEquals(v.expect.visibleTo, Collaboration.visibleToText(people, v.me), v.name)
            assertEquals(v.expect.assigned, Collaboration.assignedLabel(people, v.me), v.name)
            assertEquals(v.expect.chip, Collaboration.assignChipLabel(people, v.me), v.name)
        }
        for (v in c.countLabel) assertEquals(v.expect, Collaboration.commentCountLabel(v.count, v.unread))
        assertEquals("M", Collaboration.avatarInitial(CollabUser(1, "maya", " maya lee")))
        assertEquals("É", Collaboration.avatarInitial(CollabUser(2, "éa", "")))
    }

    @Test
    fun `your permission`() {
        assertEquals(0, Collaboration.effectivePermission(0))
        assertEquals(2, Collaboration.effectivePermission(2))
        assertEquals(0, Collaboration.effectivePermission(2, archived = true))
        assertEquals(1, Collaboration.effectivePermission(null))
        assertEquals(1, Collaboration.effectivePermission(7))
    }

    @Test
    fun `comment HTML from text`() {
        val members = c.encode.members.map { it.user() }
        for (v in c.encode.vectors) assertEquals(v.expect, Collaboration.encodeCommentHtml(v.text, members), v.name)
    }

    private fun segmentOf(obj: JsonObject): CommentSegment {
        val text = obj["text"]
        return if (text != null) {
            CommentSegment.Text(text.jsonPrimitive.content)
        } else {
            CommentSegment.Mention(obj["mention"]!!.jsonPrimitive.content, obj["label"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `text from comment HTML`() {
        val members = c.decode.members.map { it.user() }
        for (v in c.decode.vectors) {
            val decoded = Collaboration.decodeCommentHtml(v.html, members)
            assertEquals(v.segments.map(::segmentOf), decoded.segments, v.name)
            assertEquals(v.plain, decoded.plain, v.name)
            assertEquals(v.editText, decoded.editText, v.name)
        }
        val html = Collaboration.encodeCommentHtml("Hi @maya,\nsee you", members)
        val decoded = Collaboration.decodeCommentHtml(html, members)
        assertTrue(decoded.plain)
        assertEquals(html, Collaboration.encodeCommentHtml(decoded.editText ?: "", members))
    }

    @Test
    fun mentions() {
        val members = c.mention.members.map { it.user() }
        for (v in c.mention.vectors) {
            val query = Collaboration.mentionQueryAt(v.text, v.caret)
            assertEquals(v.query, query?.query, v.name)
            val suggestions = if (query == null) emptyList() else Collaboration.mentionSuggestions(query.query, members, c.mention.me)
            assertEquals(v.suggestions, suggestions.map { it.username }, v.name)
            if (v.pick != null && query != null) {
                val picked = Collaboration.applyMention(v.text, v.caret, query, v.pick)
                assertEquals(v.pickedText, picked.text, v.name)
                assertEquals(v.pickedCaret, picked.caret, v.name)
            }
        }
    }

    @Test
    fun edited() {
        for (v in c.edited) assertEquals(v.expect, Collaboration.isCommentEdited(v.created, v.updated), v.name)
        assertFalse(Collaboration.isCommentEdited(null, "2026-10-10T19:15:18Z"))
    }
}
