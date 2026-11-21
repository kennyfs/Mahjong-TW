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
    // The fewest steps, then the most improving tiles; a tie is all of them.
    assertEquals(listOf(1), Explain.greedyOf(Analysis(actions = listOf(d("5m", 2, 30), d("9m", 1, 10), d("1z", 1, 8)))))
    assertEquals(listOf(0, 2), Explain.greedyOf(Analysis(actions = listOf(d("3s", 0, 8), d("9m", 0, 4), d("6s", 0, 8)))))
    // A kong when it leaves the hand no farther from ready than the best discard, whatever the improving tiles.
    val kakan = ActionFigures(Action("kakan", "5m"), shanten = 1, ukeire = 4)
    assertEquals(listOf(1), Explain.greedyOf(Analysis(actions = listOf(kakan, d("3s", 0, 8)))))
    assertEquals(listOf(0), Explain.greedyOf(Analysis(actions = listOf(kakan, d("3s", 1, 12)))))
    // A call only when it is a step closer (the closest; ties all), whatever the improving tiles; a win always.
    val pass = ActionFigures(Action("pass"), shanten = 2, ukeire = 20)
    assertEquals(listOf(0), Explain.greedyOf(Analysis(actions = listOf(pass, ActionFigures(Action("pon", "5z"), shanten = 2, ukeire = 18)))))
    assertEquals(listOf(0), Explain.greedyOf(Analysis(actions = listOf(pass, ActionFigures(Action("pon", "7s"), shanten = 2, ukeire = 47)))))
    assertEquals(listOf(1), Explain.greedyOf(Analysis(actions = listOf(pass, ActionFigures(Action("chi", "3m"), shanten = 1, ukeire = 4)))))
    assertEquals(listOf(1, 3), Explain.greedyOf(Analysis(actions = listOf(pass, ActionFigures(Action("chi", "3m"), shanten = 1, ukeire = 4),
      ActionFigures(Action("chi", "4m"), shanten = 2, ukeire = 30), ActionFigures(Action("pon", "5m"), shanten = 1, ukeire = 9)))))
    assertEquals(listOf(2), Explain.greedyOf(Analysis(actions = listOf(pass, ActionFigures(Action("pon", "5z"), shanten = 1), ActionFigures(Action("ron", "5z"), shanten = -1)))))
    // Against the played action: none when it is one of the choices, else the one the network values most.
    val tie = Analysis(actions = listOf(d("3s", 0, 8).copy(q = 0.1), d("9m", 0, 4).copy(q = 0.5), d("6s", 0, 8).copy(q = 0.3)))
    assertEquals(-1, Explain.greedyAlt(tie, 2))
    assertEquals(2, Explain.greedyAlt(tie, 1))
  }

  @Test fun tipsThreatMonotonic() {
    val t = Explain.TIPS_THREAT
    assertEquals(4, t.size)
    // Across melds within each discard bucket.
    for (row in t) {
      for (m in 1 until row.size) {
        val prev = row[m - 1]
        val cur = row[m]
        if (prev != null && cur != null) assert(cur >= prev) { "TIPS_THREAT meld drop: $prev -> $cur" }
      }
    }
    // Across discard buckets for each meld count.
    for (m in 0 until 5) {
      for (d in 1 until t.size) {
        val prev = t[d - 1][m]
        val cur = t[d][m]
        if (prev != null && cur != null) assert(cur >= prev) { "TIPS_THREAT discard drop: $prev -> $cur" }
      }
    }
  }

  @Test fun glossaryLinking() {
    val parts = Glossary.link("先打現物，再打筋牌；別拆死對子。", english = false)
    val linked = parts.filter { it.termId != null }.map { it.text to it.termId }
    assertEquals(listOf("現物" to "genbutsu", "筋牌" to "suji", "死對子" to "dead_pair"), linked)
    // beforeId restricts cross-links strictly to earlier entries in topological order.
    val sub = Glossary.link("中張與筋牌與無筋", english = false, beforeId = "non_suji")
    assertEquals(listOf("中張" to "middle", "筋牌" to "suji"), sub.filter { it.termId != null }.map { it.text to it.termId })
  }
}
