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
/** The gap before the tile a winner won on, at the end of its shown hand, in tile widths. */
const val WON_GAP = 0.2f
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

/** The height of the player's melds and flowers over a tall table's hand, `width` wide: in one line at the largest
 *  tile width from `maxW` down to `minW` that fits, or in more lines at `minW` (a narrow phone with many). */
fun myMeldsHeight(counts: List<Int>, flowers: Int, width: Float, maxW: Float, minW: Float): Float {
  val w = fitMelds(counts, flowers, width, 1, maxW, minW)
  val lines = meldLines(counts, flowers, width, w)
  return if (lines == 0) 0f else lines * h(w) + (lines - 1) * meldGaps(w).row
}

// Another player's tiles when their hand is shown (a finished hand, a review showing everyone's): the melds and
// flowers as always, then the concealed tiles on a rack (a darker tray behind them, RACK_PAD around), so that the
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
  /** Others' concealed tiles, as backs (0: a count instead: a tall table, and a low wide one). */
  val back: Float,
  val acrossCols: Int,
  val acrossRows: Int,
  val sideCols: Int,
  val sideRows: Int,
  /** The side players' panels' width (wide); the others' strip's height; the player's area's height. */
  val sideW: Float,
  val topH: Float,
  val meH: Float,
  /** A tall table's box in the middle (round, wall, streak): its width (0: a wide table, which has none). */
  val boxW: Float,
  /** The room between the two rivers across: a tall table's box's height, a wide table's gap. */
  val mid: Float,
  /** The table's width (wide: no wider than its parts need). */
  val tableW: Float,
  /** The words' size on the table, sp. */
  val font: Float,
  /** The line over the player's hand that holds their melds and flowers (a tall table's, their name plate too). */
  val meldLine: Float = 30f,
)

private fun h(w: Float) = w * TILE_RATIO
/** A river's room at full size, in tiles: across (8 a row) and at the sides. A hand seldom has more discards a
 *  player; a longer river's tiles get smaller (River). */
private const val ACROSS = 16
private const val SIDE = 15
/** The gap between the two rivers across a wide table (nothing lies between them: the round and the wall are in the
 *  table's top-left corner, the screen's buttons in its top-right one), in tile heights. */
private const val MID_ROWS = 0.4f
/** A tall table's box between them (a line of the round and the wall, another of the streak), in tile heights. */
private const val BOX_ROWS = 1f
/** The rivers' tiles on a tall table: no smaller than this while the hand can give room. */
private const val MIN_RIVER = 26f

/** The rivers' tiles on a tall table whose others' strip has grown to show everyone's tiles: no smaller than this. */
const val SHOWN_RIVER = 20f
/** The rivers' tiles where the shown hands do not fit over rivers of SHOWN_RIVER even at 16 dp (a phone's review,
 *  short for its buttons under the table): smaller rivers rather than hands cut off. */
const val SHOWN_RIVER_MIN = 17f

/** The height left for the others' strip on a tall table `height` high when the rivers' tiles are `river` wide. */
fun tallTopRoom(s: TableSizes, height: Float, river: Float) = height - s.meH - 4 - ((2 * s.acrossRows + BOX_ROWS) * h(river) + 14) - SHOWN_GAP

/** The room kept between that strip and the rivers under it, so that a shown hand is not taken for a river. */
const val SHOWN_GAP = 14f

/** A wide table lower than this (a phone held sideways) says the others' concealed tiles as a count on their name
 *  plates, as a tall one does: rows of backs took the side players' room from their melds. */
