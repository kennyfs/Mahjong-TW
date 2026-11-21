package io.github.kennyfs.mahjong

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers of Claims.kt: forcedDiscardAction, justCalled, othersCall, robbedEvent. */
class ClaimsTest {
  private fun viewWith(
    phase: String = "self", myTurn: Boolean = true, declared: String? = "baoting",
    legal: List<Action> = listOf(Action("discard", "1m")), events: List<GameEvent> = emptyList(),
  ) = View(
    rules = "simple1,none",
    position = Position(
      setup = Setup(), me = 0, hand = "123m", drawn = "1m",
      players = List(4) { i -> PlayerPos(declared = if (i == 0) declared else null) },
      wallLeft = 40,
    ),
    phase = phase, current = 0, myTurn = myTurn, legal = legal, events = events, eventCount = events.size,
  )

  @Test fun forcedDiscardTriggersOnlyForDeclaredSingleDiscard() {
    assertEquals(Action("discard", "1m"), Claims.forcedDiscardAction(viewWith(), 0))
    assertEquals(Action("discard", "1m"), Claims.forcedDiscardAction(viewWith(declared = "tianting"), 0))
    // Not declared, not own turn, claim phase, or extra option (tsumo / ankan): player decides.
    assertNull(Claims.forcedDiscardAction(viewWith(declared = null), 0))
    assertNull(Claims.forcedDiscardAction(viewWith(myTurn = false), 0))
    assertNull(Claims.forcedDiscardAction(viewWith(phase = "react"), 0))
    assertNull(Claims.forcedDiscardAction(viewWith(legal = listOf(Action("discard", "1m"), Action("tsumo", "1m"))), 0))
    assertNull(Claims.forcedDiscardAction(viewWith(legal = listOf(Action("pass"))), 0))
  }

  @Test fun justCalledAndRobbedEvent() {
    val g = GameRecord(
      rules = "simple1,none", setup = Setup(),
      actions = listOf(
        JsonArray(listOf(JsonPrimitive(0), JsonPrimitive("discard"), JsonPrimitive("5m"))),
        JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("pon"))),
      ),
    )
    assertTrue(Claims.justCalled(g, 1))
    assertFalse(Claims.justCalled(g, 2))

    val events = listOf(
      GameEvent(0, "discard", "5m"),
      GameEvent(2, "pon", "5m"),
    )
    assertTrue(Claims.othersCall(viewWith(events = events), before = 1, me = 0))
    assertFalse(Claims.othersCall(viewWith(events = events), before = 1, me = 2))
    assertEquals(1, Claims.robbedEvent(events, from = 1, seat = 2))
    assertEquals(-1, Claims.robbedEvent(events, from = 1, seat = 1))
  }
}
