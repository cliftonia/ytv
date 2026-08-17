package com.cliftonia.fs42tv.resolver

import org.junit.Ignore
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * What renditions a clip actually offers, and which one the tier ladder hands to the player.
 *
 * A clip that resolves and then shows nothing is a decode problem, not a resolve problem, and the
 * codec is the thing that decides it. The television in the lounge is `armeabi-v7a` - a 32-bit
 * userspace behind a 4K panel - and AV1 at 2160p is exactly the combination it will accept a URL
 * for and then fail to draw.
 */
// probe run
class FormatProbe {

    private val ids = listOf(
        "SHSR0PJj0Xs" to "Runway: Chanel Couture",
        "7Ux4GeY5MXQ" to "Runway: Chanel FW26 (4K in title)",
        "eI4zWeqTmIk" to "Runway: Max Mara (4K in title)",
        "Huam5sSRjwc" to "Literature: The Door in the Wall",
        "TsD-8FGA84A" to "Literature: Great Books #1",
        "aqz-KE-bpKQ" to "CONTROL: Big Buck Bunny",
    )

    @Test
    fun `list the renditions on offer`() {
        NewPipe.init(NewPipeDownloader(), Localization("en", "AU"), ContentCountry("AU"))
        for ((id, label) in ids) {
            println("\n== $label")
            val info = try {
                StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
            } catch (e: Exception) {
                println("   could not extract: $e"); continue
            }
            val progressive = info.videoOnlyStreams.orEmpty()
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            for (stream in progressive.sortedByDescending {
                it.resolution?.substringBefore('p')?.toIntOrNull() ?: 0
            }) {
                println("   %-10s %-10s %s".format(
                    stream.resolution, stream.format?.suffix ?: "?", stream.codec ?: "?"))
            }
            if (progressive.isEmpty()) println("   NO progressive video-only streams at all")
        }
    }
}
