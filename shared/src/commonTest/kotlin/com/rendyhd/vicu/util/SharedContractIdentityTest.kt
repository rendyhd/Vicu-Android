package com.rendyhd.vicu.util

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The shared fixtures and documents are kept identical in vicu-android and the desktop repo
 * (CLAUDE.md, "Cross-app contract"). When the desktop checkout sits next to this repo, compare
 * every shared file after normalising line endings; without it the test is skipped (CI has only
 * this repo). `VICU_DESKTOP_DIR` points at a checkout somewhere else.
 *
 * `custom-list-sync-v1.json` lives in `shared/src/commonTest/resources/` here and in
 * `test-fixtures/` on the desktop, so the pairs are explicit instead of one path for both.
 */
class SharedContractIdentityTest {

    /** A shared file: its path in this repo and its path in the desktop repo. */
    private data class Pair(val android: String, val desktop: String)

    private fun same(path: String) = Pair(path, path)

    private val pairs: List<Pair> = listOf(
        same("test-fixtures/cross-app-semantics-v1.json"),
        same("test-fixtures/nlp-corpus-v1.json"),
        same("test-fixtures/routine-archive-v1.json"),
        same("test-fixtures/description-format-v1.json"),
        same("test-fixtures/design-tokens-v1.json"),
        Pair("shared/src/commonTest/resources/custom-list-sync-v1.json", "test-fixtures/custom-list-sync-v1.json"),
        same("docs/cross-app-semantics-v1.md"),
        same("docs/description-format-v1.md"),
        same("docs/design-system-v1.md"),
    )

    /** This repo's root: the nearest directory above the working directory that holds `test-fixtures/`. */
    private fun androidRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "test-fixtures").isDirectory && File(dir, "docs").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun desktopRoot(android: File): File? {
        val fromEnv = System.getenv("VICU_DESKTOP_DIR")
        val candidate = if (!fromEnv.isNullOrBlank()) File(fromEnv) else File(android.parentFile, "vicu")
        return candidate.takeIf { File(it, "test-fixtures").isDirectory && File(it, "docs").isDirectory }
    }

    private fun normalised(file: File): String =
        file.readText(Charsets.UTF_8).removePrefix(Char(0xFEFF).toString()).replace("\r\n", "\n").replace('\r', '\n')

    /** Where two texts first differ, for a failure message that points at the line. */
    private fun firstDifference(android: String, desktop: String): String {
        val a = android.split('\n')
        val d = desktop.split('\n')
        for (i in 0 until maxOf(a.size, d.size)) {
            if (a.getOrNull(i) != d.getOrNull(i)) {
                return "line ${i + 1}: android \"${(a.getOrNull(i) ?: "<end>").take(120)}\" vs desktop \"${(d.getOrNull(i) ?: "<end>").take(120)}\""
            }
        }
        return "no difference found"
    }

    @Test
    fun `shared fixtures and documents match the desktop repo`() {
        val android = androidRoot()
        val desktop = android?.let { desktopRoot(it) }
        assumeTrue("the desktop repo is not next to this one; nothing to compare", android != null && desktop != null)
        if (android == null || desktop == null) return

        val problems = mutableListOf<String>()
        for (pair in pairs) {
            val androidFile = File(android, pair.android)
            val desktopFile = File(desktop, pair.desktop)
            if (!androidFile.isFile) { problems += "missing here: ${pair.android}"; continue }
            if (!desktopFile.isFile) { problems += "missing in the desktop repo: ${pair.desktop}"; continue }
            val a = normalised(androidFile)
            val d = normalised(desktopFile)
            if (a != d) problems += "${pair.android} differs from the desktop ${pair.desktop}: ${firstDifference(a, d)}"
        }
        assertTrue("shared contract files out of sync:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
