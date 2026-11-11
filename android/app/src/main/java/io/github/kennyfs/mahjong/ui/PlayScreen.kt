package io.github.kennyfs.mahjong.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.Page
import io.github.kennyfs.mahjong.Practice
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Rules
import io.github.kennyfs.mahjong.Tiles

/** The rules in a few words: "簡單台數・無花", and "自訂" when values were changed. */
@Composable
fun rulesChip(tai: String, flowers: String, custom: Boolean = false): String =
  stringResource(R.string.rules_chip, stringResource(Rules.taiLabel(tai)), stringResource(Rules.flowerLabel(flowers))) +
    if (custom) "・" + stringResource(R.string.rules_custom) else ""

/**
 * Practice: the table against three bots; a bar over it (a phone upright) with the hand, the rules and a new game; the
 * hand's result over the table when it ends (with the network's review of it in a line, once it is there), and the
 * game's totals when the game does. No hint or analysis while the hand is in play: the review (and each decision's
 * analysis) is a button on the result. The web page's Play.svelte.
 */
@Composable
fun PlayScreen(vm: AppModel) {
  val practice = vm.practice
  val settings = vm.settings
  val names = listOf(stringResource(R.string.you)) + (1..3).map { stringResource(R.string.cpu_n, it) }
  var confirmNew by remember { mutableStateOf(false) }
  var hideResult by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { if (practice.game == null) practice.resume() }
  val meta = practice.meta
  val view = practice.view
  val final = practice.final
  val stage = practice.stage
  LaunchedEffect(stage) { if (stage == Practice.Stage.PLAYING) hideResult = false }
  val handLabel = meta?.let { stringResource(R.string.hand_n, it.hands + if (stage == Practice.Stage.PLAYING) 1 else 0) } ?: ""
  val chip = rulesChip(settings.rules.tai, settings.rules.flowers, settings.rules.overrides.isNotEmpty())
  val totals = meta?.totals ?: listOf(0.0, 0.0, 0.0, 0.0)
  val shown = if (stage != Practice.Stage.PLAYING && final != null) final else view
  val last = practice.lastRecord?.let { vm.records.get(it) }
  val lastFav = last?.fav ?: false

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth > maxHeight
    val w = maxWidth.value
    Column(Modifier.fillMaxSize()) {
      if (!wide) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).tourAnchor("top").padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (view != null) Text(stringResource(R.string.round_wind, Tiles.wind(view.position.setup.round)) + " " + handLabel,
            style = MaterialTheme.typography.titleMedium, maxLines = 1)
          Box(Modifier.weight(1f)) { Chip(chip, onClick = { vm.open(Page.RULES) }, menu = true) }
          TextButton(onClick = { confirmNew = true }) { Text(stringResource(R.string.practice_new)) }
        }
      }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        if (shown == null) Loading()
        else TableView(
          shown, Practice.ME, names, scores = totals,
          revealed = if (stage != Practice.Stage.PLAYING) final?.hands else null,
          interactive = stage == Practice.Stage.PLAYING && !practice.busy,
          onAct = { practice.act(it) }, handLabel = if (wide) handLabel else "",
          corner = if (wide) ({
            Row(Modifier.tourAnchor("top"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              MiniButton(chip) { vm.open(Page.RULES) }
              MiniButton(stringResource(R.string.practice_new)) { confirmNew = true }
            }
          }) else null,
          overlay = {
            when {
              stage == Practice.Stage.RESULT && final?.result != null && !hideResult -> Sheet {
                HandResultCard(final, names, Practice.ME, resultTileW(wide, w), rev = last?.rev, level = settings.level) {
                  IconButton(onClick = { practice.lastRecord?.let { vm.records.setFav(it, !lastFav) } }) {
                    Icon(if (lastFav) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.favorite))
                  }
                  TextButton(onClick = { hideResult = true }) { Text(stringResource(R.string.result_hands)) }
                  FilledTonalButton(onClick = { practice.lastRecord?.let(vm::review) }, Modifier.testTag("result_review")) { Text(stringResource(R.string.review_hand)) }
                  Button(onClick = { practice.nextHand() }, Modifier.testTag("result_next")) {
                    Text(stringResource(if (meta?.over == true) R.string.see_totals else R.string.next_hand))
                  }
                }
              }
              stage == Practice.Stage.RESULT && hideResult -> Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Button(onClick = { hideResult = false }) { Text(stringResource(R.string.back)) }
              }
              stage == Practice.Stage.OVER && meta != null -> Sheet {
                Totals(names, totals, meta.hands) { practice.newGame() }
              }
            }
          },
        )
      }
    }
  }
  if (confirmNew) ConfirmDialog(stringResource(R.string.practice_new_confirm), onConfirm = { practice.newGame() }, onDismiss = { confirmNew = false })
}

/** A small outlined button on the felt, for the table's box. */
@Composable
fun MiniButton(text: String, onClick: () -> Unit) {
  val c = LocalTable.current
  Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = c.felt.copy(alpha = 0f), contentColor = c.onFelt,
    modifier = Modifier.border(1.dp, c.onFeltDim, RoundedCornerShape(12.dp))) {
    Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1,
      overflow = TextOverflow.Ellipsis)
  }
}

/** A practice game's end: each player's total, and a new game. */
@Composable
private fun Totals(names: List<String>, totals: List<Double>, hands: Int, onNew: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh, shadowElevation = 8.dp, modifier = Modifier.widthIn(min = 300.dp)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.game_over), style = MaterialTheme.typography.headlineSmall)
      Text(stringResource(R.string.hands_played, hands), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (seat in 0..3) {
          Column(
            Modifier.weight(1f).background(cs.surfaceContainer, RoundedCornerShape(12.dp))
              .let { if (seat == Practice.ME) it.border(2.dp, cs.primary, RoundedCornerShape(12.dp)) else it }.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(names[seat], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(signed(totals[seat]), color = pointsColor(totals[seat]), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
          }
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onNew, Modifier.testTag("result_next")) { Text(stringResource(R.string.new_game)) }
      }
    }
  }
}
