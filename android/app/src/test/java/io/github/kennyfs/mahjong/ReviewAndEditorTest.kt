package io.github.kennyfs.mahjong

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Review coalescing, PositionEditor.load edge cases, and meldTiles parity. */
class ReviewAndEditorTest {
  @Test fun reviewCachedCoalescesConcurrentCallsAndRetriesAfterFailure() = runBlocking {
    val calls = AtomicInteger(0)
    val a = async {
      reviewCached("coalesce-1") {
        calls.incrementAndGet()
        delay(50)
        Review(seat = 0, result = 3.0)
      }
    }
    val b = async {
      reviewCached("coalesce-1") {
        calls.incrementAndGet()
        Review(seat = 0, result = 99.0)
      }
    }
    assertEquals(3.0, a.await().result, 1e-9)
    assertEquals(3.0, b.await().result, 1e-9)
    assertEquals("concurrent callers must share one engine review", 1, calls.get())

    // Failure removes the failed entry so a later call retries.
    var failed = false
    try {
      reviewCached("fail-1") { throw EngineException("boom") }
    } catch (_: EngineException) {
      failed = true
    }
    assertTrue(failed)
    val ok = reviewCached("fail-1") { Review(seat = 0, result = 1.5) }
    assertEquals(1.5, ok.result, 1e-9)
  }

  @Test fun positionEditorLoadHandlesRobKongAndMissingDrawn() {
    val ed = PositionEditor()
    ed.load(
      Position(
        setup = Setup(dealer = 0, east = 0, round = 0, streak = 0),
        me = 0,
        hand = "123456789m123456p",
        drawn = null,
        claim = Claim(seat = 1, tile = "5z", robKong = true),
        players = listOf(
          PlayerPos(),
          PlayerPos(melds = listOf(Meld("kakan", "5z", listOf("5z", "5z", "5z", "5z"))), discards = listOf(Discard("1s"))),
          PlayerPos(),
          PlayerPos(),
        ),
        wallLeft = 40,
      ),
      "simple1,none",
    )
    assertEquals("rob_kong must not drop an unrelated river discard", listOf("1s"), ed.discards[1])

    // Missing drawn tile in hand must not throw IndexOutOfBoundsException.
    ed.load(
      Position(
        setup = Setup(dealer = 0, east = 0, round = 0, streak = 0),
        me = 0,
        hand = "123m",
        drawn = "9s",
        players = List(4) { PlayerPos() },
        wallLeft = 40,
      ),
      "simple1,none",
    )
    assertEquals(listOf("1m", "2m", "3m"), ed.hand)
    assertEquals("9s", ed.drawn)
  }

  @Test fun meldTilesExpandsHandEditedMelds() {
    assertEquals(listOf("5z", "5z", "5z"), meldTiles(Meld("pon", "5z")))
    assertEquals(listOf("2m", "3m", "4m"), meldTiles(Meld("chi", "2m")))
    assertEquals(listOf(null, null, null, null), meldTiles(Meld("ankan", null)))
  }
}
