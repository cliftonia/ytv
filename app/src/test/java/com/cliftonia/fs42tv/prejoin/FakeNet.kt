package com.cliftonia.fs42tv.prejoin

/** A network of canned answers for the pre-join's tests, recording every request made of it. */
class FakeNet {
    class Answer(val code: Int, val body: ByteArray, val finalUrl: String? = null)

    val answers = HashMap<String, Answer>()
    val asked = mutableListOf<Pair<String, String?>>()

    fun text(url: String, body: String, code: Int = 200) {
        answers[url] = Answer(code, body.toByteArray())
    }

    fun bytes(url: String, size: Int, fill: Int = url.hashCode()) {
        answers[url] = Answer(200, ByteArray(size) { (fill + it).toByte() })
    }

    val open: Open = { url, range ->
        asked += url to range
        val answer = answers[url] ?: throw java.net.SocketTimeoutException("no answer")
        var body = answer.body
        val headers = mutableMapOf("Content-Length" to body.size.toString(), "Content-Type" to "video/mp2t")
        var code = answer.code
        if (range != null && code == 200) {
            val from = range.removePrefix("bytes=").substringBefore('-').toInt()
            body = body.copyOfRange(from, body.size)
            headers["Content-Length"] = body.size.toString()
            headers["Content-Range"] = "bytes $from-${answer.body.size - 1}/${answer.body.size}"
            code = 206
        }
        Upstream(code, answer.finalUrl ?: url, headers, java.io.ByteArrayInputStream(body)) {}
    }

    fun count(url: String) = asked.count { it.first == url }

    companion object {
        /** A live media playlist of [count] segments from [first], each [seconds] long. */
        fun live(first: Long, count: Int, seconds: Int = 6, key: Boolean = false): String = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$seconds\n#EXT-X-MEDIA-SEQUENCE:$first\n")
            if (key) append("#EXT-X-KEY:METHOD=AES-128,URI=\"keys/k1?t=1\",IV=0x01\n")
            for (i in 0 until count) append("#EXTINF:$seconds.000,\nseg${first + i}.ts?tok=T\n")
        }
    }
}
