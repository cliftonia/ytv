package com.cliftonia.fs42tv.resolver

import org.junit.Ignore
import org.junit.Test

/**
 * Resolve specific clips off the real dial, to find out why a channel shows a banner and no
 * picture.
 *
 * The banner comes from the lineup and the picture comes from a resolve, so a channel that names
 * a programme and then shows nothing has failed BETWEEN those two steps - which is exactly this
 * class's territory. Running it here answers the question without needing adb on the television.
 *
 * `@Ignore` because it hits the live network. Edit [IDS] and drop the annotation to use it.
 */
// probe run
class FailingChannelProbe {

    private val ids = listOf(
        "Huam5sSRjwc" to "Literature #0",
        "YG4wLL40_ws" to "Literature #7",
        "BbD0o4yKyBw" to "Literature #14",
        "OwZT8_lARa4" to "Literature #21",
        "2rpILyUYNfY" to "Literature #28",
        "HljqHNf85nE" to "Literature #35",
        "Nr-Su0Zb6Qw" to "Literature #42",
        "Re2-_u3WDsc" to "Literature #49",
        "jg7BdlqYqAU" to "Literature #56",
        "IcxZa7HOW1o" to "Literature #63",
        "DOSBfR4X3kU" to "Literature #70",
        "kO3lA0P0Yr4" to "Literature #77",
        "32HRUge-7y4" to "Literature #84",
        "ZJkSFWH2SAs" to "Literature #91",
        "rZC56gGyh1g" to "Literature #98",
        "V1vKd_5DXWM" to "Runway #0",
        "Rno9WSGUYFs" to "Runway #7",
        "g__Q2DY9-Js" to "Runway #14",
        "SMErau7jk4s" to "Runway #21",
        "OFnnlZPPoSk" to "Runway #28",
        "eVR7IAj7bik" to "Runway #35",
        "7rpEGr15i_U" to "Runway #42",
        "0LVnwP4Vy2s" to "Runway #49",
        "afbCCkgVGHY" to "Runway #56",
        "W_we-B8Ktrk" to "Runway #63",
        "926YOh5PupM" to "Runway #70",
        "VFPVn66GB0U" to "Runway #77",
        "vK3Jq8AJO5s" to "Runway #84",
        "CGxRaX3mIuc" to "Runway #91",
        "xaltMM0IjL8" to "Runway #98",

    )

    @Test
    fun `resolve each clip and report what came back`() {
        val resolver = DeviceResolver()
        val now = System.currentTimeMillis() / 1000
        var ok = 0
        for ((id, label) in ids) {
            val resolved = try {
                resolver.resolveDetailed(id, now, listOf("uhd", "hd", "sd"))
            } catch (e: Exception) {
                println("%-34s THREW %s".format(label, e))
                null
            }
            if (resolved == null) {
                println("%-34s NULL - nothing playable".format(label))
            } else {
                ok++
                println("%-34s OK  video=%s audio=%s".format(
                    label,
                    resolved.playable.videoUrl.take(48),
                    if (resolved.playable.audioUrl != null) "yes" else "NO AUDIO"))
            }
        }
        println("\n$ok of ${ids.size} resolved")
    }
}
