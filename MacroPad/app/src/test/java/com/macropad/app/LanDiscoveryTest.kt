package com.macropad.app

import com.macropad.app.ai.LanDiscovery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LanDiscovery.isLocalAddress] decides whether the app may replace a stored server
 * address with one it found on the network. A false positive silently repoints
 * someone's phone away from the server they configured by hand, so the boundary
 * between "local" and "not local" is worth pinning down.
 */
class LanDiscoveryTest {

    @Test
    fun `mdns names are local`() {
        assertTrue(LanDiscovery.isLocalAddress("https://macropad.local:8321"))
        assertTrue(LanDiscovery.isLocalAddress("http://MacroPad.Local:8321"))
        assertTrue(LanDiscovery.isLocalAddress("http://localhost:8321"))
    }

    @Test
    fun `private ranges are local`() {
        assertTrue(LanDiscovery.isLocalAddress("https://192.168.1.50:8321"))
        assertTrue(LanDiscovery.isLocalAddress("https://10.0.0.7:8321"))
        assertTrue(LanDiscovery.isLocalAddress("https://172.16.4.4:8321"))
        assertTrue(LanDiscovery.isLocalAddress("https://172.31.255.1:8321"))
    }

    @Test
    fun `a real hostname is never local`() {
        // The case that matters: an existing self-hosted setup behind a public name
        // must never be overwritten because the server was briefly unreachable.
        assertFalse(LanDiscovery.isLocalAddress("https://macropad.example.com:1403"))
        assertFalse(LanDiscovery.isLocalAddress("https://example.com"))
    }

    @Test
    fun `addresses just outside the private ranges are not local`() {
        // 172.16/12 spans 172.16 through 172.31 only.
        assertFalse(LanDiscovery.isLocalAddress("https://172.15.0.1:8321"))
        assertFalse(LanDiscovery.isLocalAddress("https://172.32.0.1:8321"))
        assertFalse(LanDiscovery.isLocalAddress("https://11.0.0.1:8321"))
        assertFalse(LanDiscovery.isLocalAddress("https://192.169.1.1:8321"))
    }

    @Test
    fun `a hostname that merely looks numeric is not local`() {
        assertFalse(LanDiscovery.isLocalAddress("https://192.168.1.evil.com"))
        assertFalse(LanDiscovery.isLocalAddress("https://10.0.0.1.attacker.net"))
    }

    @Test
    fun `junk is not local`() {
        assertFalse(LanDiscovery.isLocalAddress(""))
        assertFalse(LanDiscovery.isLocalAddress("not a url"))
        assertFalse(LanDiscovery.isLocalAddress("https://"))
    }
}
