package io.github.kennyfs.mahjong.ui

import io.github.kennyfs.mahjong.Words
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
 * A finished hand: who won and how, the winner's hand (the winning tile apart), its tai items by name (their total
 * says what every payer pays in tai: the dealer's tai is in it when every payment has it), the payments, and the next
 * dealer. `final` is the hand's view with everything shown (reveal). `rev`: the network's review of the
 * player's decisions in short, when there is one (the expected result at the start, what the decisions lost, the luck,
 * and the mistakes at the settings' `level`). The web page's HandResult.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandResultCard(
  final: View, names: List<String>, me: Int, w: Float, modifier: Modifier = Modifier, rev: ReviewSummary? = null, level: Level = Level.mid,
  next: Boolean = true, rules: String? = null, actions: @Composable RowScope.() -> Unit = {},
) {
  val r = final.result ?: return
  val cs = MaterialTheme.colorScheme
  val res = LocalResources.current
  val title = when {
    r.exhausted || r.winners.isEmpty() -> stringResource(R.string.result_exhausted)
    r.flowerWin -> stringResource(R.string.result_flower_win, names[r.winners[0].seat])
    r.selfDraw -> stringResource(R.string.result_tsumo, names[r.winners[0].seat])
    else -> r.winners.map { stringResource(R.string.result_ron, names[it.seat]) }.joinToString(Words.list)
  }
  val sub = if (!r.exhausted && !r.selfDraw && r.loser >= 0) stringResource(R.string.result_ron_from, names[r.loser]) else ""
  val setup = final.position.setup
  val dealer = setup.dealer
  // Payments involving the dealer carry the dealer's tai: every payment of a win by the dealer or on the dealer's
  // discard (the total says them all, then); of another's self-drawn win only the dealer's own (said after the total).
  val dealerWord = if (setup.streak > 0) stringResource(R.string.result_dealer_streak_tai, r.dealerTai, setup.streak)
    else stringResource(R.string.result_dealer_tai, r.dealerTai)
  fun dealerIn(seat: Int) = r.dealerTai > 0 && !r.exhausted && (seat == dealer || (!r.selfDraw && r.loser == dealer))
  fun dealerPays(seat: Int) = r.dealerTai > 0 && !r.exhausted && r.selfDraw && seat != dealer && r.points[dealer] < 0
  Surface(modifier, shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh, shadowElevation = 8.dp) {
   run {
    // The card's own width in dp, less its padding (0 until it is laid out: the tiles are the caller's size then).
    var room by remember { mutableStateOf(0f) }
    val density = LocalDensity.current.density
    // The buttons keep in sight: the rest scrolls over them where the card is taller than its room (a phone held
    // sideways, a hand of melds: the buttons were under the screen's edge).
    Column(Modifier.onSizeChanged { room = it.width / density - 32f }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
     Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = cs.primary)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.titleMedium)
        // A review's rules (the hand may be old).
        if (rules != null) Text(rules, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
      for (wn in r.winners) {
        val p = final.position.players[wn.seat]
        val all = Tiles.sort(Tiles.parse(final.hands?.getOrNull(wn.seat)))
        val win = if (r.winTile != null && !r.flowerWin) r.winTile else null
        val rest = if (win != null) Tiles.without(all, listOf(win)) else all
        // The hand on one line where a little smaller tile lets it be (down to 22 dp): the winning tile alone on a
        // second line cost the card its height.
        val flowers = flowersOf(p)
        val count = p.melds.sumOf { meldTiles(it).size } + rest.size + (if (win != null) 1 else 0) + flowers.size * 0.8f
        val gaps = p.melds.size * 8f + (if (win != null) 16f else 0f)
        val fit = (room - gaps - 1f) / count
        val tw = if (room > 0f && fit >= 22f) minOf(w, fit) else w
        FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Bottom) {
          for (m in p.melds) Row(Modifier.padding(end = 8.dp)) { for (t in meldTiles(m)) TileView(t, tw.dp) }
          // The concealed tiles keep together: on a line of their own where they do not fit after the melds.
          FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp)) { for (t in rest) TileView(t, tw.dp) }
          if (win != null) Box(Modifier.padding(start = 8.dp, end = 8.dp)) { TileView(win, tw.dp, glow = true) }
          for (f in flowers) TileView(f, (tw * 0.8f).dp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          for (it in wn.items) Pill(cs.secondaryContainer, cs.onSecondaryContainer) {
            Text(it.label, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.tai_unit, it.tai.toString()), fontWeight = FontWeight.Bold)
          }
          if (dealerIn(wn.seat)) Pill(cs.tertiaryContainer, cs.onTertiaryContainer) { Text(dealerWord, fontWeight = FontWeight.Medium) }
          Pill(cs.primary, cs.onPrimary) {
            Text(stringResource(R.string.result_tai_total, wn.tai + if (dealerIn(wn.seat)) r.dealerTai else 0), fontWeight = FontWeight.Bold)
          }
          if (dealerPays(wn.seat)) Pill(cs.tertiaryContainer, cs.onTertiaryContainer) {
            Text(stringResource(R.string.result_dealer_pays, dealerWord), fontWeight = FontWeight.Medium)
          }
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
                // (Two lines where a quarter of the card is too narrow: "Base 2 + 1 tai" on a phone, two-digit tai.)
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center,
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
          Text(mistakesText(res, level, rev.grades), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = if (mistakes > 0) LocalOutcome.current.bad else LocalOutcome.current.good)
        }
      }
      // The next hand's dealer (not for a review's branch: there is no next hand).
      if (next) Text(
        if (r.nextStreak > 0) stringResource(R.string.result_next_streak, names[r.nextDealer], r.nextStreak)
        else stringResource(R.string.result_next_dealer, names[r.nextDealer]),
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
      )
     }
      // The buttons wrap to a second line rather than squeeze (a narrow phone, a large font).
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) { actions() }
    }
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

