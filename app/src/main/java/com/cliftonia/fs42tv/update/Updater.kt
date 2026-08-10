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
class Updater(private val context: Context, private val baseUrl: String) {

    /** Where a downloaded build waits. One file, overwritten - never a directory that grows. */
    private val apkFile get() = File(context.cacheDir, "ytv-update.apk")

    /**
     * Ask the publisher what is available, and download it if it is newer.
     *
     * Returns true when a build is on disk and ready to install. Blocking: call it off the main
     * thread. Every failure is silent by design - the publisher being unreachable is the normal
     * state of a television in a car, and is not something to interrupt anyone about.
     */
    fun downloadIfNewer(installedVersion: Int): Boolean {
        val manifest = try {
            URL("${baseUrl.trimEnd('/')}/app.json").readText()
        } catch (e: Exception) {
            return false
        }
        val published = UpdateCheck.parse(manifest)
        if (!UpdateCheck.isNewer(installedVersion, published) || published == null) return false

        Log.i("fs42", "update available: ${published.version} (running $installedVersion)")
        return try {
            val target = apkFile
            // Written to a temporary name and moved into place, so a download interrupted by the
            // television being switched off cannot leave a half-apk that the installer would
            // reject and the viewer would have to be told about.
            val partial = File(context.cacheDir, "ytv-update.part")
            URL(UpdateCheck.downloadUrl(baseUrl, published)).openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            if (target.exists()) target.delete()
            partial.renameTo(target)
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
}
