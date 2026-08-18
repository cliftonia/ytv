package com.cliftonia.fs42tv.player

/**
 * mpv's EDL syntax for playing separate video and audio files as one stream.
 *
 * YouTube serves them apart above 360p. The box already does exactly this - `edl_url()` in
 * fs42/yt_cache.py - and the byte-length prefixes are what make it safe to embed URLs full of
 * `&`, `;` and `=` without escaping any of it.
 *
 * Out of [MpvView] and into its own file because `MPVLib`'s static initialiser calls
 * `System.loadLibrary("mpv")`. This is callable from a JVM test today only by accident of where
 * `BaseMPVView`'s fields happen to sit; a single `private val` in that class referencing an
 * `MPVLib` constant would turn every test touching it into an `UnsatisfiedLinkError`.
 */
object MpvEdl {

    fun of(videoUrl: String, audioUrl: String): String {
        // BYTE lengths, not character counts. mpv reads exactly this many bytes as the URL, so a
        // title or query parameter carrying a multi-byte character would otherwise cut the URL
        // short by the difference and mpv would open a truncated address.
        val v = videoUrl.toByteArray(Charsets.UTF_8).size
        val a = audioUrl.toByteArray(Charsets.UTF_8).size
        return "edl://!no_clip;!track_meta,title=video;%$v%$videoUrl" +
            ";!new_stream;!no_clip;!track_meta,title=audio;%$a%$audioUrl"
    }
}
