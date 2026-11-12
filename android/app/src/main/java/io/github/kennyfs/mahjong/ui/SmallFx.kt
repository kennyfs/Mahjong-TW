package io.github.kennyfs.mahjong.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A small moment's colors: its plaque's (CalloutKind) and the line along its panel's leading edge; 搶's ink. */
private class SmallLook(val kind: CalloutKind, val edge: Color, val rob: Color)

private val CALL_LOOK = SmallLook(CalloutKind.CALL, Color(0xFFF6C453), Color(0xFFA3150C))
private val DEALIN_LOOK = SmallLook(CalloutKind.DEALIN, Color(0xFFFF9D8C), Color(0xFFFFD56B))
private val WIN_LOOK = SmallLook(CalloutKind.WIN, Color(0xFFFF8A73), Color(0xFFFFD56B))

// The curves (the web page's cubic-bezier()s).
private val SLIDE = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val IN_OUT = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val LAND = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)
private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EASE_IN = CubicBezierEasing(0.42f, 0f, 1f, 1f)

/** How far `x` is through `a` to `b`, 0 to 1, along `e`. */
private fun seg(x: Float, a: Float, b: Float, e: Easing = LinearEasing) = if (b <= a) (if (x >= b) 1f else 0f) else e.transform(((x - a) / (b - a)).coerceIn(0f, 1f))
private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

/** The slant of the panel: tan 16°. */
private const val SKEW = 0.2867f

/** The panel: dark, leaning forward, a line of the moment's color along its leading edge, faint stripes. */
private fun DrawScope.slantPanel(edge: Color, density: Float) {
  val w = size.width
  val h = size.height
  val dx = SKEW * h / 2
  val shape = Path().apply { moveTo(dx, 0f); lineTo(w + dx, 0f); lineTo(w - dx, h); lineTo(-dx, h); close() }
  translate(0f, 5 * density) { drawPath(shape, Color.Black.copy(alpha = 0.28f)) }
  drawPath(shape, Brush.horizontalGradient(0f to Color(0xF0060908), 0.55f to Color(0xE6161C19), 1f to Color(0xF0060908), startX = -dx, endX = w + dx))
  clipPath(shape) {
    val big = w + h
    rotate(28f, Offset(w / 2, h / 2)) {
      var x = -big
      while (x < w + big) {
        drawRect(Color.White.copy(alpha = 0.045f), Offset(x + 16 * density, -big), Size(8 * density, h + 2 * big))
        x += 24 * density
      }
    }
    val e = 4 * density
    drawPath(Path().apply { moveTo(dx, 0f); lineTo(dx + e, 0f); lineTo(-dx + e, h); lineTo(-dx, h); close() }, edge)
  }
  drawPath(shape, Color.White.copy(alpha = 0.07f), style = Stroke(density))
}

/**
 * A small moment by a player (ui/Moments.kt; TableView starts it): a call (吃, 碰, 槓), who dealt in (another player),
 * a win's words alone (a review's end). It slides in from the left, slowing as it comes, and shows the word and the
 * caller's tiles with a gap; the tile called lifts off the river where it lies and drops into the gap; it stays; then it
 * goes, and the meld flies to its place among the caller's melds (kept out of sight there till now), shrinking or growing
 * to their size: it lands where it will be. Without a place to land (who dealt in, a win's words) it all fades where it
 * is. It stands over its place (the caller's river, the seat that dealt in), inside the table: a slanted dark panel with
 * the word large at its foot. `origin`: the overlay's place in the root. The web page's SmallFx.svelte.
 */
