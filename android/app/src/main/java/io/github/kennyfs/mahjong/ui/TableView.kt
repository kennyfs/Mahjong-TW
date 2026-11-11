package io.github.kennyfs.mahjong.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kennyfs.mahjong.Action
import io.github.kennyfs.mahjong.Discard
import io.github.kennyfs.mahjong.Meld
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Side
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.View
import io.github.kennyfs.mahjong.concealedCount
import io.github.kennyfs.mahjong.flowersOf
import io.github.kennyfs.mahjong.meldTiles
import io.github.kennyfs.mahjong.seatAt
import io.github.kennyfs.mahjong.sideOf
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import io.github.kennyfs.mahjong.TileMark
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Float.d: Dp get() = this.dp

/** A few words on a name plate (a review): how likely the player is ready, or what they held (level 0 to 3). */
data class SeatNote(val text: String, val level: Int)

/** The room kept over each row of the hand for the tiles' tags, when there may be some. */
private const val TAG_H = 17f

/** What another seat shows: its melds' tile counts, its flowers, and its concealed tiles when they are shown. */
private class Shown(val counts: List<Int>, val flowers: Int, val concealed: Int)

/**
 * The table as one seat sees it (a seat's `view`): the player at the bottom with their hand, the others around, the
 * rivers in the middle. A wide table has the round, the wall and the dealer's streak in its top-left corner and the
 * screen's `corner` (its buttons) in its top-right one, nothing between the rivers; a tall one has them in a box
 * between the rivers. Sizes from TableLayout (the web page's Table.svelte, the same arithmetic).
 *
 * The player's turn to discard: the hand in a pulsing frame of the turn's color, and with no tile drawn (the dealer's
 * first discard, after a call) its last tile set apart as a drawn one is. A discard the player may claim stands out in
 * its river (larger, pulsing), and the claim buttons show their tiles large, the claimed one lit. A wide table's own
 * flowers lie over the hand, above the player's name plate (in the hand's row they took its room: a drawn tile was
 * squeezed small).
 *
 * `revealed`: every seat's concealed tiles (a finished hand, a replay showing all). The others' then lie on a rack (a
 * lighter strip) after their melds, their tiles as large as their room allows: the player opposite in one line along
 * the top of a wide table; on a tall one the others' strip grows for them (the rivers give the room).
 * `status`: beside the player's name plate (a battle's clock); `overlay`: over the table.
 * A review's: `marks` (tags and danger on the hand's tiles, Review.kt), `notes` (a few words on each name plate),
 * `strip` (a line over the hand: the options that are not discards, a tile's figures), `onPick` (the tile tapped).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TableView(
  view: View, me: Int, names: List<String>, modifier: Modifier = Modifier, scores: List<Double>? = null, revealed: List<String>? = null,
  interactive: Boolean = true, onAct: ((Action) -> Unit)? = null, handLabel: String = "", maxHand: Float = 64f,
  away: List<Boolean> = listOf(false, false, false, false),
  status: (@Composable () -> Unit)? = null, corner: (@Composable () -> Unit)? = null, overlay: (@Composable BoxScope.() -> Unit)? = null,
  marks: Map<String, TileMark>? = null, notes: List<SeatNote?>? = null, strip: (@Composable () -> Unit)? = null,
  onPick: ((String?) -> Unit)? = null,
) {
  val c = LocalTable.current
  val density = LocalDensity.current
  BoxWithConstraints(modifier.fillMaxSize().background(c.felt)) {
    val roomW = maxWidth.value
    val roomH = maxHeight.value
    val pos = view.position
    val setup = pos.setup
    val turnSeat = if (view.phase == "over") -1 else view.current
    val winds = view.seatWinds
    // The name plates as they are laid out, in dp (a long note takes a second line); BOTTOM: the player's corner.
    var plates by remember { mutableStateOf(mapOf<Side, Pair<Float, Float>>()) }
    fun measured(side: Side) = Modifier.onSizeChanged { size ->
      val v = size.width / density.density to size.height / density.density
      if (plates[side] != v) plates = plates + (side to v)
    }
    fun shownOf(seat: Int): Shown {
      val p = pos.players[seat]
      return Shown(p.melds.map { meldTiles(it).size }, flowersOf(p).size, if (revealed != null) Tiles.parse(revealed[seat]).size else 0)
    }
    val tagH = if (marks != null) TAG_H else 0f

    // The sizes. The tags' room comes off the table's height: what the hand's own lift does not already leave them.
    // A tall table's others' strip with everyone's tiles shown: as high as the three need at the largest tiles (20 dp
    // and up) that leave the rivers tiles of SHOWN_RIVER.
    var s0 = tableSizes(roomW, roomH, maxHand)
    var extra = 0f
    if (tagH > 0) {
      extra = (if (s0.wide) 1 else 2) * maxOf(0f, tagH - s0.hand * TILE_RATIO * LIFT)
      if (extra > 0) s0 = tableSizes(roomW, roomH - extra, maxHand)
    }
    if (revealed != null && !s0.wide) {
      val room = tallTopRoom(s0, roomH - extra, SHOWN_RIVER)
      var need = 0f
      for (side in listOf(Side.RIGHT, Side.TOP, Side.LEFT)) {
        val o = shownOf(seatAt(side, me))
        val plateH = plates[side]?.second ?: 40f
        val w = fitShown(o.counts, o.flowers, o.concealed, s0.sideW - 9, room - plateH - 12, s0.opp, 20f)
        need = maxOf(need, plateH + 12 + shownHeight(o.counts, o.flowers, o.concealed, s0.sideW - 9, w))
      }
      val top = minOf(room, need)
      if (top > s0.topH) s0 = tableSizes(roomW, roomH - extra, maxHand, top)
    }
    val s = s0
    val meH = s.meH + extra
    fun plateH(side: Side) = plates[side]?.second ?: if (s.wide) 30f else 40f
    // A wide table's corners: the round, the wall and the streak at the top-left; the screen's buttons at the
    // top-right. The player opposite keeps between them; the side players start below them where they reach under
    // the top row.
    var infoSize by remember { mutableStateOf(0f to 0f) }
    var cornerSize by remember { mutableStateOf(0f to 0f) }
    val padL = if (s.wide) infoSize.first + 10 else 0f
    val padR = if (s.wide && corner != null) cornerSize.first + 10 else 0f
    val belowL = if (s.wide) maxOf(0f, infoSize.second + 8 - s.topH) else 0f
    val belowR = if (s.wide && corner != null) maxOf(0f, cornerSize.second + 8 - s.topH) else 0f
    /** The player's own name plate (with their flowers over it, and a battle's clock) lies over the foot of the left
     *  column of a wide table: the column ends above it. The right one ends as high as the plate alone would be. */
    val cornerR = if (s.wide) s.font * density.fontScale * 1.7f + 12 else 0f
    val cornerH = if (s.wide) maxOf(cornerR, (plates[Side.BOTTOM]?.second ?: 0f) + 8) else 0f
    /** Whole pixels, so that a line of tiles is as wide as counted. */
    fun snap(w: Float) = floor(w * density.density) / density.density

    // The others' tiles. Tall: each one's melds and flowers in OPP_LINES lines of their column, smaller when needed.
    // With everyone's tiles shown: as large as fits each one's room with the concealed tiles too: the player opposite
    // in one line along a wide table's top (the corner kept clear and the name plate off its width), the others in
    // their columns under their name plates.
    fun oppW(side: Side, seat: Int): Float {
      val o = shownOf(seat)
      if (!s.wide) {
        if (revealed == null) return fitMelds(o.counts, o.flowers, s.sideW - 8, OPP_LINES, s.opp)
        return fitShown(o.counts, o.flowers, o.concealed, s.sideW - 9, s.topH - plateH(side) - 12, s.opp)
      }
      // Wide: the concealed tiles' backs, when they are not shown, take their room first.
      val backs = if (revealed != null) 0 else concealedCount(view, seat)
      if (side == Side.TOP) {
        val room = minOf(roomW, s.tableW) - 12 - padL - padR - (plates[Side.TOP]?.first ?: 150f) - 14 - (if (backs > 0) backs * s.back + 10 else 0f)
        return fitShownRow(o.counts, o.flowers, o.concealed, room, if (revealed != null) s.opp - 1.5f else s.opp)
      }
      val width = s.sideW - 13
      val backsH = if (backs > 0) runLines(backs, width, s.back) * s.back * TILE_RATIO + 4 else 0f
      val taken = if (side == Side.LEFT) cornerH + belowL else cornerR + belowR
      return fitShown(o.counts, o.flowers, o.concealed, width, roomH - s.topH - meH - taken - plateH(side) - 12 - backsH, s.opp)
    }

    val lastDiscard = remember(view.events) {
      var seat = -1
      for (e in view.events.asReversed()) {
        if (e.type == "discard") { seat = e.seat; break }
        if (e.type in setOf("chi", "pon", "kan")) break
      }
      seat
    }
    // The player's tiles: the drawn one apart. With none drawn on the player's turn to discard (the dealer's first
    // discard, after a call), the last one apart as a drawn one is: the hand says whose turn it is.
    val all = if (revealed != null) Tiles.parse(revealed[me]) else Tiles.parse(pos.hand)
    val reallyDrawn = if (revealed == null && pos.drawn != null && view.phase == "self" && view.current == me) pos.drawn else null
    val sorted = Tiles.sort(if (reallyDrawn != null) Tiles.without(all, listOf(reallyDrawn)) else all)
    val apart = revealed == null && reallyDrawn == null && view.phase == "self" && view.current == me && sorted.size % 3 == 2
    val mine = if (apart) sorted.dropLast(1) else sorted
    val drawn = reallyDrawn ?: if (apart) sorted.last() else null
    val discardable = if (interactive && view.myTurn) view.legal.filter { it.type == "discard" }.mapNotNull { it.tile }.toSet() else emptySet()
    val claims = if (interactive && view.myTurn) view.legal.filter { it.type != "discard" } else emptyList()
    /** The player's turn to discard: the hand's frame pulses. */
    val myTurn = interactive && view.myTurn && discardable.isNotEmpty()
    /** The tile the player may claim (a discard, or a kong's added tile), while they may: it stands out. */
    val claimTile = if (claims.any { it.type != "pass" } && view.phase != "self") pos.claim?.tile else null
    val claimSeat = if (claimTile != null && view.phase == "react") pos.claim?.seat ?: -1 else -1
    val myFlowers = flowersOf(pos.players[me])
    /** The player's melds: a wide table's in the hand's row, smaller when they would take the hand's room (kongs); a
     *  tall one's in the line over the hand beside the name plate, smaller rather than in a second line. */
    val myMeldW = run {
      val counts = pos.players[me].melds.map { meldTiles(it).size }
      if (counts.isEmpty()) s.meld
      else if (s.wide) fitShownRow(counts, 0, 0, minOf(roomW, s.tableW) - 28 - (mine.size + 1 + DRAWN_GAP) * s.hand, s.meld)
      else fitMelds(counts, myFlowers.size, minOf(roomW, s.tableW) - 16 - (plates[Side.BOTTOM]?.first ?: 90f), 1, s.meld, 22f)
    }
    var selected by remember(view.eventCount) { mutableStateOf<Int?>(null) }
    if (onPick != null) LaunchedEffect(selected, view.eventCount) { onPick(selected?.let { i -> mine.getOrNull(i) ?: drawn }) }

    @Composable
    fun badge(seat: Int, count: Int?) = SeatBadge(
      Tiles.wind(winds[seat]), names[seat], scores?.get(seat), setup.dealer == seat, setup.streak, turnSeat == seat, count,
      compact = !s.wide, away = away[seat], font = s.font, note = if (seat == me) null else notes?.getOrNull(seat),
    )

    @Composable
    fun seat(side: Side, mod: Modifier) {
      val seat = seatAt(side, me)
      val p = pos.players[seat]
      val hidden = concealedCount(view, seat)
      val ow = snap(oppW(side, seat))
      val shown = revealed?.get(seat)?.let { Tiles.sort(Tiles.parse(it)) }
      val count = if (s.back == 0f && revealed == null) hidden else null
      if (s.wide && side == Side.TOP) {
        Row(mod.padding(start = (6 + padL).d, end = (6 + padR).d, top = 4.dp, bottom = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
          Box(measured(side)) { badge(seat, count) }
          Row(horizontalArrangement = Arrangement.spacedBy(RACK_GAP_ROW.d), verticalAlignment = Alignment.Bottom) {
            Melds(p.melds, flowersOf(p), ow, wrap = false)
            if (shown != null) Rack(shown, ow, wrap = false)
            else if (s.back > 0) Row { repeat(hidden) { TileView(null, s.back.d) } }
          }
        }
        return
      }
      val end = s.wide && side == Side.RIGHT
      Column(
        if (s.wide) mod.padding(start = 6.dp, end = 6.dp, top = (4 + if (end) belowR else belowL).d, bottom = (4 + if (end) cornerR else cornerH).d)
        else mod.padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = if (!s.wide) Alignment.CenterHorizontally else if (end) Alignment.End else Alignment.Start,
        verticalArrangement = if (s.wide) Arrangement.spacedBy(4.dp, Alignment.CenterVertically) else Arrangement.spacedBy(4.dp),
      ) {
        Box(measured(side)) { badge(seat, count) }
        Melds(p.melds, flowersOf(p), ow, wrap = true,
          arrangement = if (!s.wide) Arrangement.Center else if (end) Arrangement.End else Arrangement.Start)
        if (shown != null) Rack(shown, ow, wrap = true, end = end)
        else if (s.back > 0) FlowRow(horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) { repeat(hidden) { TileView(null, s.back.d) } }
      }
    }

    @Composable
    fun river(side: Side, rows: Int, cols: Int) {
      val seat = seatAt(side, me)
      River(pos.players[seat].discards, s.river, cols, rows, lastDiscard == seat, claim = claimSeat == seat)
    }

    @Composable
    fun center(mod: Modifier) {
      Box(mod, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Box(Modifier.width((s.sideCols * s.river).d), contentAlignment = Alignment.CenterEnd) { river(Side.LEFT, s.sideRows, s.sideCols) }
          // A wide table: nothing between the two rivers across but a gap. A tall one: the box.
          if (s.wide) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(s.mid.d)) {
            river(Side.TOP, s.acrossRows, s.acrossCols)
            river(Side.BOTTOM, s.acrossRows, s.acrossCols)
          } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            river(Side.TOP, s.acrossRows, s.acrossCols)
            Box(
              Modifier.width(s.boxW.d).heightIn(min = s.mid.d).background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
              contentAlignment = Alignment.Center,
            ) {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.round_wind, Tiles.wind(setup.round)) + if (handLabel.isNotEmpty()) " $handLabel" else "",
                  color = c.onFelt, fontWeight = FontWeight.Bold, fontSize = (s.font * 1.08f).sp)
                Text(stringResource(R.string.wall_left, pos.wallLeft), color = c.onFeltDim, fontSize = s.font.sp)
                if (setup.streak > 0) Text("${names[setup.dealer]} " + stringResource(R.string.streak_n, setup.streak), color = c.highlight,
                  fontWeight = FontWeight.Bold, fontSize = (s.font * 0.92f).sp)
                corner?.invoke()
              }
            }
            river(Side.BOTTOM, s.acrossRows, s.acrossCols)
          }
          Box(Modifier.width((s.sideCols * s.river).d), contentAlignment = Alignment.CenterStart) { river(Side.RIGHT, s.sideRows, s.sideCols) }
        }
      }
    }

    val discard = { t: String -> onAct?.invoke(Action("discard", t)); Unit }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Box(Modifier.fillMaxHeight().width(minOf(roomW, s.tableW).d)) {
        if (s.wide) {
          // The player opposite along the whole top (the screen's corner button, if any, before them), the other two
          // beside the rivers.
          Column(Modifier.fillMaxSize()) {
            seat(Side.TOP, Modifier.height(s.topH.d).fillMaxWidth())
            Row(Modifier.weight(1f).fillMaxWidth()) {
              seat(Side.LEFT, Modifier.width(s.sideW.d).fillMaxHeight())
              center(Modifier.weight(1f).fillMaxHeight())
              seat(Side.RIGHT, Modifier.width(s.sideW.d).fillMaxHeight())
            }
            Row(Modifier.height(meH.d).fillMaxWidth().tourAnchor("hand").padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
              Melds(pos.players[me].melds, emptyList(), snap(myMeldW), wrap = false)
              MyHand(mine, drawn, s.hand, 0, discardable, discard, selected, { selected = it }, marks, tagH, turn = myTurn, apart = apart)
            }
          }
          Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = (meH + 2).d).widthIn(max = (s.sideW - 12).d).then(measured(Side.BOTTOM)),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            status?.invoke()
            if (myFlowers.isNotEmpty()) Box(Modifier.padding(start = 4.dp)) { Melds(emptyList(), myFlowers, s.opp, wrap = true) }
            badge(me, null)
          }
          // The corners: the round, the wall and the streak; the screen's buttons.
          Column(
            // As wide as its first line needs (a round's label on one line, as the page's): the opposite seat moves aside.
            Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp).widthIn(max = maxOf(s.sideW - 12, s.font * 12).d)
              .onSizeChanged { val v = it.width / density.density to it.height / density.density; if (infoSize != v) infoSize = v }
              .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
          ) {
            Text(stringResource(R.string.round_wind, Tiles.wind(setup.round)) + if (handLabel.isNotEmpty()) " $handLabel" else "",
              color = c.onFelt, fontWeight = FontWeight.Bold, fontSize = s.font.sp, lineHeight = (s.font * 1.25f).sp)
            Text(stringResource(R.string.wall_left, pos.wallLeft), color = c.onFeltDim, fontSize = (s.font * 0.92f).sp, lineHeight = (s.font * 1.2f).sp)
            if (setup.streak > 0) Text("${names[setup.dealer]} " + stringResource(R.string.streak_n, setup.streak), color = c.highlight,
              fontWeight = FontWeight.Bold, fontSize = (s.font * 0.85f).sp, lineHeight = (s.font * 1.15f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          if (corner != null) Column(
            Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 4.dp).widthIn(max = (s.sideW - 12).d)
              .onSizeChanged { val v = it.width / density.density to it.height / density.density; if (cornerSize != v) cornerSize = v },
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp),
          ) { corner() }
        } else {
          Column(Modifier.fillMaxSize()) {
            Row(Modifier.height(s.topH.d).fillMaxWidth()) {
              seat(Side.LEFT, Modifier.weight(1f).fillMaxHeight())
              seat(Side.TOP, Modifier.weight(1f).fillMaxHeight())
              seat(Side.RIGHT, Modifier.weight(1f).fillMaxHeight())
            }
            center(Modifier.weight(1f).fillMaxWidth())
            Column(Modifier.height(meH.d).fillMaxWidth().tourAnchor("hand").padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
              horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.align(Alignment.CenterVertically).then(measured(Side.BOTTOM))) { badge(me, null) }
                Melds(pos.players[me].melds, myFlowers, snap(myMeldW), wrap = true)
              }
              MyHand(mine, drawn, s.hand, s.handRow, discardable, discard, selected, { selected = it }, marks, tagH, turn = myTurn, apart = apart)
            }
          }
          if (status != null) Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = (meH + 4).d)) { status() }
        }
        if (claims.isNotEmpty() && onAct != null) {
          Box(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, start = 8.dp, bottom = (meH + 4).d)) {
            ClaimBar(claims, s.hand, onAct, claimTile)
          }
        }
        if (strip != null) Column(Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = (meH + 4).d),
          horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { strip() }
        Callouts(view, me, s)
      }
      overlay?.invoke(this)
    }
  }
}

