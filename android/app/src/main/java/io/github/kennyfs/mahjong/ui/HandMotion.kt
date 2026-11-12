package io.github.kennyfs.mahjong.ui

import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Slow at both ends: where a tile starts and where it lands are clear. */
private val IN_OUT = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/**
 * The player's own discard (the web page's Table.svelte flyDiscard): from where it stood in the hand to its place in the
 * river (`discard_move`); once it is halfway there the hand closes the gap it left (`hand_move`: the drawn tile to its
 * place, the tiles between it and the gap a tile over). Slow at both ends. TableView arms it as the view with the discard is composed,
 * before it is laid out (the tiles' places before are still known); the hand's tiles and the river's new tile then move
 * themselves as they are placed (handSlide, discardFly).
 */
class HandMotion {
  /** Each hand tile's place when last laid out (its centre, px in the root) and its width (px), by its key (tileKeys). */
  val at = HashMap<String, Offset>()
  val widths = HashMap<String, Int>()
  /** The hand as last composed: its tiles in order, the drawn one last (`drawn`). */
  var hand: List<String> = emptyList()
  var drawn = false
  /** The view last composed (its events' count). */
  var seen = -1
  /** The moves: their number (each tile starts its own once per number), when they started and how long they take. */
  var token by mutableIntStateOf(0)
  var start = 0L
  var handMs = 1L
  var flyMs = 1L
  /** The hand's places before, by the keys of the tiles after; where the discard left the hand (its centre and width,
   *  px) and its index in the player's river. */
  var before: Map<String, Offset> = emptyMap()
  var from: Offset? = null
  var fromW = 1f
  var flyIndex = -1
  /** Changes every frame while they move: the tiles read it to be placed again. */
  var frame by mutableLongStateOf(0L)
  /** From the discard till the moves end: the parts of the table that hold the river are drawn over the hand
   *  (overWhileFlying). */
  var flying by mutableStateOf(false)

  /** The hand waits where it stood till the discard is halfway (ms after the start). */
  val handWait: Long get() = flyMs / 2

  val moving: Boolean get() = SystemClock.uptimeMillis() - start < max(handWait + handMs, flyMs)

  /** The player discarded `tile` (the river's tile `index`): the moves from the hand as it was last laid out. */
  fun arm(tile: String, index: Int, handMs: Long, flyMs: Long) {
    val old = hand
    if (old.isEmpty()) return
    // The drawn tile if it is the one, else the last of its kind.
    val gone = if (drawn && old.last() == tile) old.size - 1 else old.lastIndexOf(tile)
    if (gone < 0) return
    val keys = tileKeys(old)
    from = at[keys[gone]]
    fromW = (widths[keys[gone]] ?: 1).toFloat()
    // The rest, by the keys they have after (the n-th of a kind to the n-th).
    val rest = old.filterIndexed { i, _ -> i != gone }
    val restKeys = tileKeys(rest)
    val map = HashMap<String, Offset>()
    var k = 0
    for (i in old.indices) {
      if (i == gone) continue
      at[keys[i]]?.let { map[restKeys[k]] = it }
      k++
    }
    before = map
    flyIndex = index
    this.handMs = max(1L, handMs)
    this.flyMs = max(1L, flyMs)
    start = SystemClock.uptimeMillis()
    flying = true
    token++
  }

  companion object {
    /** Each tile's key in a hand: the tile and how many of it came before ("5m#1": the second 5m). */
    fun tileKeys(tiles: List<String>): List<String> {
      val seen = HashMap<String, Int>()
      return tiles.map { t ->
        val n = seen[t] ?: 0
        seen[t] = n + 1
        "$t#$n"
      }
    }
  }
}

/** A hand's tile (`key`, HandMotion.tileKeys): where it is laid out is kept; after the player's discard it moves from
 *  where it stood to its place, over the tiles that stay (the drawn tile passes over them, as on the web page). */
fun Modifier.handSlide(motion: HandMotion?, key: String): Modifier = if (motion == null) this else composed {
  class State(var token: Int, var d: Offset = Offset.Zero)
  val st = remember { State(motion.token) }
  // Where it rests (not where it moves), kept whenever that changes (also when the table around it moves).
  onGloballyPositioned { co ->
    motion.at[key] = co.positionInRoot() + Offset(co.size.width / 2f, co.size.height / 2f)
    motion.widths[key] = co.size.width
  }.layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) {
      var off = Offset.Zero
      val co = coordinates
      if (co != null) {
        val now = co.positionInRoot() + Offset(p.width / 2f, p.height / 2f)
        if (st.token != motion.token) {
          st.token = motion.token
          val b = if (motion.moving) motion.before[key] else null
          st.d = if (b != null && abs(b.x - now.x) + abs(b.y - now.y) >= 1) b - now else Offset.Zero
        }
        if (st.d != Offset.Zero) {
          motion.frame
          val f = ((SystemClock.uptimeMillis() - motion.start - motion.handWait).toFloat() / motion.handMs).coerceIn(0f, 1f)
          if (f >= 1f) st.d = Offset.Zero else off = st.d * (1 - IN_OUT.transform(f))
        }
      }
      p.place(off.x.roundToInt(), off.y.roundToInt(), zIndex = if (st.d != Offset.Zero) 1f else 0f)
    }
  }
}

/** A part of the table that holds the player's river (or holds what does): drawn over the parts beside it while their
 *  discard flies, so that the tile goes over the hand it leaves, not under it. Read as it is placed (no recomposition). */
fun Modifier.overWhileFlying(motion: HandMotion): Modifier = layout { m, c ->
  val p = m.measure(c)
  layout(p.width, p.height) { p.place(0, 0, zIndex = if (motion.flying) 1f else 0f) }
}

/** The player's new discard in their river (its index `index`): from where it left the hand (as large as there) to its
 *  place. */
fun Modifier.discardFly(motion: HandMotion?, index: Int): Modifier = if (motion == null) this else composed {
  class State(var token: Int, var d: Offset? = null, var k: Float = 1f)
  val st = remember { State(motion.token) }
  layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) {
      var off = Offset.Zero
      var k = 1f
      val co = coordinates
      if (co != null && motion.flyIndex == index) {
        val now = co.positionInRoot() + Offset(p.width / 2f, p.height / 2f)
        if (st.token != motion.token) {
          st.token = motion.token
          val f = motion.from
          st.d = if (f != null && motion.moving) f - now else null
          st.k = motion.fromW / max(1, p.width)
        }
        val d = st.d
        if (d != null) {
          motion.frame
          val f = ((SystemClock.uptimeMillis() - motion.start).toFloat() / motion.flyMs).coerceIn(0f, 1f)
          if (f >= 1f) st.d = null
          else {
            val e = IN_OUT.transform(f)
            off = d * (1 - e)
            k = st.k + (1 - st.k) * e
          }
        }
      }
      p.placeWithLayer(0, 0) {
        translationX = off.x
        translationY = off.y
        scaleX = k
        scaleY = k
      }
    }
  }
}
