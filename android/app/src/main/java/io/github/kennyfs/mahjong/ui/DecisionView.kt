package io.github.kennyfs.mahjong.ui

import io.github.kennyfs.mahjong.Words
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Action
import io.github.kennyfs.mahjong.Analysis
import io.github.kennyfs.mahjong.ChoiceMark
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.View
import io.github.kennyfs.mahjong.flowersOf
import io.github.kennyfs.mahjong.meldTiles
import io.github.kennyfs.mahjong.tileMarks

/**
 * A decision as a list, in place of the table (a branch's, where the window has no room beside the table): the
 * deciding seat's tiles over the list of every action (DecisionPanel). The player may play here: a tap chooses a tile
 * (its row is lit) and a second discards it; the choices' buttons are under the hand. While there is nothing to show
 * (the computer players playing) the tiles are the player's own and the list keeps its shape (PanelSkeleton).
 * Sideways the tiles at the left, the list at the right; upright the tiles over the list, which scrolls alone. The web
 * page's DecisionView.svelte.
 *
 * `view`: the deciding seat's view (or the player's, meanwhile); `heading`: whose turn it is, in a few words. `canPlay`:
 * the player's decision, now (`view` is theirs). With 聽牌提示 (`readyHint`) the tiles a ready hand waits with have
 * blue faces (as on the table).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecisionView(
  view: View?, heading: String, analysis: Analysis?, names: List<String>, onAct: (Action) -> Unit, modifier: Modifier = Modifier,
  played: Int = -1, unit: Double? = null, who: String = "", canPlay: Boolean = false, choices: ((Action) -> ChoiceMark?)? = null,
  readyHint: Boolean = false, wide: Boolean = false, hw: Float = 40f,
) {
  val res = LocalResources.current
  val cs = MaterialTheme.colorScheme
  var selected by remember(view?.eventCount, view?.position?.me, canPlay) { mutableStateOf<Int?>(null) }
  val pos = view?.position
  val all = pos?.let { Tiles.parse(it.hand) } ?: emptyList()
  val drawn = if (view?.phase == "self") pos?.drawn else null
  val tiles = Tiles.sort(if (drawn != null) Tiles.without(all, listOf(drawn)) else all)
  val discardable = if (canPlay && view != null) view.legal.filter { it.type == "discard" }.mapNotNull { it.tile }.toSet() else emptySet()
  val claims = if (canPlay && view != null) view.legal.filter { it.type != "discard" } else emptyList()
  val picked = selected?.let { tiles.getOrNull(it) ?: drawn }
  val pickedIndex = if (analysis != null && picked != null) analysis.actions.indexOfFirst { it.action.type == "discard" && it.action.tile == picked } else -1
  val marks = remember(analysis, played, unit, res) { analysis?.let { tileMarks(res, it, played, unit) } ?: emptyMap() }
  val waitAt: Set<Int>? = if (!readyHint || view == null || pos == null) null else if (view.phase == "self" && view.current == pos.me) {
    val i = selected ?: if (drawn != null) tiles.size else null
    val x = i?.let { tiles.getOrNull(it) ?: drawn }
    x?.let { view.readyTiles[it] }?.let { Tiles.groupIndices(tiles, drawn, Tiles.parse(it), i) }
  } else view.waitTiles.takeIf { it.isNotEmpty() }?.let { Tiles.groupIndices(tiles, drawn, Tiles.parse(it), null) }

  BoxWithConstraints(modifier) {
    // The tiles as wide as `hw`, or less where the hand's column is narrower: eight a row and the drawn one after a gap.
    val colW = (if (wide) maxWidth.value * 0.46f - 24 else minOf(maxWidth.value, 760f) - 24)
    val w = minOf(hw, (colW - 4) / (8 + DRAWN_GAP + 1))
    val hand = @Composable {
      Text(
        listOfNotNull(
          pos?.let { stringResource(R.string.round_wind, Tiles.wind(it.setup.round)) + Words.sep + stringResource(R.string.wall_left, it.wallLeft) },
          heading.takeIf { it.isNotEmpty() },
        ).joinToString(Words.sep),
        Modifier.heightIn(min = 40.dp).padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
      )
      if (pos != null) {
        MyHand(tiles, drawn, w, HAND_ROW, discardable, { onAct(Action("discard", tile = it)) }, selected, { selected = it },
          marks = marks, tagH = 17f, turn = discardable.isNotEmpty(), pick = claims.isEmpty() || discardable.isNotEmpty(), waitAt = waitAt)
        val claim = pos.claim
        if (claim?.tile != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("${names[claim.seat]} ${stringResource(R.string.act_discard)}", style = MaterialTheme.typography.bodyMedium)
          TileView(claim.tile, w.dp, mark = true)
        }
        if (claims.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
          ClaimBar(claims, w, onAct, claim?.tile, choices)
        }
        val p = pos.players[pos.me]
        val flowers = flowersOf(p)
        if (p.melds.isNotEmpty() || flowers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Bottom) {
          for (m in p.melds) Row { for (t in meldTiles(m)) TileView(t, (w * 0.75f).dp) }
          for (f in flowers) TileView(f, (w * 0.6f).dp)
        }
      }
    }
    val list = @Composable {
      if (analysis != null) DecisionPanel(analysis, played = played, unit = unit, w = maxOf(30f, hw * 0.8f), names = names, picked = pickedIndex, who = who)
      else PanelSkeleton(w = maxOf(30f, hw * 0.8f))
    }
    if (wide) Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp).testTag("decision_view"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) { hand() }
      Column(Modifier.weight(0.54f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp)) { list() }
    } else Box(Modifier.fillMaxSize().testTag("decision_view"), contentAlignment = Alignment.TopCenter) {
      // The tiles stay put over the list, which scrolls alone: a tile's row brought into sight at the first tap does
      // not take the tile away from under the second.
      Column(Modifier.widthIn(max = 760.dp).fillMaxSize().padding(start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        hand()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) { list() }
      }
    }
  }
}
