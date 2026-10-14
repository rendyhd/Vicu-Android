package com.rendyhd.vicu.data.local

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs [MIGRATION_3_4] (assignees, project permission, members, comment signals and comments)
 * against a real SQLite, as [TaskMetadataMigrationTest] does for 2 to 3: the version 3 database
 * comes from the exported 3.json and the result must equal the tables of 4.json.
 */
class CollaborationMigrationTest {

    private fun Connection.insertTask(id: Long, title: String) {
        prepareStatement(
            "INSERT INTO tasks (id, title, description, done, doneAt, dueDate, priority, projectId, repeatAfter, repeatMode, " +
                "startDate, endDate, hexColor, percentDone, task_index, position, kanbanPosition, bucketId, created, updated, " +
                "createdById, createdByUsername, labelsJson, remindersJson, attachmentsJson, relatedTasksJson, isFavorite) " +
                "VALUES (?, ?, '', 0, '', '', 0, 7, 0, 0, '', '', '', 0.0, 0, 0.0, 0.0, 0, '', '', 0, '', '[]', '[]', '[]', '{}', 0)",
        ).use {
            it.setLong(1, id)
            it.setString(2, title)
            it.executeUpdate()
        }
    }

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

    private fun Connection.tables(): Set<String> =
        createStatement().use { s ->
            s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private fun migrate(connection: Connection) {
        MIGRATION_3_4.migrate(JdbcSqliteConnection(connection))
    }

    @Test
    fun `it migrates from 3 to 4`() {
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }

    @Test
    fun `the migrated tables match the exported version 4 schema`() {
        val migrated = ExportedSchema.database(3).also { migrate(it) }
        val expected = ExportedSchema.database(4)

        for (entity in ExportedSchema.entities(4)) {
            val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
            assertEquals(expected.columns(table), migrated.columns(table), "columns of $table")
            assertEquals(expected.indexes(table), migrated.indexes(table), "indexes of $table")
        }
        assertTrue(setOf("project_members", "task_signals", "task_comments").all { it in migrated.tables() })
    }

    @Test
    fun `existing tasks keep their rows and get no assignees`() {
        val connection = ExportedSchema.database(3)
        connection.insertTask(1, "Milk")

        migrate(connection)
        connection.insertTask(2, "Bread")

        val rows = connection.createStatement().use { s ->
            s.executeQuery("SELECT id, title, assigneesJson FROM tasks ORDER BY id").use { rs ->
                buildList { while (rs.next()) add(listOf(rs.getLong(1), rs.getString(2), rs.getString(3))) }
            }
        }
        assertEquals(listOf(listOf(1L, "Milk", "[]"), listOf(2L, "Bread", "[]")), rows)
    }

    @Test
    fun `a project's permission is not known until the next refresh`() {
        val migrated = ExportedSchema.database(3).also { migrate(it) }
        val column = migrated.columns("projects").single { it[0] == "maxPermission" }
        assertEquals(listOf("maxPermission", "INTEGER", "1", "-1", "0"), column)
    }
}
