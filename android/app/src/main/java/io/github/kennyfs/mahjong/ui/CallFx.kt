package io.github.kennyfs.mahjong.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Side
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A moment's colors: the line along the wedges, the plaque's face, word, lip and fine line, the veil (inside and
 *  out), the light over the hand, the note's ink, 搶's ink. */
private class FxLook(
  val edge: Color, val face: Color, val ink: Color, val back: Color, val line: Color, val veilIn: Color, val veilOut: Color,
  val glint: Color, val note: Color = Color(0xFFFFD2C8), val rob: Color = Color(0xFFFFD56B),
)

private val BASE = FxLook(Color(0xFFFF8A73), Color(0xFFD5382D), Color(0xFFFFF6DC), Color(0xFF8C1C15), Color(0xE6FFD678), Color(0x8C121E1A),
  Color(0xD1040807), Color(0xD9FFECAA))
/** The player's own win: gold. */
private val MINE = FxLook(Color(0xFFFFD36B), Color(0xFFF7C948), Color(0xFF3B2200), Color(0xFFA8740C), Color(0x6B3B2200), Color(0x80302208),
  Color(0xD10A0702), Color(0xD9FFECAA), rob = Color(0xFFA3150C))
/** Another's (a self-draw, a win on the player's discard): steel blue, quiet. */
private val THEIRS = FxLook(Color(0xFF8FB3D9), Color(0xFF3F5E7D), Color(0xFFEEF5FF), Color(0xFF22374D), Color(0x80C8E1FF), Color(0xB3080C12),
  Color(0xE6020407), Color(0x99D2E6FF))
/** 天聽, 地聽: jade. */
private val DECLARE = FxLook(Color(0xFF7FE0C8), Color(0xFF1F8A70), Color(0xFFEFFFF9), Color(0xFF0F4F40), Color(0x80DCFFF4), Color(0x8C121E1A),
  Color(0xD1040807), Color(0xB3C8FFEC))
/** What the player neither gains nor loses by (another's win on another's discard, a draw): graphite. */
private val NEUTRAL = FxLook(Color(0xFFC5CCD1), Color(0xFF4D565C), Color(0xFFF4F6F7), Color(0xFF2B3236), Color(0x66FFFFFF), Color(0x9916191B),
  Color(0xDB060708), Color(0x99F0F4F8), note = Color(0xFFE3E7EA))

// The moment's curves (the web page's cubic-bezier()s).
private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EASE_IN = CubicBezierEasing(0.42f, 0f, 1f, 1f)
private val WEDGE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val POP = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.25f)
/** A move from a river: slow at both ends, so that where it starts and where it lands are clear. */
private val IN_OUT = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val SPARK = CubicBezierEasing(0.1f, 0.7f, 0.3f, 1f)

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

/** The sparks of the player's own win: out from the hand as the tile lands (angle, distance in vmin, size dp, delay). */
private class Spark(val a: Float, val d: Float, val s: Float, val delay: Float)
private val SPARKS = List(18) { i -> Spark((i * 137.5f) % 360, 22f + (i * 41) % 26, 4f + (i * 7) % 6, ((i * 11) % 9) / 100f) }

/** A long hand's tiles no smaller than this (dp): two lines instead. */
private const val MIN_TW = 34f

