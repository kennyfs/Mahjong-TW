package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.EQUAL
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.WhatIf
import io.github.kennyfs.mahjong.actionText
import io.github.kennyfs.mahjong.pts

/** A cell of a table: a tile and what follows it, with a line of figures under them. */
@Composable
private fun Cell(top: Boolean, off: Boolean, pair: @Composable () -> Unit, under: @Composable () -> Unit, lit: Boolean = top,
  minWidth: Dp = 0.dp, onWidth: ((Int) -> Unit)? = null) {
  val cs = MaterialTheme.colorScheme
  val shape = RoundedCornerShape(10.dp)
  Surface(
    shape = shape, color = if (lit) cs.secondaryContainer else cs.surfaceContainer, contentColor = if (lit) cs.onSecondaryContainer else cs.onSurface,
    modifier = Modifier.widthIn(min = minWidth).let { m -> onWidth?.let { f -> m.onSizeChanged { f(it.width) } } ?: m }
      .alpha(if (off) 0.5f else 1f).let { if (top) it.border(2.dp, Marks.found, shape) else it },
  ) {
    Column(Modifier.width(IntrinsicSize.Max).padding(horizontal = 7.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) { pair() }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)) { under() }
    }
  }
}

/**
 * What the hand would do next, by the network: after each tile it may draw (the best action and the hand's value then;
 * the tiles it would keep lit, the best quarter of them ringed, the ones it would discard at once dim; every cell as
 * wide as the widest, so that they stand in columns), and on each discard of the others that it would do better to
 * claim than to leave. The web page's WhatIfTables.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhatIfTables(data: WhatIf, w: Float, seatName: (Int) -> String, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  val res = LocalResources.current
  val small = MaterialTheme.typography.bodySmall
  val dim = cs.onSurfaceVariant
  // A draw is kept when the answer is anything but discarding that tile again.
  fun kept(i: Int) = data.draws[i].let { !(it.action.type == "discard" && it.action.tile == it.tile) }
  val keptOnes = data.draws.indices.filter(::kept)
  // Ringed: the kept draws at the 75th percentile of their values or above (the best within EQUAL of each other too).
  val top = keptOnes.map { data.draws[it].value }.sortedDescending().let { v ->
    if (v.isEmpty()) Double.POSITIVE_INFINITY else minOf(v[(v.size + 3) / 4 - 1], v[0] - EQUAL)
  }
  // Every cell as wide as the widest (measured afresh for other draws).
  val widths = remember(data) { mutableStateMapOf<Int, Int>() }
  val cw = with(LocalDensity.current) { (widths.values.maxOrNull() ?: 0).toDp() }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
      Text(stringResource(R.string.whatif_draws), style = MaterialTheme.typography.titleSmall, color = cs.primary)
      Text(stringResource(R.string.whatif_keep, keptOnes.size, keptOnes.sumOf { data.draws[it].left }), style = small, color = dim)
    }
    Text(stringResource(R.string.whatif_draws_hint), style = small, color = dim)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (i in data.draws.indices.sortedBy { Tiles.index(data.draws[it].tile) }) {
        val d = data.draws[i]
        val keep = kept(i)
        Cell(top = keep && d.value >= top, off = !keep, minWidth = cw, onWidth = { widths[i] = it }, pair = {
          TileView(d.tile, w.dp)
          if (keep) {
            Text("→", color = dim)
            val t = d.action.tile
            if (d.action.type == "discard" && t != null) TileView(t, (w * 0.8f).dp)
            else Text(stringResource(actionLabel(d.action.type)), fontWeight = FontWeight.Bold, maxLines = 1)
          }
        }, under = {
          if (keep) Text(pts(d.value), style = small, fontWeight = FontWeight.Bold, maxLines = 1)
          else Text(stringResource(R.string.whatif_tsumogiri), style = small, maxLines = 1)
          Text(stringResource(R.string.whatif_left, d.left), style = small, color = dim, maxLines = 1)
        }, lit = keep)
      }
    }

    Text(stringResource(R.string.whatif_claims), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall, color = cs.primary)
    Text(stringResource(R.string.whatif_claims_hint), style = small, color = dim)
    // The discards that are better claimed than left, by who makes them.
    val taken = data.claims.filter { c -> c.options.getOrNull(c.best)?.action?.type.let { it != null && it != "pass" } }
    for (seat in taken.map { it.seat }.distinct()) {
      Text(seatName(seat), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in taken.filter { it.seat == seat }) {
          val best = c.options[c.best]
          val pass = c.options.firstOrNull { it.action.type == "pass" }
          val gain = (best.q ?: 0.0) - (pass?.q ?: best.q ?: 0.0)
          Cell(top = false, off = false, pair = {
            TileView(c.tile, w.dp)
            Text("→", color = dim)
            Text(if (best.action.type == "chi") actionText(res, best.action) else stringResource(actionLabel(best.action.type)),
              fontWeight = FontWeight.Bold, maxLines = 1)
          }, under = {
            Text(pts(gain), style = small, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(stringResource(R.string.whatif_left, c.left), style = small, color = dim, maxLines = 1)
          })
        }
      }
    }
    if (taken.isEmpty()) Text(stringResource(R.string.whatif_no_claims), style = MaterialTheme.typography.bodyMedium, color = dim)
    val left = data.claims.filter { it !in taken }.map { it.tile }.distinct().size
    if (left > 0) Text(stringResource(R.string.whatif_pass_better, left), style = small, color = dim)
  }
}