/** A hand shown: on a rack (a lighter strip), to tell it from the melds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Rack(tiles: List<String>, w: Float, wrap: Boolean, end: Boolean = false) {
  if (tiles.isEmpty()) return
  val mod = Modifier.background(Color.White.copy(alpha = 0.17f), RoundedCornerShape(6.dp)).padding(RACK_PAD.d)
  if (wrap) FlowRow(mod, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) { for (t in tiles) TileView(t, w.d) }
  else Row(mod) { for (t in tiles) TileView(t, w.d) }
}

/**
 * A player's name plate: their seat wind (lit for the dealer, with the streak), name, tiles held, score. `note` (a
 * review): a few words in place of the count; it goes to a second line where the plate has no room for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeatBadge(
  wind: String, name: String, score: Double?, dealer: Boolean, streak: Int, turn: Boolean, count: Int?, compact: Boolean = false,
  away: Boolean = false, font: Float = 16f, note: SeatNote? = null,
) {
  val c = LocalTable.current
  val f = (if (compact) font * 0.86f else font).sp
  Row(
    Modifier.alpha(if (away) 0.6f else 1f)
      .background(Color.Black.copy(alpha = if (turn) 0.28f else 0.16f), RoundedCornerShape(16.dp))
      .border(2.dp, if (turn) c.highlight else Color.Transparent, RoundedCornerShape(16.dp))
      .padding(start = 2.dp, end = if (compact) 6.dp else 8.dp, top = 2.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
  ) {
    val d = with(LocalDensity.current) { (f.toDp() * 1.7f) }
    Box(Modifier.size(d).background(if (dealer) c.highlight else c.onFelt, CircleShape), contentAlignment = Alignment.Center) {
      Text(wind, color = if (dealer) Color(0xFF3A2A00) else c.felt, fontWeight = FontWeight.Bold, fontSize = f)
    }
    val nameLine: @Composable RowScope.() -> Unit = {
      Text(name, color = c.onFelt, fontWeight = FontWeight.Medium, fontSize = f, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false))
      if (dealer) Text(stringResource(R.string.dealer_mark) + if (streak > 0) stringResource(R.string.streak_n, streak) else "",
        color = c.highlight, fontWeight = FontWeight.Bold, fontSize = f, maxLines = 1)
    }
    val figures: @Composable RowScope.() -> Unit = {
      if (note != null) Text(
        note.text, color = if (note.level > 0) Marks.on(note.level) else c.onFelt, fontWeight = FontWeight.Bold, fontSize = f, maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false).background(if (note.level > 0) Marks.danger(note.level) else Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
          .padding(horizontal = 6.dp),
      ) else if (count != null) Text(stringResource(R.string.hand_cards, count), color = c.onFeltDim, fontSize = f, maxLines = 1)
      if (score != null) Text(signed(score), color = if (score > 0) c.good else if (score < 0) c.bad else c.onFelt, fontWeight = FontWeight.Bold,
        fontSize = f, maxLines = 1)
    }
    val gap = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
    // A third of an upright phone's width: the name over the figures (a name of a battle's player has room then).
    if (compact && (count != null || note != null)) Column(Modifier.weight(1f, fill = false)) {
      Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { nameLine() }
      Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { figures() }
    } else {
      // One line, or the figures under the name when the seat's column is too narrow for both (a dealer's long name
      // at a large font): the name stays whole.
      FlowRow(Modifier.weight(1f, fill = false), horizontalArrangement = gap, itemVerticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { nameLine() }
        if (note != null || count != null || score != null) Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { figures() }
      }
    }
  }
}

/** "+3", "−1.5", "±0". */
fun signed(x: Double): String {
  val a = kotlin.math.abs(x)
  val n = if (a == a.roundToInt().toDouble()) a.roundToInt().toString() else "%.1f".format(a)
  return (if (x > 0) "+" else if (x < 0) "−" else "±") + n
}

