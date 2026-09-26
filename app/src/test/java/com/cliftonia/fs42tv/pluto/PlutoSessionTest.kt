package com.cliftonia.fs42tv.pluto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two ways a Pluto session arrives - Pluto's own boot reply, and the home server's - and the
 * one url built from either. The shapes are the ones measured on 26 Sep 2026: boot.pluto.tv's
 * reply trimmed to the fields read (plus a stranger, as the real one carries dozens), and the
 * server's `/pluto/session` reply verbatim in form.
 */
class PlutoSessionTest {

    private val boot = """{"servers":{"api":"https://api.pluto.tv",
        "stitcher":"https://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv"},
        "session":{"activeRegion":"AU","countryCode":"AU"},
        "stitcherParams":"appName=web&country=AU&sid=s-1","sessionToken":"eyJ.local.sig",
        "refreshInSec":28800,"features":{"x":1}}"""

    private val server = """{"stitcher": "https://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv",
        "stitcherParams": "appName=web&country=GB&sid=s-2", "jwt": "eyJ.uk.sig", "region": "GB",
        "issuedAt": 1790394009, "expiresAt": 1790404809}"""

    @Test
    fun `the master url is the stitcher's channel route with the session's params and token`() {
        val session = PlutoSession("https://stitch.pluto.tv", "a=1&b=2", "eyJ.t.s", "AU", 0)
        assertEquals(
            "https://stitch.pluto.tv/v2/stitch/hls/channel/628e685ba3811100070551a8/master.m3u8" +
                "?a=1&b=2&jwt=eyJ.t.s&masterJWTPassthrough=true&includeExtendedEvents=true",
            session.masterUrl("628e685ba3811100070551a8"))
    }

    @Test
    fun `a boot reply becomes a local session good for three hours`() {
        val session = PlutoBoot.parseBoot(boot, nowMillis = 1_000L)!!
        assertEquals("https://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv", session.stitcher)
        assertEquals("appName=web&country=AU&sid=s-1", session.stitcherParams)
        assertEquals("eyJ.local.sig", session.jwt)
        assertEquals("AU", session.region)
        assertEquals(1_000L + 3 * 3_600_000L, session.expiresAtMillis)
    }

    @Test
    fun `a server reply keeps the server's own expiry`() {
        val session = PlutoBoot.parseServer(server)!!
        assertEquals("eyJ.uk.sig", session.jwt)
        assertEquals("GB", session.region)
        assertEquals(1790404809_000L, session.expiresAtMillis)
    }

    @Test
    fun `anything missing a stitcher, params or token is no session`() {
        assertNull(PlutoBoot.parseBoot("""{"servers":{},"sessionToken":"t","stitcherParams":"a"}""", 0))
        assertNull(PlutoBoot.parseBoot("""{"servers":{"stitcher":"https://s.pluto.tv"},"stitcherParams":"a"}""", 0))
        assertNull(PlutoBoot.parseServer("""{"stitcher":"https://s.pluto.tv","jwt":"t","expiresAt":5}"""))
        assertNull(PlutoBoot.parseServer("""{"stitcher":"https://s.pluto.tv","jwt":"t","stitcherParams":"a"}"""))
        assertNull(PlutoBoot.parseBoot("not json", 0))
        assertNull(PlutoBoot.parseServer("<html>502</html>"))
    }

    @Test
    fun `a stitcher that is not Pluto over https is refused`() {
        // Whatever this names goes straight into the player on every Pluto channel - the same
        // rule curation applies to the playlists: exact host or a real subdomain, https only.
        for (bad in listOf("http://s.pluto.tv", "https://fakepluto.tv", "https://s.pluto.tv.evil.com",
                "https://evil.example.com")) {
            val text = server.replace("https://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv", bad)
            assertNull(bad, PlutoBoot.parseServer(text))
        }
        assertTrue(PlutoBoot.parseServer(server) != null)
    }

    @Test
    fun `the boot url asks as a desktop web client with the given client id`() {
        val url = PlutoBoot.bootUrl("c-1")
        assertTrue(url.startsWith("https://boot.pluto.tv/v4/start?appName=web&appVersion=9.1.0"))
        assertTrue(url.contains("&serverSideAds=false&drmCapabilities=&clientID=c-1"))
    }

    @Test
    fun `the server url names the region`() {
        assertEquals("http://h:4246/pluto/session?region=uk", PlutoBoot.serverUrl("http://h:4246", "uk"))
    }

    @Test
    fun `the second server address is tried only when the first cannot be connected to`() {
        val asked = mutableListOf<String>()
        val session = PlutoBoot.fetchFromServer("uk") { url ->
            asked += url
            if (url.startsWith(PlutoBoot.SERVERS[0])) throw PlutoBoot.Unreachable(java.net.ConnectException("refused"))
            server
        }
        assertEquals("GB", session!!.region)
        assertEquals(PlutoBoot.SERVERS.map { "$it/pluto/session?region=uk" }, asked)
    }

    @Test
    fun `a bad answer is not asked again of the same box on its other address`() {
        val asked = mutableListOf<String>()
        val session = PlutoBoot.fetchFromServer("uk") { url ->
            asked += url
            throw java.io.IOException("pluto session HTTP 502")
        }
        assertNull(session)
        assertEquals(1, asked.size)
    }

    @Test
    fun `neither address connecting is reported as unreachable`() {
        val thrown = runCatching {
            PlutoBoot.fetchFromServer("uk") { throw PlutoBoot.Unreachable(java.net.NoRouteToHostException()) }
        }.exceptionOrNull()
        assertTrue(thrown is PlutoBoot.Unreachable)
    }

    @Test
    fun `a network not up yet is transient, a silent address is not`() {
        assertTrue(PlutoBoot.isTransient(PlutoBoot.Unreachable(java.net.UnknownHostException())))
        assertTrue(PlutoBoot.isTransient(PlutoBoot.Unreachable(java.net.ConnectException())))
        assertTrue(PlutoBoot.isTransient(PlutoBoot.Unreachable(java.net.NoRouteToHostException())))
        assertTrue(PlutoBoot.isTransient(java.net.SocketException("Network is unreachable")))
        assertFalse(PlutoBoot.isTransient(PlutoBoot.Unreachable(java.net.SocketTimeoutException())))
        assertFalse(PlutoBoot.isTransient(java.io.IOException("pluto session HTTP 500")))
    }
}
