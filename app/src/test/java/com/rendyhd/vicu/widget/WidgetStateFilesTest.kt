package com.rendyhd.vicu.widget

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Widget state holds task titles, so it lives where backups and device transfers do not reach. */
class WidgetStateFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var noBackupDir: File

    @Before
    fun setUp() {
        filesDir = folder.newFolder("files")
        noBackupDir = folder.newFolder("no_backup")
    }

    private fun legacy(name: String) = File(filesDir, "datastore/$name.preferences_pb")

    private fun writeLegacy(name: String, bytes: ByteArray): File =
        legacy(name).also {
            it.parentFile!!.mkdirs()
            it.writeBytes(bytes)
        }

    @Test
    fun `the state file is in the no-backup folder`() {
        val file = WidgetStateFiles.locate(filesDir, noBackupDir, "widget_state_42")

        assertEquals(File(noBackupDir, "datastore/widget_state_42.preferences_pb"), file)
        assertTrue("the folder is there for the data store to write into", file.parentFile!!.isDirectory)
        assertFalse(file.exists())
    }

    @Test
    fun `an existing state file is moved over with its content`() {
        val content = byteArrayOf(1, 2, 3, 4, 5)
        val old = writeLegacy("widget_state_7", content)

        val file = WidgetStateFiles.locate(filesDir, noBackupDir, "widget_state_7")

        assertArrayEquals(content, file.readBytes())
        assertFalse("the copy that backups see is gone", old.exists())
    }

    @Test
    fun `a state file already in place wins over a stale one left behind`() {
        val kept = byteArrayOf(9, 9)
        val target = File(noBackupDir, "datastore/widget_state_7.preferences_pb")
        target.parentFile!!.mkdirs()
        target.writeBytes(kept)
        val old = writeLegacy("widget_state_7", byteArrayOf(1))

        val file = WidgetStateFiles.locate(filesDir, noBackupDir, "widget_state_7")

        assertArrayEquals(kept, file.readBytes())
        assertFalse(old.exists())
    }

    @Test
    fun `asking again changes nothing`() {
        writeLegacy("routine_widget_state_3", byteArrayOf(5))

        val first = WidgetStateFiles.locate(filesDir, noBackupDir, "routine_widget_state_3")
        val second = WidgetStateFiles.locate(filesDir, noBackupDir, "routine_widget_state_3")

        assertEquals(first, second)
        assertArrayEquals(byteArrayOf(5), second.readBytes())
    }

    @Test
    fun `only the named widget is moved`() {
        writeLegacy("widget_state_1", byteArrayOf(1))
        val other = writeLegacy("widget_state_2", byteArrayOf(2))
        val auth = writeLegacy("auth_prefs", byteArrayOf(3))

        WidgetStateFiles.locate(filesDir, noBackupDir, "widget_state_1")

        assertTrue(other.exists())
        assertTrue("other data stores stay where they are", auth.exists())
    }

    @Test
    fun `a half-written leftover of the old file is cleared too`() {
        writeLegacy("widget_state_4", byteArrayOf(1))
        val tmp = File(filesDir, "datastore/widget_state_4.preferences_pb.tmp").also { it.writeBytes(byteArrayOf(7)) }

        WidgetStateFiles.locate(filesDir, noBackupDir, "widget_state_4")

        assertFalse(tmp.exists())
    }
}
