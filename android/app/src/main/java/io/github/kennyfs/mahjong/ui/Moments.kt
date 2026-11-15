package io.github.kennyfs.mahjong.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.github.kennyfs.mahjong.CalloutClock
import io.github.kennyfs.mahjong.Sounds
import io.github.kennyfs.mahjong.FxClock
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Side
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.View
import kotlin.math.roundToInt

// The moments of the table (TableView starts them from the events, one after another): a call's (吃, 碰, 槓), a
// deal-in's and a win's, a declaration's (天聽, 地聽), an exhaustive draw's. Calls and another's deal-in are small, by the
// player (SmallFx); what ends a hand, and a declaration, takes the whole screen (CallFx; the player's own deal-in SadFx).
// Their times: Timing (res/raw/timing.json). The web page's lib/moments.ts and the moments of Table.svelte.

/**
 * CALL: a chow, a pung, a kong; DEALIN: who dealt in (another); SMALL: a win's words by the winner (a review's end,
 * small); SAD: the player's own deal-in; WIN: a win (on a discard, self-drawn, by flowers); DECLARE: 天聽, 地聽; DRAWN: an
 * exhaustive draw; GAP: nothing shown, a pause at a hand's end (`hide`: before the hands are shown, a draw's; else after,
 * before the result's moment).
 */
enum class MomentKind { CALL, DEALIN, SMALL, SAD, WIN, DECLARE, DRAWN, GAP }

/** A small one's parts, ms: it comes in (sliding from the left, slowing), the tile called drops into it, it stays, it
 *  goes (its meld flies to its place on the table). */
class Parts(val come: Long, val drop: Long, val hold: Long, val out: Long) {
  val total: Long get() = come + drop + hold + out
}

/** Where a moment's tile comes from: the last tile of a seat's river, or a flower where it lies (七搶一). */
sealed interface FxFrom {
  class River(val seat: Int) : FxFrom
  class Flower(val tile: String) : FxFrom
}

/** Where a small one stands: over a seat's river (a call's), over a seat's tiles (who dealt in), the rivers' middle. */
sealed interface FxAt {
  class River(val seat: Int) : FxAt
  class Seat(val seat: Int) : FxAt
  data object Middle : FxAt
}

/**
 * A moment. `type`: the event's (chi, pon, kan, ankan, kakan, ron, tsumo, flower_win, declare, exhausted); `who`: whose
 * (the name), `note`: a line under it (who dealt in); `tiles` around the gap (`gap`, -1 none) and the tile that comes into
 * it (`tile`; null: a back), from `src` (else from the side `from`; null: above); `meld`: the meld a call brings (kept out
 * of sight at its place till it lands; -1 none); `at`: where a small one stands; `ms`: its whole time, a small one's
 * `parts`; `hide`: a gap's: the hands stay hidden through it; `neutral`: a result the player neither gains nor loses by
 * (another's win on another's discard, a draw): its neutral look.
 */
data class Moment(
  val id: Int, val kind: MomentKind, val type: String, val seat: Int, val side: Side, val word: String, val who: String = "",
  val note: String = "", val mine: Boolean = false, val tiles: List<String?> = emptyList(), val gap: Int = -1, val tile: String? = null,
  val src: FxFrom? = null, val from: Side? = null, val meld: Int = -1, val at: FxAt? = null, val ms: Long, val parts: Parts? = null,
  val hide: Boolean = false, val neutral: Boolean = false,
)

/** What takes the whole screen. */
val FULL = setOf(MomentKind.SAD, MomentKind.WIN, MomentKind.DECLARE, MomentKind.DRAWN)

/** A result's moment stops here (the light has run over the hand) till the player's tap. */
const val HOLD_AT = 0.78f

/** Where a tile or a meld is on the screen: its centre (px, in the root) and its own width (px, before any scale). */
class FxAnchor(val center: Offset, val width: Int)

