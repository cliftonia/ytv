package com.cliftonia.fs42tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.URL

/**
 * Fetches the publisher's manifest, downloads a newer build, and hands it to Android to install.
 *
 * Everything here touches the network or the system; the decision about whether an update is
 * worth offering lives in [UpdateCheck], which is testable.
 *
 * Android will not let a normal app install silently, and that is deliberate on its part - the
 * system install dialog is the only thing standing between a background download and arbitrary
 * code. So this gets as far as "downloaded and ready", and the viewer presses OK.
 */
class Updater(private val context: Context, private val repo: String) {

    /** Where a downloaded build waits. One file, overwritten - never a directory that grows. */
    private val apkFile get() = File(context.cacheDir, "ytv-update.apk")

    /**
     * Ask the publisher what is available, and download it if it is newer.
     *
     * Returns true when a build is on disk and ready to install. Blocking: call it off the main
     * thread. Download failures are logged, never surfaced - the publisher being unreachable is
     * the normal state of a television in a car. The manifest fetch, though, THROWS: the caller
     * has a "COULD NOT CHECK" branch for exactly this, and swallowing it here made an explicit
     * CHECK FOR UPDATE on a dead network answer "UP TO DATE", which is a lie.
     *
     * Both reads carry timeouts, because the default is none at all: one stalled connection
     * held [updateCheckRunning] forever, and a box that installs its own updates had quietly
     * stopped doing so with nothing on screen or in the log to say why.
     */
    fun downloadIfNewer(installedVersion: Int): Boolean {
        val release = fetch(UpdateCheck.latestReleaseUrl(repo))
        val published = UpdateCheck.parse(release)
        if (!UpdateCheck.isNewer(installedVersion, published) || published == null) return false

        Log.i("fs42", "update available: ${published.version} (running $installedVersion)")
        // Already downloaded? Do not fetch 66MB again.
        //
        // The check runs on every onResume, and a viewer who dismisses the install prompt is the
        // normal case - so glancing at the home screen and coming back re-downloaded the whole
        // apk, over a car hotspot, competing with the video that is playing. The marker records
        // which version is sitting in the cache.
        val marker = File(context.cacheDir, "ytv-update.version")
        if (apkFile.exists() && runCatching { marker.readText().trim().toInt() }.getOrNull()
            == published.version) {
            Log.i("fs42", "update ${published.version} is already downloaded")
            return true
        }
        return try {
            val target = apkFile
            // Written to a temporary name and moved into place, so a download interrupted by the
            // television being switched off cannot leave a half-apk that the installer would
            // reject and the viewer would have to be told about.
            val partial = File(context.cacheDir, "ytv-update.part")
            val connection = URL(published.apkUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            try {
                connection.inputStream.use { input ->
                    partial.outputStream().use { input.copyTo(it) }
                }
            } finally {
                connection.disconnect()
            }
            if (target.exists()) target.delete()
            partial.renameTo(target)
            marker.writeText(published.version.toString())
            Log.i("fs42", "update downloaded: ${target.length() / 1024 / 1024} MB")
            true
        } catch (e: Exception) {
            Log.w("fs42", "update download failed: $e")
            false
        }
    }

    /**
     * Hand the downloaded build to Android's installer.
     *
     * A content:// URI via FileProvider, not a file:// one: passing a file URI across an app
     * boundary has thrown FileUriExposedException since Android 7, and the installer is very much
     * another app.
     */
    fun install() {
        val apk = apkFile
        if (!apk.exists()) return
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("fs42", "could not start the installer: $e")
        }
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        // The DialRepository values, for the same reason it has them: the default is to wait
        // forever, and forever is what an idle hotspot delivers.
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
    }
}
