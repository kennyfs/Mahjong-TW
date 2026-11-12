package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.FigureCell
import io.github.kennyfs.mahjong.FigureKey

// An action's figures in fixed columns (Review.kt figureCells): each column as wide as its widest cell over a set of
// lines (a list's rows, the figures of each tile over the hand), so that every figure keeps its place whatever the
// others have. The web page's FigureRuler.svelte and FigureLine.svelte.

/** The gap between a cell's label and its figure. */
private val LABEL_GAP = 4.dp

/** Tabular figures, so that numbers of the same length are as wide. */
fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/** Each column's width over `lines` (a column no line has a cell in: none). */
@Composable
fun rememberFigureWidths(lines: List<List<FigureCell>>, keys: List<FigureKey>, style: TextStyle): Map<FigureKey, Dp> {
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  return remember(lines, keys, style, density) {
    val gap = with(density) { LABEL_GAP.toPx() }
    keys.associateWith { k ->
      var w = 0f
      for (line in lines) {
        val c = line.firstOrNull { it.key == k } ?: continue
        if (c.label.isEmpty() && c.value.isEmpty()) continue
        val label = if (c.label.isEmpty()) 0f else measurer.measure(c.label, style).size.width + gap
        w = maxOf(w, label + (if (c.value.isEmpty()) 0f else measurer.measure(c.value, style).size.width.toFloat()))
      }
      with(density) { if (w > 0f) w.toDp() + 1.dp else 0.dp }
    }
  }
}

/** One action's figures: the label at a cell's left, the figure at its right, `good` or `bad` where notably better or
 *  worse than the best action's. A cell the action has not keeps its room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FigureLine(
  cells: List<FigureCell>, keys: List<FigureKey>, widths: Map<FigureKey, Dp>, style: TextStyle, color: Color, soft: Color, good: Color, bad: Color,
  modifier: Modifier = Modifier, arrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
) {
  FlowRow(modifier, horizontalArrangement = arrangement, verticalArrangement = Arrangement.spacedBy(1.dp)) {
    FigureCells(cells, keys, widths, style, color, soft, good, bad)
  }
}

/** The cells alone, for a FlowRow of the caller's (with words before them). */
@Composable
fun FigureCells(cells: List<FigureCell>, keys: List<FigureKey>, widths: Map<FigureKey, Dp>, style: TextStyle, color: Color, soft: Color, good: Color, bad: Color) {
    for (k in keys) {
      val w = widths[k] ?: 0.dp
      if (w == 0.dp) continue
      val c = cells.firstOrNull { it.key == k }
      Row(Modifier.width(w), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(c?.label ?: "", style = style, color = soft, maxLines = 1, softWrap = false)
        Text(c?.value ?: "", style = style, color = when (c?.tone) { 1 -> good; -1 -> bad; else -> color }, maxLines = 1, softWrap = false)
      }
    }
}