/** Records where a composable is, as an FxAnchor (its centre through whatever transforms it). */
fun Modifier.fxAnchor(onPlace: (FxAnchor) -> Unit): Modifier = onGloballyPositioned {
  onPlace(FxAnchor(it.localToRoot(Offset(it.size.width / 2f, it.size.height / 2f)), it.size.width))
}

/** Records a composable's box in the root, px (by its place and size: an empty river has no bounds of its own). */
fun Modifier.fxArea(onPlace: (Rect) -> Unit): Modifier = onGloballyPositioned {
  onPlace(Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())))
}

/**
 * A graphics layer (`block`) with `room` around its content: Android draws a layer that is not wholly opaque apart, at
 * the layer's size, and loses what its content draws outside it. A call's plaque and the caller's name, which stand
 * out of its panel, were cut off as the panel slid in, and a win's words and the slant of the panel as they faded (the
 * web page's opacity cuts nothing). The layer is `room` larger on every side; the content keeps its own place and size.
 */
fun Modifier.roomyLayer(room: Dp, block: GraphicsLayerScope.() -> Unit): Modifier = this
  .layout { m, c ->
    val r = room.roundToPx()
    val p = m.measure(c.offset(2 * r, 2 * r))
    layout(p.width - 2 * r, p.height - 2 * r) { p.placeWithLayer(-r, -r, layerBlock = block) }
  }
  .padding(room)

/**
 * The table's moments (TableView makes one and keeps it): the queue, the one held for a tap, and where the table's
 * parts are for the moments to start from, stand over and land at.
 */
class FxHost {
  /** One after another; the first is on the screen. */
  var moments by mutableStateOf<List<Moment>>(emptyList())
    private set
  val cur: Moment? get() = moments.firstOrNull()
  /** A review's table: its moments of a hand's end are small and none stops for a tap. */
  var review = false
  /** The view's events seen (TableEvents): -1 none yet. */
  var seen by mutableIntStateOf(-1)
  /** The result's moment stopped for the tap; the one let go after it (it plays on to its end). */
  var holding by mutableStateOf<Int?>(null)
    private set
  var released by mutableStateOf<Int?>(null)
    private set

  /** What the moments show as the table has it (TableView keeps them): the player's seat, a win's hand (the winner's
   *  concealed tiles once shown), the tiles' widths over the whole screen and in a small one (dp). */
  var me by mutableIntStateOf(0)
  var hand by mutableStateOf<List<String>>(emptyList())
  var w by mutableFloatStateOf(40f)
  var smallW by mutableFloatStateOf(36f)
  /** Each seat's last discard, each meld ("seat-index"), each flower where it lies; the rivers' and seats' boxes
   *  ("river-seat", "seat-seat"), the rivers' middle and the table's ("middle", "table"). */
  val rivers = mutableStateMapOf<Int, FxAnchor>()
  val melds = mutableStateMapOf<String, FxAnchor>()
  val flowers = mutableStateMapOf<String, FxAnchor>()
  val areas = mutableStateMapOf<String, Rect>()
  private var next = 0
  fun nextId() = next++

  /** The moment the result's jingle comes with (the first over the whole screen of a hand's end), and the jingle. */
  var jingleAt = -1
  var jingle: Sounds.Name? = null
  /** The moments' sounds still to come: they go with the moments (a moment let go before its sound: none). */
  private val sounds = ArrayList<() -> Unit>()

  /** A moment's sounds as it comes (MomentsClock): a call's cue as it starts, its knock (a call's, a kong's) as the
   *  tile taken lands in its gap (at 0.82 of its drop), and a tile set down as the meld lands at its place (at its
   *  end); a declaration's; the result's jingle. (The hands shown at a hand's end sound as they are shown: TableView.) */
  fun soundsOf(c: Moment) {
    val p = c.parts
    when {
      c.kind == MomentKind.CALL -> {
        sounds += Sounds.play(if (c.type == "chi" || c.type == "pon") Sounds.Name.CALL else Sounds.Name.KONG,
          if (p != null) p.come + (0.82 * p.drop).toLong() else 0L)
        if (p != null && c.meld >= 0) sounds += Sounds.play(Sounds.Name.DISCARD, p.total)
        Sounds.cueOf(c.type)?.let { sounds += Sounds.play(it) }
      }
      c.kind == MomentKind.DECLARE -> sounds += Sounds.play(Sounds.Name.DECLARE)
      c.id == jingleAt -> jingle?.let { sounds += Sounds.play(it) }
    }
  }

