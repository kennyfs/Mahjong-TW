package io.github.kennyfs.mahjong.ui

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The table's sizes for the room it has: how wide each kind of tile is, how the four rivers (discards) are laid out
// around the center, and how much room the players' panels take. Wide (a phone held sideways, a tablet) or tall (a
// phone upright). The same arithmetic as the web page's lib/layout.ts, so both look alike. Sizes in dp.
//
// Tiles stay large: the player's own are the ones tapped (40 dp or more on a phone, never fewer than 30), and an
// upright phone wraps the hand after HAND_ROW tiles rather than shrinking them.

/** Tiles a row of the player's hand on a tall table (the drawn tile apart at the end of the last row). */
const val HAND_ROW = 8
/** The gap before the drawn tile, in tile widths. */
const val DRAWN_GAP = 0.3f
/** The gap between the rows of a wrapped hand, in tile heights (room for a lifted tile, too). */
const val ROW_GAP = 0.2f
/** How far a chosen tile is lifted, in tile heights. */
const val LIFT = 0.2f
/** A flower's width, for the melds' tiles'. */
const val FLOWER_SCALE = 0.82f
/** Lines of the others' melds and flowers on a tall table. */
const val OPP_LINES = 2

data class MeldGaps(val row: Float, val col: Float)

fun meldGaps(w: Float) = MeldGaps(max(3f, w * 0.16f), max(3f, w * 0.14f))

/** How many lines melds of these tile counts and these flowers take in `width`, with tiles `w` wide. */
fun meldLines(counts: List<Int>, flowers: Int, width: Float, w: Float): Int {
  val gap = meldGaps(w).col
  val items = counts.map { it * w } + List(flowers) { w * FLOWER_SCALE }
  var lines = if (items.isNotEmpty()) 1 else 0
  var x = 0f
  for (it in items) {
    if (x > 0 && x + gap + it > width) {
      lines++
      x = it
    } else x += (if (x > 0) gap else 0f) + it
  }
  return lines
}

/** The largest tile width from `max` down to `min` at which these melds and flowers fit in `lines` lines. */
fun fitMelds(counts: List<Int>, flowers: Int, width: Float, lines: Int, maxW: Float, minW: Float = 16f): Float {
  var w = maxW
  while (w > minW) {
    if (meldLines(counts, flowers, width, w) <= lines) return w
    w -= 0.5f
  }
  return minW
}

// Another player's tiles when their hand is shown (a finished hand, a review showing everyone's): the melds and
// flowers as always, then the concealed tiles on a rack (a lighter strip behind them, RACK_PAD around), so that the
// hand is told from the melds at a glance.
const val RACK_PAD = 3f
/** The gap between the melds and the rack: under them, and beside them in one line. */
const val RACK_GAP = 5f
const val RACK_GAP_ROW = 10f

/** Lines that `n` tiles `w` wide take in `width`. */
fun runLines(n: Int, width: Float, w: Float): Int = if (n > 0) ceil(n / max(1f, floor(width / w))).toInt() else 0

/** The height of a seat's shown tiles in a column `width` wide: its melds and flowers in lines, the rack under them. */
fun shownHeight(counts: List<Int>, flowers: Int, concealed: Int, width: Float, w: Float): Float {
  val a = meldLines(counts, flowers, width, w)
  val b = runLines(concealed, width - 2 * RACK_PAD, w)
  return a * h(w) + max(0, a - 1) * meldGaps(w).row + (if (a > 0 && b > 0) RACK_GAP else 0f) + (if (b > 0) b * h(w) + 2 * RACK_PAD else 0f)
}

/** The largest tile width from `maxW` down to `minW` at which a seat's shown tiles fit in `width` x `height`. */
fun fitShown(counts: List<Int>, flowers: Int, concealed: Int, width: Float, height: Float, maxW: Float, minW: Float = 16f): Float {
  var w = maxW
  while (w > minW) {
    if (shownHeight(counts, flowers, concealed, width, w) <= height) return w
    w -= 0.5f
  }
  return minW
}

/** The same in one line (the player opposite on a wide table): melds, flowers and the rack side by side in `width`. */
fun fitShownRow(counts: List<Int>, flowers: Int, concealed: Int, width: Float, maxW: Float, minW: Float = 14f): Float {
  val items = counts.size + flowers
  var w = maxW
  while (w > minW) {
    val melds = counts.sum() * w + flowers * w * FLOWER_SCALE + max(0, items - 1) * meldGaps(w).col
    if (melds + (if (items > 0 && concealed > 0) RACK_GAP_ROW else 0f) + (if (concealed > 0) concealed * w + 2 * RACK_PAD else 0f) <= width) return w
    w -= 0.5f
  }
  return minW
}

data class TableSizes(
  val wide: Boolean,
  /** The player's own concealed tiles. */
  val hand: Float,
  /** The player's hand: tiles a row (0: one row). */
  val handRow: Int,
  /** The player's melds and flowers. */
  val meld: Float,
  /** Every river's tiles. */
  val river: Float,
  /** Others' melds and flowers. */
  val opp: Float,
  /** Others' concealed tiles, as backs (0: a count instead). */
  val back: Float,
  val acrossCols: Int,
  val acrossRows: Int,
  val sideCols: Int,
  val sideRows: Int,
  /** The side players' panels' width (wide); the others' strip's height; the player's area's height. */
  val sideW: Float,
  val topH: Float,
  val meH: Float,
  /** The box in the middle (round, wall, turn). */
  val boxW: Float,
  val boxH: Float,
  /** The table's width (wide: no wider than its parts need). */
  val tableW: Float,
  /** The words' size on the table, sp. */
  val font: Float,
)

