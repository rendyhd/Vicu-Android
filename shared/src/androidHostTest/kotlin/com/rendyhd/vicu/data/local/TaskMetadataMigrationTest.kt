package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.util.CustomListEnvelope
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs [MIGRATION_2_3] against a real SQLite (sqlite-jdbc) instead of Room's migration test
 * helper, which needs a device. The version 2 database is built from the exported schema 2.json,
 * the migrated result is compared with the tables of 3.json, and the backfill is checked against
 * the rule that sets the column on every later write.
 */
class TaskMetadataMigrationTest {

    private fun Connection.insertTask(id: Long, title: String, description: String) {
        prepareStatement(
            "INSERT INTO tasks (id, title, description, done, doneAt, dueDate, priority, projectId, repeatAfter, repeatMode, " +
                "startDate, endDate, hexColor, percentDone, task_index, position, kanbanPosition, bucketId, created, updated, " +
                "createdById, createdByUsername, labelsJson, remindersJson, attachmentsJson, relatedTasksJson, isFavorite) " +
                "VALUES (?, ?, ?, 0, '', '', 0, 7, 0, 0, '', '', '', 0.0, 0, 0.0, 0.0, 0, '', '', 0, '', '[]', '[]', '[]', '{}', 0)",
        ).use {
            it.setLong(1, id)
            it.setString(2, title)
            it.setString(3, description)
            it.executeUpdate()
        }
    }

    private fun Connection.flags(): Map<Long, Int> =
        createStatement().use { s ->
            s.executeQuery("SELECT id, isMetadata FROM tasks ORDER BY id").use { rs ->
                buildMap { while (rs.next()) put(rs.getLong(1), rs.getInt(2)) }
            }
        }

    /** What Room checks of a table after a migration: name, type, not null, default and primary key per column. */
    private fun Connection.columns(table: String): List<List<String?>> =
        createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(listOf(rs.getString("name"), rs.getString("type"), rs.getString("notnull"), rs.getString("dflt_value"), rs.getString("pk")))
                    }
                }
            }
        }

    private fun Connection.indexes(table: String): Set<Pair<String, List<String>>> =
        createStatement().use { s ->
            val names = s.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            names.filterNot { it.startsWith("sqlite_autoindex") }.map { name ->
                name to s.executeQuery("PRAGMA index_info(`$name`)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }.toSet()
        }

    private fun migrate(connection: Connection) {
        MIGRATION_2_3.migrate(JdbcSqliteConnection(connection))
    }

    private val routineCarrier = "<!-- vicu-routine:v1:eyJ4IjoxfQ -->"
    private val routineArchive = "<!-- vicu-routine:archive:v1:eyJ4IjoxfQ -->"
    private val customListCarrier = "<!-- vicu-custom-lists:v1:eyJ4IjoxfQ -->"

    private val metadataSamples = listOf(
        "routine carrier" to routineCarrier,
        "routine archive part" to routineArchive,
        "custom-list carrier" to customListCarrier,
        "carrier after a body" to "<p>Notes</p>\n$routineCarrier",
        "carrier without spaces" to "<!--vicu-custom-lists:v1:e30-->",
    )

    private val ordinarySamples = listOf(
        "empty" to "",
        "html" to "<p>Buy milk</p>",
        "plain text naming a marker" to "see vicu-routine: and vicu-custom-lists: in the docs",
        "ordinary comment" to "<!-- an ordinary comment -->",
        "marker name outside a comment" to "<p>vicu-routine:v1:abc</p>",
    )

    private val samples = metadataSamples + ordinarySamples

    @Test
    fun `it migrates from 2 to 3`() {
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test
    fun `the migrated tables match the exported version 3 schema`() {
        val migrated = ExportedSchema.database(2).also { migrate(it) }
        val expected = ExportedSchema.database(3)

        for (entity in ExportedSchema.entities(3)) {
            val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
            assertEquals(expected.columns(table), migrated.columns(table), "columns of $table")
            assertEquals(expected.indexes(table), migrated.indexes(table), "indexes of $table")
        }
        assertTrue("index_tasks_isMetadata" in migrated.indexes("tasks").map { it.first })
    }

    @Test
    fun `the new column is not null and defaults to zero for a row written without it`() {
        val migrated = ExportedSchema.database(2).also { migrate(it) }

        migrated.insertTask(1, "Plain", "plain")

        assertEquals(mapOf(1L to 0), migrated.flags())
        val column = migrated.columns("tasks").single { it[0] == "isMetadata" }
        assertEquals(listOf("isMetadata", "INTEGER", "1", "0", "0"), column)
    }

    @Test
    fun `existing carriers are flagged and ordinary tasks are not`() {
        val connection = ExportedSchema.database(2)
        samples.forEachIndexed { index, (_, description) -> connection.insertTask(index + 1L, "Task $index", description) }

        migrate(connection)

        val flags = connection.flags()
        samples.forEachIndexed { index, (name, _) ->
            assertEquals(index < metadataSamples.size, flags.getValue(index + 1L) == 1, name)
        }
    }

    @Test
    fun `the backfill agrees with the rule that sets the column on every later write`() {
        val connection = ExportedSchema.database(2)
        samples.forEachIndexed { index, (_, description) -> connection.insertTask(index + 1L, "Task $index", description) }

        migrate(connection)

        val flags = connection.flags()
        samples.forEachIndexed { index, (name, description) ->
            assertEquals(CustomListEnvelope.isAnyMetadataTask(description), flags.getValue(index + 1L) == 1, name)
        }
    }

    @Test
    fun `the backfill is looser than the write rule only in case, which the next write corrects`() {
        val connection = ExportedSchema.database(2)
        connection.insertTask(1, "Shouting", "<!-- VICU-ROUTINE:v1:abc -->")

        migrate(connection)

        assertEquals(1, connection.flags().getValue(1L), "LIKE ignores case")
        assertEquals(false, CustomListEnvelope.isAnyMetadataTask("<!-- VICU-ROUTINE:v1:abc -->"), "the write rule does not")
    }

    @Test
    fun `no other column or row changes`() {
        val connection = ExportedSchema.database(2)
        connection.insertTask(1, "Carrier", routineCarrier)
        connection.insertTask(2, "Milk", "plain")

        migrate(connection)

        val rows = connection.createStatement().use { s ->
            s.executeQuery("SELECT id, title, description, projectId FROM tasks ORDER BY id").use { rs ->
                buildList { while (rs.next()) add(listOf(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4))) }
            }
        }
        assertEquals(listOf(listOf(1L, "Carrier", routineCarrier, 7L), listOf(2L, "Milk", "plain", 7L)), rows)
    }

    @Test
    fun `a list query can leave metadata rows out by the indexed column`() {
        val connection = ExportedSchema.database(2)
        connection.insertTask(1, "Carrier", routineCarrier)
        connection.insertTask(2, "Milk", "plain")
        migrate(connection)

        val visible = connection.createStatement().use { s ->
            s.executeQuery("SELECT id FROM tasks WHERE done = 0 AND isMetadata = 0 AND projectId = 7 ORDER BY id").use { rs ->
                buildList { while (rs.next()) add(rs.getLong(1)) }
            }
        }

        assertEquals(listOf(2L), visible)
    }
}
