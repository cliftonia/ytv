package com.cliftonia.fs42tv.player

import android.content.Context
import android.util.Log

/**
 * The CA bundle mpv's TLS reads from disk.
 *
 * mpv's TLS is mbedtls reading a PEM file from a path; it cannot use Android's system trust
 * store, and an asset is not a path. Without this every https URL fails to open with
 * `mbedtls_x509_crt_parse_file ... -15872` and the channel goes straight to a re-tune.
 * mpv-android ships the same bundle for the same reason.
 */
object CaBundle {

    /**
     * Extract the bundle into the app's files directory and return its path.
     *
     * Copied every run rather than only when absent: it is 182KB against an app that downloads
     * megabytes of video per minute, and a half-written file from a killed first launch would
     * otherwise poison TLS until someone cleared the app's data.
     */
    fun extract(context: Context): String {
        val out = java.io.File(context.filesDir, "cacert.pem")
        runCatching {
            context.assets.open("cacert.pem").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { Log.e("fs42", "could not extract cacert.pem: $it") }
        return out.path
    }
}
