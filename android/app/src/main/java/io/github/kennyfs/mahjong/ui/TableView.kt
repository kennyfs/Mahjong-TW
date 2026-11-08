package io.github.kennyfs.mahjong.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Float.d: Dp get() = this.dp

/**
 * The table as one seat sees it (a seat's `view`): the player at the bottom with their hand, the others around, the
 * rivers in the middle around a box with the round, the wall and the streak. Sizes from TableLayout (the web page's
 * Table.svelte, the same arithmetic). `revealed`: every seat's concealed tiles (a finished hand, a replay showing all).
 * `status`: beside the player's name plate (a battle's clock); `corner`: in the box (wide); `overlay`: over the table.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TableView(
  view: View, me: Int, names: List<String>, modifier: Modifier = Modifier, scores: List<Double>? = null, revealed: List<String>? = null,
  interactive: Boolean = true, onAct: ((Action) -> Unit)? = null, handLabel: String = "", maxHand: Float = 64f,
  away: List<Boolean> = listOf(false, false, false, false),
  status: (@Composable () -> Unit)? = null, corner: (@Composable () -> Unit)? = null, overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
  val c = LocalTable.current
  BoxWithConstraints(modifier.fillMaxSize().background(c.felt)) {
    val roomW = maxWidth.value
    val s = remember(maxWidth, maxHeight, maxHand) { tableSizes(maxWidth.value, maxHeight.value, maxHand) }
    val pos = view.position
    val setup = pos.setup
    val turnSeat = if (view.phase == "over") -1 else view.current
    val winds = view.seatWinds
    val lastDiscard = remember(view.events) {
      var seat = -1
      for (e in view.events.asReversed()) {
        if (e.type == "discard") { seat = e.seat; break }
        if (e.type in setOf("chi", "pon", "kan")) break
      }
      seat
    }
    // The player's tiles: the drawn one apart.
    val all = if (revealed != null) Tiles.parse(revealed[me]) else Tiles.parse(pos.hand)
    val drawn = if (revealed == null && pos.drawn != null && view.phase == "self" && view.current == me) pos.drawn else null
    val mine = Tiles.sort(if (drawn != null) Tiles.without(all, listOf(drawn)) else all)
    val discardable = if (interactive && view.myTurn) view.legal.filter { it.type == "discard" }.mapNotNull { it.tile }.toSet() else emptySet()
    val claims = if (interactive && view.myTurn) view.legal.filter { it.type != "discard" } else emptyList()
    var selected by remember(view.eventCount) { mutableStateOf<Int?>(null) }

    @Composable
    fun badge(seat: Int, count: Int?) = SeatBadge(
      Tiles.wind(winds[seat]), names[seat], scores?.get(seat), setup.dealer == seat, setup.streak, turnSeat == seat, count,
      compact = !s.wide, away = away[seat], font = s.font,
    )

    @Composable
    fun seat(side: Side, mod: Modifier) {
      val seat = seatAt(side, me)
      val p = pos.players[seat]
      val hidden = concealedCount(view, seat)
      val oppW = if (s.wide) s.opp else fitMelds(p.melds.map { meldTiles(it).size }, flowersOf(p).size, s.sideW - 8, OPP_LINES, s.opp)
      val align = when {
        !s.wide -> Alignment.CenterHorizontally
        side == Side.RIGHT -> Alignment.End
        else -> Alignment.Start
      }
      if (s.wide && side == Side.TOP) {
        Row(mod.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
          badge(seat, if (s.back == 0f && revealed == null) hidden else null)
          Melds(p.melds, flowersOf(p), oppW, wrap = false)
          Concealed(revealed?.get(seat), hidden, s, wrap = false)
        }
        return
      }
      Column(mod.padding(horizontal = 4.dp, vertical = 4.dp), horizontalAlignment = align,
        verticalArrangement = if (s.wide) Arrangement.spacedBy(4.dp, Alignment.CenterVertically) else Arrangement.spacedBy(4.dp)) {
        badge(seat, if (s.back == 0f && revealed == null) hidden else null)
        Melds(p.melds, flowersOf(p), oppW, wrap = true,
          arrangement = if (!s.wide) Arrangement.Center else if (side == Side.RIGHT) Arrangement.End else Arrangement.Start)
        Concealed(revealed?.get(seat), hidden, s, wrap = true, end = side == Side.RIGHT)
      }
    }

    @Composable
    fun river(side: Side, rows: Int, cols: Int) {
      val seat = seatAt(side, me)
      River(pos.players[seat].discards, s.river, cols, rows, lastDiscard == seat)
    }

    @Composable
    fun center(mod: Modifier) {
      Box(mod, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Box(Modifier.width((s.sideCols * s.river).d), contentAlignment = Alignment.CenterEnd) { river(Side.LEFT, s.sideRows, s.sideCols) }
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            river(Side.TOP, s.acrossRows, s.acrossCols)
            Box(
              Modifier.width(s.boxW.d).heightIn(min = s.boxH.d).background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
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
          Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
              seat(Side.LEFT, Modifier.width(s.sideW.d).fillMaxHeight())
              Column(Modifier.weight(1f).fillMaxHeight()) {
                seat(Side.TOP, Modifier.height(s.topH.d).fillMaxWidth())
                center(Modifier.weight(1f).fillMaxWidth())
              }
              seat(Side.RIGHT, Modifier.width(s.sideW.d).fillMaxHeight())
            }
            Row(Modifier.height(s.meH.d).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
              Melds(pos.players[me].melds, flowersOf(pos.players[me]), s.meld, wrap = false)
              MyHand(mine, drawn, s.hand, 0, discardable, discard, selected) { selected = it }
            }
          }
          Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = (s.meH + 2).d).widthIn(max = (s.sideW - 12).d),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            status?.invoke()
            badge(me, null)
          }
        } else {
          Column(Modifier.fillMaxSize()) {
            Row(Modifier.height(s.topH.d).fillMaxWidth()) {
              seat(Side.LEFT, Modifier.weight(1f).fillMaxHeight())
              seat(Side.TOP, Modifier.weight(1f).fillMaxHeight())
              seat(Side.RIGHT, Modifier.weight(1f).fillMaxHeight())
            }
            center(Modifier.weight(1f).fillMaxWidth())
            Column(Modifier.height(s.meH.d).fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
              horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.align(Alignment.CenterVertically)) { badge(me, null) }
                Melds(pos.players[me].melds, flowersOf(pos.players[me]), s.meld, wrap = true)
              }
              MyHand(mine, drawn, s.hand, s.handRow, discardable, discard, selected) { selected = it }
            }
          }
          if (status != null) Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = (s.meH + 4).d)) { status() }
        }
        if (claims.isNotEmpty() && onAct != null) {
          Box(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, start = 8.dp, bottom = (s.meH + 4).d)) {
            ClaimBar(claims, s.hand, onAct)
          }
        }
        Callouts(view, me, s)
      }
      overlay?.invoke(this)
    }
  }
}

/** Others' concealed tiles: their faces when revealed, else backs (wide) or nothing (tall: a count on the badge). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Concealed(revealed: String?, hidden: Int, s: TableSizes, wrap: Boolean, end: Boolean = false) {
  if (revealed != null) {
    FlowRow(horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
      for (t in Tiles.sort(Tiles.parse(revealed))) TileView(t, s.opp.d)
    }
  } else if (s.back > 0) {
    if (wrap) FlowRow(horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) { repeat(hidden) { TileView(null, s.back.d) } }
    else Row { repeat(hidden) { TileView(null, s.back.d) } }
  }
}

/** A player's name plate: their seat wind (lit for the dealer, with the streak), name, tiles held, score. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeatBadge(
  wind: String, name: String, score: Double?, dealer: Boolean, streak: Int, turn: Boolean, count: Int?, compact: Boolean = false,
  away: Boolean = false, font: Float = 16f,
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
      if (count != null) Text(stringResource(R.string.hand_cards, count), color = c.onFeltDim, fontSize = f, maxLines = 1)
      if (score != null) Text(signed(score), color = if (score > 0) c.good else if (score < 0) c.bad else c.onFelt, fontWeight = FontWeight.Bold,
        fontSize = f, maxLines = 1)
    }
    val gap = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
    // A third of an upright phone's width: the name over the figures (a name of a battle's player has room then).
    if (compact && count != null) Column(Modifier.weight(1f, fill = false)) {
      Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { nameLine() }
      Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { figures() }
    } else {
      // One line, or the figures under the name when the seat's column is too narrow for both (a dealer's long name
      // at a large font): the name stays whole.
      FlowRow(Modifier.weight(1f, fill = false), horizontalArrangement = gap, itemVerticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { nameLine() }
        Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { figures() }
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

/** A player's melds, each a group of tiles, and their flowers after them (each on its own, so a line breaks between). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Melds(melds: List<Meld>, flowers: List<String>, w: Float, wrap: Boolean, arrangement: Arrangement.Horizontal = Arrangement.Start) {
  if (melds.isEmpty() && flowers.isEmpty()) return
  val g = meldGaps(w)
  val content: @Composable () -> Unit = {
    for (m in melds) Row { for (t in meldTiles(m)) TileView(t, w.d) }
    for (f in flowers) TileView(f, (w * FLOWER_SCALE).d)
  }
  if (wrap) FlowRow(horizontalArrangement = Arrangement.spacedBy(g.col.d, if (arrangement == Arrangement.End) Alignment.End else if (arrangement == Arrangement.Center) Alignment.CenterHorizontally else Alignment.Start),
    verticalArrangement = Arrangement.spacedBy(g.row.d), itemVerticalAlignment = Alignment.Bottom) { content() }
  else Row(horizontalArrangement = Arrangement.spacedBy(g.col.d), verticalAlignment = Alignment.Bottom) { content() }
}

/** One player's discards in order, `cols` a row; the tiles taken by a meld faded, the last discard lit. */
@Composable
fun River(discards: List<Discard>, w: Float, cols: Int, rows: Int, last: Boolean) {
  val fit = if (rows > 0 && discards.size > cols * rows) ceil(discards.size.toFloat() / rows).toInt() else cols
  val tw = w * cols / fit
  Column(Modifier.width((cols * w).d)) {
    for (r in discards.indices step fit) {
      Row {
        for (i in r until minOf(r + fit, discards.size)) {
          val d = discards[i]
          TileView(d.tile, tw.d, dim = d.called, glow = last && i == discards.size - 1 && !d.called)
        }
      }
    }
  }
}

