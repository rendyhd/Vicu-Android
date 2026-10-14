package com.rendyhd.vicu.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * A [SQLiteConnection] over a plain JDBC SQLite connection (sqlite-jdbc), so a Room
 * [androidx.room.migration.Migration] can run against a real SQLite in a JVM unit test. Only what
 * a migration needs: bind, step, read. Bind indexes are 1-based like SQLite's, column indexes
 * 0-based like [SQLiteStatement]'s.
 */
class JdbcSqliteConnection(val jdbc: Connection) : SQLiteConnection {
    override fun prepare(sql: String): SQLiteStatement = JdbcSqliteStatement(jdbc.prepareStatement(sql))

    override fun close() {
        jdbc.close()
    }
}

private class JdbcSqliteStatement(private val statement: PreparedStatement) : SQLiteStatement {
    private var rows: ResultSet? = null
    private var started = false

    override fun bindBlob(index: Int, value: ByteArray) = statement.setBytes(index, value)
    override fun bindDouble(index: Int, value: Double) = statement.setDouble(index, value)
    override fun bindLong(index: Int, value: Long) = statement.setLong(index, value)
    override fun bindText(index: Int, value: String) = statement.setString(index, value)
    override fun bindNull(index: Int) = statement.setNull(index, java.sql.Types.NULL)

    override fun getBlob(index: Int): ByteArray = checkNotNull(rows).getBytes(index + 1)
    override fun getDouble(index: Int): Double = checkNotNull(rows).getDouble(index + 1)
    override fun getLong(index: Int): Long = checkNotNull(rows).getLong(index + 1)
    override fun getText(index: Int): String = checkNotNull(rows).getString(index + 1)
    override fun isNull(index: Int): Boolean = checkNotNull(rows).getObject(index + 1) == null
    override fun getColumnCount(): Int = checkNotNull(rows).metaData.columnCount
    override fun getColumnName(index: Int): String = checkNotNull(rows).metaData.getColumnName(index + 1)
    override fun getColumnType(index: Int): Int = throw UnsupportedOperationException("not needed by a migration")

    override fun step(): Boolean {
        if (!started) {
            started = true
            rows = if (statement.execute()) statement.resultSet else null
        }
        return rows?.next() ?: false
    }

    override fun reset() {
        rows?.close()
        rows = null
        started = false
    }

    override fun clearBindings() = statement.clearParameters()

    override fun close() {
        rows?.close()
        statement.close()
    }
}
