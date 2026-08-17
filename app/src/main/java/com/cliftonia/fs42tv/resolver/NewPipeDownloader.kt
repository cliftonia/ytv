package com.cliftonia.fs42tv.resolver

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * The http client NewPipeExtractor asks the host application to provide.
 *
 * Built on HttpURLConnection rather than OkHttp on purpose: this makes perhaps three requests per
 * clip and none of them are latency-critical in a way a connection pool would fix, so pulling in
 * another networking stack would be weight for nothing. The app already talks to googlevideo
 * through [com.cliftonia.fs42tv.player.ChunkedProxy], which is where the throughput problem
 * actually lives.
 */
class NewPipeDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val connection = (URL(request.url()).openConnection() as HttpURLConnection).apply {
            requestMethod = request.httpMethod()
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            // YouTube serves a different, poorer page to clients it does not recognise, and to
            // some it serves a consent wall instead of the video. NewPipe sets its own headers on
            // top of these; these are the floor.
            setRequestProperty("User-Agent", USER_AGENT)
        }
        for ((name, values) in request.headers()) {
            // Replaces rather than appends: NewPipe passes each header once with all its values,
            // and addRequestProperty would leave the User-Agent above duplicated alongside it.
            connection.setRequestProperty(name, values.joinToString(", "))
        }

        val body = request.dataToSend()
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }

        try {
            val code = connection.responseCode
            // 429 means YouTube wants a captcha solved, which cannot happen on a television with
            // no keyboard. NewPipe has a dedicated exception for it so callers can tell this apart
            // from an ordinary failure, and the app's answer either way is to skip the clip.
            if (code == HTTP_TOO_MANY_REQUESTS) {
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            val text = (if (code >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream
                        else connection.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, connection.responseMessage, connection.headerFields, text,
                            connection.url.toString())
        } catch (e: ReCaptchaException) {
            throw e
        } catch (e: Exception) {
            // Everything the extractor can sensibly act on is an IOException; wrapping keeps the
            // contract honest rather than letting a RuntimeException escape into its internals.
            throw IOException("request failed: ${request.url()}", e)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    }
}
