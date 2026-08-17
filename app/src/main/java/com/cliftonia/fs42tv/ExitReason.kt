package com.cliftonia.fs42tv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Why the app died last time, according to Android rather than according to us.
 *
 * [CrashLog] only sees what the JVM can catch, and the crash being chased produced nothing there -
 * which narrowed it to "below Kotlin" but no further. Android records the real reason for every
 * process death and will hand it back on request, including the cases a Java handler can never
 * observe: a native SIGSEGV, an ANR, and the low-memory killer.
 *
 * That last one matters here. The television has 2.34GB of memory in total and the crash is random
 * rather than tied to any channel - which is exactly what being killed under memory pressure looks
 * like, and is a completely different problem from a decoder fault with a completely different fix.
 * Guessing between them is what this exists to stop.
 */
object ExitReason {

    /** The last abnormal exit, in a form that fits on a television screen, or null if clean. */
    fun lastAbnormal(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val info = runCatching {
            // Just the most recent. Older ones are history; the question is always "what happened
            // the time it just died".
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null

        if (!isAbnormal(info.reason, info.status)) return null
        val detail = info.description?.take(48).orEmpty()
        return buildString {
            append(name(info.reason))
            if (info.status != 0) append(" (status ").append(info.status).append(')')
            if (detail.isNotEmpty()) append(": ").append(detail)
        }
    }

    /**
     * Whether this exit is worth telling anyone about.
     *
     * A normal exit, a user-requested stop, or Android reclaiming a backgrounded app are all
     * ordinary and constant on a television - reporting them would bury the one that matters.
     */
    internal fun isAbnormal(reason: Int, status: Int = 0): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        -> true
        // A process that called exit() itself. Ordinary at status 0 - that is a clean shutdown -
        // but a NON-ZERO status is native code giving up, and this file was blind to precisely
        // the fault it was written to catch: libmpv's `die()` logs and calls exit(1), which
        // Android files here rather than under any crash reason. No signal, no tombstone, no
        // Java exception, and until this line no report either.
        ApplicationExitInfo.REASON_EXIT_SELF -> status != 0
        else -> false
    }

    /** Plain words, because "reason 5" on a television screen helps nobody. */
    internal fun name(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "JAVA CRASH"
        // The one the uncaught-exception handler can never see.
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
        ApplicationExitInfo.REASON_ANR -> "FROZE (ANR)"
        // Killed for memory rather than broken - a different fault with a different fix.
        ApplicationExitInfo.REASON_LOW_MEMORY -> "KILLED - LOW MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "KILLED BY SIGNAL"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "KILLED - USED TOO MUCH"
        // Reached only with a non-zero status; see isAbnormal. Native code called exit().
        ApplicationExitInfo.REASON_EXIT_SELF -> "NATIVE GAVE UP (exit)"
        else -> "EXIT $reason"
    }
}
