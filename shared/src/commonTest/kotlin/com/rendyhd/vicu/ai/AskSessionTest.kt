package com.rendyhd.vicu.ai

import com.rendyhd.vicu.ai.ask.AskBackend
import com.rendyhd.vicu.ai.ask.AskList
import com.rendyhd.vicu.ai.ask.AskSession
import com.rendyhd.vicu.ai.ask.AskSnapshot
import com.rendyhd.vicu.ai.ask.Conversation
import com.rendyhd.vicu.ai.ask.ConversationStore
import com.rendyhd.vicu.ai.ask.Proposal
import com.rendyhd.vicu.ai.ask.Proposals
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DraftWriter
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.LlmClient
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.TimeSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class AskSessionTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val now = LocalDateTime(2026, 10, 6, 10, 0).toInstant(zone)

    private class FakeKeys : AiKeyStore {
        val keys = mutableMapOf<String, String>()
        override suspend fun getKey(providerId: String) = keys[providerId]
        override suspend fun setKey(providerId: String, key: String?) {
            if (key.isNullOrBlank()) keys.remove(providerId) else keys[providerId] = key
        }
        override suspend fun clear() = keys.clear()
    }

    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun toolCall(id: String, name: String, args: String) =
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"$id","function":{"name":"$name","arguments":${kotlinx.serialization.json.JsonPrimitive(args)}}}]},"finish_reason":"tool_calls"}]}"""

    private data class Harness(
        val session: AskSession,
        val sent: MutableList<JsonObject>,
        val tasks: FakeTaskRepository,
        val proposals: Proposals,
        val store: ConversationStore,
    )

    private suspend fun harness(vararg bodies: String): Harness {
        val sent = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            sent += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond(bodies[sent.size - 1], HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val keys = FakeKeys().apply { keys["openai"] = "sk" }
        val settings = AiSettingsStore(InMemoryPreferencesDataStore())
        settings.update {
            val s = it.copy(providerId = "openai", address = "https://api.openai.com/v1", askModel = "m", askOn = true)
            s.copy(tested = mapOf(s.modelKey("m") to ModelCapabilities("json_schema", true)), consented = setOf(s.consentKey()))
        }
        val tasks = FakeTaskRepository().apply { put(Task(id = 1, title = "Buy milk", projectId = 1)) }
        val labels = FakeLabelRepository()
        val proposals = Proposals(tasks, labels, DraftWriter(tasks, labels, NotificationPrefsStore(InMemoryPreferencesDataStore()), null))
        val store = ConversationStore(InMemoryPreferencesDataStore())
        val backend = object : AskBackend {
            override suspend fun snapshot() = AskSnapshot(
                tasks = tasks.getAllTasksFlat().first(),
                projects = listOf(Project(1, "Inbox", maxPermission = 1)),
                labels = emptyList(),
                customLists = emptyList(),
                collab = CollabState(),
                signals = emptyMap(),
                order = TaskOrderSettings(),
                inboxId = 1,
                today = LocalDate(2026, 10, 6),
                zone = zone,
                reviewCadenceDays = 14,
                reviewEnabled = false,
                reviewExcludeInbox = true,
            )
            override suspend fun logbook() = emptyList<Task>()
            override suspend fun dictationContext(list: AskList) = DictationContext(
                now = LocalDateTime(2026, 10, 6, 10, 0), zone = zone, list = "Inbox", defaultProjectId = 1,
                defaultDue = null, projects = emptyList(), labels = emptyList(), places = emptyList(),
            )
        }
        val time = object : TimeSource {
            override fun now(): Instant = now
            override fun zone(): TimeZone = zone
        }
        val session = AskSession(AiProviders(LlmClient(engine), keys, settings), settings, backend, proposals, store, Conversation("c"), time) { "id" }
        return Harness(session, sent, tasks, proposals, store)
    }

    private fun answer(text: String) =
        sse("""{"choices":[{"delta":{"content":"$text"}}]}""", """{"choices":[{"delta":{},"finish_reason":"stop"}]}""")

    @Test
    fun aReadToolThenAProposalThenAnAnswer() = runTest {
        val h = harness(
            sse(toolCall("c1", "get_overview", "{}")),
            sse(toolCall("c2", "propose_complete_tasks", """{"task_ids":[1,99],"done":true}""")),
            answer("Done: [task:1]"),
        )
        val (session, sent, tasks, proposals, store) = h

        val turn = launch { session.send("Mark the milk done", AskList("Today"), talk = false) }
        val pending = session.state.first { it.pending != null }.pending!!
        val proposal = assertIs<Proposal.Complete>(pending.proposal)
        assertEquals(listOf(1L), proposal.tasks.map { it.id })
        assertEquals(listOf(99L to "not_found"), proposal.skipped.map { it.taskId to it.reason })
        session.decide(proposals.apply(proposal, zone))
        turn.join()

        assertEquals(listOf(1L to true), tasks.setDoneCalls)
        assertEquals(3, sent.size)
        // The first message carries the context block with the overview.
        val first = sent[0].getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(first.startsWith("<context>\n{\"now\":\"2026-10-06T10:00\",\"weekday\":\"Tuesday\",\"time_zone\":\"Europe/Amsterdam\""))
        assertTrue(first.endsWith("</context>\nMark the milk done"))
        val toolResult = sent[2].getValue("messages").jsonArray.last().jsonObject
        assertEquals("tool", toolResult.getValue("role").jsonPrimitive.content)
        assertEquals(
            """{"status":"applied","applied":[1],"skipped":[{"task_id":99,"reason":"not_found"}]}""",
            toolResult.getValue("content").jsonPrimitive.content,
        )
        val conversation = session.state.value.conversation
        assertTrue(1L in conversation.knownTaskIds)
        assertEquals("applied", conversation.outcomes.getValue("c2").status)
        assertEquals("Done: [task:1]", (conversation.messages.last() as ChatMessage.Assistant).text)
        assertEquals(conversation, store.all().single())
    }

    @Test
    fun wordsPastAnOpenCardDeclineItAndTheCardLingers() = runTest {
        val h = harness(
            sse(toolCall("c1", "propose_complete_tasks", """{"task_ids":[1],"done":true}""")),
            answer("Fine, I left it."),
        )
        val turn = launch { h.session.send("Mark the milk done", AskList("Today"), talk = false) }
        h.session.state.first { it.pending != null }
        h.session.send("Actually leave it", AskList("Today"), talk = false)
        turn.join()

        // The card's call was answered declined and the words went on at once (section 5.4).
        assertEquals(2, h.sent.size)
        val messages = h.sent[1].getValue("messages").jsonArray
        val tool = messages[messages.size - 2].jsonObject
        assertEquals("tool", tool.getValue("role").jsonPrimitive.content)
        assertEquals("""{"status":"declined","applied":[],"skipped":[]}""", tool.getValue("content").jsonPrimitive.content)
        assertEquals("Actually leave it", messages.last().jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("declined", h.session.conversation.outcomes.getValue("c1").status)

        // The card stays and can still be applied; only the conversation's record changes.
        val card = h.session.state.value.lingering!!
        assertEquals(null, h.session.state.value.pending)
        h.session.decideLingering(h.proposals.apply(card.proposal, zone))
        assertEquals(listOf(1L to true), h.tasks.setDoneCalls)
        assertEquals(null, h.session.state.value.lingering)
        assertEquals("applied", h.session.conversation.outcomes.getValue("c1").status)
        assertEquals(2, h.sent.size)
    }

    @Test
    fun stoppingAnswersAnOpenCallSoTheNextRequestIsValid() = runTest {
        val h = harness(
            sse(toolCall("c1", "propose_complete_tasks", """{"task_ids":[1],"done":true}""")),
            answer("Stopped, as you asked."),
        )
        val turn = launch { h.session.send("Mark the milk done", AskList("Today"), talk = false) }
        h.session.state.first { it.pending != null }
        turn.cancelAndJoin()

        h.session.stopped()

        val state = h.session.state.value
        assertTrue(state.stopped)
        assertTrue(!state.busy)
        assertTrue(state.conversation.messages.any { it is ChatMessage.User }, "what was asked stays")
        val closed = assertIs<ChatMessage.ToolResults>(state.conversation.messages.last())
        assertEquals("c1", closed.results.single().callId)
        assertTrue(closed.results.single().isError)
        assertTrue("cancelled" in closed.results.single().content)

        h.session.send("Something else", AskList("Today"), talk = false)

        assertTrue(!h.session.state.value.stopped, "the next turn clears it")
        val messages = h.sent[1].getValue("messages").jsonArray
        assertEquals("tool", messages[messages.size - 2].jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun stoppingWithoutAnOpenCallAddsNothing() = runTest {
        val h = harness(answer("Hello"))
        h.session.send("Hi", AskList("Today"), talk = false)
        val before = h.session.conversation.messages

        h.session.stopped()

        assertEquals(before, h.session.conversation.messages)
        assertTrue(h.session.state.value.stopped)
    }
}