/**
 * A player's melds, each a group of tiles, and their flowers after them (each on its own, so a line breaks between).
 * A chow's taken tile lies in the middle (meldTiles), tinted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Melds(melds: List<Meld>, flowers: List<String>, w: Float, wrap: Boolean, arrangement: Arrangement.Horizontal = Arrangement.Start) {
  if (melds.isEmpty() && flowers.isEmpty()) return
  val g = meldGaps(w)
  val content: @Composable () -> Unit = {
    for (m in melds) Row { for ((k, t) in meldTiles(m).withIndex()) TileView(t, w.d, mark = m.type == "chi" && m.called != null && k == 1) }
    for (f in flowers) TileView(f, (w * FLOWER_SCALE).d)
  }
  if (wrap) FlowRow(horizontalArrangement = Arrangement.spacedBy(g.col.d, if (arrangement == Arrangement.End) Alignment.End else if (arrangement == Arrangement.Center) Alignment.CenterHorizontally else Alignment.Start),
    verticalArrangement = Arrangement.spacedBy(g.row.d), itemVerticalAlignment = Alignment.Bottom) { content() }
  else Row(horizontalArrangement = Arrangement.spacedBy(g.col.d), verticalAlignment = Alignment.Bottom) { content() }
}

/**
 * One player's discards in order, `cols` a row; the tiles taken by a meld faded, the last discard lit. `claim`: the
 * player may claim the last discard: it stands out, larger and pulsing.
 */
