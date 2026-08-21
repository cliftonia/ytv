package com.cliftonia.fs42tv.player

/**
 * Holding the picture back to meet audio that arrives late somewhere the player cannot see.
 *
 * This exists because of a measurement. "The audio is delayed" was reported across five builds
 * and five fixes, and mpv was never once asked what IT thought - it publishes `avsync`, its own
 * measured offset between the audio it has played and the video it has shown. Read on this
 * television, four samples across one 24fps clip:
 *
 *     AVSYNC t=3s  avsync= 0.005384  audio-delay=0.000000 vo=gpu ao=opensles
 *     AVSYNC t=10s avsync= 0.005515
 *     AVSYNC t=25s avsync=-0.006034
 *     AVSYNC t=50s avsync=-0.006443
 *
 * Six milliseconds, not growing. mpv is in sync and stays in sync, so the offset is neither drift
 * (a resampler on the wrong clock) nor a seek landing the two tracks apart. It is downstream.
 *
 * And the device says where. There is exactly one audio output thread on this television:
 *
 *     Output devices: 0x80 (AUDIO_DEVICE_OUT_BLUETOOTH_A2DP)
 *     name: iLoud Micro-Monitor(AUDIO_DEVICE_OUT_BLUETOOTH_A2DP)
 *     Current Codec: SBC          (getSupportsOptionalCodecs: 0 - no aptX, no AAC, no LDAC)
 *     Delay Reporting: 0 (in 1/10 milliseconds)
 *     HAL does not support Bluetooth latency modes / Supported latency modes: { }
 *
 * Everything the television plays goes out over Bluetooth SBC to a speaker, and that speaker
 * reports its own buffering as ZERO through AVDTP delay reporting. A2DP SBC costs 150-250ms in
 * practice; the framework's mixer thread accounts for 89.83ms of local buffering and has no way
 * to learn the rest, so neither has mpv, and neither would Media3 or any other player. That is
 * why five player-side fixes each changed nothing.
 *
 * A delay nothing can measure can still be cancelled, by hand, if there is a control for it -
 * which is what this is. mpv's `audio-delay` shifts the two apart deliberately; a NEGATIVE value
 * holds the video back, which is what late audio needs.
 */
object AudioSync {

    /**
     * How far the picture can be held back, in milliseconds, and in the order the row cycles.
     *
     * 40ms steps because that is roughly the finest step worth having: ITU-R BT.1359 puts the
     * detectability threshold at 45ms for audio running ahead and 125ms for audio running behind,
     * so a finer step would be adjusting something nobody can hear.
     *
     * Zero first, and zero is the default: the correct value depends entirely on what the
     * television is plugged into or paired with, and shipping a guess would make every set-up
     * without a Bluetooth speaker worse.
     *
     * Positive values run up to 400ms before the negatives, because the measured fault is audio
     * arriving LATE and every useful value for it is positive. The negatives are there for the
     * opposite set-up - a soundbar over eARC whose own video processing puts the picture behind
     * the sound - and are reached last because that is the rarer complaint.
     */
    val HOLD_MILLIS = listOf(0, 40, 80, 120, 160, 200, 240, 280, 320, 360, 400, -40, -80, -120)

    /** The row's value, e.g. "OFF" or "+200MS". Signed, because the sign is the whole point. */
    fun label(holdMillis: Int): String = when {
        holdMillis == 0 -> "OFF"
        holdMillis > 0 -> "+${holdMillis}MS"
        else -> "${holdMillis}MS"
    }

    /** The next value the row shows, wrapping. An unknown value restarts the ladder at OFF. */
    fun next(holdMillis: Int): Int {
        val at = HOLD_MILLIS.indexOf(holdMillis)
        return if (at < 0) HOLD_MILLIS.first() else HOLD_MILLIS[(at + 1).mod(HOLD_MILLIS.size)]
    }

