package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Level
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.ReviewSummary
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.View
import io.github.kennyfs.mahjong.flowersOf
import io.github.kennyfs.mahjong.meldTiles
import io.github.kennyfs.mahjong.mistakesOf
import io.github.kennyfs.mahjong.mistakesText
import io.github.kennyfs.mahjong.pts
import kotlin.math.abs

/**
 * A finished hand: who won and how, the winner's hand (the winning tile apart), its tai items by name, the payments,
 * and the next dealer. `final` is the hand's view with everything shown (reveal). `rev`: the network's review of the
 * player's decisions in short, when there is one (the expected result at the start, what the decisions lost, the luck,
 * and the mistakes at the settings' `level`). The web page's HandResult.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandResultCard(
  final: View, names: List<String>, me: Int, w: Float, modifier: Modifier = Modifier, rev: ReviewSummary? = null, level: Level = Level.mid,
  actions: @Composable RowScope.() -> Unit = {},
) {
  val r = final.result ?: return
  val cs = MaterialTheme.colorScheme
  val res = LocalResources.current
  val title = when {
    r.exhausted || r.winners.isEmpty() -> stringResource(R.string.result_exhausted)
    r.flowerWin -> stringResource(R.string.result_flower_win, names[r.winners[0].seat])
    r.selfDraw -> stringResource(R.string.result_tsumo, names[r.winners[0].seat])
    else -> r.winners.map { stringResource(R.string.result_ron, names[it.seat]) }.joinToString("、")
  }
  val sub = if (!r.exhausted && !r.selfDraw && r.loser >= 0) stringResource(R.string.result_ron_from, names[r.loser]) else ""
  val setup = final.position.setup
  val dealer = setup.dealer
  val dealerShown = r.dealerTai > 0 && !r.exhausted && (r.winners.any { it.seat == dealer } || r.points[dealer] < 0)
  Surface(modifier, shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh, shadowElevation = 8.dp) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = cs.primary)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.titleMedium)
      }
      for (wn in r.winners) {
        val p = final.position.players[wn.seat]
        val all = Tiles.sort(Tiles.parse(final.hands?.getOrNull(wn.seat)))
        val win = if (r.winTile != null && !r.flowerWin) r.winTile else null
        val rest = if (win != null) Tiles.without(all, listOf(win)) else all
        FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Bottom) {
          for (m in p.melds) Row(Modifier.padding(end = 8.dp)) { for (t in meldTiles(m)) TileView(t, w.dp) }
          for (t in rest) TileView(t, w.dp)
          if (win != null) Box(Modifier.padding(start = 8.dp, end = 8.dp)) { TileView(win, w.dp, glow = true) }
          for (f in flowersOf(p)) TileView(f, (w * 0.8f).dp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          for (it in wn.items) Pill(cs.secondaryContainer, cs.onSecondaryContainer) {
            Text(it.name, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.tai_unit, it.tai.toString()), fontWeight = FontWeight.Bold)
          }
          if (dealerShown) Pill(cs.tertiaryContainer, cs.onTertiaryContainer) {
            Text(
              if (setup.streak > 0) stringResource(R.string.result_dealer_streak_tai, r.dealerTai, setup.streak)
              else stringResource(R.string.result_dealer_tai, r.dealerTai),
              fontWeight = FontWeight.Medium,
            )
          }
          Pill(cs.primary, cs.onPrimary) { Text(stringResource(R.string.result_tai_total, wn.tai), fontWeight = FontWeight.Bold) }
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (seat in 0..3) {
          val pts = r.points[seat]
          Column(
            Modifier.weight(1f).background(cs.surfaceContainer, RoundedCornerShape(10.dp))
              .let { if (seat == me) it.border(2.dp, cs.primary, RoundedCornerShape(10.dp)) else it }.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(names[seat], maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(signed(pts), color = pointsColor(pts), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            // How the points come about, "底 2＋1 台" for −3: the bases, and the tai (with the dealer's where they count).
            if (r.baseDelta.getOrElse(seat) { 0 } != 0) {
              val tai = r.taiDelta.getOrElse(seat) { 0 }
              val base = abs(pts - tai)
              Text(
                stringResource(R.string.result_parts, if (base % 1.0 == 0.0) base.toInt().toString() else "%.1f".format(base), abs(tai).toString()),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1,
              )
            }
          }
        }
      }
      if (rev != null) {
        val mistakes = mistakesOf(rev, level)
        FlowRow(
          Modifier.fillMaxWidth().background(cs.surfaceContainer, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("result_review_line"),
          horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(stringResource(R.string.review_line, pts(rev.first), pts(-rev.loss), pts(rev.luck)), style = MaterialTheme.typography.bodyMedium)
          Text(mistakesText(res, level, mistakes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = if (mistakes > 0) LocalOutcome.current.bad else LocalOutcome.current.good)
        }
      }
      Text(
        if (r.nextStreak > 0) stringResource(R.string.result_next_streak, names[r.nextDealer], r.nextStreak)
        else stringResource(R.string.result_next_dealer, names[r.nextDealer]),
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
      )
      // The buttons wrap to a second line rather than squeeze (a narrow phone, a large font).
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) { actions() }
    }
  }
}

@Composable
private fun Pill(container: Color, content: Color, body: @Composable RowScope.() -> Unit) {
  Surface(shape = RoundedCornerShape(10.dp), color = container, contentColor = content) {
    Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), content = body)
  }
}

/** A sheet over the table: dimmed, the card in the middle. */
@Composable
fun BoxScope.Sheet(content: @Composable BoxScope.() -> Unit) {
  Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)).padding(12.dp), contentAlignment = Alignment.Center) {
    Box(Modifier.widthIn(max = 760.dp), contentAlignment = Alignment.Center, content = content)
  }
}

/** The tile width for a result card in a window `w` dp wide. */
fun resultTileW(wide: Boolean, w: Float): Float = if (wide) (w / 27f).coerceIn(26f, 40f) else (w / 13f).coerceIn(26f, 36f)