@Composable
fun River(discards: List<Discard>, w: Float, cols: Int, rows: Int, last: Boolean, claim: Boolean = false) {
  val fit = if (rows > 0 && discards.size > cols * rows) ceil(discards.size.toFloat() / rows).toInt() else cols
  val tw = w * cols / fit
  val pulse = if (claim) pulse(900) else 0f
  Column(Modifier.width((cols * w).d)) {
    for (r in discards.indices step fit) {
      val end = r + fit >= discards.size
      Row(if (claim && end) Modifier.zIndex(1f) else Modifier) {
        for (i in r until minOf(r + fit, discards.size)) {
          val d = discards[i]
          val out = claim && i == discards.size - 1 && !d.called
          Box(if (out) Modifier.zIndex(1f).scale(1.25f + 0.15f * pulse) else Modifier) {
            TileView(d.tile, tw.d, dim = d.called, glow = (last || out) && i == discards.size - 1 && !d.called)
          }
        }
      }
    }
  }
}

/** A hand's end: how long the table shows how it ended (the hands, the words of the win) before the result, ms. */
const val END_PAUSE = 2200L

/**
 * A hand's end: whether the table is still to show how it ended (every hand shown, the words of the win, 放槍 over who
 * dealt in) before the result: for END_PAUSE after `over` turns true here, not for a result come back to. The second
 * value lets go at once (a tap on the table: SkipPause).
 */