/**
 * A moment over the whole screen (ui/Moments.kt; TableView starts it): what ends a hand (a win on a discard, a
 * self-draw, a flower win, an exhaustive draw) and a declaration (天聽, 地聽). A dark veil; two black wedges slide in from
 * opposite corners; across the middle a band laid askew, its white speed lines running; on it the word on its plaque
 * (whose it is under it, and who dealt in), and the tiles:
 *
 * - a win: the winner's concealed tiles with a gap at their end, the winning tile coming from the river of the one who
 *   dealt in (a self-draw: lit, from above) and dropping into the gap; a light runs over the hand, and it all fades
 *   where it is (the hand's result comes next); a flower win: the winner's flowers, the last of them (七搶一: the one the
 *   other player held, from where it lies) coming into the gap;
 * - a declaration: the declared hand (another's: its backs);
 * - an exhaustive draw: the words alone.
 *
 * The player's own win in gold, with rays turning behind it and sparks; another's in steel blue, quieter (no lines, no
 * wedges, a darker veil); a result the player neither gains nor loses by (another's win on another's discard, a draw)
 * neutral: graphite, quiet. It lasts `m.ms`; a result stops where the light has run over the hand till the player's tap
 * (FxHost), what turns and runs behind it going on. A long hand in two lines rather than in tiles smaller than MIN_TW.
 * Nothing here takes a touch: a touch anywhere lets it go. The web page's CallFx.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallFx(m: Moment, host: FxHost) {
  val time = rememberFxTime(m, host)
  val density = LocalDensity.current.density
  val win = m.kind == MomentKind.WIN
  val look = if (m.neutral) NEUTRAL else when (m.kind) {
    MomentKind.WIN -> if (m.mine) MINE else THEIRS
    MomentKind.DECLARE -> DECLARE
    else -> BASE
  }
  val quiet = m.kind == MomentKind.DRAWN || m.neutral || (win && !m.mine)
  val lively = win && m.mine
  // A win's hand: the winner's tiles from the table, the gap at their end (a flower win's: its flowers, as made).
  val hand = win && m.type != "flower_win"
  val tiles: List<String?> = if (hand) host.hand else m.tiles
  val gap = if (hand) tiles.size else m.gap
  // Where the tile starts (as large as there), px from the gap here.
  val src = remember(m.id) { host.anchorOf(m.src) }
  var slot by remember(m.id) { mutableStateOf<FxAnchor?>(null) }
  val shineAt = if (m.kind == MomentKind.DECLARE) 0.24f else 0.5f

  BoxWithConstraints(Modifier.fillMaxSize().testTag("call_fx"), contentAlignment = Alignment.Center) {
    val wDp = maxWidth.value
    val hDp = maxHeight.value
    val vmin = minOf(wDp, hDp) * density / 100
    // Out of sight: towards a side of the screen, px.
    fun away(side: Side?): Offset = when (side) {
      null -> Offset(0f, -0.3f * hDp)
      Side.BOTTOM -> Offset(0f, 0.38f * hDp)
      Side.TOP -> Offset(0f, -0.38f * hDp)
      Side.LEFT -> Offset(-0.42f * wDp, 0f)
      Side.RIGHT -> Offset(0.42f * wDp, 0f)
    } * density

    Canvas(Modifier.fillMaxSize()) {
      val p = time.p
      val w = size.width
      val h = size.height
      // The veil, the band and the frame come, stay and go with the moment.
      val veil = if (p < 0.08f) p / 0.08f else 1 - seg(p, 0.84f, 1f)
      drawRect(Brush.radialGradient(listOf(look.veilIn, look.veilOut), Offset(w / 2, h / 2), hypot(w / 2, h / 2)), alpha = veil)
      val bandA = seg(p, 0f, 0.08f) * (1 - seg(p, 0.84f, 1f, EASE_OUT))
      val bandH = (0.46f * h).coerceIn(180 * density, 420 * density)
      val top = (h - bandH) / 2
      if (quiet) {
        // Level and still.
        drawRect(
          Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color(0x0DFFFFFF), 0.5f to Color(0x12FFFFFF), 0.75f to Color(0x0DFFFFFF),
            1f to Color.Transparent, startY = top, endY = top + bandH),
          Offset(0f, top), Size(w, bandH), alpha = bandA,
        )
      } else {
        // Askew, across the whole screen (long enough to reach past its corners however it is turned); its speed lines
        // running, each over the band's whole length.
        val left = -0.5f * maxOf(w, h)
        val bandW = w + maxOf(w, h)
        rotate(-14f, Offset(w / 2, h / 2)) {
          clipRect(left, top, left + bandW, top + bandH) {
            drawRect(
              Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color(0x12FFFFFF), 0.5f to Color(0x1AFFFFFF), 0.82f to Color(0x12FFFFFF),
                1f to Color.Transparent, startY = top, endY = top + bandH),
              Offset(left, top), Size(bandW, bandH), alpha = bandA,
            )
            val span = 1.1f * w + 0.5f * bandH
            for (s in STREAKS) {
              val len = s.len * span
              val phase = ((time.real + s.delay) / s.dur) % 1f
              val x = w / 2 - span / 2 - len + phase * (span + len)
              val y = top + s.y * bandH
              drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 0.35f to Color.White, 0.8f to Color.White, 1f to Color.Transparent, startX = x, endX = x + len),
                Offset(x, y), Size(len, s.weight * density), alpha = s.op * bandA,
              )
            }
          }
        }
        // Two black wedges from opposite corners, a fine line of the moment's color along their long edge.
        val off = maxOf(1 - seg(p, 0f, 0.12f, WEDGE), seg(p, 0.84f, 1f, WEDGE))
        val wa = 1 - seg(p, 0.84f, 1f)
        val ww = 0.62f * w
        val wh = 0.46f * h
        val dx = 0.6f * ww * off
        val dy = 0.6f * wh * off
        val tl = Path().apply { moveTo(-dx, -dy); lineTo(ww - dx, -dy); lineTo(-dx, wh - dy); close() }
        drawPath(tl, Color(0xFF050706), alpha = wa)
        drawLine(look.edge, Offset(ww - dx, -dy), Offset(-dx, wh - dy), 2.5f * density, alpha = wa)
        val br = Path().apply { moveTo(w + dx, h - wh + dy); lineTo(w + dx, h + dy); lineTo(w - ww + dx, h + dy); close() }
        drawPath(br, Color(0xFF050706), alpha = wa)
        drawLine(look.edge, Offset(w + dx, h - wh + dy), Offset(w - ww + dx, h + dy), 2.5f * density, alpha = wa)
      }
      // The player's own win: rays of gold turning slowly behind the plaque (on their own: also while it waits).
      if (lively) {
        val ra = seg(p, 0f, 0.1f) * (1 - seg(p, 0.84f, 1f))
        val c = Offset(w / 2, h / 2)
        val r = 0.42f * 1.06f * 150 * vmin
        val inner = 0.08f / 0.42f
        val turn = (time.real / 36f) * 360f
        for (k in 0 until 24) {
          val a0 = Math.toRadians((turn + k * 15f - 90f).toDouble())
          val a1 = Math.toRadians((turn + k * 15f + 5f - 90f).toDouble())
          val ray = Path().apply {
            moveTo(c.x, c.y)
            lineTo(c.x + r * cos(a0).toFloat(), c.y + r * sin(a0).toFloat())
            lineTo(c.x + r * cos(a1).toFloat(), c.y + r * sin(a1).toFloat())
            close()
          }
          drawPath(ray, Brush.radialGradient(inner to Color(0x33FFD66E), 1f to Color(0x00FFD66E), center = c, radius = r), alpha = ra)
        }
      }
    }

    // The word and the tiles, upright on the band.
    val fs = (0.15f * minOf(wDp, hDp)).coerceIn(48f, 120f)
    // A long hand in smaller tiles on one line; tiles that would be smaller than MIN_TW: two lines. (The window's
    // width, at most 900.)
    val n = tiles.size + (if (gap >= 0) 1.4f else 0f)
    val room = minOf(wDp, 900f) - 48
    val two = room / maxOf(4f, n) < MIN_TW && n > 4
    val tw = minOf(host.w, room / (if (two) ceil(n / 2) else maxOf(4f, n)))
    val th = tw * TILE_RATIO
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((0.06f * hDp).coerceIn(26f, 48f).dp)) {
      // The plaque: in, larger, settling (quiet: from a little above); out.
      Column(
        Modifier.graphicsLayer {
          val p = time.p
          val out = seg(p, 0.8f, 1f)
          if (quiet) {
            val f = seg(p, 0f, 0.14f, EASE_OUT)
            translationY = -0.12f * fs * density * (1 - f)
            alpha = f * (1 - out)
          } else {
            val f = seg(p, 0f, 0.1f, POP)
            val k = mix(1.6f, 1f, f) * mix(1f, 0.94f, out)
            scaleX = k; scaleY = k
            alpha = seg(p, 0f, 0.1f) * (1 - out)
          }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Plaque(m.word, CalloutKind.WIN, fs, if (isCjk(m.word)) (if (m.word.length > 1) 2 else 1) else 3, m.who, robInk = look.rob,
          tilt = if (quiet) 0f else -4f, face = look.face, ink = look.ink, back = look.back, line = look.line)
        if (m.note.isNotEmpty()) {
          val f = with(LocalDensity.current) { (fs * 0.17f).coerceAtLeast(13f).dp.toSp() }
          Text(m.note, Modifier.padding(top = (fs * 0.1f).dp).background(Color(0xEB10140F), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 2.dp),
            style = TextStyle(color = look.note, fontWeight = FontWeight.Bold, fontSize = f), maxLines = 1, softWrap = false)
        }
      }
      if (tiles.isNotEmpty() || gap >= 0) {
        FlowRow(
          Modifier.then(if (two) Modifier.widthIn(max = (ceil(n / 2) * tw + 2).dp) else Modifier).graphicsLayer {
            // They stay, then go a little larger.
            val f = seg(time.p, 0.84f, 1f, EASE_IN)
            val k = mix(1f, 1.04f, f)
            scaleX = k; scaleY = k; alpha = 1 - f
          }.drawWithContent {
            drawContent()
            val p = time.p
            // A light runs over the hand (a declaration's sooner).
            if ((win || m.kind == MomentKind.DECLARE) && p in shineAt..shineAt + 0.28f) {
              val a = if (p < shineAt + 0.02f) seg(p, shineAt, shineAt + 0.02f) else if (p > shineAt + 0.26f) 1 - seg(p, shineAt + 0.26f, shineAt + 0.28f) else 1f
              val bw = size.width * 0.4f
              val x = (size.width - bw) * mix(-0.6f, 1.6f, seg(p, shineAt + 0.02f, shineAt + 0.26f))
              drawRect(
                Brush.linearGradient(0.38f to Color.Transparent, 0.5f to look.glint, 0.62f to Color.Transparent,
                  start = Offset(x, 0f), end = Offset(x + bw, size.height * 0.27f)),
                Offset(x, -size.height * 0.06f), Size(bw, size.height * 1.12f), alpha = a, blendMode = BlendMode.Screen,
              )
            }
            // Sparks from the player's own hand as the tile lands.
            if (lively) for (k in SPARKS) {
              val start = 0.5f + k.delay * 0.7f
              val f = (p - start) / 0.22f
              if (f <= 0f || f >= 1f) continue
              val e = SPARK.transform(f)
              val a = Math.toRadians(k.a.toDouble())
              val d = k.d * vmin * e
              val c = Offset(size.width / 2 + d * cos(a).toFloat(), size.height / 2 + d * sin(a).toFloat())
              val r = maxOf(0.5f, k.s * density * mix(1.2f, 0.4f, e) / 2)
              drawCircle(Brush.radialGradient(0f to Color(0xFFFFF7D6), 0.78f to Color(0xFFFFD36B), 1f to Color(0x00FFB43C), center = c, radius = r),
                r, c, alpha = 1 - e)
            }
          },
          horizontalArrangement = Arrangement.Center,
          verticalArrangement = Arrangement.spacedBy((tw * 0.18f).dp),
          itemVerticalAlignment = Alignment.Bottom,
        ) {
          @Composable
          fun own(tile: String?) = Box(Modifier.graphicsLayer {
            val f = seg(time.p, 0f, 0.08f, EASE_OUT)
            translationY = 12 * density * (1 - f); alpha = f
          }) { TileView(tile, tw.dp) }
          for (x in tiles.take(maxOf(0, gap))) own(x)
          if (gap < 0) for (x in tiles) own(x)
          if (gap >= 0) {
            // The gap, and the tile: from where it lay (as large as there), up over the gap (a little larger), down
            // into it.
            Box(Modifier.padding(start = if (tiles.isNotEmpty()) (tw * 0.3f).dp else 0.dp).size(tw.dp, th.dp)
              .fxAnchor { if (time.p < 0.3f && (slot?.center != it.center || slot?.width != it.width)) slot = it }) {
              TileView(m.tile, tw.dp, glow = win, modifier = Modifier.graphicsLayer {
                val p = time.p
                val sl = slot
                val start = if (src != null && sl != null) Offset(src.center.x - sl.center.x, src.center.y - sl.center.y) else away(m.from)
                val fk = if (src != null && sl != null && sl.width > 0) src.width.toFloat() / sl.width else 1f
                val fo = if (src != null) 1f else 0f
                val fr = if (src != null) 0f else -18f
                val thPx = th * density
                when {
                  p < 0.3f -> {
                    val f = seg(p, 0f, 0.3f, IN_OUT)
                    translationX = mix(start.x, 0f, f); translationY = mix(start.y, -0.55f * thPx, f)
                    rotationZ = mix(fr, -4f, f)
                    val k = mix(fk, 1.08f, f); scaleX = k; scaleY = k
                  }
                  p < 0.44f -> {
                    val f = seg(p, 0.3f, 0.44f, IN_OUT)
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
}
