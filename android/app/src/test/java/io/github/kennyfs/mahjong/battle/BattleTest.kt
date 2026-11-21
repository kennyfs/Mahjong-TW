package io.github.kennyfs.mahjong.battle

import io.github.kennyfs.mahjong.Position
import io.github.kennyfs.mahjong.Setup
import io.github.kennyfs.mahjong.View
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

  @Test fun staleAndDuplicateIncrementalViewsAreDropped() {
    val dummyView = View("simple1,none", Position(Setup(), players = emptyList()), "self")
    val cur = MyTable(hand = 2, seat = 0, step = 5, seats = listOf(0, 1, 2, 3), view = dummyView, deadline = null)
    assertFalse(Battle.isStaleTableView(null, hand = 0, step = 0, full = false))
    assertTrue(Battle.isStaleTableView(cur, hand = 1, step = 9, full = false))
    assertTrue(Battle.isStaleTableView(cur, hand = 2, step = 4, full = false))
    assertTrue(Battle.isStaleTableView(cur, hand = 2, step = 5, full = false))
    assertFalse(Battle.isStaleTableView(cur, hand = 2, step = 6, full = false))
    assertFalse(Battle.isStaleTableView(cur, hand = 3, step = 0, full = false))
    // Full views (e.g. after resume) are always accepted even at the same step.
    assertFalse(Battle.isStaleTableView(cur, hand = 2, step = 5, full = true))
  }
}