@Composable
fun endPause(over: Boolean): Pair<Boolean, () -> Unit> {
  var held by remember { mutableStateOf(false) }
  val inPlay = remember { booleanArrayOf(false) }
  LaunchedEffect(over) {
    if (!over) {
      inPlay[0] = true
      held = false
    } else if (inPlay[0]) {
      held = true
      inPlay[0] = false
      delay(END_PAUSE)
      held = false
    }
  }
  return (over && (held || inPlay[0])) to { held = false }
}

/** Over the table during the pause at a hand's end: a tap goes on to the result. */
@Composable
fun BoxScope.SkipPause(onSkip: () -> Unit) =
  Box(Modifier.matchParentSize().testTag("result_skip").pointerInput(Unit) { detectTapGestures { onSkip() } })

/** A value going between 0 and 1 and back, `ms` each way: what pulses (the turn's frame, a tile to claim). */
@Composable
fun pulse(ms: Int): Float {
  val t = rememberInfiniteTransition(label = "pulse")
  val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
  return v
}

/**
 * The player's concealed tiles, the drawn one apart at the end. On the player's turn a tap lifts a tile and a second
 * tap discards it; a tile dragged upwards is discarded when let go. `perRow`: tiles a row (an upright phone), 0 one row.
 * `turn`: the player's turn to discard: a frame of the turn's color pulses around the tiles. `apart`: the tile set
 * apart (`drawn`) was not drawn (the dealer's first discard, after a call).
 */
