package com.veeha.fastfin

import com.veeha.fastfin.data.serverCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What people type or paste into the Server field, and where we look. */
class ServerAddressTest {

    @Test
    fun browserAddressBarIsCleanedToTheServerRoot() {
        assertEquals(listOf("http://192.168.1.5:8096"), serverCandidates("http://192.168.1.5:8096/web/#/home.html"))
        assertEquals(listOf("https://media.example.com"), serverCandidates("https://media.example.com/web/index.html#!/home"))
        assertEquals(listOf("http://nas:8096"), serverCandidates("  http://nas:8096/  "))
    }

    @Test
    fun reverseProxyPathIsKept() {
        assertEquals(listOf("https://example.com/jellyfin"), serverCandidates("https://example.com/jellyfin/web/"))
        assertEquals(listOf("https://example.com/jellyfin", "http://example.com/jellyfin"), serverCandidates("example.com/jellyfin"))
    }

    @Test
    fun lanAddressesTryPlainHttpFirst() {
        assertEquals(listOf("http://192.168.1.5:8096", "https://192.168.1.5:8096"), serverCandidates("192.168.1.5:8096"))
        assertEquals(listOf("http://jellyfin.local:8096", "https://jellyfin.local:8096"), serverCandidates("jellyfin.local:8096"))
        assertEquals(listOf("http://nas.lan", "https://nas.lan"), serverCandidates("nas.lan"))
        assertEquals("http://myserver:8096", serverCandidates("myserver:8096").first())
        assertEquals("http://[fd00::5]:8096", serverCandidates("[fd00::5]:8096").first())
    }

    @Test
    fun publicNamesAndTheHttpsPortTryHttpsFirst() {
        assertEquals("https://media.example.com", serverCandidates("media.example.com").first())
        assertEquals("https://192.168.1.5:8920", serverCandidates("192.168.1.5:8920").first())
    }

    @Test
    fun anExplicitSchemeIsRespected() {
        assertEquals(listOf("http://media.example.com"), serverCandidates("http://media.example.com"))
        assertEquals(listOf("https://10.0.0.2:8096"), serverCandidates("HTTPS://10.0.0.2:8096"))
    }

    @Test
    fun emptyInputHasNoCandidates() {
        assertTrue(serverCandidates("   ").isEmpty())
        assertTrue(serverCandidates("http://").isEmpty())
    }
}