@Composable
fun SmallFx(m: Moment, host: FxHost, origin: Offset) {
  val density = LocalDensity.current.density
  val tw = host.smallW
  val th = tw * TILE_RATIO
  val parts = m.parts ?: Parts(0, 0, m.ms, 0)
  val time = rememberFxTime(m, host)
  val look = when (m.kind) {
    MomentKind.DEALIN -> DEALIN_LOOK
    MomentKind.SMALL -> WIN_LOOK
    else -> CALL_LOOK
  }
  val table = host.areas["table"]
  val area = host.areaOf(m.at)
  // Where the tile lay, as it starts (the river may change by then); the gap and the tiles, as they are laid out.
  val src = remember(m.id) { host.anchorOf(m.src) }
  var slot by remember(m.id) { mutableStateOf<FxAnchor?>(null) }
  var row by remember(m.id) { mutableStateOf<FxAnchor?>(null) }
  val dst = if (m.meld >= 0) host.melds["${m.seat}-${m.meld}"] else null
  val before = if (m.gap >= 0) m.tiles.take(m.gap) else m.tiles
  val after = if (m.gap >= 0) m.tiles.drop(m.gap) else emptyList()
  val chars = m.word.length
  val fs = tw * 1.2f
  // The phases' ends, ms.
  val come = parts.come.toFloat()
  val dropEnd = come + parts.drop
  val holdEnd = dropEnd + parts.hold
  val end = holdEnd + parts.out
  fun now() = time.p * m.ms
  /** The decoration's fading as the meld flies off. */
  fun deco(): Float = if (dst != null) 1 - seg(now(), holdEnd, holdEnd + parts.out * 0.6f, EASE_IN) else 1f

  Box(
    Modifier.testTag("small_fx").layout { measurable, _ ->
      val p = measurable.measure(Constraints())
      layout(0, 0) {
        val bw = p.width.toFloat()
        val bh = p.height.toFloat()
        val c = area?.center ?: table?.center ?: Offset(origin.x + bw, origin.y + bh)
        var x = c.x - bw / 2
        var y = c.y - bh / 2
        if (table != null) {
          x = x.coerceIn(table.left + 6 * density, max(table.left + 6 * density, table.right - 6 * density - bw))
          y = y.coerceIn(table.top + 6 * density, max(table.top + 6 * density, table.bottom - 6 * density - bh))
        }
        p.placeWithLayer((x - origin.x).roundToInt(), (y - origin.y).roundToInt()) {
          val t = now()
          // In: from the left by part of its width, slowing to its place. Out without a place to land: fading.
          val fin = seg(t, 0f, come, SLIDE)
          val fout = if (dst == null) seg(t, holdEnd, end, EASE_IN) else 0f
          translationX = -0.4f * bw * (1 - fin) + min(bw * 0.06f, 24 * density) * fout
          alpha = fin * (1 - fout)
        }
      }
    },
  ) {
    Box(Modifier.matchParentSize().graphicsLayer { alpha = deco() }.drawBehind { slantPanel(look.edge, density) })
    Row(Modifier.padding(start = (fs * (chars * 1.04f + 0.68f) * 0.78f + tw * 0.25f).dp, top = (tw * 0.34f).dp, end = (tw * 0.75f).dp, bottom = (tw * 0.34f).dp)) {
      Row(
        Modifier.fxAnchor { if (row?.center != it.center || row?.width != it.width) row = it }.graphicsLayer {
          // The meld to its place (as large as there), the rest fading.
          val d = dst
          val r = row
          if (d != null && r != null) {
            val f = seg(now(), holdEnd, end, LAND)
            translationX = (d.center.x - r.center.x) * f
            translationY = (d.center.y - r.center.y) * f
            val k = mix(1f, d.width.toFloat() / max(1, r.width), f)
            scaleX = k
            scaleY = k
          }
        },
        verticalAlignment = Alignment.Bottom,
      ) {
        for (x in before) TileView(x, tw.dp)
        // (The gap's place till the tile has dropped into it: the meld moves after.)
        if (m.gap >= 0) Box(Modifier.size(tw.dp, th.dp).fxAnchor { if (now() < dropEnd && (slot?.center != it.center || slot?.width != it.width)) slot = it }) {
          // The tile called: from where it lay (as large as there), up over the gap, down into it, once in.
          TileView(m.tile, tw.dp, modifier = Modifier.graphicsLayer {
            val t = now()
            if (parts.drop <= 0) return@graphicsLayer
            if (t < come) {
              alpha = 0f
              return@graphicsLayer
            }
            val f = seg(t, come, dropEnd)
            val g = slot
            val s = src
            val hPx = th * density
            val sx: Float
            val sy: Float
            val sk: Float
            val so: Float
            if (s != null && g != null) {
              sx = s.center.x - g.center.x; sy = s.center.y - g.center.y; sk = s.width.toFloat() / max(1, g.width); so = 1f
            } else {
              sx = 0f; sy = -1.3f * hPx; sk = 1f; so = 0f
            }
            when {
              f < 0.55f -> {
                val e = seg(f, 0f, 0.55f, IN_OUT)
                translationX = mix(sx, 0f, e); translationY = mix(sy, -0.55f * hPx, e)
                rotationZ = mix(0f, -4f, e)
                val k = mix(sk, 1.08f, e); scaleX = k; scaleY = k
                alpha = mix(so, 1f, e)
              }
              f < 0.82f -> {
                val e = seg(f, 0.55f, 0.82f, IN_OUT)
                translationY = mix(-0.55f * hPx, 0f, e); rotationZ = mix(-4f, 0f, e)
                val k = mix(1.08f, 1f, e); scaleX = k; scaleY = k
              }
              f < 0.9f -> translationY = mix(0f, -0.08f * hPx, seg(f, 0.82f, 0.9f, EASE_OUT))
              f < 1f -> translationY = mix(-0.08f * hPx, 0f, seg(f, 0.9f, 1f, EASE_IN))
            }
          })
        }
        for (x in after) TileView(x, tw.dp)
      }
    }
    // The word, large, over the panel's lower left corner; the name on a tab over its top edge.
    val len = if (!isCjk(m.word)) 3 else if (chars > 1) 2 else 1
    Box(
      Modifier.align(Alignment.BottomStart).layout { measurable, _ ->
        val p = measurable.measure(Constraints())
        layout(0, 0) { p.place((-tw * 0.3f * density).roundToInt(), (-p.height + tw * 0.42f * density).roundToInt()) }
      }.graphicsLayer { alpha = deco(); rotationZ = -6f },
    ) { Plaque(m.word, look.kind, fs, len, "", robInk = look.rob, tilt = 0f) }
    if (m.who.isNotEmpty()) {
      val f = with(LocalDensity.current) { (tw * 0.32f).coerceIn(12f, 16f).dp.toSp() }
      val pill = RoundedCornerShape(50)
      Text(
        m.who,
        Modifier.align(Alignment.TopEnd).layout { measurable, _ ->
          val p = measurable.measure(Constraints())
          // (Without tiles the panel is low: the name over it, clear of the word.)
          val up = if (m.kind == MomentKind.SMALL && m.tiles.isEmpty()) 1.05f else 0.34f
          layout(0, 0) { p.place((-p.width - tw * 0.5f * density).roundToInt(), (-tw * up * density).roundToInt()) }
        }.graphicsLayer { alpha = deco() }.background(Color(0xFF18221F), pill).border(1.5.dp, look.edge, pill)
          .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 3.dp),
        style = TextStyle(color = Color(0xFFFFF6DC), fontWeight = FontWeight.Bold, fontSize = f), maxLines = 1, softWrap = false,
      )
    }
  }
}

/** One to four Chinese characters (a plaque's word, sized as such). */
fun isCjk(text: String) = text.length in 1..4 && text.all { it in '぀'..'ヿ' || it in '㐀'..'鿿' }
