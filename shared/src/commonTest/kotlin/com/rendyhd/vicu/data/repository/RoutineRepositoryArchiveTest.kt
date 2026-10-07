package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.RoutineRig.Companion.payload
import com.rendyhd.vicu.data.repository.RoutineRig.Companion.record
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineArchivePart
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutinePeriod
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RoutineEnvelope
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the repository keeps the main carrier and the server-side archive in step
 * (docs/cross-app-semantics-v1.md, sections 6.2 and 6.4): archive first, carrier second.
 * "Today" is 2026-10-06, so the 400 day window starts on 2025-09-01.
 */
class RoutineRepositoryArchiveTest {

    private val old1 = record("2025-01-15")
    private val old2 = record("2025-08-31")
    private val edge = record("2025-09-01")
    private val recent = record("2026-10-05")

    private fun requestIndex(rig: RoutineRig, method: String, path: String) =
        rig.server.requests.indexOfFirst { it.method == method && it.path == path }

    // --- Write order ---------------------------------------------------------------------------

    @Test
    fun `old history is written to an archive part before the carrier drops it`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(old1, old2, edge, recent))

        val result = rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        assertIs<NetworkResult.Success<*>>(result)
        val createdAt = requestIndex(rig, "POST", "/projects/5/tasks")
        val carrierPatchedAt = requestIndex(rig, "PATCH", "/tasks/100")
        assertTrue(createdAt >= 0 && createdAt < carrierPatchedAt, "the archive part is created before the carrier is patched")

        val partRow = rig.server.archiveRows().single()
        assertTrue(partRow.done, "an archive part is a done task")
        assertEquals("Vicu routine archive", partRow.title)
        assertEquals(5L, partRow.projectId, "in the carrier's project")
        assertTrue(
            Regex("""<!-- vicu-routine:archive:v1:[A-Za-z0-9_-]+ -->""").matches(partRow.description),
            "the description is only the marker",
        )
        val part = rig.serverPart(partRow.id)
        assertEquals(1, part.part)
        assertEquals("r1", part.routineId)
        assertEquals(setOf(old1.key, old2.key), part.occurrences.keys)

        val carrier = rig.serverPayload(100)
        assertEquals("2025-09-01", carrier.prunedBefore)
        assertEquals(setOf(edge.key, recent.key, "r1:2026-10-06:s1"), carrier.occurrences.keys)
        assertEquals(OccurrenceStatus.COMPLETED, carrier.occurrences.getValue("r1:2026-10-06:s1").status)
        assertNull(rig.repository.archiveWarning.value)
    }

    @Test
    fun `the part is created in one request that is already done`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(old1, recent))

        rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        val create = rig.server.requests.single { it.method == "POST" }
        assertTrue(create.bodyJson!!.getValue("done").jsonPrimitive.boolean)
        assertEquals("Vicu routine archive", create.bodyJson!!.getValue("title").jsonPrimitive.content)
        assertTrue(rig.server.requests.none { it.method == "PATCH" && it.path == "/tasks/${rig.server.archiveRows().single().id}" })
    }

    @Test
    fun `an archive that cannot be written leaves the carrier unpruned`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(old1, old2, edge, recent))
        rig.server.failure = { if (it.method == "POST" && it.path.startsWith("/projects/")) HttpStatusCode.InternalServerError else null }

        val result = rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        assertIs<NetworkResult.Success<*>>(result, "the user's change is saved regardless")
        assertTrue(rig.server.archiveRows().isEmpty())
        val carrier = rig.serverPayload(100)
        assertEquals("", carrier.prunedBefore, "prunedBefore only moves once the archive has the history")
        assertEquals(
            setOf(old1.key, old2.key, edge.key, recent.key, "r1:2026-10-06:s1"),
            carrier.occurrences.keys,
        )
        assertNull(rig.repository.archiveWarning.value, "a server that is down is not worth a warning")
    }

    @Test
    fun `an archive the server refuses leaves the carrier unpruned and says so`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(old1, edge, recent))
        rig.server.failure = { if (it.method == "POST" && it.path.startsWith("/projects/")) HttpStatusCode.UnprocessableEntity else null }

        val result = rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals("", rig.serverPayload(100).prunedBefore)
        assertTrue(old1.key in rig.serverPayload(100).occurrences)
        assertNotNull(rig.repository.archiveWarning.value)
    }

    @Test
    fun `a part the server did not store completely does not let the carrier drop the history`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(old1, edge, recent))
        // A misbehaving server keeps the part but loses its records.
        rig.server.afterWrite = { row ->
            if (row.description.contains("vicu-routine:archive:")) {
                row.description = RoutineEnvelope.encodeArchive(RoutineArchivePart(routineId = "r1", part = 1), rig.json)
            }
        }

        rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        val carrier = rig.serverPayload(100)
        assertEquals("", carrier.prunedBefore)
        assertTrue(old1.key in carrier.occurrences, "the history is still in the carrier")
        assertNotNull(rig.repository.archiveWarning.value)
    }

    @Test
    fun `offline the change is queued with its history intact and prunes on a later write`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(old1, edge, recent))
        rig.server.failure = { HttpStatusCode.ServiceUnavailable }

        val result = rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(1, rig.pendingActionDao.snapshot().size, "the carrier update waits in the queue")
        val cached = checkNotNull(RoutineEnvelope.parse(rig.taskDao.entity(100)!!.description, rig.json).payload)
        assertTrue(old1.key in cached.occurrences)
        assertEquals("", cached.prunedBefore)

        // Back online: the next write archives. The old history goes to a part on the server at
        // once; the carrier change joins the one still queued (sent on its own it would be
        // overwritten when the older one is replayed) and the sync sends both, merged.
        rig.server.failure = { null }
        rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.PENDING)
        assertTrue(old1.key in rig.serverPart(rig.server.archiveRows().single().id).occurrences)
        assertEquals(1, rig.pendingActionDao.snapshot().size, "one carrier update waits, merged")
        val local = checkNotNull(RoutineEnvelope.parse(rig.taskDao.entity(100)!!.description, rig.json).payload)
        assertEquals("2025-09-01", local.prunedBefore)
        assertFalse(old1.key in local.occurrences)
    }

    @Test
    fun `a record that is itself archived cannot be changed without the archive`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(edge, recent, prunedBefore = "2025-09-01"))
        rig.server.failure = { if (it.path.startsWith("/projects/")) HttpStatusCode.InternalServerError else null }

        val result = rig.repository.setOccurrenceStatus("r1", "2025-03-01", "s1", OccurrenceStatus.COMPLETED)

        assertIs<NetworkResult.Error>(result)
        assertFalse("r1:2025-03-01:s1" in rig.serverPayload(100).occurrences, "it must not be written into the carrier")
    }

    @Test
    fun `a change to an archived day is written to the part that holds it`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        val archived = record("2025-03-01", OccurrenceStatus.SKIPPED, modifiedAt = "2025-03-01T08:00:00.000Z")
        rig.seedCarrier(100, payload(edge, recent, prunedBefore = "2025-09-01"))
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1, occurrences = mapOf(archived.key to archived)))

        val result = rig.repository.setOccurrenceStatus("r1", "2025-03-01", "s1", OccurrenceStatus.COMPLETED)

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(OccurrenceStatus.COMPLETED, rig.serverPart(200).occurrences.getValue(archived.key).status)
        assertFalse(archived.key in rig.serverPayload(100).occurrences, "it does not come back into the carrier")
        assertEquals(1, rig.server.archiveRows().size)
    }

    @Test
    fun `nothing to archive means no archive requests at all`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(recent))

        rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        assertTrue(rig.server.requests.none { it.method == "POST" })
        assertTrue(rig.server.requests.none { it.method == "GET" && it.path == "/tasks" })
    }

    // --- Parts that already exist ----------------------------------------------------------

    @Test
    fun `a part found by the search is appended to, not duplicated`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        val earlier = record("2024-12-01")
        rig.seedCarrier(100, payload(old1, edge, recent, prunedBefore = "2024-12-01"))
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1, occurrences = mapOf(earlier.key to earlier)))

        rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        assertEquals(listOf(200L), rig.server.archiveRows().map { it.id }, "no new part task")
        assertEquals(setOf(earlier.key, old1.key), rig.serverPart(200).occurrences.keys)
        assertTrue(rig.server.requests.none { it.method == "POST" })
        val search = rig.server.requests.first { it.method == "GET" && it.path == "/tasks" }
        assertEquals("vicu-routine:archive", search.query["q"])
        assertTrue("done = true" in search.query.getValue("filter"))
    }

    @Test
    fun `an append another device made since the search is kept`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        val earlier = record("2024-12-01")
        val fromOtherDevice = record("2024-12-02", modifiedBy = "tablet")
        rig.seedCarrier(100, payload(old1, edge, recent, prunedBefore = "2024-12-01"))
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1, occurrences = mapOf(earlier.key to earlier)))
        var injected = false
        rig.server.beforeRequest = {
            if (!injected && it.method == "GET" && it.path == "/tasks/200") {
                injected = true
                rig.seedPart(
                    200,
                    RoutineArchivePart(
                        routineId = "r1",
                        part = 1,
                        occurrences = mapOf(earlier.key to earlier, fromOtherDevice.key to fromOtherDevice),
                    ),
                )
            }
        }

        rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        assertEquals(setOf(earlier.key, fromOtherDevice.key, old1.key), rig.serverPart(200).occurrences.keys)
    }

    @Test
    fun `when the search shows no part for pruned history one scan of all done tasks double checks`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(old1, edge, recent, prunedBefore = "2024-12-01"))

        rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        val scans = rig.server.requests.filter { it.method == "GET" && it.path == "/tasks" }
        assertEquals(2, scans.size)
        assertEquals("vicu-routine:archive", scans[0].query["q"])
        assertNull(scans[1].query["q"], "the second one looks at every done task")
        assertEquals(1, rig.server.archiveRows().size, "and finding nothing, a part is created")
    }

    // --- Reading --------------------------------------------------------------------------

    @Test
    fun `history is the carrier plus every part plus history not uploaded yet`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        val inPart = record("2025-01-10")
        val duplicate = record("2025-01-10", OccurrenceStatus.SKIPPED, modifiedAt = "2025-02-01T08:00:00.000Z", modifiedBy = "tablet")
        val inSecondPart = record("2025-06-01")
        val phoneOnly = record("2024-05-01")
        rig.seedCarrier(100, payload(recent, prunedBefore = "2025-09-01"))
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1, occurrences = mapOf(inPart.key to inPart)))
        rig.seedPart(201, RoutineArchivePart(routineId = "r1", part = 1, occurrences = mapOf(duplicate.key to duplicate)))
        rig.seedPart(202, RoutineArchivePart(routineId = "r1", part = 2, occurrences = mapOf(inSecondPart.key to inSecondPart)))
        rig.archiveDao.add(phoneOnly.toLegacyRow())

        val history = rig.repository.observeHistory("r1").first { it.size == 4 }

        assertEquals(
            listOf("2026-10-05", "2025-06-01", "2025-01-10", "2024-05-01"),
            history.map { it.scheduledDate },
            "newest first",
        )
        assertEquals(OccurrenceStatus.SKIPPED, history.first { it.scheduledDate == "2025-01-10" }.status, "last write wins")
    }

    @Test
    fun `history of a routine that never pruned does not ask the server`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(recent))

        val history = rig.repository.observeHistory("r1").first { it.isNotEmpty() }

        assertEquals(listOf("2026-10-05"), history.map { it.scheduledDate })
        assertTrue(rig.server.requests.isEmpty())
    }

    @Test
    fun `history keeps showing the parts this process has seen when the server is unreachable`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        val inPart = record("2025-01-10")
        rig.seedCarrier(100, payload(recent, prunedBefore = "2025-09-01"))
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1, occurrences = mapOf(inPart.key to inPart)))
        rig.repository.observeHistory("r1").first { it.size == 2 }

        rig.server.failure = { HttpStatusCode.ServiceUnavailable }
        val offline = rig.repository.observeHistory("r1").first { it.size == 2 }

        assertEquals(listOf("2026-10-05", "2025-01-10"), offline.map { it.scheduledDate })
    }

    @Test
    fun `the day views never read the archive`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(old1, recent, prunedBefore = "2025-09-01"))

        rig.repository.observeDay("2026-10-06").first()
        rig.repository.observeActive().first()

        assertTrue(rig.server.requests.isEmpty())
    }

    // --- Export --------------------------------------------------------------------------------

    @Test
    fun `the csv export includes archived history and guards formulas`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        val inPart = record("2025-01-10").copy(note = "=1+1")
        val definition = RoutineRig.definition().copy(name = "-Vitamin D")
        rig.seedCarrier(100, payload(recent, prunedBefore = "2025-09-01", definition = definition))
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1, occurrences = mapOf(inPart.key to inPart)))

        val export = rig.repository.exportCsvWithStatus()

        assertTrue(export.complete)
        val lines = export.csv.trimEnd().lines()
        assertEquals(3, lines.size, "header, the main record and the archived one")
        assertTrue(lines[1].startsWith("\"'-Vitamin D\",\"2025-01-10\""), lines[1])
        assertTrue(lines[1].endsWith(",\"'=1+1\""), lines[1])
        assertTrue(lines[2].startsWith("\"'-Vitamin D\",\"2026-10-05\""))
    }

    @Test
    fun `an export that cannot reach the archive says so and still returns the carrier history`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(recent, prunedBefore = "2025-09-01"))
        rig.server.failure = { HttpStatusCode.ServiceUnavailable }

        val export = rig.repository.exportCsvWithStatus()

        assertEquals(2, export.csv.trimEnd().lines().size, "the history in the carrier is still exported")
        assertFalse(export.complete, "and the caller is told the archive was missing")
    }

    // --- Deleting -----------------------------------------------------------------------------

    @Test
    fun `deleting a routine deletes its parts first and then the carrier`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        val inPart = record("2025-01-10")
        val foreign = record("2025-01-10", routineId = "other")
        rig.seedCarrier(100, payload(recent, prunedBefore = "2025-09-01"))
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1, occurrences = mapOf(inPart.key to inPart)))
        rig.seedPart(201, RoutineArchivePart(routineId = "r1", part = 2))
        rig.seedPart(300, RoutineArchivePart(routineId = "other", part = 1, occurrences = mapOf(foreign.key to foreign)))
        rig.archiveDao.add(inPart.toLegacyRow())

        val result = rig.repository.deletePermanently("r1")

        assertIs<NetworkResult.Success<*>>(result)
        val deletes = rig.server.requests.filter { it.method == "DELETE" }.map { it.path }
        assertEquals(listOf("/tasks/200", "/tasks/201", "/tasks/100"), deletes)
        assertEquals(setOf(300L), rig.server.rows.keys, "another routine's part stays")
        assertTrue(rig.archiveDao.all.isEmpty())
    }

    @Test
    fun `a part that cannot be deleted keeps the routine`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(recent, prunedBefore = "2025-09-01"))
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1))
        rig.server.failure = { if (it.method == "DELETE" && it.path == "/tasks/200") HttpStatusCode.InternalServerError else null }

        val result = rig.repository.deletePermanently("r1")

        assertIs<NetworkResult.Error>(result)
        assertTrue(100L in rig.server.rows, "the carrier is still there, so the delete can be retried")
    }

    @Test
    fun `a routine that never pruned deletes without searching`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(recent))

        val result = rig.repository.deletePermanently("r1")

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(listOf("DELETE /tasks/100"), rig.server.requests.map { it.toString() })
    }

    // --- Creating ---------------------------------------------------------------------------

    @Test
    fun `a routine is created in one request, already done, without positioning`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        val draft = RoutineDraft(
            name = "Vitamin D",
            kind = RoutineKind.HEALTH,
            schedule = RoutineSchedule.Calendar(anchorDate = "2026-10-06"),
            slots = listOf(RoutineSlot("", "Morning", RoutinePeriod.MORNING, reminderMinutes = 480)),
        )

        val result = rig.repository.create(draft)

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(listOf("POST /projects/5/tasks"), rig.server.requests.map { it.toString() })
        val body = rig.server.requests.single().bodyJson!!
        assertTrue(body.getValue("done").jsonPrimitive.boolean)
        assertTrue(RoutineEnvelope.hasCarrierMarker(body.getValue("description").jsonPrimitive.content))
        val carrier = rig.server.rows.values.single()
        assertTrue(carrier.done)
        val payload = rig.serverPayload(carrier.id)
        assertEquals("2026-10-06", payload.definition.activeFrom)
        assertTrue(rig.taskDao.entity(carrier.id)!!.done, "cached as done, so it never shows in a list")
    }

    @Test
    fun `timestamps written by the repository have exactly three fraction digits`() = runTest {
        val rig = RoutineRig(backgroundScope, now = "2026-10-06T08:00:00Z").also { it.signIn() }
        rig.seedCarrier(100, payload(recent))

        rig.repository.setOccurrenceStatus("r1", "2026-10-06", "s1", OccurrenceStatus.COMPLETED)

        val written = rig.serverPayload(100).occurrences.getValue("r1:2026-10-06:s1")
        assertEquals("2026-10-06T08:00:00.000Z", written.modifiedAt)
        assertEquals("2026-10-06T08:00:00.000Z", written.loggedAt)
        assertEquals("Europe/Amsterdam", written.timeZoneId)
    }

    // --- The window follows the clock it is given ------------------------------------------------

    @Test
    fun `the rolling window is computed from the injected clock`() = runTest {
        val rig = RoutineRig(backgroundScope, now = "2027-01-15T08:00:00Z").also { it.signIn() }
        // 2027-01-15 minus 400 days is 2025-12-11.
        val before = record("2025-12-10")
        val onTheCutoff = record("2025-12-11")
        rig.seedCarrier(100, payload(before, onTheCutoff, record("2027-01-14")))

        rig.repository.setOccurrenceStatus("r1", "2027-01-15", "s1", OccurrenceStatus.COMPLETED)

        assertEquals("2025-12-11", rig.serverPayload(100).prunedBefore)
        assertEquals(setOf(before.key), rig.serverPart(rig.server.archiveRows().single().id).occurrences.keys)
    }

    @Test
    fun `finalizing past days never makes records for days that are already archived`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        // Pruned forward to July by the size budget; nothing is recorded for the days before.
        rig.seedCarrier(100, payload(record("2026-09-01"), prunedBefore = "2026-07-01"))

        rig.repository.finalizeAndPrune()

        val carrier = rig.serverPayload(100)
        assertTrue(carrier.occurrences.values.none { it.scheduledDate < "2026-07-01" })
        assertTrue(carrier.occurrences.values.any { it.scheduledDate == "2026-07-01" && it.status == OccurrenceStatus.NOT_LOGGED })
        assertEquals("2026-07-01", carrier.prunedBefore)
        assertTrue(rig.server.archiveRows().isEmpty())
    }

    // --- Hiding -------------------------------------------------------------------------------

    @Test
    fun `an archive part in the task cache is not a routine, not an issue and not a task`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(recent))
        val description = RoutineEnvelope.encodeArchive(RoutineArchivePart(routineId = "r1", part = 1), rig.json)
        rig.taskDao.upsert(
            com.rendyhd.vicu.data.local.entity.TaskEntity(
                id = 200, title = "Vicu routine archive", description = description, done = true, projectId = 5,
            ),
        )

        assertEquals(listOf("r1"), rig.repository.observeActive().first().map { it.definition.id })
        assertTrue(rig.repository.observeIssues().first().isEmpty())
        assertTrue(rig.taskRepository.getAllTasksFlat().first().isEmpty(), "neither the carrier nor the part is a task")
        assertTrue(rig.taskRepository.getByIds(setOf(100L, 200L)).isEmpty())
    }

    @Test
    fun `the task refresh never caches archive parts`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload(recent))
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1))
        rig.server.seed(300, "Buy milk", "", done = false)

        rig.taskRepository.refreshAll()

        assertNotNull(rig.taskDao.entity(300))
        assertNotNull(rig.taskDao.entity(100), "the carrier is cached for the merge engine")
        assertNull(rig.taskDao.entity(200), "a part can be large and is read on demand")
    }

    @Test
    fun `a part an older build cached is dropped by the next full refresh`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedPart(200, RoutineArchivePart(routineId = "r1", part = 1))
        rig.taskDao.upsert(
            com.rendyhd.vicu.data.local.entity.TaskEntity(
                id = 200,
                description = rig.server.row(200).description,
                done = true,
                projectId = 5,
            ),
        )

        rig.taskRepository.refreshAll()

        assertNull(rig.taskDao.entity(200))
    }
}
