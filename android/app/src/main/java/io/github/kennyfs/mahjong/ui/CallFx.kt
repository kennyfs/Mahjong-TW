package io.github.kennyfs.mahjong.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.CalloutClock
import io.github.kennyfs.mahjong.FxClock
import io.github.kennyfs.mahjong.GameEvent
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Side
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.View
import io.github.kennyfs.mahjong.sideOf
import kotlin.math.hypot

/** Where a tile or a meld is on the screen: its centre (px, in the root) and its own width (px, before any scale). */
class FxAnchor(val center: Offset, val width: Int)

/** Records where a composable is, as an FxAnchor (its centre through whatever transforms it). */
fun Modifier.fxAnchor(onPlace: (FxAnchor) -> Unit): Modifier = onGloballyPositioned {
  onPlace(FxAnchor(it.localToRoot(Offset(it.size.width / 2f, it.size.height / 2f)), it.size.width))
}

/**
 * A chow's, a pung's or a win's moment. `seat`: whose; `from`: who discarded the tile (-1: a self-draw), `srcSeat`:
 * whose river it lies at the end of (-1: none, a robbed kong's); `tiles` and `gap`: the caller's own tiles around the
 * gap (a win's: their hand, from the view, when it is shown); `meld`: the index of the meld a call makes.
 */
class FxSpec(
  val id: Int, val win: Boolean, val word: String, val who: String, val note: String, val seat: Int, val from: Int, val srcSeat: Int,
  val tile: String, val tiles: List<String>, val gap: Int, val meld: Int, val ms: Long,
)

/**
 * The table's moments (TableView makes one): the one on the screen, if any, and where the rivers' last tiles and the
 * melds are, for the tile to lift off and the meld to land at.
 */
class FxHost {
  var fx by mutableStateOf<FxSpec?>(null)
  /** What the moment shows as the table has it (TableView keeps them): the player's seat, a win's tiles, the tiles'
   *  width (dp). */
  var me by mutableIntStateOf(0)
  var tiles by mutableStateOf<List<String>>(emptyList())
  var w by mutableFloatStateOf(40f)
  val rivers = mutableStateMapOf<Int, FxAnchor>()
  val melds = mutableStateMapOf<String, FxAnchor>()
  private var next = 0
  fun nextId() = next++

  /** The meld a call's moment is bringing (-1: none): out of sight at its place till it lands. */
  fun hideMeld(seat: Int): Int = fx?.takeIf { !it.win && it.seat == seat }?.meld ?: -1

  fun river(seat: Int, a: FxAnchor) {
    val o = rivers[seat]
    if (o == null || o.center != a.center || o.width != a.width) rivers[seat] = a
  }

  fun meld(seat: Int, i: Int, a: FxAnchor) {
    val k = "$seat-$i"
    val o = melds[k]
    if (o == null || o.center != a.center || o.width != a.width) melds[k] = a
  }
}

/** The table on the screen (its host), whose moments the window shows over everything (FxOverlay). */
object FxStage {
  var host by mutableStateOf<FxHost?>(null)
}

/**
 * Over the whole window, above every screen and bar (MainActivity): the moment of the table on the screen, if any. (In
 * the table it covered only the table: not the controls beside it, nor the felt's edges kept clear of a camera.)
 */
@Composable
fun FxOverlay() {
  val h = FxStage.host ?: return
  val fx = h.fx ?: return
  Box(Modifier.fillMaxSize()) {
    androidx.compose.runtime.key(fx.id) { CallFx(fx, h, h.me, h.tiles, h.w) }
  }
}

/**
 * Starts the moments of the events that came since the last view (the words over the players are Callouts'): a chow,
 * a pung, a win by discard or self-draw, unless the settings keep to the words. `robbed`: the event (an index into the
 * view's events) that was a claim taken from the player: its word is 搶碰, 搶胡. A tap anywhere (CalloutClock's skips)
 * lets the moment go. The web page's Table.svelte (startFx).
 */