@Composable
fun MyHand(
  tiles: List<String>, drawn: String?, w: Float, perRow: Int, discardable: Set<String>, onDiscard: (String) -> Unit,
  selected: Int?, onSelect: (Int?) -> Unit, marks: Map<String, TileMark>? = null, tagH: Float = 0f, turn: Boolean = false,
  apart: Boolean = false,
) {
  val highlight = LocalTable.current.highlight
  val glow = if (turn) pulse(1100) else 0f
  val all = if (drawn != null) tiles + drawn else tiles
  val h = w * TILE_RATIO
  val size = if (perRow > 0) perRow else maxOf(1, tiles.size)
  val rows = ArrayList<MutableList<Int>>()
  for (i in tiles.indices step size) rows += (i until minOf(i + size, tiles.size)).toMutableList()
  if (rows.isEmpty()) rows += mutableListOf<Int>()
  // A seventeenth tile not drawn (the dealer's at the deal) ends the second row, as a drawn tile would: two rows.
  if (perRow > 0 && drawn == null && rows.size > 1 && rows.last().size == 1) rows[rows.size - 2] += rows.removeAt(rows.size - 1)
  if (drawn != null) rows.last() += tiles.size
  var dragging by remember { mutableIntStateOf(-1) }
  var dy by remember { mutableStateOf(0f) }
  val density = LocalDensity.current
  fun tap(i: Int) {
    val t = all[i]
    if (t !in discardable) return onSelect(if (selected == i) null else i)
    if (selected == i) {
      onSelect(null)
      onDiscard(t)
    } else onSelect(i)
  }
  Column(
    Modifier.padding(top = maxOf(h * LIFT, tagH).d).let { if (perRow > 0) it.width(((perRow + DRAWN_GAP + 1) * w).d) else it }
      // The turn's frame, around the tiles (not the room over them for a lifted tile): a line and its glow.
      .then(if (turn) Modifier.drawBehind {
        val out = 5.dp.toPx()
        val corner = CornerRadius(10.dp.toPx())
        val at = Offset(-out, -out)
        val area = this.size
        val box = Size(area.width + 2 * out, area.height + 2 * out)
        for (k in 3 downTo 1) drawRoundRect(highlight.copy(alpha = 0.10f * glow), at - Offset(k * 2.dp.toPx(), k * 2.dp.toPx()),
          Size(box.width + k * 4.dp.toPx(), box.height + k * 4.dp.toPx()), CornerRadius(10.dp.toPx() + k * 2.dp.toPx()), Stroke(4.dp.toPx()))
        drawRoundRect(highlight.copy(alpha = 0.45f + 0.55f * glow), at, box, corner, Stroke(3.dp.toPx()))
      } else Modifier),
    verticalArrangement = Arrangement.spacedBy(maxOf(h * ROW_GAP, tagH).d),
  ) {
    for (row in rows) {
      Row(Modifier.height(h.d), verticalAlignment = Alignment.Bottom) {
        for (i in row) {
          val isDrawn = drawn != null && i == tiles.size
          val lift = when {
            dragging == i -> dy
            selected == i -> -h * LIFT * density.density
            else -> 0f
          }
          val mark = marks?.get(all[i])
          // The touch target is the tile's own place, whether the tile is lifted or not, and while it is lifted the
          // room over it too: the second tap lands where the first did (a thumb at the tile's foot) or on the tile
          // where it now is. (A target that rose with the tile left its foot dead: "sometimes it will not discard".)
          val reach = if (selected == i) h * LIFT else 0f
          Box(
            Modifier
              .testTag(if (isDrawn) "hand_drawn" else "hand_$i")
              .semantics(mergeDescendants = true) {}
              .padding(start = if (isDrawn && row.size > 1) (w * DRAWN_GAP).d else 0.dp)
              .layout { m, c ->
                val up = reach.d.roundToPx()
                val p = m.measure(if (c.hasBoundedHeight) c.copy(maxHeight = c.maxHeight + up) else c)
                layout(p.width, p.height - up) { p.place(0, -up) }
              }
              .pointerInput(all[i], i, selected, discardable) { detectTapGestures { tap(i) } }
              .pointerInput(all[i], i, discardable) {
                detectVerticalDragGestures(
                  onDragStart = { dragging = i; dy = 0f },
                  onDragEnd = {
                    val t = all.getOrNull(i)
                    if (dy < -h * 0.45f * density.density && t != null && t in discardable) {
                      onSelect(null)
                      onDiscard(t)
                    }
                    dragging = -1
                    dy = 0f
                  },
                  onDragCancel = { dragging = -1; dy = 0f },
                ) { _, d -> dy = minOf(0f, dy + d) }
              }
              .padding(top = reach.d),
          ) {
            Box(Modifier.offset { IntOffset(0, lift.roundToInt()) }) {
              TileView(all[i], w.d, selected = selected == i, glow = mark?.played == true, danger = mark?.danger ?: 0)
              if (mark != null) TileTag(mark, w, if (tagH > 0) tagH else TAG_H, Modifier.align(Alignment.TopCenter))
            }
          }
        }
      }
    }
  }
}

