package com.rendyhd.vicu.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The Room schema exports (`shared/schemas`), loaded into a real in-memory SQLite (sqlite-jdbc)
 * for JVM unit tests: the tables and indexes exactly as Room creates them for a version.
 */
object ExportedSchema {
    private val schemaDir = File("schemas/com.rendyhd.vicu.data.local.VikunjaDatabase")

    fun entities(version: Int): JsonArray =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray

    /** An in-memory SQLite holding the tables and indexes the exported schema of [version] describes. */
    fun database(version: Int): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            for (entity in entities(version)) {
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                fun run(text: String) = statement.execute(text.replace("\${TABLE_NAME}", table))
                run(entity.jsonObject.getValue("createSql").jsonPrimitive.content)
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    run(index.jsonObject.getValue("createSql").jsonPrimitive.content)
                }
            }
        }
        return connection
    }
}
