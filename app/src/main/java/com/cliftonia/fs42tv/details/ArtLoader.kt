package com.cliftonia.fs42tv.details

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The Android half of [ArtCache]: the download, the decode, and the thread.
 *
 * Its own thread, not the prefetch or tune threads: a poster is 50-150 KB and must never queue a
 * tune or a Pluto guide read behind it. Decoded at no more than [MAX_HEIGHT_PX] tall - the pane is
 * half of a 1080-line canvas - so a 1000-pixel poster costs a quarter of the memory it would.
 */
object ArtLoader {

    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_HEIGHT_PX = 600
    private const val MAX_WIDTH_PX = 1000
    private const val TIMEOUT_MILLIS = 6_000

    fun create(cacheDir: File?): ArtCache<ImageBitmap> = ArtCache(
        dir = cacheDir?.let { File(it, "art") },
        fetch = ::httpBytes,
        decode = ::decodeSampled,
        sizeOf = { it.width * it.height * 4 },
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fs42-art").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        },
        elapsedMillis = android.os.SystemClock::elapsedRealtime,
    )

    private fun httpBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("art http ${connection.responseCode}")
            }
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) throw java.io.IOException("art over ${MAX_BYTES / 1024} KB")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeSampled(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= MAX_HEIGHT_PX || bounds.outWidth / (sample * 2) >= MAX_WIDTH_PX) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}
