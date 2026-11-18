package io.github.kennyfs.mahjong

import org.junit.Assert.assertEquals
import org.junit.Test

/** Explain's parts that need no resources: what a discard takes from the hand, what a tile is to an opponent, the
 *  signs' table, and tile efficiency's choice. */
class ExplainTest {
  private fun counts(tiles: String): IntArray {
    val c = IntArray(34)
    for (t in Tiles.parse(tiles)) c[Explain.kindOf(t)]++
    return c
  }

  @Test fun roles() {
    // 123m 46m 99p 1z 55z 8s: a set, a closed wait, a pair, a lone honor, an honor pair, a lone tile.
    val c = counts("12346m99p8s155z")
    assertEquals("set", Explain.roleOf(c, Explain.kindOf("2m")))
    assertEquals("pair", Explain.roleOf(c, Explain.kindOf("9p")))
    assertEquals("zlone", Explain.roleOf(c, Explain.kindOf("1z")))
    assertEquals("zpair", Explain.roleOf(c, Explain.kindOf("5z")))
    assertEquals("lone", Explain.roleOf(c, Explain.kindOf("8s")))
    // 46m beside 123m: either of 4 and 6 breaks the closed wait.
    assertEquals("kanchan", Explain.roleOf(c, Explain.kindOf("6m")))
    // 12p: an edge; 45p: two-sided.
    assertEquals("penchan", Explain.roleOf(counts("12p"), Explain.kindOf("1p")))
    assertEquals("ryanmen", Explain.roleOf(counts("45p"), Explain.kindOf("5p")))
  }

  @Test fun suji() {
    val disc = setOf(Explain.kindOf("4m"), Explain.kindOf("1z"))
    assertEquals('g', Explain.tileCat(Explain.kindOf("4m"), disc))
    assertEquals('g', Explain.tileCat(Explain.kindOf("1z"), disc))
    assertEquals('s', Explain.tileCat(Explain.kindOf("1m"), disc))
    assertEquals('s', Explain.tileCat(Explain.kindOf("7m"), disc))
    assertEquals('x', Explain.tileCat(Explain.kindOf("1p"), disc))
    // 7 with 4: suji; 5 with only 2 (no 8): half.
    assertEquals('h', Explain.tileCat(Explain.kindOf("5p"), setOf(Explain.kindOf("2p"))))
    assertEquals('s', Explain.tileCat(Explain.kindOf("5p"), setOf(Explain.kindOf("2p"), Explain.kindOf("8p"))))
    assertEquals("19", Explain.rankClass(Explain.kindOf("9s")))
    assertEquals("456", Explain.rankClass(Explain.kindOf("5s")))
  }

  @Test fun signs() {
    // 9-11 discards, 2 melds: 39%; with two 摸切 at the end, 67%.
    assertEquals(0.3898, Explain.threatOf(10, 2, 0)!!, 1e-9)
    assertEquals(0.6731, Explain.threatOf(10, 2, 3)!!, 1e-9)
    assertEquals(0.7736, Explain.threatOf(14, 6, 0)!!, 1e-9)
  }

  @Test fun greedy() {
    fun d(tile: String, sh: Int, uk: Int) = ActionFigures(Action("discard", tile), shanten = sh, ukeire = uk)
    // The fewest steps, then the most improving tiles, then honors and terminals first.
    assertEquals(1, Explain.greedyOf(Analysis(actions = listOf(d("5m", 2, 30), d("9m", 1, 10), d("1z", 1, 8)))))
    assertEquals(2, Explain.greedyOf(Analysis(actions = listOf(d("5m", 1, 10), d("9m", 1, 10), d("1z", 1, 10)))))
    // A call only when it is a step closer; a win always.
    val pass = ActionFigures(Action("pass"), shanten = 2)
    assertEquals(0, Explain.greedyOf(Analysis(actions = listOf(pass, ActionFigures(Action("pon", "5z"), shanten = 2)))))
    assertEquals(1, Explain.greedyOf(Analysis(actions = listOf(pass, ActionFigures(Action("chi", "3m"), shanten = 1)))))
    assertEquals(2, Explain.greedyOf(Analysis(actions = listOf(pass, ActionFigures(Action("pon", "5z"), shanten = 1), ActionFigures(Action("ron", "5z"), shanten = -1)))))
  }
}
