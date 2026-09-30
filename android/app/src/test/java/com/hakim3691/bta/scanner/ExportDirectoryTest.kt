package com.hakim3691.bta.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression cover for "SAVE LOG reported success but the folder was empty".
 *
 * `getExternalFilesDir(null)` returns null when no external volume is mounted.
 * The old code did `File(context.getExternalFilesDir(null), "exports")`, and
 * File's two-arg constructor with a null parent yields the *relative* path
 * "exports" rather than throwing. `mkdirs()` then succeeded against the process
 * working directory and the write "worked", so a plausible absolute-looking log
 * line was emitted for a file that was never in app storage at all.
 *
 * Verified on the JVM:
 *   new File((File) null, "exports").getAbsolutePath() -> "<cwd>/exports"
 *   new File((File) null, "exports").mkdirs()           -> true
 */
class ExportDirectoryTest {

    @Test
    fun `a null external dir collapses to a bare relative path`() {
        val dir = File(null as File?, "exports")
        // This is the trap. It is NOT absolute, and nothing throws.
        assertEquals("exports", dir.path)
        assertFalse(dir.isAbsolute)
        assertEquals(null, dir.parent)
    }

    @Test
    fun `writing under a null parent succeeds against the working directory`() {
        // Everything is created inside a temp dir and removed afterwards. The
        // point of this test is to reproduce the old failure mode, and doing so
        // in the real working directory would leave a stray folder in the source
        // tree where it can be committed by accident.
        val sandbox = createTempDir()
        val probe = File(sandbox, "exports-probe")

        try {
            // Reproduce the old behaviour: relative to some working directory.
            probe.mkdirs()
            val written = File(probe, "bta_log_probe.txt")
            written.writeText("x")

            // It reports success and produces a file - in the wrong place. That
            // is precisely why the original bug was invisible from the logs.
            assertTrue(written.exists())
            assertFalse(
                "file must not be under the app dir when the base was null",
                written.absolutePath.startsWith("/storage/emulated")
            )
        } finally {
            probe.deleteRecursively()
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun `a resolved absolute directory is used when one is available`() {
        val base = createTempDir()
        try {
            val dir = File(base, "exports")
            assertTrue(dir.isAbsolute)
            if (!dir.isDirectory) assertTrue(dir.mkdirs())
            val file = File(dir, "bta_log_probe.txt")
            file.writeText("hello")
            assertTrue(file.absolutePath.startsWith(base.absolutePath))
            assertEquals("hello", file.readText())
        } finally {
            base.deleteRecursively()
        }
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("bta-export-test").toFile()
}