@Composable
fun FxTrigger(host: FxHost, view: View, me: Int, names: List<String>, robbed: Int) {
  var seen by remember { mutableIntStateOf(-1) }
  val words = mapOf(
    "chi" to stringResource(R.string.act_chi), "pon" to stringResource(R.string.act_pon), "ron" to stringResource(R.string.act_ron),
    "tsumo" to stringResource(R.string.act_tsumo),
  )
  val robWords = mapOf(
    "chi" to stringResource(R.string.rob_chi), "pon" to stringResource(R.string.rob_pon), "ron" to stringResource(R.string.rob_ron),
  )
  val dealtIn = stringResource(R.string.call_deal_in)
  val skips by CalloutClock.skips.collectAsState()
  val skips0 = remember { intArrayOf(skips) }
  LaunchedEffect(skips) {
    if (skips != skips0[0]) host.fx = null
    skips0[0] = skips
  }
  DisposableEffect(Unit) { onDispose { FxClock.on.value = false } }
  LaunchedEffect(host.fx) { FxClock.on.value = host.fx != null }
  LaunchedEffect(view.eventCount) {
    val count = view.eventCount
    if (seen < 0 || count < seen) {
      seen = count
      host.fx = null
      return@LaunchedEffect
    }
    val evs = view.events
    val fresh = evs.takeLast(count - seen)
    seen = count
    if (fresh.size > 8 || !FxClock.enabled()) return@LaunchedEffect
    val base = evs.size - fresh.size
    fun lastBefore(at: Int, types: Set<String>): GameEvent? = (at - 1 downTo 0).firstOrNull { evs[it].type in types }?.let { evs[it] }
    for ((i, e) in fresh.withIndex()) {
      val tile = e.tile ?: continue
      if (e.type !in words) continue
      val at = base + i
      val word = (if (at == robbed) robWords[e.type] else null) ?: words[e.type]!!
      val call = e.type == "chi" || e.type == "pon"
      var from = -1
      var river = true
      when (e.type) {
        "ron" -> {
          val last = lastBefore(at, setOf("discard", "kakan"))
          from = view.result?.loser?.takeIf { it >= 0 } ?: last?.seat ?: -1
          river = last?.type != "kakan"
        }
        "chi", "pon" -> from = lastBefore(at, setOf("discard"))?.seat ?: -1
      }
      if (from == e.seat) from = -1
      val tiles = when (e.type) {
        "chi" -> Tiles.without(Tiles.sort(e.tiles ?: emptyList()), listOf(tile))
        "pon" -> listOf(tile, tile)
        else -> emptyList()
      }
      host.fx = FxSpec(
        host.nextId(), !call, word, names.getOrElse(e.seat) { "" },
        if (e.type == "ron" && from >= 0) "${names.getOrElse(from) { "" }} $dealtIn" else "",
        e.seat, from, if (river && from >= 0) from else -1, tile, tiles, if (call) 1 else 0,
        if (call) view.position.players[e.seat].melds.size - 1 else -1, CalloutClock.fxMs(),
      )
      // The computer players of a practice game wait for it, as for the words.
      CalloutClock.shown(CalloutClock.fxMs())
    }
  }
}

// The moment's curves (the web page's cubic-bezier()s).
private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EASE_IN = CubicBezierEasing(0.42f, 0f, 1f, 1f)
private val WEDGE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val POP = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.25f)
private val RISE = CubicBezierEasing(0.3f, 0.6f, 0.3f, 1f)
private val FALL = CubicBezierEasing(0.6f, 0f, 0.9f, 0.5f)
private val FLY = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)

/** How far `p` is through `a` to `b`, 0 to 1, along `e`. */
private fun seg(p: Float, a: Float, b: Float, e: Easing = LinearEasing) = e.transform(((p - a) / (b - a)).coerceIn(0f, 1f))
private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

/** The speed lines on the band: fixed at random once, each its own height (%), length (% of the band), weight (dp),
 *  opacity, pace (s) and start (s). */
private class Streak(val y: Float, val len: Float, val weight: Float, val op: Float, val dur: Float, val delay: Float)
private val STREAKS = List(22) { i ->
  Streak(((i * 37) % 100) / 100f, (18 + (i * 53) % 40) / 100f, 1f + (i * 7) % 3, 0.25f + ((i * 29) % 60) / 100f,
    0.35f + ((i * 17) % 40) / 100f, ((i * 13) % 50) / 100f)
}

