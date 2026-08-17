package com.cliftonia.fs42tv

import android.util.Log
import java.io.File

/**
 * Keep the last crash so it can be read off the television screen.
 *
 * Written because a crash on a device with no adb is a dead end. Chasing one channel that failed
 * cost an afternoon of probing from a laptop - resolution, channel data, codecs, titles - and
 * every answer came back clean, because the fault was never in any of the places that could be
 * inspected remotely. A stack trace would have named it immediately.
 *
 * Only catches what the JVM can catch. A native crash inside a decoder takes the process down
 * without ever reaching this handler, so an empty crash file after a hard crash is itself
 * evidence: it means the fault was below Kotlin, in mediacodec or a player's own native code.
 * That distinction is worth as much as the trace.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"
    private const val FRAMES = 12

    /**
     * Install the handler, chaining rather than replacing whatever is already there.
     *
     * Chaining matters: Android's default handler is what actually kills the process and reports
     * the crash to the system. Swallowing it would leave the app in a half-dead state that looks
     * like a freeze rather than a crash, which is strictly harder to diagnose than what we have.
     */
    fun install(dir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(dir, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(dir: File, thread: Thread, error: Throwable) {
        val text = buildString {
            append(error::class.java.simpleName).append(": ").append(error.message).append('\n')
            append("on thread ").append(thread.name).append('\n')
            // The top frames only. The screen has room for about a dozen lines, and the top of a
            // stack is where the answer is; a full trace that has to be scrolled on a television
            // is a trace nobody reads.
            error.stackTrace.take(FRAMES).forEach { append("  ").append(it).append('\n') }
            error.cause?.let { cause ->
                append("caused by ").append(cause::class.java.simpleName)
                    .append(": ").append(cause.message).append('\n')
                cause.stackTrace.take(FRAMES / 2).forEach { append("  ").append(it).append('\n') }
            }
        }
        File(dir, FILE).writeText(text)
        Log.e("fs42", "crash recorded:\n$text")
    }

    /** The last recorded crash, or null. Survives a restart; that is the entire point. */
    fun last(dir: File): String? =
        File(dir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    /** One line naming the crash, for a settings row that has no room for a stack. */
    fun summary(dir: File): String? = last(dir)?.lineSequence()?.firstOrNull()?.take(60)

    fun clear(dir: File) {
        File(dir, FILE).delete()
    }
}
