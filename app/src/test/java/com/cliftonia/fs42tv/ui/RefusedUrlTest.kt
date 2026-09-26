package com.cliftonia.fs42tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which playback errors refuse a url - moved out of the director unchanged. */
class RefusedUrlTest {

    @Test
    fun `both engines' spellings of a refused url match, and nothing else does`() {
        assertTrue(RefusedUrl.matches("ERROR_CODE_IO_BAD_HTTP_STATUS"))
        assertTrue(RefusedUrl.matches("ERROR_CODE_IO_FILE_NOT_FOUND"))
        assertTrue(RefusedUrl.matches("MPV_END_ERROR"))
        assertFalse(RefusedUrl.matches("ERROR_CODE_DECODING_FAILED"))
    }

    @Test
    fun `a refusal is reported for the clip on air, and only then`() {
        val condemned = mutableListOf<String>()
        RefusedUrl.report("MPV_END_ERROR", "abc", { condemned += it; "hd" })
        RefusedUrl.report("MPV_END_ERROR", null, { condemned += it; "hd" })
        RefusedUrl.report("ERROR_CODE_DECODING_FAILED", "def", { condemned += it; null })
        assertEquals(listOf("abc"), condemned)
    }
}
