package io.github.kennyfs.mahjong

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// The analysis screen's position, set up by hand: the player's hand (and the tile just drawn, or another player's
// discard to decide on), every seat's melds, flowers and discards, the dealer, the round wind and the wall. It is
// turned into the engine's POSITION (docs/gui-engine.md), which checks it. The web page's lib/position-editor.svelte.ts.

enum class EditMode { SELF, CLAIM }

/** A part of the position being edited. `seat` for melds, flowers and discards. */
data class Section(val kind: Kind, val seat: Int = 0) {
  enum class Kind { HAND, DRAWN, CLAIM, MELDS, FLOWERS, DISCARDS }
}

/** A meld's tiles (a chow by its lowest tile). */
fun meldOf(m: Meld): List<String> {
  val t = m.tile ?: return emptyList()
  return when (m.type) {
    "chi" -> (0..2).map { "${t[0] + it}${t[1]}" }
    "pon" -> List(3) { t }
    else -> List(4) { t }
  }
}

class PositionEditor {
  var hand by mutableStateOf<List<String>>(emptyList())
  var drawn by mutableStateOf<String?>(null)
  var mode by mutableStateOf(EditMode.SELF)
  var claimSeat by mutableIntStateOf(3)
  var claimTile by mutableStateOf<String?>(null)
  var melds by mutableStateOf(List(4) { emptyList<Meld>() })
  var flowers by mutableStateOf(List(4) { emptyList<String>() })
  var discards by mutableStateOf(List(4) { emptyList<String>() })
  var dealer by mutableIntStateOf(0)
  var round by mutableIntStateOf(0)
  var wallLeft by mutableIntStateOf(40)
  var section by mutableStateOf(Section(Section.Kind.HAND))

  /** How many concealed tiles the hand needs, the drawn tile apart: 16 less 3 a meld. */
  val handSize: Int get() = 16 - 3 * melds[0].size

  private fun <T> List<List<T>>.with(seat: Int, v: List<T>) = mapIndexed { i, x -> if (i == seat) v else x }

  /** Copies of a tile seen anywhere. */
  fun count(t: String): Int {
    var n = hand.count { it == t } + (if (drawn == t) 1 else 0) + (if (claimTile == t) 1 else 0)
    for (s in 0..3) {
      for (m in melds[s]) n += meldOf(m).count { it == t }
      n += discards[s].count { it == t } + flowers[s].count { it == t }
    }
    return n
  }

  fun left(t: String) = (if (Tiles.isFlower(t)) 1 else 4) - count(t)

  /** A tile from the palette into the section being edited. False: there is no room for it. */
  fun add(t: String): Boolean {
    val s = section
    if (left(t) <= 0) return false
    if (Tiles.isFlower(t) != (s.kind == Section.Kind.FLOWERS)) return false
    when (s.kind) {
      Section.Kind.HAND -> {
        if (hand.size >= handSize) return false
        hand = Tiles.sort(hand + t)
      }
      Section.Kind.DRAWN -> drawn = t
      Section.Kind.CLAIM -> claimTile = t
      Section.Kind.FLOWERS -> flowers = flowers.with(s.seat, Tiles.sort(flowers[s.seat] + t))
      Section.Kind.DISCARDS -> {
        if (discards[s.seat].size >= 30) return false
        discards = discards.with(s.seat, discards[s.seat] + t)
      }
      Section.Kind.MELDS -> return false
    }
    return true
  }

  /** A meld for a seat: its kind and tile (a chow's lowest). */
  fun addMeld(seat: Int, type: String, tile: String): Boolean {
    if (melds[seat].size >= 5) return false
    val m = Meld(type, tile)
    val need = meldOf(m)
    for (t in need.toSet()) if (left(t) < need.count { it == t }) return false
    melds = melds.with(seat, melds[seat] + m)
    if (seat == 0) hand = hand.take(handSize)
    return true
  }

  fun removeAt(s: Section, i: Int) {
    when (s.kind) {
      Section.Kind.HAND -> hand = hand.filterIndexed { k, _ -> k != i }
      Section.Kind.DRAWN -> drawn = null
      Section.Kind.CLAIM -> claimTile = null
      Section.Kind.MELDS -> melds = melds.with(s.seat, melds[s.seat].filterIndexed { k, _ -> k != i })
      Section.Kind.FLOWERS -> flowers = flowers.with(s.seat, flowers[s.seat].filterIndexed { k, _ -> k != i })
      Section.Kind.DISCARDS -> discards = discards.with(s.seat, discards[s.seat].filterIndexed { k, _ -> k != i })
    }
  }

  fun clear() {
    hand = emptyList()
    drawn = null
    claimTile = null
    melds = List(4) { emptyList() }
    flowers = List(4) { emptyList() }
    discards = List(4) { emptyList() }
    section = Section(Section.Kind.HAND)
  }

  /** A random hand of 16 and a drawn tile (or a discard to decide on), nothing else seen. */
  fun random() {
    clear()
    val wall = Tiles.KINDS.flatMap { listOf(it, it, it, it) }.shuffled()
    hand = Tiles.sort(wall.take(16))
    if (mode == EditMode.SELF) drawn = wall[16] else claimTile = wall[16]
  }

  /** The tiles the hand needs and has, when they differ. */
  fun missing(): Pair<Int, Int>? = if (hand.size != handSize) handSize to hand.size else null

  fun position(): Position {
    val d = drawn
    val all = if (mode == EditMode.SELF && d != null) hand + d else hand
    return Position(
      setup = Setup(dealer = dealer, east = -1, round = round, streak = 0),
      me = 0,
      hand = Tiles.string(all),
      drawn = if (mode == EditMode.SELF) d else null,
      claim = if (mode == EditMode.CLAIM) Claim(claimSeat, claimTile) else null,
      players = (0..3).map { s ->
        PlayerPos(
          melds = melds[s].map { if (s == 0) it else it.copy(from = -1) },
          flowers = Tiles.string(flowers[s]),
          discards = discards[s].map { Discard(it) },
        )
      },
      wallLeft = wallLeft,
    )
  }
}