/**
 * A tile's tag at a decision (a review): what discarding it costs against the best, over the tile (outside its own
 * room: the hand keeps `tagH` over each row). The tile played has a lit edge.
 */
@Composable
fun TileTag(mark: TileMark, w: Float, tagH: Float, modifier: Modifier = Modifier) {
  val c = LocalTable.current
  val bg = when (mark.tone) {
    TileMark.Tone.BEST -> Marks.found
    TileMark.Tone.G1 -> Marks.danger1
    TileMark.Tone.G2 -> Marks.danger2
    TileMark.Tone.G3 -> Marks.danger3
    TileMark.Tone.PLAIN -> Color.Black.copy(alpha = 0.42f)
  }
  val fg = if (mark.tone == TileMark.Tone.G1 || mark.tone == TileMark.Tone.G2) Marks.on(1) else Color.White
  // In dp, whatever the font scale: the tag has a fixed room.
  val size = with(LocalDensity.current) { (w * 0.27f).coerceIn(10f, 13f).dp.toSp() }
  Box(
    modifier
      .layout { m, _ ->
        val p = m.measure(Constraints())
        layout(0, 0) { p.place(-p.width / 2, -p.height - 1.dp.roundToPx()) }
      }
      .height((tagH - 2).d).background(bg, RoundedCornerShape(5.dp))
      .let { if (mark.played) it.border(2.dp, c.highlight, RoundedCornerShape(5.dp)) else it }.padding(horizontal = 3.dp),
    contentAlignment = Alignment.Center,
  ) {
    // The words' own line may be higher than the tag (Chinese characters' is): it is centered over the tag's edges
    // rather than held to the tag's height, where a line that does not fit is not drawn at all.
    Text(mark.tag, Modifier.wrapContentHeight(Alignment.CenterVertically, unbounded = true), color = fg, fontSize = size, lineHeight = size,
      fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
  }
}

private val ORDER = listOf("ron", "tsumo", "kan", "ankan", "kakan", "pon", "chi", "pass")

fun actionLabel(type: String) = when (type) {
  "ron" -> R.string.act_ron
  "tsumo" -> R.string.act_tsumo
  "kan" -> R.string.act_kan
  "ankan" -> R.string.act_ankan
  "kakan" -> R.string.act_kakan
  "pon" -> R.string.act_pon
  "chi" -> R.string.act_chi
  "discard" -> R.string.act_discard
  else -> R.string.act_pass
}

/**
 * The player's choices other than discarding: win, kongs, pung, each way of a chow (its three tiles), and pass. The
 * tiles are large (`w` is the hand's tile), and `claimed`, the discard (or added tile) being claimed, is lit in each.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClaimBar(actions: List<Action>, w: Float, onAct: (Action) -> Unit, claimed: String? = null) {
  val shown = actions.filter { it.type != "discard" }.sortedBy { ORDER.indexOf(it.type) }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val c = LocalTable.current
    for (a in shown) {
      val win = a.type == "ron" || a.type == "tsumo"
      val pass = a.type == "pass"
      Surface(
        onClick = { onAct(a) }, shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp, modifier = Modifier.testTag("claim_${a.type}"),
        color = when { win -> Color(0xFFD84315); pass -> MaterialTheme.colorScheme.surfaceContainerHighest; else -> c.action },
        contentColor = when { win -> Color.White; pass -> MaterialTheme.colorScheme.onSurface; else -> c.onAction },
      ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(actionLabel(a.type)), fontWeight = if (pass) FontWeight.Medium else FontWeight.Bold, fontSize = if (win) 20.sp else 18.sp)
          val ts = if (a.type == "chi") a.tiles ?: emptyList() else if (a.tile != null && a.type != "ron") listOf(a.tile) else emptyList()
          val at = if (claimed != null) ts.indexOf(claimed) else -1
          if (ts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            for ((k, t) in ts.withIndex()) TileView(t, (w * 0.8f).d, glow = k == at)
          }
        }
      }
    }
  }
}

private class Callout(val id: Int, val side: Side, val text: String, val big: Boolean)

/** Words over a player for a call, a win, a flower: from the events that came since the last view. */
@Composable
private fun BoxScope.Callouts(view: View, me: Int, s: TableSizes) {
  val list = remember { mutableStateListOf<Callout>() }
  var seen by remember { mutableIntStateOf(-1) }
  var next by remember { mutableIntStateOf(0) }
  val words = mapOf(
    "chi" to R.string.act_chi, "pon" to R.string.act_pon, "kan" to R.string.act_kan, "ankan" to R.string.act_ankan, "kakan" to R.string.act_kakan,
    "tsumo" to R.string.act_tsumo, "ron" to R.string.act_ron, "flower" to R.string.call_flower, "flower_win" to R.string.call_flower_win,
    "exhausted" to R.string.call_exhausted,
  )
  val texts = words.mapValues { stringResource(it.value) }
  val dealtIn = stringResource(R.string.call_deal_in)
  LaunchedEffect(view.eventCount) {
    val count = view.eventCount
    if (seen < 0 || count < seen) {
      seen = count
      return@LaunchedEffect
    }
    val fresh = view.events.takeLast(count - seen)
    seen = count
    // A jump over many events (a review going to another decision) says none of them.
    if (fresh.size > 8) return@LaunchedEffect
    val flowers = HashSet<Int>()
    var said = false
    for (e in fresh) {
      val text = texts[e.type] ?: continue
      if (e.type == "flower" && !flowers.add(e.seat)) continue
      list += Callout(next++, if (e.type == "exhausted") Side.TOP else sideOf(e.seat, me), text, e.type in setOf("tsumo", "ron", "flower_win"))
      // Who dealt in: the discard (or the added kong's tile) the win was on. Once, however many won on it.
      if (e.type == "ron" && !said) {
        said = true
        val from = view.result?.loser?.takeIf { it >= 0 } ?: view.events.lastOrNull { it.type == "discard" || it.type == "kakan" }?.seat ?: -1
        if (from >= 0 && from != e.seat) list += Callout(next++, sideOf(from, me), dealtIn, false)
      }
    }
  }
  for (co in list.toList()) {
    key(co.id) {
      val a = remember { Animatable(0f) }
      LaunchedEffect(Unit) {
        a.animateTo(1f, tween(if (co.big) 2000 else 900))
        list.remove(co)
      }
      val t = a.value
      val alpha = when { t < 0.15f -> t / 0.15f; t > 0.8f -> (1 - t) / 0.2f; else -> 1f }
      val scale = when { t < 0.15f -> 0.5f + t / 0.15f * 0.65f; t < 0.3f -> 1.15f - (t - 0.15f) / 0.15f * 0.15f; else -> 1f }
      val align = when (co.side) {
        Side.BOTTOM -> Alignment.BottomCenter
        Side.TOP -> Alignment.TopCenter
        Side.LEFT -> Alignment.CenterStart
        Side.RIGHT -> Alignment.CenterEnd
      }
      val pad = when (co.side) {
        Side.BOTTOM -> Modifier.padding(bottom = (s.meH + 10).d)
        Side.TOP -> Modifier.padding(top = (if (s.wide) s.topH + 6 else s.topH * 0.5f).d)
        Side.LEFT -> Modifier.padding(start = (if (s.wide) s.sideW * 0.5f else 16f).d)
        Side.RIGHT -> Modifier.padding(end = (if (s.wide) s.sideW * 0.5f else 16f).d)
      }
      Text(
        co.text, Modifier.align(align).then(pad).scale(scale).alpha(alpha),
        style = TextStyle(
          color = Color.White, fontWeight = FontWeight.Black, fontSize = if (co.big) 54.sp else 35.sp,
          shadow = Shadow(Color(0xFFB71C1C), blurRadius = 12f),
        ),
      )
    }
  }
}

@Composable
private inline fun key(id: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(id) { content() }