    /**
     * The value for mpv's `audio-delay`, in seconds.
     *
     * NEGATED on purpose. mpv documents `audio-delay` as "positive values delay the audio, and
     * negative values delay the video", so holding the picture back by 200ms to meet audio that
     * is 200ms late is `audio-delay=-0.2`. Getting this sign backwards doubles the fault instead
     * of cancelling it, and the two are indistinguishable from the sofa without a stopwatch,
     * which is why the conversion is one named function with a test rather than a minus sign
     * somewhere in the player.
     */
    fun mpvAudioDelaySeconds(holdMillis: Int): Double = -holdMillis / 1000.0

    /**
     * What the audio is actually coming out of, for the diagnostics screen.
     *
     * Shown because this fault was invisible for five builds purely for want of this line. From
     * the sofa a television playing through a paired Bluetooth speaker looks exactly like a
     * television playing through its own panel, and only one of those two can be in lip-sync.
     *
     * [outputs] are the (`AudioDeviceInfo.TYPE_*`, product name) pairs the system reports as
     * AVAILABLE, which is not the same as routed - Android reports the built-in speaker and HDMI
     * alongside a paired Bluetooth sink whether or not anything is playing through them. The
     * ordering below is Android's own routing preference for media, so the first match is the one
     * that will actually carry the sound. The caller does the Android query, so this stays a pure
     * function with a test.
     */
    fun describeRoute(outputs: List<Pair<Int, String>>): String {
        if (outputs.isEmpty()) return "UNKNOWN"
        val chosen = outputs.minByOrNull { rank(it.first) } ?: return "UNKNOWN"
        val route = ROUTE_NAMES[chosen.first] ?: "OTHER"
        // The product name matters as much as the type: "BLUETOOTH" invites an argument about
        // whether it is really routed there, and "BLUETOOTH (iLoud Micro-Monitor)" ends it.
        return if (chosen.second.isBlank()) route else "$route (${chosen.second})"
    }

    /**
     * Whether the routed output is one the player cannot compensate for on its own.
     *
     * True for Bluetooth: A2DP's only latency channel is AVDTP delay reporting, the sink on this
     * television reports 0, and Android confirms it cannot fill the gap - `HAL does not support
     * Bluetooth latency modes`, `Supported latency modes: { }`. Nothing downstream of the mixer
     * is knowable, so no player of any kind can correct it and only the AV DELAY row can.
     */
    fun needsManualTrim(outputs: List<Pair<Int, String>>): Boolean =
        outputs.any { it.first in WIRELESS }

    /** Anything unlisted sorts last, so an unrecognised output never outranks a known one. */
    private fun rank(type: Int): Int =
        ROUTE_PRIORITY.indexOf(type).takeIf { it >= 0 } ?: ROUTE_PRIORITY.size

    /** Android's routing preference for media, worst latency first where it coincides. */
    private val ROUTE_PRIORITY = listOf(
        TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_HEARING_AID,
        TYPE_WIRED_HEADPHONES, TYPE_WIRED_HEADSET,
        TYPE_HDMI_EARC, TYPE_HDMI_ARC, TYPE_HDMI,
        TYPE_BUILTIN_SPEAKER,
    )

    private val ROUTE_NAMES = mapOf(
        TYPE_BLUETOOTH_A2DP to "BLUETOOTH",
        TYPE_BLE_HEADSET to "BLUETOOTH LE",
        TYPE_BLE_SPEAKER to "BLUETOOTH LE",
        TYPE_HEARING_AID to "HEARING AID",
        TYPE_WIRED_HEADPHONES to "WIRED",
        TYPE_WIRED_HEADSET to "WIRED",
        TYPE_HDMI_EARC to "HDMI EARC",
        TYPE_HDMI_ARC to "HDMI ARC",
        TYPE_HDMI to "HDMI",
        TYPE_BUILTIN_SPEAKER to "TV SPEAKER",
    )

    private val WIRELESS =
        setOf(TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_HEARING_AID)

    // AudioDeviceInfo constants, copied rather than imported so this file stays on the JVM and
    // therefore testable. They are frozen platform API and cannot change under us.
    const val TYPE_BUILTIN_SPEAKER = 2
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_WIRED_HEADPHONES = 4
    const val TYPE_HDMI = 9
    const val TYPE_HDMI_ARC = 10
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_HEARING_AID = 23
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_BLE_SPEAKER = 27
    const val TYPE_HDMI_EARC = 29
}