private const val LOW = 500f

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
    val meld = hand * 0.7f
    val opp = max(22f, min(hand * 0.66f, if (height >= 900) 44f else 36f))
    // The player's melds and flowers in a line over the hand, at its left (the user: over the hand and at the left,
    // whatever the screen); the hand under it.
    val meldLine = max(30f, h(meld))
    val meH = meldLine + 4 + h(hand) * (1 + LIFT) + 10
    val topH = h(opp) + 12
    val centerH = height - meH - topH - 6
    val sideMin = max(opp * 3.4f + 16, 128f)
    data class Best(val river: Float, val acrossCols: Int, val acrossRows: Int, val sideCols: Int)
    var best: Best? = null
    // The rivers: the largest tile for which a layout fits, rivers across of 2 rows of 8 (3 rows of 7 when there is
    // room) and side rivers of 3 to 5 columns (ACROSS and SIDE tiles at full size), a gap of MID_ROWS between the two
    // across.
    var river = min(hand * 0.9f, if (height >= 900) 66f else 56f)
    while (river >= 12 && best == null) {
      for (acrossRows in listOf(3, 2)) {
        if ((2 * acrossRows + MID_ROWS) * h(river) + 14 > centerH) continue
        val sideRows = floor((centerH - 4) / h(river)).toInt()
        val acrossCols = max(7, ceil(ACROSS.toFloat() / acrossRows).toInt())
        val sideCols = listOf(3, 4, 5).firstOrNull { it * sideRows >= SIDE } ?: continue
        if ((acrossCols + 2 * sideCols) * river + 28 > w - 2 * sideMin) continue
        best = Best(river, acrossCols, acrossRows, sideCols)
        break
      }
      river -= 0.5f
    }
    val b = best ?: Best(12f, 8, 2, 4)
    val riversW = (b.acrossCols + 2 * b.sideCols) * b.river + 28
    val handRowW = (17 + DRAWN_GAP + 0.3f) * hand + 24
    val tableW = min(w, max(handRowW, riversW + 2 * max(sideMin, opp * 4.6f + 16)))
    val sideW = max(sideMin, (tableW - riversW) / 2)
    val sideRows = max(4, floor((centerH - 4) / h(b.river)).toInt())
    return TableSizes(
      wide, hand, 0, meld, b.river, opp, if (height < LOW) 0f else max(10f, opp * 0.66f), b.acrossCols, b.acrossRows, b.sideCols, sideRows, sideW, topH, meH,
      0f, MID_ROWS * h(b.river), tableW, max(14f, min(18f, opp * 0.46f)), meldLine = meldLine,
    )
  }
  // Tall (a phone upright): the others along the top, each in a column over their river; the rivers the whole width;
  // the player's melds and flowers (at the left) and name plate (at the right) in a line over the hand, which wraps
  // after HAND_ROW tiles.
  // The rivers: 3 columns at the sides, 5 across in 3 rows; as wide as the table allows, unless its height says less.
  val sideCols = 3
  val acrossCols = 5
  val acrossRows = 3
  data class Fit(val hand: Float, val meld: Float, val opp: Float, val meH: Float, val topH: Float, val centerH: Float, val river: Float,
    val meldLine: Float)
  fun tall(hand: Float, more: Float = 0f): Fit {
    val meld = min(hand * 0.72f, 40f)
    val opp = max(22f, min(hand * 0.6f, 32f))
    val meldLine = max(30f, h(meld))
    val meH = meldLine + 4 + h(hand) * (2 + ROW_GAP + LIFT) + 6
    // The others' name plates in two lines (TableView's SeatBadge: the name over the tiles held and the score), the
    // column's padding (4 above and below) and the gap under the plate (4).
    val own = 40 + 12 + OPP_LINES * h(opp) + (OPP_LINES - 1) * meldGaps(opp).row
    val topH = max(more, own)
    // A strip grown for shown hands keeps SHOWN_GAP clear of the rivers (they are centered in what is left).
    val centerH = height - meH - topH - 4 - (if (more > own) SHOWN_GAP else 0f)
    var river = min(hand * 0.9f, (w - 24) / (acrossCols + 2 * sideCols))
    while (river > 16 && (2 * acrossRows + BOX_ROWS) * h(river) + 14 > centerH) river -= 0.5f
    return Fit(hand, meld, opp, meH, topH, centerH, river, meldLine)
  }
  var fit = tall(max(30f, min(maxHand, (w - 10) / (HAND_ROW + DRAWN_GAP + 1))))
  while (fit.river < MIN_RIVER && fit.hand > 30) fit = tall(max(30f, fit.hand - 1))
  // A higher strip takes its room from the rivers alone: the hand stays as it was.
  if (top > fit.topH) fit = tall(fit.hand, top)
  val sideRows = max(4, floor((fit.centerH - 4) / h(fit.river)).toInt())
  val mid = max(h(fit.river) * BOX_ROWS, min(h(fit.river) * 2, fit.centerH - 2 * acrossRows * h(fit.river) - 14))
  return TableSizes(
    wide, fit.hand, HAND_ROW, fit.meld, fit.river, fit.opp, 0f, acrossCols, acrossRows, sideCols, sideRows, (w - 8) / 3, fit.topH,
    fit.meH, acrossCols * fit.river, mid, w, 14f, meldLine = fit.meldLine,
  )
}
