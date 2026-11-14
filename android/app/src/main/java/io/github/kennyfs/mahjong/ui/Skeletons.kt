package io.github.kennyfs.mahjong.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

// Shapes in a lighter tone, pulsing, where something is on its way (the engine starting, a battle's first view, a
// decision's list while the computer players play), as a page shows while it loads: the place neither stands empty
// nor jumps when what it waits for comes. The web page's TableSkeleton.svelte and PanelSkeleton.svelte.

/** The pulse: from `low` to `high` opacity and back in 1.6 s. */
@Composable
private fun pulse(low: Float, high: Float): Float {
  val t = rememberInfiniteTransition(label = "skeleton")
  val a by t.animateFloat(low, high, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
  return a
}

@Composable
private fun Shape(mod: Modifier, color: Color, radius: Dp) {
  Box(mod.background(color, RoundedCornerShape(radius)))
}

/**
 * A table before there is one: the felt with the shapes of the players' plates, the middle and the hand. Sideways the
 * player opposite along the top (a plate and their tiles' backs), the others at the sides, the hand in a line; upright
 * the three others' plates in a line at the top, the hand in two rows of eight.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TableSkeleton(modifier: Modifier = Modifier) {
  val c = LocalTable.current
  BoxWithConstraints(modifier.fillMaxSize().background(c.felt).padding(12.dp).testTag("table_skeleton")) {
    val wide = maxWidth > maxHeight
    val a = pulse(0.1f, 0.2f)
    val tone = c.onFelt
    val mod = Modifier.alpha(a)
    val plate = @Composable { w: Dp -> Shape(mod.width(w).height(24.dp), tone, 12.dp) }
    val tileW = if (wide) min(46.dp, (maxWidth - 96.dp) / 16) else min(46.dp, (maxWidth - 24.dp) / 8 - 3.dp)
    if (wide) {
      Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plate(112.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(8) { Shape(mod.size(18.dp, 26.dp), tone, 3.dp) } }
      }
      Box(Modifier.align(Alignment.CenterStart)) { plate(112.dp) }
      Box(Modifier.align(Alignment.CenterEnd)) { plate(112.dp) }
      Shape(mod.align(Alignment.Center).width(150.dp).aspectRatio(1f), tone, 12.dp)
      Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plate(112.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(16) { Shape(mod.width(tileW).aspectRatio(60f / 87f), tone, 6.dp) } }
      }
    } else {
      Row(Modifier.align(Alignment.TopCenter).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { Shape(mod.weight(1f).widthIn(max = 112.dp).height(24.dp), tone, 12.dp) }
      }
      Shape(mod.align(Alignment.Center).width(min(150.dp, maxWidth * 0.6f)).aspectRatio(1f), tone, 12.dp)
      Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plate(112.dp)
        FlowRow(Modifier.widthIn(max = (tileW + 3.dp) * 8), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          repeat(16) { Shape(mod.width(tileW).aspectRatio(60f / 87f), tone, 6.dp) }
        }
      }
    }
  }
}

/** A decision's list before it is there: a heading and `rows` rows (a tile, two bars), in grey. `w`: the list's tile width. */
@Composable
fun PanelSkeleton(modifier: Modifier = Modifier, w: Float = 32f, rows: Int = 5) {
  val cs = MaterialTheme.colorScheme
  val a = pulse(0.5f, 1f)
  val line = cs.surfaceContainerHighest
  Column(modifier.fillMaxWidth().testTag("panel_skeleton"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Shape(Modifier.alpha(a).padding(vertical = 4.dp).fillMaxWidth(0.45f).height(12.dp), line, 5.dp)
    for (i in 0 until rows) Row(
      Modifier.fillMaxWidth().background(cs.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Shape(Modifier.alpha(a).size((w * 0.75f).dp, w.dp), line, 5.dp)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Shape(Modifier.alpha(a).fillMaxWidth((92 - i * 9) / 100f).height(10.dp), line, 5.dp)
        Shape(Modifier.alpha(a).fillMaxWidth(0.55f).height(8.dp), line, 5.dp)
      }
    }
  }
}
