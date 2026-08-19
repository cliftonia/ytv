package com.cliftonia.fs42tv.player

/**
 * mpv's EDL syntax for playing separate video and audio files as one stream.
 *
 * YouTube serves them apart above 360p. The box already does exactly this - `edl_url()` in
 * fs42/yt_cache.py - and the byte-length prefixes are what make it safe to embed URLs full of
 * `&`, `;` and `=` without escaping any of it.
 *
 * NO LONGER ON THE DIAL'S PLAY PATH. [MpvSource] attaches the audio as an external track
 * instead, because an EDL whose streams all fail is a fatal error that shuts mpv's core down,
 * and a dial has to survive a dead clip. This is kept, and kept in use by [MpvTestActivity],
 * precisely so the two can still be compared on the television - the change was made on upstream
 * evidence rather than on a measurement of this device, and being able to switch back is what
 * makes that honest.
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
