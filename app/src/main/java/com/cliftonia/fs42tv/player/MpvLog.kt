package com.cliftonia.fs42tv.player

import android.util.Log

/**
 * The last few things mpv said before it gave up, kept so they can be read off the screen.
 *
 * mpv always explains itself. It logs the reason - "Failed to open", "No video or audio streams
 * selected", a TLS failure, a demuxer error - immediately before shutting its core down. That line
 * is the answer to "why did MPV_SHUTDOWN happen", and until now it went only to logcat, which
 * needs a laptop and an authorised adb connection to a television that has neither.
 *
 * So the app keeps its own copy. When the engine dies, the stand-by card can say what mpv said
 * rather than the useless fact that it died.
 *
 * A small ring buffer of warnings and worse. Info and below are far too chatty to keep - mpv emits
 * hundreds of lines per file - and the fatal reason is always at ERROR or FATAL anyway.
 */
object MpvLog {

    private const val KEEP = 12
    private val lines = ArrayDeque<String>()

    /** Called from mpv's own thread, so every access is synchronised. */
    fun record(prefix: String?, level: Int, text: String?) {
        val message = text?.trim().orEmpty()
        if (message.isEmpty()) return
        synchronized(lines) {
            lines.addLast("[$prefix] $message")
            while (lines.size > KEEP) lines.removeFirst()
        }
        Log.w("fs42mpv", "[$prefix] $message")
    }

    /**
     * The most recent line kept, or null.
     *
     * The newest one, with no search: the last error before a shutdown is the one that caused it,
     * and the ones before are usually consequences of it being reported once per stream in the
     * EDL. Truncated to what the stand-by card can draw on a television at viewing distance.
     */
    fun lastReason(): String? = synchronized(lines) {
        lines.lastOrNull()?.take(90)
    }

    /** Everything kept, newest last, for the settings screen. */
    fun recent(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
