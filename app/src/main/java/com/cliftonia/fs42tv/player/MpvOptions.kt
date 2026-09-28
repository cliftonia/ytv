package com.cliftonia.fs42tv.player

import android.util.Log
import `is`.xyz.mpv.MPVLib

/**
 * The options [MpvView] hands mpv before its core starts, and the two file-level settings they
 * read.
 *
 * Out of the view because the view is its event thread's rules - the per-load CAS token, the
 * entry ids, the observers on mpv's process-global list - and these are a different idea: a
 * configuration, each line carrying the measurement or the crash that chose it. Nothing here
 * runs after init: `BaseMPVView.initialize()` calls `initOptions()` itself, and this is all that
 * override does.
 */

/**
 * Which pacing mode to ask mpv for; see initOptions.
 *
 * A file-level variable rather than a constructor argument because `BaseMPVView.initialize()`
 * calls `initOptions()` itself, so there is no parameter to thread through. Set before the engine
 * is built and read once during init.
 */
var videoSyncMode: String? = null

/**
 * How far to hold the picture back, in milliseconds, to meet audio that arrives late downstream.
 *
 * A file-level variable for the same reason as [videoSyncMode]: `BaseMPVView.initialize()` calls
 * `initOptions()` itself, so there is no constructor parameter to thread through. Unlike the
 * pacing mode this one is ALSO settable while a clip is playing - see
 * [MpvChannelPlayer.setAudioHoldMillis] - because the right value cannot be reasoned to, only
 * heard, and trimming it by ear needs the sound to keep running while you turn the knob.
 *
 * See [AudioSync] for the measurement that put it here.
 */
var audioHoldMillis: Int = 0

/**
 * Every pre-init option, in the order mpv is given them - [MpvView.initOptions]'s body.
 *
 * An extension rather than a member only to keep [MpvView] one idea; it reads `context` and the
 * view itself exactly as the override did.
 */