/**
 * The player's concealed tiles, the drawn one apart at the end. On the player's turn a tap lifts a tile and a second
 * tap discards it; a tile dragged upwards is discarded when let go. `perRow`: tiles a row (an upright phone), 0 one row.
 */
@Composable
fun MyHand(
  tiles: List<String>, drawn: String?, w: Float, perRow: Int, discardable: Set<String>, onDiscard: (String) -> Unit,
  selected: Int?, onSelect: (Int?) -> Unit,
) {
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
    Modifier.padding(top = (h * LIFT).d).let { if (perRow > 0) it.width(((perRow + DRAWN_GAP + 1) * w).d) else it },
    verticalArrangement = Arrangement.spacedBy((h * ROW_GAP).d),
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
          TileView(
            all[i], w.d, selected = selected == i,
            modifier = Modifier
              .testTag(if (isDrawn) "hand_drawn" else "hand_$i")
              .padding(start = if (isDrawn && row.size > 1) (w * DRAWN_GAP).d else 0.dp)
              .offset { IntOffset(0, lift.roundToInt()) }
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
              },
          )
        }
      }
    }
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

/** The player's choices other than discarding: win, kongs, pung, each way of a chow (its three tiles), and pass. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClaimBar(actions: List<Action>, w: Float, onAct: (Action) -> Unit) {
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
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(actionLabel(a.type)), fontWeight = if (pass) FontWeight.Medium else FontWeight.Bold, fontSize = if (win) 20.sp else 18.sp)
          val ts = if (a.type == "chi") a.tiles ?: emptyList() else if (a.tile != null && a.type != "ron") listOf(a.tile) else emptyList()
          if (ts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) { for (t in ts) TileView(t, (w * 0.62f).d) }
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
  LaunchedEffect(view.eventCount) {
    val count = view.eventCount
    if (seen < 0 || count < seen) {
      seen = count
      return@LaunchedEffect
    }
    val fresh = view.events.takeLast(count - seen)
    seen = count
    val flowers = HashSet<Int>()
    for (e in fresh) {
      val text = texts[e.type] ?: continue
      if (e.type == "flower" && !flowers.add(e.seat)) continue
      list += Callout(next++, if (e.type == "exhausted") Side.TOP else sideOf(e.seat, me), text, e.type in setOf("tsumo", "ron", "flower_win"))
    }
  }
  for (co in list.toList()) {
    key(co.id) {
      val a = remember { Animatable(0f) }
      LaunchedEffect(Unit) {
        a.animateTo(1f, tween(if (co.big) 1600 else 900))
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

