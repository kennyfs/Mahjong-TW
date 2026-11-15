package io.github.kennyfs.mahjong

// Tiles by their engine names: "1m"-"9m" (萬), "1p"-"9p" (筒), "1s"-"9s" (條), "1z"-"7z" (東南西北白發中), "1f"-"8f"
// (春夏秋冬梅蘭菊竹). TILES strings list them grouped by suit ("123m55z"). The web page's lib/tiles.ts and lib/seats.ts.
object Tiles {
  private const val SUITS = "mpszf"

  /** All 34 kinds in the engine's order (an index into its figures by kind), and the 8 flowers. */
  val KINDS: List<String> = listOf('m', 'p', 's').flatMap { s -> (1..9).map { "$it$s" } } + (1..7).map { "${it}z" }
  val FLOWERS: List<String> = (1..8).map { "${it}f" }

  /** "123m55z" -> 1m 2m 3m 5z 5z. */
  fun parse(s: String?): List<String> {
    if (s.isNullOrEmpty()) return emptyList()
    val out = ArrayList<String>()
    val digits = StringBuilder()
    for (c in s) {
      if (c.isDigit()) digits.append(c)
      else if (c in SUITS) {
        for (d in digits) out += "$d$c"
        digits.clear()
      }
    }
    return out
  }

  /** The tiles as a TILES string, sorted. */
  fun string(tiles: List<String>): String = buildString {
    for (suit in SUITS) {
      val ds = tiles.filter { it[1] == suit }.map { it[0] }.sorted()
      if (ds.isNotEmpty()) append(ds.joinToString("")).append(suit)
    }
  }

  /** Order of kinds as shown: characters, dots, bamboo, honors (東南西北中發白, the way they are said: not the engine's
   *  白發中), flowers. */
  fun index(t: String): Int {
    val n = t[0] - '0'
    return when (t[1]) {
      'm' -> n - 1
      'p' -> 8 + n
      's' -> 17 + n
      'z' -> if (n <= 4) 26 + n else 38 - n
      else -> 33 + n
    }
  }

  fun sort(ts: List<String>) = ts.sortedBy(::index)

  /** All 34 kinds as shown (中發白). */
  val KINDS_SHOWN: List<String> by lazy { sort(KINDS) }

  fun isFlower(t: String) = t.length == 2 && t[1] == 'f'

  /** "五萬", "三筒", "七條", "東", "白", "春" (in the app's language: Words). */
  fun name(t: String): String {
    val n = t[0] - '0'
    return when (t[1]) {
      'm' -> Words.suited(Words.numerals[n - 1], 0)
      'p' -> Words.suited(Words.numerals[n - 1], 1)
      's' -> Words.suited(Words.numerals[n - 1], 2)
      'z' -> Words.honors[n - 1]
      else -> Words.flowers[n - 1]
    }
  }

  /** A wind (0-3) by name: 東, "East". */
  fun wind(w: Int): String = Words.honors.getOrNull(w) ?: "?"
  /** A wind's mark on a seat's plate: 東, "E". */
  fun windMark(w: Int): String = Words.windMarks.getOrNull(w) ?: "?"

  /**
   * Where a hand's tiles that its waits use (the engine's wait_tiles, ready_tiles: 聽的相關牌組) are: indices into `tiles`
   * (sorted) and then the drawn tile, the tile at `skip` (the one to be discarded) left out. Of a kind it has more of
   * than the group, the hand's own before the drawn one, and those next to the group's neighbouring kind. The web
   * page's groupIndices.
   */
  fun groupIndices(tiles: List<String>, drawn: String?, group: List<String>, skip: Int?): Set<Int> {
    val out = HashSet<Int>()
    val need = group.groupingBy { it }.eachCount()
    for ((x, c) in need) {
      val at = tiles.indices.filter { tiles[it] == x && it != skip }
      val n = x[0].digitToIntOrNull() ?: 0
      fun next(d: Int) = x[1] != 'z' && need.containsKey("${n + d}${x[1]}")
      val mine = if (next(1) && !next(-1)) at.takeLast(c) else at.take(c)
      out += mine
      if (mine.size < c && drawn == x && skip != tiles.size) out += tiles.size
    }
    return out
  }

  /** `tiles` without one copy of each of `remove`. */
  fun without(tiles: List<String>, remove: List<String>): List<String> {
    val out = tiles.toMutableList()
    for (r in remove) out.remove(r)
    return out
  }
}

/** Where a seat sits as the player sees it: the player at the bottom, the next (下家) at the right, the previous at the left. */
enum class Side { BOTTOM, RIGHT, TOP, LEFT }

fun sideOf(seat: Int, me: Int): Side = Side.entries[(seat - me + 4) % 4]
fun seatAt(side: Side, me: Int): Int = (me + side.ordinal) % 4

/** How many concealed tiles a seat holds now. */
fun concealedCount(view: View, seat: Int): Int {
  val p = view.position.players[seat]
  return 16 - 3 * p.melds.size + if (view.phase == "self" && view.current == seat) 1 else 0
}

/**
 * A meld's tiles in the order to show them: a chow with the tile taken in the middle, as on a real table (it says
 * which tile was claimed); another player's concealed kong as four backs (nulls).
 */
fun meldTiles(m: Meld): List<String?> {
  if (m.tile == null) return listOf(null, null, null, null)
  val ts = m.tiles ?: emptyList()
  if (m.type == "ankan") return listOf(null, ts.getOrNull(1) ?: m.tile, ts.getOrNull(2) ?: m.tile, null)
  if (m.type == "chi" && m.called != null && ts.size == 3) {
    val rest = Tiles.without(ts, listOf(m.called))
    if (rest.size == 2) return listOf(rest[0], m.called, rest[1])
  }
  if (ts.isNotEmpty()) return ts
  // A meld set up by hand (the analysis' position) has its kind and tile only.
  return when (m.type) {
    "chi" -> (0..2).map { "${m.tile[0] + it}${m.tile[1]}" }
    "pon" -> List(3) { m.tile }
    else -> List(4) { m.tile }
  }
}

fun flowersOf(p: PlayerPos) = Tiles.parse(p.flowers)

/** A record's action (a chow by its lowest tile) as the full action: the chow's three tiles. */
fun fullAction(a: Action): Action {
  if (a.type == "chi" && a.tile != null && a.tiles == null) return a.copy(tiles = (0..2).map { "${a.tile[0] + it}${a.tile[1]}" })
  return a
}

/** Whether an analysis' action is the one a record says was taken (claims are told apart by their kind only). */
fun isTaken(legal: Action, taken: Action): Boolean {
  if (legal.type != taken.type) return false
  if (legal.type in setOf("discard", "ankan", "kakan", "chi")) return legal.tile == taken.tile
  return true
}

/** The tiles an action shows: a chow's three, a discard's or a kong's one. */
fun actionTiles(a: Action): List<String> = when {
  a.type == "chi" && !a.tiles.isNullOrEmpty() -> a.tiles
  a.tile != null && a.type != "pass" -> listOf(a.tile)
  else -> emptyList()
}