private fun h(w: Float) = w * TILE_RATIO
/** A river's room, in tiles. */
private const val RIVER = 20
/** The rivers' tiles on a tall table: no smaller than this while the hand can give room. */
private const val MIN_RIVER = 26f

/** The rivers' tiles on a tall table whose others' strip has grown to show everyone's tiles: no smaller than this. */
const val SHOWN_RIVER = 20f

/** The height left for the others' strip on a tall table `height` high when the rivers' tiles are `river` wide. */
fun tallTopRoom(s: TableSizes, height: Float, river: Float) = height - s.meH - 4 - ((2 * s.acrossRows + 1.3f) * h(river) + 14)

/**
 * Sizes for a table of w x h dp; `maxHand` caps the hand's tiles (a tablet). `top`: a tall table's others' strip is
 * this high when that is more than it would be (everyone's tiles shown): the rivers give the room.
 */
fun tableSizes(width: Float, height: Float, maxHand: Float = 64f, top: Float = 0f): TableSizes {
  var w = width
  val wide = w >= height * 1.1f
  if (wide) w = min(w, height * 2.3f)
  if (wide) {
    val hand = max(24f, minOf(maxHand, (w - 24) / (17 + DRAWN_GAP + 0.3f), (height * 0.165f) / TILE_RATIO))
    val meld = hand * 0.78f
    val opp = max(22f, min(hand * 0.66f, if (height >= 900) 44f else 36f))
    val meH = h(hand) * (1 + LIFT) + 10
    val topH = h(opp) + 12
    val centerH = height - meH - topH - 6
    val sideMin = max(opp * 3.4f + 16, 128f)
    data class Best(val river: Float, val acrossCols: Int, val acrossRows: Int, val sideCols: Int)
    var best: Best? = null
    var river = min(hand * 0.85f, if (height >= 900) 60f else 52f)
    while (river >= 12 && best == null) {
      for (acrossRows in listOf(3, 2)) {
        if ((2 * acrossRows + 1.3f) * h(river) + 14 > centerH) continue
        val sideRows = floor((centerH - 4) / h(river)).toInt()
        val acrossCols = max(9, ceil(RIVER.toFloat() / acrossRows).toInt())
        val sideCols = listOf(3, 4, 5).firstOrNull { it * sideRows >= RIVER } ?: continue
        if ((acrossCols + 2 * sideCols) * river + 28 > w - 2 * sideMin) continue
        best = Best(river, acrossCols, acrossRows, sideCols)
        break
      }
      river -= 0.5f
    }
    val b = best ?: Best(12f, 11, 2, 4)
    val riversW = (b.acrossCols + 2 * b.sideCols) * b.river + 28
    val handRowW = (17 + DRAWN_GAP + 0.3f) * hand + 24
    val tableW = min(w, max(handRowW, riversW + 2 * max(sideMin, opp * 4.6f + 16)))
    val sideW = max(sideMin, (tableW - riversW) / 2)
    val sideRows = max(4, floor((centerH - 4) / h(b.river)).toInt())
    val boxH = max(h(b.river) * 1.3f, min(h(b.river) * 1.8f, centerH - 2 * b.acrossRows * h(b.river) - 14))
    return TableSizes(
      wide, hand, 0, meld, b.river, opp, max(10f, opp * 0.66f), b.acrossCols, b.acrossRows, b.sideCols, sideRows, sideW, topH, meH,
      b.acrossCols * b.river, boxH, tableW, max(14f, min(18f, opp * 0.46f)),
    )
  }
  // Tall (a phone upright): the others along the top, each in a column over their river; the rivers the whole width;
  // the player's name plate, melds and flowers in a line over the hand, which wraps after HAND_ROW tiles.
  val sideCols = 3
  val acrossCols = 6
  val acrossRows = 3
  data class Fit(val hand: Float, val meld: Float, val opp: Float, val meH: Float, val topH: Float, val centerH: Float, val river: Float)
  fun tall(hand: Float, more: Float = 0f): Fit {
    val meld = min(hand * 0.72f, 40f)
    val opp = max(22f, min(hand * 0.6f, 32f))
    val meldLine = max(30f, h(meld))
    val meH = meldLine + 4 + h(hand) * (2 + ROW_GAP + LIFT) + 6
    // The others' name plates in two lines (TableView's SeatBadge: the name over the tiles held and the score).
    val topH = max(more, 40 + OPP_LINES * (h(opp) + meldGaps(opp).row) + 4)
    val centerH = height - meH - topH - 4
    var river = min(hand * 0.9f, (w - 24) / (acrossCols + 2 * sideCols))
    while (river > 16 && (2 * acrossRows + 1.3f) * h(river) + 14 > centerH) river -= 0.5f
    return Fit(hand, meld, opp, meH, topH, centerH, river)
  }
  var fit = tall(max(30f, min(maxHand, (w - 10) / (HAND_ROW + DRAWN_GAP + 1))))
  while (fit.river < MIN_RIVER && fit.hand > 30) fit = tall(max(30f, fit.hand - 1))
  // A higher strip takes its room from the rivers alone: the hand stays as it was.
  if (top > fit.topH) fit = tall(fit.hand, top)
  val sideRows = max(4, floor((fit.centerH - 4) / h(fit.river)).toInt())
  val boxH = max(h(fit.river) * 1.3f, min(h(fit.river) * 2, fit.centerH - 2 * acrossRows * h(fit.river) - 14))
  return TableSizes(
    wide, fit.hand, HAND_ROW, fit.meld, fit.river, fit.opp, 0f, acrossCols, acrossRows, sideCols, sideRows, (w - 8) / 3, fit.topH,
    fit.meH, acrossCols * fit.river, boxH, w, 14f,
  )
}
