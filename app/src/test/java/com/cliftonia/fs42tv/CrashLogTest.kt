package com.cliftonia.fs42tv

import java.nio.file.Files
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Keeping a crash long enough to read it off the screen.
 *
 * The value here is entirely in surviving the process that recorded it, so these check the round
 * trip rather than the formatting.
 */
class CrashLogTest {

    private lateinit var dir: java.io.File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("fs42crash").toFile()
    }

    @Test
    fun `nothing is reported when nothing has crashed`() {
        assertNull(CrashLog.last(dir))
        assertNull(CrashLog.summary(dir))
    }

    @Test
    fun `a crash survives to be read back`() {
        CrashLog.install(dir)
        val error = IllegalStateException("channel 48 exploded")
        // Invoke the handler directly. Actually throwing would take the test JVM with it, which
        // is exactly the behaviour being relied on in production and exactly what cannot be done
        // inside a test runner.
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), error)

        val text = CrashLog.last(dir)
        assertTrue("the crash was not recorded", text != null)
        assertTrue("the exception type is missing", text!!.contains("IllegalStateException"))
        assertTrue("the message is missing", text.contains("channel 48 exploded"))
        assertTrue("no stack frames were kept", text.lines().size > 2)
    }

    @Test
    fun `the summary is one short line, for a row with no room for a stack`() {
        CrashLog.install(dir)
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(
            Thread.currentThread(), RuntimeException("a".repeat(200)))
        val summary = CrashLog.summary(dir)!!
        assertTrue(summary.length <= 60)
        assertTrue(!summary.contains("\n"))
    }

    @Test
    fun `clearing means the next crash is unambiguously new`() {
        CrashLog.install(dir)
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), RuntimeException("first"))
        CrashLog.clear(dir)
        assertNull(CrashLog.last(dir))
    }

    @Test
    fun `the existing handler still runs, because it is what kills the process`() {
        // Swallowing Android's default handler would leave a half-dead app that looks like a
        // freeze - strictly harder to diagnose than the crash it replaced.
        var chained = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained = true }
        CrashLog.install(dir)
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertTrue("the previous handler was not called", chained)
    }

    @Test
    fun `a cause is recorded too, since that is usually the real fault`() {
        CrashLog.install(dir)
        val error = RuntimeException("outer", IllegalArgumentException("the actual problem"))
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), error)
        assertTrue(CrashLog.last(dir)!!.contains("the actual problem"))
    }
}