  private fun dropSounds() {
    for (off in sounds) off()
    sounds.clear()
  }

  /** A hand's result over the whole screen (a win, a draw; not in a review, which has its own steps) stops for the tap. */
  fun holds(m: Moment) = !review && (m.kind == MomentKind.WIN || m.kind == MomentKind.DRAWN)

  /** The meld a call's moment is bringing (now or next): out of sight at its place till it lands. -1: none. */
  fun hideMeld(seat: Int): Int = moments.firstOrNull { it.kind == MomentKind.CALL && it.seat == seat }?.meld ?: -1

  /** These follow one another (what was still up goes: it is old news). */
  fun start(list: List<Moment>) {
    if (list.isEmpty()) return
    dropSounds()
    moments = list
    holding = null
    released = null
    if (list.any(::holds)) FxClock.tapped = false
    // The computer players of a practice game wait for them, as for words.
    CalloutClock.shown(list.sumOf { it.ms })
  }

  /** `id`'s time is up. */
  fun done(id: Int) {
    if (cur?.id == id) moments = moments.drop(1)
  }

  /** `id` came to where it stops for the tap. */
  fun hold(id: Int) {
    if (cur?.id == id) holding = id
  }

  /** Another hand, another table: none. */
  fun clear() {
    dropSounds()
    moments = emptyList()
    holding = null
    released = null
  }

  /** A touch anywhere: a result held for it goes on (and the result comes after it at once); before that, every moment
   *  goes, all of a hand's end with them. */
  fun tap() {
    val h = holding
    if (h != null && cur?.id == h) {
      holding = null
      released = h
      FxClock.tapped = true
      return
    }
    if (moments.any(::holds)) FxClock.tapped = true
    clear()
  }

  fun river(seat: Int, a: FxAnchor) {
    val o = rivers[seat]
    if (o == null || o.center != a.center || o.width != a.width) rivers[seat] = a
  }

  fun meld(seat: Int, i: Int, a: FxAnchor) {
    val k = "$seat-$i"
    val o = melds[k]
    if (o == null || o.center != a.center || o.width != a.width) melds[k] = a
  }

  fun flower(tile: String, a: FxAnchor) {
    val o = flowers[tile]
    if (o == null || o.center != a.center || o.width != a.width) flowers[tile] = a
  }

  fun area(name: String, r: Rect) {
    if (areas[name] != r) areas[name] = r
  }

  /** Where `from` lies now (a river's last tile, a flower). */
  fun anchorOf(from: FxFrom?): FxAnchor? = when (from) {
    is FxFrom.River -> rivers[from.seat]
    is FxFrom.Flower -> flowers[from.tile]
    null -> null
  }

  /** The box a small one stands over, px in the root. */
  fun areaOf(at: FxAt?): Rect? = when (at) {
    is FxAt.River -> areas["river-${at.seat}"]
    is FxAt.Seat -> areas["seat-${at.seat}"]
    FxAt.Middle -> areas["middle"]
    null -> null
  }
}

/** The table on the screen (its host), whose moments the window shows over everything (FxOverlay); and whether its
 *  hand's end waits for a tap now (TapOn: the words of it show over the moments too). */
object FxStage {
  var host by mutableStateOf<FxHost?>(null)
  var tapOn by mutableStateOf(false)
}

/** The tiles of a win's moment (not a flower win's): the winner's concealed tiles (once shown) without the winning one. */
fun winHand(m: Moment, view: View, revealed: List<String>?): List<String> {
  if (m.kind != MomentKind.WIN || m.type == "flower_win") return emptyList()
  val hand = (revealed ?: view.hands)?.getOrNull(m.seat) ?: return emptyList()
  return Tiles.sort(Tiles.without(Tiles.parse(hand), listOfNotNull(m.tile)))
}