internal fun MpvView.applyDialOptions() {
    // --- the reason mpv is here ---------------------------------------------------------
    // Lock video to the display's real refresh and resample audio to follow, rather than
    // scheduling frames against a media clock and letting the compositor place them.
    // Chosen at runtime rather than fixed, because which is right here is genuinely unsettled
    // and the device is the only thing that can answer.
    //
    // `display-resample` locks video to the panel's real refresh and RESAMPLES THE AUDIO to
    // follow. It is why mpv is in this app at all: it is the only thing that fixed the judder.
    //
    // But `vo=mediacodec_embed` means MediaCodec presents the frames and mpv never touches
    // the pixels - that vo was forced on us because gpu and gpu-next both SIGSEGV in this
    // television's Mali driver. Resampling audio to follow a clock mpv does not fully own is
    // a plausible cause of the audio sliding against the picture, which is what is reported.
    //
    // `audio` is mpv's default: video is timed against the audio clock and CANNOT drift from
    // it, at the cost of the frame pacing that display-resample buys.
    //
    // So both are offered and the setting says which. Judder and drift are different faults
    // with different cures, and guessing between them has now cost several rounds.
    MPVLib.setOptionString("video-sync", FrameCadence.optionFor(videoSyncMode))
    // A separate feature that blends frames. display-resample does not need it and on a
    // 32-bit SoC it is expensive - off unless it is ever measured to help.
    MPVLib.setOptionString("interpolation", "no")

    // Deliberate A/V offset, for a delay that happens BELOW the player and that mpv therefore
    // cannot measure. On this television the sound leaves over Bluetooth SBC to a paired
    // speaker which reports its own buffering as zero, so mpv's `avsync` reads +-6ms while
    // the viewer hears the audio well behind the picture. Set as an option as well as a
    // runtime property so it survives an engine rebuild after a shutdown, which happens
    // often enough that a trim that quietly reset itself would look like the fault returning.
    MPVLib.setOptionString(
        "audio-delay", AudioSync.mpvAudioDelaySeconds(audioHoldMillis).toString())

    // display-resample is only as good as mpv's idea of the refresh rate, and mpv's own
    // detection is unreliable on Android - the reference implementation overrides it for
    // exactly this reason. This panel reports 60.000004Hz, not 60; that difference is the
    // difference between resampling to the right rate and slowly drifting against it.
    val refreshHz = DisplayRefresh.of(context, this)
    if (refreshHz != null) {
        MPVLib.setOptionString("display-fps-override", refreshHz.toString())
        Log.i("fs42", "mpv display-fps-override=$refreshHz")
    } else {
        // No trustworthy refresh rate, so do NOT resample audio against a guess. Syncing
        // video to the audio clock is mpv's default and cannot drift; it gives up the frame
        // pacing that put mpv here in the first place, which is the right way round - judder
        // is irritating, audio out of step with a talking head is unwatchable.
        Log.w("fs42", "no display refresh rate; falling back to video-sync=audio")
        MPVLib.setOptionString("video-sync", "audio")
    }

    // --- video output, from mpv-android's reference implementation -----------------------
    // No GL at all. mediacodec_embed hands decoded frames straight to the SurfaceView and
    // mpv never opens a GL context, which is the point: both `gpu` and `gpu-next` crashed
    // this television outright - SIGSEGV in /vendor/lib/egl/libGLES_mali.mt5879.so, on the
    // app's OWN RenderThread rather than any mpv thread. mpv's EGL use and the Compose
    // overlay drawing above the video could not share this Mali driver.
    //
    // The judder fix survives the change: video-sync=display-resample governs WHEN a frame
    // is released, not who draws it, so mpv still paces to the display's real refresh.
    // What is given up is everything mpv would do to the pixels - scaling, interpolation,
    // colour management - none of which this dial asks for.
    //
    // MEASURED, AND NOT WHAT HAPPENS: `current-vo` reads `gpu` on this television, not
    // `mediacodec_embed`. The reason is in the library, not here - `BaseMPVView` keeps its
    // own `voInUse` field, initialised to "gpu", and `surfaceCreated` writes THAT to the `vo`
    // property once the surface arrives, after `initOptions` has run. The option below is set
    // and then overwritten. `BaseMPVView.setVo(...)` is the only thing that changes both.
    //
    // Left as it is on purpose. Switching to mediacodec_embed for real is a change to the
    // vo that this file records as having SIGSEGV'd in the Mali driver, it belongs to the
    // mid-session shutdown fault rather than to audio sync, and the audio measurement above
    // rules the vo out either way: mpv is in sync under `gpu` too.
    // NOT set here - see useDirectVideoOutput. `BaseMPVView` keeps its own `voInUse` field
    // initialised to "gpu" and writes THAT to the vo property from `surfaceCreated`, after
    // `initOptions` has run, so an option set here is overwritten before a frame is drawn.
    // Every theory that reasoned from this line rather than from `current-vo` was reasoning
    // about a video output that was not running.
    MPVLib.setOptionString("hwdec", "mediacodec")

    // --- audio --------------------------------------------------------------------------
    // opensles FIRST, audiotrack only as a fallback.
    //
    // mpv-android lists audiotrack first, and on this device it aborts the whole process when
    // one clip ends and the next loads:
    //   FORTIFY: pthread_mutex_lock called on a destroyed mutex
    //   Fatal signal 6 (SIGABRT) in tid ... (ao/audiotrack)
    // - the audio output racing its own teardown. A dial rolls a clip over on every channel
    // every few minutes, so that is not an edge case here, it is the normal path.
    MPVLib.setOptionString("ao", "opensles,audiotrack")
    // Hold the audio device open between clips, streaming silence when nothing is playing.
    //
    // Without it mpv closes the output on every loadfile and opens it again for the next
    // one, and the hardware makes that audible: a speaker click on every single channel
    // change. This dial changes channel constantly, so an artefact that a normal player
    // produces once per file happens here every few seconds.
    //
    // The option exists for precisely this - it was added for AV receivers that click or
    // mute while re-syncing - and the cost is a device kept open, which this app wants
    // anyway since it is never idle for long.
    // DELIBERATELY NOT audio-stream-silence.
    //
    // It was set to stop a speaker click on every channel change, which this dial makes
    // constantly - and it worked. But mpv warns about it on every single load:
    //
    //     [ao/opensles] The --audio-stream-silence option is set.
    //                   This will break certain player behavior.
    //
    // That warning was being logged and never read. The option holds the audio device open
    // streaming silence between clips, so the audio clock never stops - and BOTH pacing modes
    // derive video timing from that clock, `audio` directly and `display-resample` through
    // the resampler. Audio sliding against the picture is exactly what a clock that keeps
    // running when nothing is playing would produce.
    //
    // The option exists for AV receivers that mute while re-syncing. A click at a channel
    // change is a second of mild annoyance; a programme whose voices do not match the mouths
    // is unwatchable, so the trade goes the other way.
    // A short grace period before the device is considered ready, so the first moments of a
    // clip are not swallowed while the output is still coming up.
    MPVLib.setOptionString("audio-wait-open", "0.2")

    // --- https --------------------------------------------------------------------------
    // Every URL on this dial is https, and Android has no /etc/ssl/certs for mpv to find.
    // Without a bundle it can only connect by not verifying, which is not a trade worth
    // making on a device fetching signed URLs over someone else's network.
    MPVLib.setOptionString("tls-verify", "yes")
    MPVLib.setOptionString("tls-ca-file", CaBundle.extract(context))

    // --- startup, lifted from the box's [googlevideo] profile ---------------------------
    // These are plain mp4 whose shape is already known, so deep probing is a second of black
    // screen bought for nothing.
    // Land on the keyframe rather than decoding forward to the exact frame.
    //
    // Every tune joins a clip at a clock-derived offset, routinely tens of minutes in. With
    // mpv's default precise seeking it reaches that frame by decoding and discarding every
    // frame from the preceding keyframe - measured at 3.77s between opening the decoder and
    // showing a picture, against 0.38s for the proxy to deliver the bytes.
    //
    // Being a second or two off the exact wall-clock position cannot be perceived here: the
    // illusion is that the channel was already running when you arrived.
    MPVLib.setOptionString("hr-seek", "no")

    MPVLib.setOptionString("demuxer-lavf-analyzeduration", "0.1")
    MPVLib.setOptionString("demuxer-lavf-probesize", "524288")
    MPVLib.setOptionString("cache-pause-initial", "no")
    MPVLib.setOptionString("cache-secs", "3")
    MPVLib.setOptionString("demuxer-readahead-secs", "0")
    MPVLib.setOptionString("demuxer-max-bytes", "33554432")
    MPVLib.setOptionString("demuxer-max-back-bytes", "8388608")
    // reconnect: a dropped CDN connection recovers silently rather than ending the clip.
    // multiple_requests: keeps ffmpeg issuing further RANGE requests on one connection.
    // googlevideo throttles an unbounded request to roughly the video's own bitrate and
    // serves bounded ones at line speed - the discovery ChunkedDataSource exists for, and
    // the reason mpv's first frame is slower than Media3's until this is right.
    MPVLib.setOptionString(
        "stream-lavf-o",
        "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2,multiple_requests=1",
    )

    // --- nothing on this dial is interactive --------------------------------------------
    MPVLib.setOptionString("sub-auto", "no")
    MPVLib.setOptionString("osc", "no")
    MPVLib.setOptionString("input-default-bindings", "no")
    MPVLib.setOptionString("input-vo-keyboard", "no")
    // Stay alive with nothing loaded: the dial tunes into this instance repeatedly, and an
    // mpv that shuts down when its file ends would take the app with it.
    MPVLib.setOptionString("idle", "yes")
    // Send warnings and errors to the log observer. Without a msg-level mpv reports almost
    // nothing through the callback, and the reason for a shutdown is exactly what is wanted.
    MPVLib.setOptionString("msg-level", "all=warn")
}
