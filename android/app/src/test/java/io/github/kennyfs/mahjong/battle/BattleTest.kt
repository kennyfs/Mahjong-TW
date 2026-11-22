package io.github.kennyfs.mahjong.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Battle URL parsing (including IPv6), local-network detection, and stale TableView filtering. */
class BattleTest {
  @Test fun serverUrlHandlesHostnamesAndIpv6() {
    assertEquals("wss://mahjong.1li.tw/ws", serverUrl("mahjong.1li.tw"))
    assertEquals("ws://127.0.0.1:9/ws", serverUrl("127.0.0.1:9"))
    assertEquals("ws://localhost:8080/ws", serverUrl("localhost:8080"))
    assertEquals("ws://10.0.2.2:8080/ws", serverUrl("10.0.2.2:8080"))
    assertEquals("ws://[::1]:8080/ws", serverUrl("[::1]:8080"))
    assertEquals("ws://[::1]/ws", serverUrl("ws://[::1]"))
    assertEquals("wss://[2001:db8::1]:8443/ws", serverUrl("[2001:db8::1]:8443"))
    // Rejects cleartext ws:// to non-local hosts and malformed bracketed IPv6.
    assertNull(serverUrl("ws://example.com/ws"))
    assertNull(serverUrl("ws://[2001:db8::1]/ws"))
    assertNull(serverUrl("ws://[::1"))
    assertNull(serverUrl(""))
    assertNull(serverUrl("bad host"))

    assertFalse(isLocalNetwork("wss://mahjong.1li.tw/ws"))
    assertFalse(isLocalNetwork("ws://[::1]:8080/ws"))
    assertTrue(isLocalNetwork("wss://192.168.1.5/ws"))
    assertTrue(isLocalNetwork("ws://10.0.2.2:8080/ws"))
    assertTrue(isLocalNetwork("wss://[fe80::1]/ws"))
  }
}
