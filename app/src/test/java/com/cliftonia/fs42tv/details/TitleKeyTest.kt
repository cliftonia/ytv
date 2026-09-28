package com.cliftonia.fs42tv.details

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The details key must be computed exactly as the server computes it (`tools/details/titles.py`),
 * or a title with a poster on the server shows none here. Both suites run the same cases, from the
 * one file both read.
 */
class TitleKeyTest {

    private val shared = listOf("../tools/details/title_keys.json", "tools/details/title_keys.json")
        .map(::File).first { it.exists() }

    @Test
    fun `every shared case keys as the server keys it`() {
        val cases = Json.parseToJsonElement(shared.readText()).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size > 10)
        cases.forEach { case ->
            val (raw, want) = case.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(raw, want, TitleKey.of(raw))
        }
    }

    @Test
    fun `null and blank key to nothing`() {
        assertEquals("", TitleKey.of(null))
        assertEquals("", TitleKey.of("   "))
    }
}
