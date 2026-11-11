package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.ReviewKey
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A reviewed hand at a glance: the expected result (the best action's value) at each of the player's decisions, along
 * the hand's steps; the decisions worth a look as dots (a mistake by its grade, a good find as a star); where the
 * review is now as a line. A tap goes to the nearest decision. The web page's ValueGraph.svelte.
 *
 * `points`: the value at each decision's step; `keys`: the decisions to mark (those the review stops at).
 */
@Composable
fun ValueGraph(points: List<Pair<Int, Double>>, keys: List<ReviewKey>, step: Int, n: Int, height: Dp, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
  val cs = MaterialTheme.colorScheme
  val label = stringResource(R.string.review_graph)
  var lo = 0.0
  var hi = 0.0
  for ((_, v) in points) {
    lo = min(lo, v)
    hi = max(hi, v)
  }
  val pad = max(0.3, (hi - lo) * 0.12)
  lo -= pad
  hi += pad
  Canvas(
    modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(8.dp)).background(cs.onSurface.copy(alpha = 0.06f))
      .testTag("replay_graph").semantics { contentDescription = label }
      .pointerInput(points, keys, n) {
        detectTapGestures { at ->
          if (points.isEmpty()) return@detectTapGestures
          val edge = 6.dp.toPx()
          fun x(s: Int) = edge + (size.width - 2 * edge) * (if (n > 0) s.toFloat() / n else 0f)
          // A marked decision near the tap wins over a plain one nearer.
          var best = points[0].first
          var d = Float.MAX_VALUE
          for ((s, _) in points) {
            val dist = abs(x(s) - at.x) - if (keys.any { it.step == s }) 10.dp.toPx() else 0f
            if (dist < d) {
              d = dist
              best = s
            }
          }
          onPick(best)
        }
      },
  ) {
    if (points.isEmpty()) return@Canvas
    val edge = 6.dp.toPx()
    val top = 3.dp.toPx()
    fun x(s: Int) = edge + (size.width - 2 * edge) * (if (n > 0) s.toFloat() / n else 0f)
    fun y(v: Double) = top + (size.height - 2 * top) * (1 - ((v - lo) / (hi - lo)).toFloat())
    drawLine(cs.outline, Offset(edge, y(0.0)), Offset(size.width - edge, y(0.0)), 1.dp.toPx(),
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
    val line = Path()
    points.forEachIndexed { i, (s, v) -> if (i == 0) line.moveTo(x(s), y(v)) else line.lineTo(x(s), y(v)) }
    drawPath(line, cs.primary, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
    val now = x(min(step, n))
    drawLine(cs.onSurface.copy(alpha = 0.6f), Offset(now, 0f), Offset(now, size.height), 2.dp.toPx())
    for (k in keys) {
      val at = Offset(x(k.step), y(points.firstOrNull { it.first == k.step }?.second ?: 0.0))
      if (k.found) {
        // A star: five points, 6.5 dp out and 2.8 dp in.
        val star = Path()
        for (i in 0 until 10) {
          val r = (if (i % 2 == 0) 6.5f else 2.8f).dp.toPx()
          val a = -PI / 2 + i * PI / 5
          val p = Offset(at.x + r * cos(a).toFloat(), at.y + r * sin(a).toFloat())
          if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
        }
        star.close()
        drawPath(star, cs.surface, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        drawPath(star, Marks.found)
      } else {
        val r = (if (k.grade >= 3) 5.5f else 4.5f).dp.toPx()
        drawCircle(if (k.grade > 0) Marks.danger(k.grade) else Marks.found, r, at)
        drawCircle(cs.surface, r, at, style = Stroke(1.5.dp.toPx()))
      }
    }
  }
}
