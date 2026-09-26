package com.cliftonia.fs42tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switches for the extras, one Settings row each.
 *
 * They exist so a feature that misbehaves on one television can be turned off with the remote,
 * by someone who cannot attach a debugger. So the rules that matter are that every one starts ON,
 * that OK flips exactly one, and that the flip survives a relaunch.
 */
class FeaturesTest {

    private class Store {
        val saved = mutableMapOf<String, Boolean>()
        val text = mutableMapOf<String, String>()
        fun features() = Features(
            read = { key, default -> saved[key] ?: default },
            write = { key, value -> saved[key] = value },
            readText = { key -> text[key] },
            writeText = { key, value -> text[key] = value },
        )
    }

    private fun Features.tuningRow() =
        rows(onToggled = { _, _ -> }, refresh = {}).first { it.label == "TUNING SCREEN" }

    @Test
    fun `the tuning screen starts as static and cycles static, blue, none`() {
        val features = Store().features()
        assertEquals("STATIC", features.tuningRow().value)
        features.tuningRow().action!!.invoke()
        assertEquals(Features.TuningScreen.BLUE, features.tuningScreen)
        features.tuningRow().action!!.invoke()
        assertEquals("NONE", features.tuningRow().value)
        features.tuningRow().action!!.invoke()
        assertEquals(Features.TuningScreen.STATIC, features.tuningScreen)
    }

    @Test
    fun `the tuning screen choice survives a relaunch`() {
        val store = Store()
        store.features().tuningRow().action!!.invoke()
        assertEquals(Features.TuningScreen.BLUE, store.features().tuningScreen)
    }

    @Test
    fun `a television that had switched STATIC off keeps a plain black screen`() {
        // The old ON/OFF row's preference: someone who turned the snow off chose "none".
        val store = Store().apply { saved["feature.static"] = false }
        assertEquals(Features.TuningScreen.NONE, store.features().tuningScreen)
    }

    @Test
    fun `the tuning screen row sits where STATIC was, after PLUTO GUIDE`() {
        val labels = Store().features().rows(onToggled = { _, _ -> }, refresh = {}).map { it.label }
        assertEquals(labels.indexOf("PLUTO GUIDE") + 1, labels.indexOf("TUNING SCREEN"))
    }

    @Test
    fun `every feature starts on`() {
        val features = Store().features()
        Features.Flag.values().forEach { assertTrue("$it should default on", features.isOn(it)) }
    }

    @Test
    fun `each flag has its own row, labelled as the owner will look for it`() {
        val rows = Store().features().rows(onToggled = { _, _ -> }, refresh = {})
            .filter { it.label != "TUNING SCREEN" }
        assertEquals(Features.Flag.values().map { it.label }, rows.map { it.label })
        assertTrue(rows.all { row ->
            row.value == Features.Flag.values().first { it.label == row.label }.onValue &&
                row.action != null
        })
    }

    @Test
    fun `the schedule row reads as the choice it is, not as ON and OFF`() {
        val features = Store().features()
        val row = { features.rows(onToggled = { _, _ -> }, refresh = {})
            .first { it.label == "SCHEDULE" } }
        assertEquals("HALF-HOUR", row().value)
        row().action!!.invoke()
        assertEquals("CONTINUOUS", row().value)
        assertFalse(features.isOn(Features.Flag.SCHEDULE))
    }

    @Test
    fun `the pluto route row starts DIRECT and flips to LEGACY, remembered`() {
        val store = Store()
        val row = { store.features().rows(onToggled = { _, _ -> }, refresh = {})
            .first { it.label == "PLUTO ROUTE" } }
        assertEquals("DIRECT", row().value)
        row().action!!.invoke()
        assertEquals("LEGACY", row().value)
        assertEquals(false, store.saved["feature.plutoroute"])
        assertFalse(store.features().isOn(Features.Flag.PLUTO_ROUTE))
    }

    @Test
    fun `OK on a row flips that flag alone and remembers it`() {
        val store = Store()
        val features = store.features()
        val toggled = mutableListOf<Pair<Features.Flag, Boolean>>()
        var refreshed = 0
        val flag = Features.Flag.values().first()
        features.rows(onToggled = { f, on -> toggled += f to on }, refresh = { refreshed++ })
            .first { it.label == flag.label }.action!!.invoke()

        assertFalse(features.isOn(flag))
        assertEquals(listOf(flag to false), toggled)
        assertEquals(1, refreshed)
        assertEquals(false, store.saved[flag.key])
        Features.Flag.values().filter { it != flag }.forEach { assertTrue(features.isOn(it)) }
        // A fresh read of the same store, which is what the next launch does.
        assertFalse("the choice must survive a relaunch", store.features().isOn(flag))
    }

    @Test
    fun `keys are distinct, so one row can never flip another`() {
        val keys = Features.Flag.values().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
