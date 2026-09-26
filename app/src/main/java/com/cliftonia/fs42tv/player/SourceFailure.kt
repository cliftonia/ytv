package com.cliftonia.fs42tv.player

/**
 * One grep-able line saying WHICH request a Media3 source error was and WHY, without a token.
 *
 * Here because "playback failed: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" was all a Media3 run
 * on Pluto's own route said (Sep 2026): mpv played the same urls, the legacy route played under
 * Media3, and nothing named the request that failed - the master, a variant, a rendition, a
 * segment or an AES key, on the stitcher or the CDN. The stack trace alone does not say it, and
 * Android drops the whole trace when an UnknownHostException is anywhere in the chain.
 *
 * Never a query: a Pluto url carries the session's jwt in it, and googlevideo's carry signatures.
 * Messages are scrubbed the same way, since some exceptions quote the url they failed on.
 *
 * Pure; [ChannelPlayer] walks the Throwable and hands over plain strings.
 */
object SourceFailure {

    private val URL = Regex("""\b(https?)://([^/\s?#"']+)([^\s?#"']*)(\?[^\s#"']*)?""")

    /** [url] as scheme://host/path - never its query or fragment. */
    fun redact(url: String): String {
        val m = URL.find(url) ?: return url.substringBefore('?').substringBefore('#')
        return "${m.groupValues[1]}://${m.groupValues[2]}${m.groupValues[3]}"
    }

    /** Session parameters as a relative uri in a message carries them - no scheme to find. */
    private val SESSION_PARAM = Regex("""[?&](jwt|sid|deviceId)=[^&\s"']*""")

    /** [text] with every url in it cut back to host and path, and no session parameter left. */
    fun scrub(text: String): String = URL.replace(text) { m ->
        "${m.groupValues[1]}://${m.groupValues[2]}${m.groupValues[3]}"
    }.replace(SESSION_PARAM, "")

    /**
     * "source failed on https://host/path: A(msg) <- B(msg) <- ...". [request] is the failing
     * DataSpec's url when one was found; [chain] is outermost first, class simple name and message.
     */
    fun describe(request: String?, chain: List<Pair<String, String?>>): String {
        val where = request?.let { " on ${redact(it)}" }.orEmpty()
        val why = chain.joinToString(" <- ") { (name, message) ->
            if (message.isNullOrBlank()) name else "$name(${scrub(message).take(MAX_MESSAGE)})"
        }
        return "source failed$where: $why"
    }

    /** A message longer than this is a body or a dump, not a reason. */
    private const val MAX_MESSAGE = 200
}

/**
 * [SourceFailure.describe] for a Media3 error: the url of the innermost HTTP request in its cause
 * chain, and every cause. Android-only (DataSpec's uri is android.net.Uri).
 */
@androidx.media3.common.util.UnstableApi
fun describeSource(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
    val request = chain.filterIsInstance<androidx.media3.datasource.HttpDataSource.HttpDataSourceException>()
        .lastOrNull()?.dataSpec?.uri?.toString()
    return SourceFailure.describe(request, chain.map { it.javaClass.simpleName to it.message })
}

/** Deeper than any real chain; a cycle cannot loop the log. */
private const val MAX_CAUSES = 8
