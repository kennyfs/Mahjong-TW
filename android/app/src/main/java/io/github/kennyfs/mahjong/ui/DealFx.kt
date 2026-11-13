package io.github.kennyfs.mahjong.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Sounds
import io.github.kennyfs.mahjong.Timing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The hands' tiles: 16 each and the dealer's first draw. */
private const val N = 16 * 4 + 1
/** How far apart the tiles leave (ms, at most): together, not as one block. */
private const val SPREAD = 90L
/** A shuffled tile's time from one spot to the next (ms, about): its pace, whatever deal_shuffle is (as many spots as
 *  fill it). The web page's DealFx.svelte the same. */
private const val LEG_MS = 470L
private val SHUFFLE_EASING = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val FLY_EASING = CubicBezierEasing(0.45f, 0f, 0.3f, 1f)

private fun legsOf(shuffleMs: Long) = max(1, Math.round(shuffleMs.toFloat() / LEG_MS))

/** A hand's start in all, ms: the shuffle, its pause, the tiles flying, the player's hand turning up. */
fun dealMs(): Long = Timing.ms("deal_shuffle") + Timing.ms("deal_pause") + Timing.ms("deal_fly") + SPREAD + Timing.ms("deal_flip")

/** The last hand whose deal was shown: a table that comes back to it (another screen and back) does not deal it again. */
object DealShown { var last: String? = null }

/**
 * A hand's deal going on (TableView): the tiles shuffled and flying, then (`flipping`) the player's hand turning face
 * up, `flipT` ms into it.
 */
class Dealing(val center: Rect, val targets: List<Offset>) {
  var flipping by mutableStateOf(false)
  var flipT by mutableLongStateOf(0L)
}

/**
 * The hands' tiles (16 each, the dealer's 17th) shuffled face down in the middle of the table, then dealt all at once
 * as they lie, no walls built: each flies from where the shuffle left it to its player's seat, all together (a little
 * apart, as hands would push them), fading into their seat (the web page's DealFx.svelte). Its times: deal_shuffle,
 * deal_pause (the pile still a moment, the shuffle's sound heard out), deal_fly. Sounds: the shuffle, and one knock of
 * tiles as they come. `center`: the rivers' box, `targets`: each seat's place the tiles go to, px in the table;
 * `tw`: a river tile's width, dp. `onDone`: they have landed.
 */
@Composable
fun BoxScope.DealFx(center: Rect, targets: List<Offset>, tw: Float, dealer: Int, onDone: () -> Unit) {
  val density = LocalDensity.current.density
  val sizePx = max(14f * density, min(tw * 0.9f * density, sqrt(center.width * center.height * 0.5f / N / TILE_RATIO)))
  val hPx = sizePx * TILE_RATIO
  class Way(val xs: FloatArray, val ys: FloatArray, val rs: FloatArray, val owner: Int, val jx: Float, val jy: Float, val delay: Long)
  val ways = remember {
    val rx = min(center.width, center.height * 1.6f) * 0.32f
    val ry = min(center.height, center.width) * 0.3f
    // Whose each tile is: the dealer's 17, the others' 16, at random among the pile.
    val owner = IntArray(N) { if (it == N - 1) dealer else it % 4 }.also { it.shuffle() }
    val spots = legsOf(Timing.ms("deal_shuffle")) + 1
    List(N) { k ->
      val xs = FloatArray(spots)
      val ys = FloatArray(spots)
      val rs = FloatArray(spots)
      for (i in 0 until spots) {
        val a = Random.nextFloat() * 2 * PI.toFloat()
        val d = sqrt(Random.nextFloat())
        xs[i] = center.center.x + cos(a) * rx * d
        ys[i] = center.center.y + sin(a) * ry * d
        rs[i] = Random.nextFloat() * 360f - 180f
      }
      Way(xs, ys, rs, owner[k], (Random.nextFloat() * 1.2f - 0.6f) * sizePx, (Random.nextFloat() * 0.6f - 0.3f) * sizePx,
        (Random.nextFloat() * SPREAD).toLong())
    }
  }
  val shuffleMs = remember { Timing.ms("deal_shuffle") }
  val legs = remember { legsOf(shuffleMs) }
  val flyAt = remember { shuffleMs + Timing.ms("deal_pause") }
  val fly = remember { Timing.ms("deal_fly") }
  var t by remember { mutableLongStateOf(0L) }
  var gone by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    Sounds.play(Sounds.Name.SHUFFLE)
    // They come together: one knock, as they land.
    Sounds.play(Sounds.Name.DEAL, flyAt + (fly * 0.8).toLong() + SPREAD / 2)
    val start = withFrameMillis { it }
    while (t < flyAt + fly + SPREAD) withFrameMillis { t = it - start }
    gone = true
    onDone()
  }
  if (gone) return
  Box(Modifier.matchParentSize().clipToBounds()) {
    for (way in ways) Box(Modifier.graphicsLayer {
      val now = t
      var x: Float
      var y: Float
      var r: Float
      var s = 1f
      var a: Float
      // 1. Shuffled: among a few spots, turning (hands swirling them on the felt).
      val p = SHUFFLE_EASING.transform((now.toFloat() / shuffleMs).coerceIn(0f, 1f)) * legs
      val i = min(legs - 1, floor(p).toInt())
      val f = p - i
      x = way.xs[i] + (way.xs[i + 1] - way.xs[i]) * f
      y = way.ys[i] + (way.ys[i + 1] - way.ys[i]) * f
      r = way.rs[i] + (way.rs[i + 1] - way.rs[i]) * f
      a = if (i == 0) f else 1f
      // 2. Dealt: from where it lies to its player, fading into their seat.
      val u = ((now - flyAt - way.delay).toFloat() / fly).coerceIn(0f, 1f)
      if (now >= flyAt + way.delay) {
        val to = targets.getOrNull(way.owner) ?: Offset(x, y)
        val e = FLY_EASING.transform(u)
        val tx = to.x + way.jx
        val ty = to.y + way.jy
        if (e <= 0.85f) {
          val g = e / 0.85f
          x += (tx - x) * g
          y += (ty - y) * g
          r += (0f - r) * g
          s = 1f - 0.1f * g
          a = 1f - 0.15f * g
        } else {
          val g = (e - 0.85f) / 0.15f
          x = tx
          y = ty
          r = 0f
          s = 0.9f - 0.1f * g
          a = 0.85f * (1 - g)
        }
      }
      translationX = x - sizePx / 2
      translationY = y - hPx / 2
      rotationZ = r
      scaleX = s
      scaleY = s
      alpha = a
    }) { TileView(null, (sizePx / density).dp) }
  }
}

/** The player's hand turning face up after the deal, one tile after another from the left: tile `i` of `n`, its turn
 *  (degrees about the vertical) `since` ms after the turn began (deal_flip). */
fun flipTurn(i: Int, n: Int, since: Long, flipMs: Long): Float {
  val half = flipMs * 0.5f
  val start = i.toFloat() / max(1, n) * half
  val u = ((since - start) / half).coerceIn(0f, 1f)
  // (Ease out.)
  val e = 1 - (1 - u) * (1 - u)
  return 90f * (1 - e)
}
