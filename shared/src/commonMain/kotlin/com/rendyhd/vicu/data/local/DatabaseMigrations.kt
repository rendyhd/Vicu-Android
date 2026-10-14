package com.rendyhd.vicu.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `routine_occurrence_archive` (
                `routineId` TEXT NOT NULL,
                `occurrenceKey` TEXT NOT NULL,
                `slotId` TEXT NOT NULL,
                `scheduledDate` TEXT NOT NULL,
                `scheduledMinutes` INTEGER NOT NULL,
                `timeZoneId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `loggedAt` TEXT NOT NULL,
                `modifiedAt` TEXT NOT NULL,
                `modifiedBy` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                PRIMARY KEY(`routineId`, `occurrenceKey`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * Version 3 stores whether a task is hidden sync metadata (a routine carrier or archive part, a
 * custom-list carrier) in a column of its own, so the list queries can leave those rows out in SQL
 * (A-UI-19). The statements are public so a test can run them against a real SQLite.
 *
 * The backfill uses `LIKE`: a marker comment names `vicu-routine:` or `vicu-custom-lists:` after
 * `<!--`. That is slightly looser than the regex that sets the column on every later write
 * ([com.rendyhd.vicu.util.CustomListEnvelope.isAnyMetadataTask]): it ignores case and what sits
 * between `<!--` and the name. A row that matches here but not there is corrected the next time
 * the row is written, which the next refresh does because the stored row differs from the one
 * mapped from the server.
 */
val TASK_METADATA_MIGRATION_SQL: List<String> = listOf(
    "ALTER TABLE `tasks` ADD COLUMN `isMetadata` INTEGER NOT NULL DEFAULT 0",
    """
    UPDATE `tasks` SET `isMetadata` = 1
    WHERE `description` LIKE '%<!--%vicu-routine:%-->%'
       OR `description` LIKE '%<!--%vicu-custom-lists:%-->%'
    """.trimIndent(),
    "CREATE INDEX IF NOT EXISTS `index_tasks_isMetadata` ON `tasks` (`isMetadata`)",
)

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        TASK_METADATA_MIGRATION_SQL.forEach { connection.execSQL(it) }
    }
}

/**
 * Version 4 adds collaboration (docs/cross-app-semantics-v1.md section 9): the assignees of a task,
 * your permission on a project, the members of each project, the comment signals of the tasks in
 * shared projects and the comment threads that were opened on this phone. Public so a test can run
 * the statements against a real SQLite.
 */
val COLLABORATION_MIGRATION_SQL: List<String> = listOf(
    "ALTER TABLE `tasks` ADD COLUMN `assigneesJson` TEXT NOT NULL DEFAULT '[]'",
    "ALTER TABLE `projects` ADD COLUMN `maxPermission` INTEGER NOT NULL DEFAULT -1",
    "CREATE TABLE IF NOT EXISTS `project_members` (`projectId` INTEGER NOT NULL, `userId` INTEGER NOT NULL, `username` TEXT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`projectId`, `userId`))",
    "CREATE TABLE IF NOT EXISTS `task_signals` (`taskId` INTEGER NOT NULL, `commentCount` INTEGER NOT NULL, `isUnread` INTEGER NOT NULL, PRIMARY KEY(`taskId`))",
    "CREATE TABLE IF NOT EXISTS `task_comments` (`id` INTEGER NOT NULL, `taskId` INTEGER NOT NULL, `authorId` INTEGER NOT NULL, `authorUsername` TEXT NOT NULL, `authorName` TEXT NOT NULL, `comment` TEXT NOT NULL, `created` TEXT NOT NULL, `updated` TEXT NOT NULL, `pending` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_task_comments_taskId` ON `task_comments` (`taskId`)",
)

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        COLLABORATION_MIGRATION_SQL.forEach { connection.execSQL(it) }
    }
}