/** A moment's time: how far it is (0 to 1; a result's stops at HOLD_AT till it is let go) and the seconds it has been
 *  up (what turns and runs goes on by these, also while it stops). Counted by the frames. */
class FxTime {
  var p by mutableFloatStateOf(0f)
  var real by mutableFloatStateOf(0f)
}

@Composable
fun rememberFxTime(m: Moment, host: FxHost): FxTime {
  val t = remember(m.id) { FxTime() }
  LaunchedEffect(m.id) {
    val holds = host.holds(m)
    val total = m.ms.coerceAtLeast(1L).toFloat()
    var last = -1L
    while (true) {
      withFrameMillis { now ->
        if (last >= 0) {
          val dt = (now - last).toFloat()
          t.real += dt / 1000f
          var p = t.p + dt / total
          if (holds && host.released != m.id) p = minOf(p, HOLD_AT)
          t.p = minOf(1f, p)
        }
        last = now
      }
    }
  }
  return t
}

/**
 * Over the whole window, above every screen and bar (MainActivity): the moment of the table on the screen, if any, and
 * the words that the table waits for a tap (≫ 按一下以繼續, at the table's bottom right: from the start of a result's
 * moment, or at once without one). Nothing here takes a touch: a touch anywhere lets a moment go (MainActivity,
 * CalloutClock's skips). (In the table it covered only the table: not the controls beside it, nor the felt's edges kept
 * clear of a camera.)
 */
@Composable
fun FxOverlay() {
  val h = FxStage.host ?: return
  // (In the table's density, as the table: its tiles as large as the table's.)
  CompositionLocalProvider(LocalDensity provides LocalTableDensity.current) { FxLayer(h) }
}

@Composable
private fun FxLayer(h: FxHost) {
  var origin by remember { mutableStateOf(Offset.Zero) }
  Box(Modifier.fillMaxSize().onGloballyPositioned { val o = it.positionInRoot(); if (o != origin) origin = o }) {
    val m = h.cur
    if (m != null) androidx.compose.runtime.key(m.id) {
      when (m.kind) {
        MomentKind.GAP -> {}
        MomentKind.SAD -> SadFx(m, h)
        MomentKind.WIN, MomentKind.DECLARE, MomentKind.DRAWN -> CallFx(m, h)
        else -> SmallFx(m, h, origin)
      }
    }
    // (Gone at the tap, while the moment it held plays out.)
    if (FxStage.tapOn && (m == null || (h.holds(m) && h.released != m.id))) TapHint(h, origin)
  }
}

/** ≫ 按一下以繼續, at the table's bottom right. */
@Composable
private fun TapHint(h: FxHost, origin: Offset) {
  val table = h.areas["table"] ?: return
  val density = LocalDensity.current.density
  val nudge by rememberInfiniteTransition(label = "nudge").animateFloat(0f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "nudge")
  Row(
    Modifier.layout { m, _ ->
      val p = m.measure(Constraints())
      layout(0, 0) { p.place((table.right - origin.x - 12 * density - p.width).roundToInt(), (table.bottom - origin.y - 12 * density - p.height).roundToInt()) }
    }.shadow(6.dp, RoundedCornerShape(50)).background(Color(0xD1080B0A), RoundedCornerShape(50)).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
      .testTag("tap_hint"),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Canvas(Modifier.size(22.dp).graphicsLayer { translationX = 3 * density * nudge }) {
      val s = size.width / 24f
      fun tri(x: Float) = Path().apply { moveTo(x * s, 6 * s); lineTo((x + 7) * s, 12 * s); lineTo(x * s, 18 * s); close() }
      drawPath(tri(4f), Color(0xFFF6C453))
      drawPath(tri(13f), Color(0xFFF6C453))
    }
    val f = with(LocalDensity.current) { 15.dp.toSp() }
    Text(stringResource(R.string.tap_to_continue), color = Color(0xFFFFF6DC), fontWeight = FontWeight.Bold, fontSize = f, maxLines = 1)
  }
}
