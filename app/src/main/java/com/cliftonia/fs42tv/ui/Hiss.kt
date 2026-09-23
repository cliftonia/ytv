package com.cliftonia.fs42tv.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import kotlin.random.Random

/**
 * The soft white-noise hiss under the channel-change static: a tiny looped PCM buffer on its own
 * AudioTrack, built when a tune starts and released when it fades.
 *
 * Its own AudioTrack, never the engine's audio: mpv's `ao` ordering is load-bearing (HANDOVER -
 * `audiotrack` first SIGABRTs at roll-over) and Media3's volume is the programme's mute. This is
 * a separate stream the system mixes in, so neither engine is touched. MODE_STATIC with a loop
 * point, so after the one write nothing runs per buffer - no thread, no callback.
 *
 * Released outright after every fade rather than paused, for the reason the guide music is
 * (GuidePicker.stopMusic): an idle audio track still holds a mixer slot on a device that has
 * few of anything. Everything is main-thread only - [handler] is on the main looper - and every
 * audio call is wrapped: a hiss is atmosphere, and it is never worth an exception.
 */
class Hiss(private val handler: Handler) {

    private var track: AudioTrack? = null

    /** Bumped on every start and release, so a stale fade step cannot touch a newer track. */
    private var session = 0

    fun start() {
        release()
        val pcm = noise
        val built = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .apply {
                    write(pcm, 0, pcm.size)
                    setLoopPoints(0, pcm.size, -1)
                    setVolume(1f)
                    play()
                }
        }.onFailure { Log.w("fs42", "hiss unavailable: $it") }.getOrNull() ?: return
        track = built
        session += 1
    }

    /** Die away over [fadeMillis], then release. A no-op when nothing is playing. */
    fun fadeOut(fadeMillis: Long = FADE_MILLIS) {
        val playing = track ?: return
        val mine = session
        val started = SystemClock.elapsedRealtime()
        val step = object : Runnable {
            override fun run() {
                if (session != mine) return
                val gain = HissEnvelope.fadeGain(SystemClock.elapsedRealtime() - started, fadeMillis)
                runCatching { playing.setVolume(gain) }
                if (gain <= 0f) release() else handler.postDelayed(this, FADE_STEP_MILLIS)
            }
        }
        handler.post(step)
    }

    fun release() {
        session += 1
        val old = track ?: return
        track = null
        runCatching {
            old.stop()
            old.release()
        }.onFailure { Log.w("fs42", "hiss release: $it") }
    }

    private companion object {
        /** 22kHz mono is plenty for noise and halves the buffer against 44.1k. */
        const val SAMPLE_RATE = 22_050
        const val FADE_MILLIS = 300L
        const val FADE_STEP_MILLIS = 25L

        /**
         * Half a second, looped; generated once per process. Quiet by construction - peaks
         * at 6% of full scale, about -29dBFS RMS - so even a television turned up for a quiet
         * film does not blast static at the room.
         */
        val noise: ShortArray by lazy { WhiteNoise.pcm(SAMPLE_RATE / 2, 0.06f, Random.Default) }
    }
}
