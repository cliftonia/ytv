package com.cliftonia.fs42tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The background runner the schedule pre-warm uses. It exists because the pre-warm used to be
 * queued on an executor from the tune thread, and an executor shut down by onDestroy first
 * threw RejectedExecutionException on that thread - the process died.
 */
class AppThreadsTest {

    @Test
    fun `background work runs after every executor is shut down, and a failure is swallowed`() {
        val threads = AppThreads()
        threads.shutdown()
        val ran = CountDownLatch(2)
        threads.inBackground("boom") {
            ran.countDown()
            error("a failure must be logged, not thrown into whoever started it")
        }
        threads.inBackground("work") { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `background work runs on a low-priority daemon`() {
        val seen = CountDownLatch(1)
        var daemon = false
        var priority = -1
        AppThreads().apply { shutdown() }.inBackground("probe") {
            daemon = Thread.currentThread().isDaemon
            priority = Thread.currentThread().priority
            seen.countDown()
        }
        assertTrue(seen.await(5, TimeUnit.SECONDS))
        assertTrue(daemon)
        assertEquals(Thread.MIN_PRIORITY, priority)
    }
}