/**
 * A chow's, a pung's or a win's moment over the whole table (TableView starts it; the advanced settings can keep to the
 * words alone). A dark veil; two black wedges slide in from opposite corners; across the middle a band laid askew, its
 * white speed lines running; on it the word on its plaque (whose it is under it), and the tiles:
 *
 * - a chow or a pung: the caller's own tiles with a gap; the tile called lifts off the river where it lies, growing as
 *   it comes over the gap, and drops into it; then the meld flies to its place among the caller's melds (kept out of
 *   sight there till now), shrinking or growing to their size on the way, as the rest fades: it lands where it will be;
 * - a win: the winner's concealed tiles with a gap at their end, the winning tile coming from the river of the one who
 *   dealt in (a self-draw: lit, from above) and dropping into the gap; a light runs over the hand, and it all fades
 *   where it is (the hand's result comes next).
 *
 * Without the place to start from or to land at (a robbed kong) the tile comes from the side of the one who
 * discarded it, and the meld goes off towards the caller's side, smaller, fading. It lasts `fx.ms`; a tap anywhere
 * lets it go (CalloutClock's skips). `w`: the tiles' width, dp. The web page's CallFx.svelte.
 */
@Composable
fun BoxScope.CallFx(fx: FxSpec, host: FxHost, me: Int, tiles: List<String>, w: Float) {
  val t = remember(fx.id) { Animatable(0f) }
  LaunchedEffect(fx.id) {
    t.animateTo(1f, tween(fx.ms.toInt(), easing = LinearEasing))
    if (host.fx?.id == fx.id) host.fx = null
  }
  val p = t.value
  val elapsed = p * fx.ms / 1000f
  val win = fx.win
  val density = LocalDensity.current.density
  // The place the tile starts at and the meld lands at, px from the untransformed gap and meld here.
  val src = remember(fx.id) { if (fx.srcSeat >= 0) host.rivers[fx.srcSeat] else null }
  var slot by remember(fx.id) { mutableStateOf<FxAnchor?>(null) }
  var place by remember(fx.id) { mutableStateOf<FxAnchor?>(null) }
  val land = if (win) null else host.melds["${fx.seat}-${fx.meld}"]
  val edge = if (win) Color(0xFFFF8A73) else Color(0xFFF6C453)

  BoxWithConstraints(Modifier.matchParentSize().testTag("call_fx"), contentAlignment = Alignment.Center) {
    val wDp = maxWidth.value
    val hDp = maxHeight.value
    // Out of sight: towards a side of the screen, px.
    fun away(side: Side?): Offset = when (side) {
      null -> Offset(0f, -0.3f * hDp)
      Side.BOTTOM -> Offset(0f, 0.38f * hDp)
      Side.TOP -> Offset(0f, -0.38f * hDp)
      Side.LEFT -> Offset(-0.42f * wDp, 0f)
      Side.RIGHT -> Offset(0.42f * wDp, 0f)
    } * density

    Canvas(Modifier.fillMaxSize()) {
      val W = size.width
      val H = size.height
      // The veil: comes, stays, goes (a call's sooner, while its meld flies to its place).
      val veil = if (p < 0.08f) p / 0.08f else if (win) 1 - seg(p, 0.84f, 1f) else 1 - seg(p, 0.64f, 0.92f)
      drawRect(Brush.radialGradient(listOf(Color(0x8C121E1A), Color(0xD1040807)), Offset(W / 2, H / 2), hypot(W / 2, H / 2)), alpha = veil)
      // The band, askew, across the whole screen from the start (it comes in as the veil does); its speed lines
      // running, each over the band's whole length.
      val bandA = seg(p, 0f, 0.08f) * (if (win) 1 - seg(p, 0.84f, 1f, EASE_OUT) else 1 - seg(p, 0.64f, 0.88f, EASE_OUT))
      val bandH = (0.46f * H).coerceIn(180 * density, 420 * density)
      // Long enough to reach past the screen's corners however it is turned.
      val left = -0.5f * maxOf(W, H)
      val bandW = W + maxOf(W, H)
      val top = (H - bandH) / 2
      rotate(-14f, Offset(W / 2, H / 2)) {
        clipRect(left, top, left + bandW, top + bandH) {
          drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color(0x12FFFFFF), 0.5f to Color(0x1AFFFFFF), 0.82f to Color(0x12FFFFFF),
              1f to Color.Transparent, startY = top, endY = top + bandH),
            Offset(left, top), Size(bandW, bandH), alpha = bandA,
          )
          // The lines run over the part of the band on the screen (as wide as the screen, turned, and a little more).
          val span = 1.1f * W + 0.5f * bandH
          for (s in STREAKS) {
            val len = s.len * span
            val phase = ((elapsed + s.delay) / s.dur) % 1f
            val x = W / 2 - span / 2 - len + phase * (span + len)
            val y = top + s.y * bandH
            drawRect(
              Brush.horizontalGradient(0f to Color.Transparent, 0.35f to Color.White, 0.8f to Color.White, 1f to Color.Transparent, startX = x, endX = x + len),
              Offset(x, y), Size(len, s.weight * density), alpha = s.op * bandA,
            )
          }
        }
      }
      // Two black wedges from opposite corners, a fine line of the moment's color along their long edge.
      val inF = 1 - seg(p, 0f, 0.12f, WEDGE)
      val outF = if (win) seg(p, 0.84f, 1f, WEDGE) else seg(p, 0.64f, 0.9f, WEDGE)
      val off = maxOf(inF, outF)
      val wa = 1 - (if (win) seg(p, 0.84f, 1f) else seg(p, 0.64f, 0.9f))
      val ww = 0.62f * W
      val wh = 0.46f * H
      val dx = 0.6f * ww * off
      val dy = 0.6f * wh * off
      val tl = Path().apply { moveTo(-dx, -dy); lineTo(ww - dx, -dy); lineTo(-dx, wh - dy); close() }
      drawPath(tl, Color(0xFF050706), alpha = wa)
      drawLine(edge, Offset(ww - dx, -dy), Offset(-dx, wh - dy), 2.5f * density, alpha = wa)
      val br = Path().apply { moveTo(W + dx, H - wh + dy); lineTo(W + dx, H + dy); lineTo(W - ww + dx, H + dy); close() }
      drawPath(br, Color(0xFF050706), alpha = wa)
      drawLine(edge, Offset(W + dx, H - wh + dy), Offset(W - ww + dx, H + dy), 2.5f * density, alpha = wa)
    }

    // The word and the tiles, upright on the band.
    val fs = (0.15f * minOf(wDp, hDp)).coerceIn(48f, 120f)
    // (The table's width, not the screen's: a navigation rail beside it.)
    val tw = minOf(w, (minOf(wDp, 900f) - 48) / maxOf(4f, tiles.size + 1.4f))
    // A win's gap: at the end of the hand.
    val gap = if (win) tiles.size else fx.gap
    val th = tw * TILE_RATIO
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((0.06f * hDp).coerceIn(26f, 48f).dp)) {
      // The plaque: in, larger, settling; out (a call's while its meld flies).
      val pin = seg(p, 0f, 0.1f, POP)
      val pout = if (win) seg(p, 0.8f, 1f) else seg(p, 0.62f, 0.82f)
      Column(
        Modifier.graphicsLayer {
          val k = mix(1.6f, 1f, pin) * mix(1f, 0.94f, pout)
          scaleX = k; scaleY = k
          alpha = (pin.coerceIn(0f, 1f)) * (1 - pout)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Plaque(fx.word, if (win) CalloutKind.WIN else CalloutKind.CALL, fs, if (fx.word.length > 1) 2 else 1, fx.who,
          robInk = if (win) Color(0xFFFFD56B) else Color(0xFFA3150C))
        if (fx.note.isNotEmpty()) {
          val f = with(LocalDensity.current) { (fs * 0.17f).coerceAtLeast(13f).dp.toSp() }
          Text(fx.note, Modifier.padding(top = (fs * 0.1f).dp).background(Color(0xEB10140F), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 2.dp),
            style = TextStyle(color = Color(0xFFFFD2C8), fontWeight = FontWeight.Bold, fontSize = f), maxLines = 1, softWrap = false)
        }
      }
      // The tiles. The outer box keeps the meld's untransformed place (what the moves are measured from).
      Box(Modifier.fxAnchor { if (place?.center != it.center || place?.width != it.width) place = it }) {
        val pl = place
        val gx = if (land != null && pl != null) land.center.x - pl.center.x else away(sideOf(fx.seat, me)).x
        val gy = if (land != null && pl != null) land.center.y - pl.center.y else away(sideOf(fx.seat, me)).y
        val gk = if (land != null && pl != null && pl.width > 0) land.width.toFloat() / pl.width else 0.4f
        val go = if (land != null) 1f else 0f
        Row(
          Modifier.graphicsLayer {
            if (win) {
              val f = seg(p, 0.84f, 1f, EASE_IN)
              val k = mix(1f, 1.04f, f)
              scaleX = k; scaleY = k; alpha = 1 - f
            } else {
              val f = seg(p, 0.62f, 0.9f, FLY)
              translationX = gx * f; translationY = gy * f
              val k = mix(1f, gk, f)
              scaleX = k; scaleY = k; alpha = mix(1f, go, f)
            }
          }.then(if (win) Modifier.drawWithContent {
            drawContent()
            // A light runs over the hand.
            if (p in 0.5f..0.78f) {
              val a = if (p < 0.52f) seg(p, 0.5f, 0.52f) else if (p > 0.76f) 1 - seg(p, 0.76f, 0.78f) else 1f
              val bw = size.width * 0.4f
              val x = (size.width - bw) * mix(-0.6f, 1.6f, seg(p, 0.5f, 0.76f))
              drawRect(
                Brush.linearGradient(0.38f to Color.Transparent, 0.5f to Color(0xD9FFECAA), 0.62f to Color.Transparent,
                  start = Offset(x, 0f), end = Offset(x + bw, size.height * 0.27f)),
                Offset(x, -size.height * 0.06f), Size(bw, size.height * 1.12f), alpha = a, blendMode = BlendMode.Screen,
              )
            }
          } else Modifier),
          verticalAlignment = Alignment.Bottom,
        ) {
          @Composable
          fun own(tile: String) = Box(Modifier.graphicsLayer {
            val f = seg(p, 0f, 0.08f, EASE_OUT)
            translationY = 12 * density * (1 - f); alpha = f
          }) { TileView(tile, tw.dp) }
          for (x in tiles.take(gap)) own(x)
          // The gap, and the tile called: from where it lay (as large as there), up over the gap (a little larger),
          // down into it.
          Box(Modifier.padding(start = if (win) (tw * 0.3f).dp else 0.dp).size(tw.dp, th.dp)
            .fxAnchor { if (p < 0.5f && (slot?.center != it.center || slot?.width != it.width)) slot = it }) {
            val sl = slot
            val start = if (src != null && sl != null) Offset(src.center.x - sl.center.x, src.center.y - sl.center.y) else away(fx.from.takeIf { it >= 0 }?.let { sideOf(it, me) })
            val fk = if (src != null && sl != null && sl.width > 0) src.width.toFloat() / sl.width else 1f
            val fo = if (src != null) 1f else 0f
            val fr = if (src != null) 0f else -18f
            TileView(fx.tile, tw.dp, glow = win, modifier = Modifier.graphicsLayer {
              val thPx = th * density
              when {
                p < 0.3f -> {
                  val f = seg(p, 0f, 0.3f, RISE)
                  translationX = mix(start.x, 0f, f); translationY = mix(start.y, -0.55f * thPx, f)
                  rotationZ = mix(fr, -4f, f)
                  val k = mix(fk, 1.08f, f); scaleX = k; scaleY = k
                }
                p < 0.44f -> {
                  val f = seg(p, 0.3f, 0.44f, FALL)
                  translationY = mix(-0.55f * thPx, 0f, f); rotationZ = mix(-4f, 0f, f)
                  val k = mix(1.08f, 1f, f); scaleX = k; scaleY = k
                }
                p < 0.49f -> translationY = mix(0f, -0.09f * thPx, seg(p, 0.44f, 0.49f, EASE_OUT))
                p < 0.54f -> translationY = mix(-0.09f * thPx, 0f, seg(p, 0.49f, 0.54f, EASE_IN))
              }
              alpha = if (p < 0.06f) mix(fo, 1f, p / 0.06f) else 1f
            })
          }
          for (x in tiles.drop(gap)) own(x)
        }
      }
    }
  }
}

/** The tiles of a win's moment: the winner's concealed tiles (once shown) without the winning one. */
fun fxTiles(fx: FxSpec, view: View, revealed: List<String>?): List<String> {
  if (!fx.win) return fx.tiles
  val hand = (revealed ?: view.hands)?.getOrNull(fx.seat) ?: return emptyList()
  return Tiles.sort(Tiles.without(Tiles.parse(hand), listOf(fx.tile)))
}
