package com.cliftonia.fs42tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The structural rules the codebase was cleaned up to meet, enforced so they stay met.
 *
 * A ceiling on file length is really a ceiling on how many ideas share a file: MainActivity
 * reached nineteen hundred lines by absorbing tuning, refusals, settings, captions and the
 * guide one convenient edit at a time, and no single edit looked like the problem. Five
 * hundred lines - roughly half comment, in this codebase - holds one idea comfortably and two
 * uncomfortably, which is exactly the discomfort wanted: when a file outgrows it, the answer
 * is the next extraction, not a bigger number.
 */
class SourceHygieneTest {

    @Test
    fun `no source file grows beyond a single idea again`() {
        val sources = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("the source root should be found from the test working directory",
            sources.isNotEmpty())
        val over = sources
            .map { it to it.readLines().size }
            .filter { (_, lines) -> lines > MAX_LINES }
        assertTrue(
            "these files have outgrown one idea; extract, don't raise the limit: " +
                over.joinToString { "${it.first.name} (${it.second})" },
            over.isEmpty(),
        )
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/java")
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "app/src/main/java")
            if (nested.isDirectory) return nested
            dir = dir.parentFile
        }
        error("src/main/java not found above ${File(".").absolutePath}")
    }

    private companion object {
        const val MAX_LINES = 500
    }
}
