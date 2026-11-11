package io.github.kennyfs.mahjong

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// The analysis screen's position, set up by hand: the player's hand (and the tile just drawn, or another player's
// discard to decide on), every seat's melds, flowers and discards, the dealer, the round wind and the wall. It is
// turned into the engine's POSITION (docs/gui-engine.md), which checks it. A step of a hand's review can be taken here
// (load), to try changes on it: while only the hand's own tiles are changed, the figures are asked of that hand's game
// (source), whose history the network reads; a position of its own has only the plain history of its discards.
// The web page's lib/position-editor.svelte.ts.

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
  /** From a hand's review: its rules (null: the settings'), and what the editor does not show of its position. */
  var rules by mutableStateOf<String?>(null)
  private var east = -1
  private var streak = 0
  private var afterCall = false
  private var missed = false

  /** What the editor holds of the table: everything but the hand's own tiles. */
  private data class Table(
    val mode: EditMode, val claimSeat: Int, val claimTile: String?, val melds: List<List<Meld>>, val flowers: List<List<String>>,
    val discards: List<List<String>>, val dealer: Int, val round: Int, val wallLeft: Int, val rules: String?,
  )
  private fun table() = Table(
    mode, if (mode == EditMode.CLAIM) claimSeat else -1, if (mode == EditMode.CLAIM) claimTile else null, melds, flowers, discards, dealer, round,
    wallLeft, rules,
  )
  private class Source(val game: GameRecord, val seat: Int, val table: Table)
  private var from by mutableStateOf<Source?>(null)

  /**
   * The step of a hand the position was taken from (the hand's record cut there, and the seat deciding), for as long as
   * nothing but the hand's own tiles differs from it: the figures are then asked of that game with this hand
   * (Engine.analyzeGame), and read the game's history as the hand's review does. Null: a position of its own.
   */
  val source: Pair<GameRecord, Int>? get() = from?.takeIf { it.table == table() }?.let { it.game to it.seat }

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
    rules = null
    east = -1
    streak = 0
    afterCall = false
    missed = false
    from = null
  }

  /**
   * A position of a hand as its seat `me` saw it (a view's), under the hand's rules: the seats turned so that `me` is
   * the player. The tiles taken by melds leave the rivers (the melds show them); a discard being decided on is the
   * tile to claim. `game`: the hand's record up to this position (see source).
   */
  fun load(p: Position, rules: String, game: GameRecord? = null) {
    clear()
    fun rel(s: Int) = (s - p.me + 4) % 4
    fun seat(r: Int) = (r + p.me) % 4
    this.rules = rules
    val claim = p.claim?.takeIf { it.tile != null }
    mode = if (claim != null) EditMode.CLAIM else EditMode.SELF
    melds = (0..3).map { r -> p.players[seat(r)].melds.map { Meld(it.type, it.tile, called = it.called) } }
    flowers = (0..3).map { r -> Tiles.parse(p.players[seat(r)].flowers) }
    discards = (0..3).map { r ->
      val list = p.players[seat(r)].discards
      val kept = if (claim != null && seat(r) == claim.seat) list.dropLast(1) else list
      kept.filter { !it.called }.map { it.tile }
    }
    var tiles = Tiles.sort(Tiles.parse(p.hand))
    if (claim != null) {
      claimSeat = rel(claim.seat).takeIf { it != 0 } ?: 3
      claimTile = claim.tile
    } else {
      // The tile drawn; after a call there is none, and the last tile stands for it.
      val d = p.drawn ?: tiles.lastOrNull()
      afterCall = p.afterCall
      if (d != null) tiles = tiles.toMutableList().also { it.removeAt(it.lastIndexOf(d)) }
      drawn = d
    }
    hand = tiles
    dealer = rel(p.setup.dealer)
    east = if (p.setup.east >= 0) rel(p.setup.east) else -1
    round = p.setup.round
    streak = p.setup.streak
    wallLeft = p.wallLeft
    missed = p.missed
    section = Section(if (claim != null) Section.Kind.CLAIM else Section.Kind.DRAWN)
    from = game?.let { Source(it, p.me, table()) }
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
    val self = mode == EditMode.SELF
    val d = drawn
    val all = if (self && d != null) hand + d else hand
    val called = self && afterCall
    val c = claimTile
    // The discard being decided on is the last of its seat's river.
    fun river(s: Int) = if (!self && c != null && s == claimSeat) discards[s] + c else discards[s]
    return Position(
      setup = Setup(dealer = dealer, east = east, round = round, streak = streak),
      me = 0,
      hand = Tiles.string(all),
      drawn = if (self && !called) d else null,
      afterCall = called,
      claim = if (self) null else Claim(claimSeat, c),
      players = (0..3).map { s ->
        PlayerPos(
          melds = melds[s].map { if (s == 0) it else it.copy(from = -1) },
          flowers = Tiles.string(flowers[s]),
          discards = river(s).map { Discard(it) },
        )
      },
      wallLeft = wallLeft,
      missed = missed,
    )
  }
}
