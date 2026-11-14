package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Analysis
import io.github.kennyfs.mahjong.FigureKey
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.costOf
import io.github.kennyfs.mahjong.figureCells

/**
 * The figures of the tile chosen in the hand at a decision (a branch's, a practice hand's with the analysis shown), in
 * a dark strip over the hand: as the review's (ReviewScreens), each figure in a fixed column from one tile to the next.
 * `seat`: the deciding seat (the opponents' names after it, for the danger). The web page's PickedFigures.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PickedFigures(analysis: Analysis, seat: Int, names: List<String>, tile: String) {
  val res = LocalContext.current.resources
  val oppNames = (1..3).map { names[(seat + it) % 4] }
  val lines = remember(analysis, oppNames, res) {
    val best = analysis.best?.let { analysis.actions[it] }
    analysis.actions.indices.filter { analysis.actions[it].action.type == "discard" }
      .associateWith { figureCells(res, analysis.actions[it], costOf(analysis, it), oppNames, best) }
  }
  val i = analysis.actions.indexOfFirst { it.action.type == "discard" && it.action.tile == tile }
  if (i < 0) return
  val style = MaterialTheme.typography.bodySmall.tabular()
  // (Measured in bold: a green or red figure is bold there.)
  val widths = rememberFigureWidths(lines.values.toList(), FigureKey.entries, style.copy(fontWeight = FontWeight.Bold))
  val small = MaterialTheme.typography.bodySmall
  // The words as light as the figures on the black strip (dimmed, they were hard to read there).
  val soft = Color(0xFFD6EBDF)
  FlowRow(
    Modifier.background(Color(0xDB080E0B), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(1.dp),
  ) {
    // As wide for every tile (二萬 or 東).
    Text("${stringResource(R.string.act_discard)} ${Tiles.name(tile)}", Modifier.widthIn(min = with(LocalDensity.current) { (small.fontSize * 5.2f).toDp() }),
      style = small, color = Color.White, fontWeight = FontWeight.Bold)
    FigureCells(lines[i] ?: emptyList(), FigureKey.entries, widths, style, Color.White, soft, Color(0xFFB9F6C2), Color(0xFFFFBDB5), boldTones = true)
  }
}
