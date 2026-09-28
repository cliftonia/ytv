package com.cliftonia.fs42tv.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
// `is` is a Kotlin keyword, so mpv's package has to be quoted to be importable.
import `is`.xyz.mpv.BaseMPVView
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import java.util.concurrent.atomic.AtomicLong

/**
 * libmpv on a SurfaceView, configured for this dial.
 *
 * Here because Media3's frame pacing judders on this television and mpv's does not, measured on
 * the same clips at the same wall-clock offsets after eight Media3-side theories were each ruled
 * out. The option that matters is `video-sync=display-resample`.
 *
 * The settings ([applyDialOptions]) come from three places, kept distinguishable on purpose:
 * mpv-android's own MPVView (the reference implementation), the box's `~/.config/mpv/mpv.conf`
 * which plays these exact googlevideo URLs, and this panel's measured refresh rate.
 */
class MpvView(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {

    /** What the dial needs to hear about. Set before use; cleared on destroy. */
    interface Events {
        /** [entryId] is the playlist entry that opened, when its START_FILE named one. */
        fun onFileLoaded(entryId: Long?)

        /**
         * mpv terminated and cannot play anything again.
         *
         * Not theoretical: when an EDL's video URL is refused with 403 both segments fail, mpv
         * logs `No video or audio streams selected` as FATAL and shuts the core down - `idle=yes`
         * does not cover a fatal. One dead URL would otherwise black out the dial permanently.
         */
        fun onShutdown()

        /**
         * A PLAYBACK_RESTART while a first frame is awaited. [startedEntryId] is the entry the
         * most recent START_FILE named - mpv delivers events in order, so that is the file this
         * restart belongs to - or null when the event carried no id.
         *
         * Returns whether the frame was accepted as the awaited load's. Only then does this view
         * stop reporting restarts; a rejected (stale) frame leaves the next one reportable.
         */
        fun onFirstFrame(startedEntryId: Long?): Boolean
        /**
         * [reason] is "error" or "eof"; [entryId] is mpv's playlist entry id for the file that
         * ended, when the event carried one - see [MpvLoadGuard.parseEndFile].
         */
        fun onEndFile(reason: String, entryId: Long?)
        fun onBuffering(buffering: Boolean)
    }

    // Same two threads as awaitingLoad, and release() relies on the null being seen.
    @Volatile var events: Events? = null

    /**
     * Which load (its [loads] number) is still waiting for its first presented frame, or 0 when
     * none is - so mid-clip restarts after a seek are not reported.
     *
     * Cleared only when the guard ACCEPTS a frame. It used to be cleared before asking, so a
     * rejected stale frame used up the only report the wanted file would ever get: its own
     * PLAYBACK_RESTART then found nothing awaited and the blank stayed up until the watchdog.
     *
     * A number rather than a flag, and cleared by compare-and-set, because mpv delivers events
     * on its own native thread while playAt runs on the UI thread: a plain `= false` after an
     * accepted frame could land just after the NEXT load set it, and swallow that load's frame.
     */
    private val awaitingLoad = AtomicLong(0)
    private val loads = AtomicLong(0)

    /**
     * The playlist entry the latest START_FILE named. Only ever touched on mpv's event thread,
     * and events arrive in order - START_FILE, FILE_LOADED, PLAYBACK_RESTART, END_FILE for one
     * file before the next file's START_FILE - so it is exactly the file the next FILE_LOADED or
     * PLAYBACK_RESTART is about. A property read at that moment would race the core, which may
     * already have moved on to the load that replaced it.
     */
    private var startedEntryId: Long? = null

    private val observer = object : MPVLib.EventObserver {
        override fun event(eventId: Int, node: MPVNode) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> startedEntryId =
                    MpvLoadGuard.parseEntryId(runCatching { node.toJson() }.getOrDefault(""))

                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> events?.onFileLoaded(startedEntryId)

                // PLAYBACK_RESTART fires once decoding has produced output and playback is
                // actually running - after a load and after any seek. Guarded so only the first
                // per clip counts as "the picture appeared".
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    val awaited = awaitingLoad.get()
                    if (awaited != 0L && events?.onFirstFrame(startedEntryId) == true) {
                        awaitingLoad.compareAndSet(awaited, 0L)
                    }
                }

                // mpv reports the end of a file for a clip finishing AND for a load failing, and
                // the dial's response differs completely: one moves to whatever is on next, the
                // other must drop a dead URL first or it resolves straight back to it. When the
                // reason cannot be read, "eof" is the safer default - re-tuning is harmless,
                // while wrongly declaring an error discards a URL that was never dead.
                MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> events?.onShutdown()

                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    val (reason, entryId) =
                        MpvLoadGuard.parseEndFile(runCatching { node.toJson() }.getOrDefault(""))
                    events?.onEndFile(reason, entryId)
                }
            }
        }

        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) = Unit
        override fun eventProperty(property: String, value: MPVNode) = Unit

        override fun eventProperty(property: String, value: Boolean) {
            // paused-for-cache is mpv's stall: playback stopped because the cache ran dry. It is
            // the direct equivalent of Media3's STATE_BUFFERING, and the only one worth surfacing.
            if (property == "paused-for-cache") events?.onBuffering(value)
        }
    }

    /** The dial's options, set before the core starts - see [applyDialOptions] for each one's reason. */
    override fun initOptions() = applyDialOptions()

    override fun postInitOptions() {
        // Re-assert idle AFTER init, as a property this time.
        //
        // Set only as a pre-init option it was accepted - mpv logged `event: idle` on startup -
        // and then mpv still emitted `event: shutdown` the moment a file failed to open, which
        // kills the instance for good: every later tune loads into a dead player and the screen
        // stays black with nothing in the log. A dial tunes into one instance hundreds of times,
        // so surviving a failed load is not optional here.
        MPVLib.setPropertyString("idle", "yes")
        // Do not tear down the video output between files either. Without this each tune
        // reinitialises the whole gpu context, which on this SoC is visible as a longer black
        // gap than the channel change itself needs.
        MPVLib.setPropertyString("keep-open", "no")
        MPVLib.setPropertyString("vid", "auto")
        // Read back rather than assumed: mpv logged `event: idle` at startup and then still shut
        // itself down the moment a file failed to open, so whether this option is actually held
        // is the difference between a dial that survives a 403 and one that dies on the first.
        // Read back, because an option that silently fails to apply is what caused the audio
        // drift this block exists to prevent - and the only way to know is to ask.
        Log.i("fs42", "mpv idle=${MPVLib.getPropertyString("idle")} " +
            "keep-open=${MPVLib.getPropertyString("keep-open")} " +
            "video-sync=${MPVLib.getPropertyString("video-sync")} " +
            "display-fps-override=${MPVLib.getPropertyString("display-fps-override")} " +
            "audio-delay=${MPVLib.getPropertyString("audio-delay")}")
        PlaybackDiagnostics.recordSync(
            MPVLib.getPropertyString("video-sync"),
            MPVLib.getPropertyString("display-fps-override"))
    }

    /**
     * mpv's own log, at warning and above.
     *
     * Registered alongside the event observer and on the same process-global list, so it is
     * removed in [detachObserver] for the same reason.
     */
    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            MpvLog.record(prefix, level, text)
        }
    }

    override fun observeProperties() {
        // Registered against a PROCESS-GLOBAL list - `MPVLib` is an object, and its observer list
        // is static. The base class's `destroy()` does not remove it, so every engine rebuild used
        // to leave one behind, and each holds this view, its context, and therefore the whole
        // activity with the parsed nine-thousand-clip dial hanging off it. See `detach`.
        MPVLib.addObserver(observer)
        // mpv explains every failure it has, immediately before acting on it. Without this the
        // explanation goes only to logcat, which needs an authorised adb connection to a
        // television that does not have one - so a shutdown could only ever be reported as the
        // fact that it happened.
        MPVLib.addLogObserver(logObserver)
        // Only what the dial acts on. The reference implementation observes nineteen properties
        // because it draws a full player UI; each one is a JNI callback on every change, and this
        // app draws its banner from its own clock arithmetic instead.
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    /**
     * Unregister from mpv's global observer list.
     *
     * Must be called before `destroy()`. `BaseMPVView.destroy()` clears properties and tears the
     * core down but never touches the observer list, which is static on `MPVLib` and therefore
     * outlives every instance. Without this each engine rebuild leaks an entire activity graph on
     * a television with 2.34GB of memory, and every future mpv event is dispatched to every dead
     * observer as well as the live one.
     */
    fun detachObserver() {
        runCatching { MPVLib.removeObserver(observer) }
            .onFailure { Log.w("fs42", "could not remove the mpv observer: $it") }
        runCatching { MPVLib.removeLogObserver(logObserver) }
            .onFailure { Log.w("fs42", "could not remove the mpv log observer: $it") }
    }

    /**
     * Start [url] at [startSeconds], the way a channel is joined mid-programme.
     *
     * `start=` is part of the load rather than a seek afterwards: a seek issued before playback
     * has begun is silently dropped, and every channel then opens at 00:00. The Media3 path
     * passes its start position into setMediaSource for the same reason.
     */
    /**
     * Attach a subtitle track to whatever is playing, and show it.
     *
     * Called after the file is loaded rather than passed with it. `sub-add` is mpv's documented
     * way to add a track at runtime, it takes `select` so the viewer does not have to find a
     * track menu this dial has no button for, and unlike a per-file option it can be checked
     * afterwards by reading `sid`.
     */
    /**
     * Ask MediaCodec to present frames straight to this SurfaceView.
     *
     * This is the difference between us and the YouTube app at 4K, and it is why 4K stuttered
     * here on a panel that plays 4K perfectly well elsewhere. Under `vo=gpu` a hardware-decoded
     * frame goes MediaCodec -> AImageReader -> GL texture -> composite -> present, so every frame
     * is copied through the GPU: at 2160p that is 8.3 million pixels a frame on a 32-bit Mali.
     * `mediacodec_embed` is the zero-copy path - the decoder renders onto the surface and nothing
     * touches the pixels in between.
     *
     * It could not be used before because mpv cannot draw subtitles under it. That cost is gone:
     * the app draws its own cues in the Compose overlay now, which was forced by this same vo
     * question from the other side.
     *
     * `setVo` rather than `setOptionString`, because only this updates `voInUse` as well - the
     * field the base class writes back over the property when the surface is created.
     */
    fun useDirectVideoOutput() {
        runCatching { setVo("mediacodec_embed") }
            .onFailure { Log.w("fs42", "could not switch to mediacodec_embed: $it") }
        Log.i("fs42", "vo requested=mediacodec_embed current=${MPVLib.getPropertyString("current-vo")}")
    }

    fun addSubtitle(url: String) {
        runCatching { MPVLib.command("sub-add", url, "select") }
            .onFailure { Log.w("fs42", "sub-add failed: $it") }
    }

    /** Returns mpv's playlist entry id for the new load, or null when it cannot be learned. */
    fun playAt(
        url: String,
        startSeconds: Double,
        audioFile: String? = null,
        subFile: String? = null,
    ): Long? {
        awaitingLoad.set(loads.incrementAndGet())
        // Per-FILE options, so they apply to this load and are gone by the next one. `audio-file`
        // set as a property would persist, and the following clip - which has its own audio, or
        // none - would inherit the last one's track.
        //
        // mpv parses this string as a comma-separated key=value list, so a url with a comma in it
        // would be cut in half. YouTube's audio is the proxy's own `http://127.0.0.1:<port>/<id>`
        // and has none; a Pluto audio rendition is a direct url, so it goes through
        // MpvSource.perFileValue, which length-escapes it only if it needs to.
        val options = buildString {
            append("start=").append(startSeconds.toInt())
            if (audioFile != null) append(",audio-file=").append(MpvSource.perFileValue(audioFile))
            // The subtitle is NOT set here. It is added with `sub-add` once the file is
            // loaded - see addSubtitle - because a per-file option is applied while mpv is still
            // opening the file, gives no indication of whether it worked, and cannot be checked
            // afterwards. `sub-add ... select` is the documented runtime way, and it fails
            // loudly rather than silently.
        }
        // Newer mpv takes an insertion INDEX before the per-file options; without it the options
        // string is parsed as that index and the command is rejected outright.
        //
        // commandNode rather than command, for its result: mpv answers loadfile with the new
        // entry's `playlist_entry_id`, which is what lets an end-file event be matched to the
        // load it belongs to exactly rather than by counting - and it comes back with the call
        // that was being made anyway, no second round trip into the core.
        val result = MPVLib.commandNode("loadfile", url, "replace", "0", options)
        return runCatching { result?.get("playlist_entry_id")?.asInt() }.getOrNull()
            ?: entryIdFromPlaylist()
    }

    /**
     * The fallback when loadfile's result carried no id. `loadfile ... replace` leaves the new
     * file as the only entry and the command is synchronous, so entry 0 is it. A blocking
     * property read on the UI thread - it should be timed on the TCL if the log shows this path
     * being taken at all.
     */
    private fun entryIdFromPlaylist(): Long? =
        runCatching { MPVLib.getPropertyString("playlist/0/id")?.toLongOrNull() }.getOrNull()

    /**
     * The playlist entry mpv is playing now, or null when there is none (`playlist-playing-pos`
     * is -1 once the entry has been removed by a replace) or it cannot be read. The fallback
     * for a PLAYBACK_RESTART whose START_FILE carried no id; racier than that, because the core
     * may already be on the load that replaced it - a race that can only let one stale frame
     * through, never hold a real one back.
     */
    fun playingEntryId(): Long? = runCatching {
        val pos = MPVLib.getPropertyString("playlist-playing-pos")?.toIntOrNull()
        if (pos == null || pos < 0) null
        else MPVLib.getPropertyString("playlist/$pos/id")?.toLongOrNull()
    }.getOrNull()

}
