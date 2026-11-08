package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Action
import io.github.kennyfs.mahjong.Analysis
import io.github.kennyfs.mahjong.AnalysisTarget
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.View
import io.github.kennyfs.mahjong.actionTiles
import io.github.kennyfs.mahjong.flowersOf
import io.github.kennyfs.mahjong.fullAction
import io.github.kennyfs.mahjong.isTaken
import io.github.kennyfs.mahjong.meldTiles
import io.github.kennyfs.mahjong.recordAction

/** An action in words: "打出 五萬", "吃 三四五萬". */
@Composable
fun actionText(a: Action): String {
  val word = stringResource(actionLabel(a.type))
  val ts = actionTiles(a)
  return if (ts.isEmpty()) word else "$word ${ts.joinToString("") { Tiles.name(it) }}"
}

/**
 * A finished hand's review: the table as the player saw it (or everyone's tiles), step by step through the record's
 * decisions (others' passes skipped), each decision open to analysis, and the result at the end. The web page's
 * Replay.svelte.
 */
@Composable
fun ReplayScreen(vm: AppModel, onBack: () -> Unit) {
  val rec = vm.reviewId?.let { vm.records.get(it) }
  if (rec == null) {
    Column(Modifier.fillMaxSize()) {
      TitleBar(stringResource(R.string.replay_title), onBack)
      Text(stringResource(R.string.replay_nothing), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
    }
    return
  }
  val n = rec.game.actions.size
  var step by remember(rec.id) { mutableStateOf(vm.reviewStep.coerceIn(0, n)) }
  LaunchedEffect(step) { vm.reviewStep = step }
  var showAll by remember { mutableStateOf(false) }
  var showResult by remember { mutableStateOf(true) }
  var view by remember(rec.id) { mutableStateOf<View?>(null) }
  var final by remember(rec.id) { mutableStateOf<View?>(null) }
  LaunchedEffect(rec.id) { final = runCatching { Engine.view(rec.game, rec.me, 0, reveal = true) }.getOrNull() }
  LaunchedEffect(rec.id, step, showAll) {
    view = runCatching { Engine.view(rec.game.cut(step), rec.me, 0, reveal = showAll || step == n) }.getOrNull()
  }
  val you = stringResource(R.string.you)
  val names = rec.names.mapIndexed { i, x -> if (i == rec.me) you else x }
  fun isStop(k: Int): Boolean {
    val (seat, a) = recordAction(rec.game.actions[k])
    return a.type != "pass" || seat == rec.me
  }
  fun mine(k: Int) = recordAction(rec.game.actions[k]).first == rec.me
  fun next() { var k = step + 1; while (k < n && !isStop(k)) k++; step = minOf(k, n); showResult = true }
  fun prev() { var k = step - 1; while (k > 0 && !isStop(k)) k--; step = maxOf(0, k) }
  fun nextMine() { var k = step + 1; while (k < n && !mine(k)) k++; step = minOf(k, n); showResult = true }
  fun prevMine() { var k = step - 1; while (k > 0 && !mine(k)) k--; step = maxOf(0, k) }
  val deciding = if (step < n) recordAction(rec.game.actions[step]).let { (s, a) -> s to fullAction(a) } else null
  fun analyze() {
    val d = deciding ?: return
    vm.analyze(AnalysisTarget(rec.game.cut(step), d.first, d.second, names))
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val side = maxWidth > maxHeight && maxHeight < 560.dp
    val wide = maxWidth > maxHeight
    val w = maxWidth.value
    val fav = rec.fav
    val table = @Composable { mod: Modifier ->
      Box(mod) {
        val v = view
        if (v == null) Loading()
        else TableView(
          v, rec.me, names, revealed = if (showAll || step == n) v.hands else null, interactive = false, maxHand = 56f,
          overlay = {
            val f = final
            if (step == n && f?.result != null && showResult) Sheet {
              HandResultCard(f, names, rec.me, resultTileW(wide, w)) {
                TextButton(onClick = { showResult = false }) { Text(stringResource(R.string.result_hands)) }
                FilledTonalButton(onClick = { step = 0 }) { Text(stringResource(R.string.replay_start)) }
              }
            }
          },
        )
      }
    }
    val info = @Composable {
      Text(if (step >= n) stringResource(R.string.replay_end) else stringResource(R.string.replay_step, step + 1, n),
        style = MaterialTheme.typography.labelLarge)
      if (deciding != null) Text(
        if (deciding.first == rec.me) stringResource(R.string.replay_you_chose, actionText(deciding.second))
        else stringResource(R.string.replay_seat_chose, names[deciding.first], actionText(deciding.second)),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (side) 3 else 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    val buttons = listOf(
      Triple(Icons.Filled.SkipPrevious, R.string.replay_start) { step = 0 },
      Triple(Icons.AutoMirrored.Filled.Undo, R.string.replay_prev_decision) { prevMine() },
      Triple(Icons.AutoMirrored.Filled.NavigateBefore, R.string.replay_prev) { prev() },
      Triple(Icons.AutoMirrored.Filled.NavigateNext, R.string.replay_next) { next() },
      Triple(Icons.AutoMirrored.Filled.Redo, R.string.replay_next_decision) { nextMine() },
      Triple(Icons.Filled.SkipNext, R.string.replay_end) { step = n; showResult = true },
    )
    val step0 = step
    @Composable
    fun stepButton(i: Int) {
      val (icon, label, go) = buttons[i]
      val enabled = if (i < 3) step0 > 0 else step0 < n
      val tag = Modifier.testTag("replay_step_$i")
      if (i == 3) FilledIconButton(onClick = go, enabled = enabled, modifier = tag.size(48.dp)) { Icon(icon, stringResource(label)) }
      else FilledTonalIconButton(onClick = go, enabled = enabled, modifier = tag.size(44.dp)) { Icon(icon, stringResource(label)) }
    }
    val analyzeButton = @Composable {
      if (deciding != null) Button(onClick = ::analyze, Modifier.testTag("replay_analyze")) {
        Icon(Icons.Filled.Assessment, null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.analyze_title))
      } else if (step >= n && !showResult) FilledTonalButton(onClick = { showResult = true }) { Text(stringResource(R.string.replay_result)) }
    }
    val favButton = @Composable {
      IconButton(onClick = { vm.records.setFav(rec.id, !fav) }) {
        Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.favorite))
      }
    }
    val allButton = @Composable {
      IconToggleButton(checked = showAll, onCheckedChange = { showAll = it }) {
        Icon(Icons.Filled.Visibility, stringResource(R.string.replay_show_all))
      }
    }
    if (side) {
      Row(Modifier.fillMaxSize()) {
        table(Modifier.weight(1f).fillMaxHeight())
        Column(
          Modifier.width(184.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(rememberScrollState())
            .padding(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            if (vm.records.get(rec.id) != null) favButton()
            allButton()
          }
          info()
          for (r in 0..1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            for (i in r * 3 until r * 3 + 3) stepButton(i)
          }
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { analyzeButton() }
        }
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.replay_title), onBack) {
          if (vm.records.get(rec.id) != null) favButton()
          allButton()
        }
        table(Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 10.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)) {
          // The step and the choice made, with the analysis button at their end; under them the steps, spread over the
          // width (all seven in one row squeezed the button's label into a column).
          Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) { info() }
            analyzeButton()
          }
          Row(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in buttons.indices) stepButton(i)
          }
        }
      }
    }
  }
}

/**
 * One decision's analysis, from a finished hand (its review): the engine's figures for each legal action of the
 * deciding seat, the one taken marked, with the table as that seat saw it. A large window shows both side by side; a
 * phone shows the seat's hand over the list, and the whole table at a tap. Only after the hand: never while it is
 * played. The web page's DecisionAnalysis.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecisionAnalysisScreen(vm: AppModel, onBack: () -> Unit) {
  val target = vm.analysis ?: return onBack()
  var view by remember(target) { mutableStateOf<View?>(null) }
  var analysis by remember(target) { mutableStateOf<Analysis?>(null) }
  var error by remember(target) { mutableStateOf<String?>(null) }
  var showTable by remember { mutableStateOf(false) }
  LaunchedEffect(target) {
    try {
      view = Engine.view(target.game, target.seat)
      analysis = Engine.analyzeGame(target.game, target.seat)
    } catch (e: Exception) {
      error = e.message ?: ""
    }
  }
  val names = target.names
  val seat = target.seat
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val sideBySide = maxWidth > maxHeight && maxWidth >= 1000.dp
    val sideways = maxWidth > maxHeight
    val hw = minOf(44f, (minOf(maxWidth.value, 720f) - 32) / 9.4f)
    val half = minOf(44f, (maxWidth.value / 2 - 24) / 9.4f)
    Column(Modifier.fillMaxSize()) {
      TitleBar(stringResource(R.string.analysis_title_decision, names[seat]), onBack) {
        if (!sideBySide) Chip(
          stringResource(if (showTable) R.string.analysis_results else R.string.records_table), onClick = { showTable = !showTable }, on = showTable,
          leading = { Icon(if (showTable) Icons.Filled.Assessment else Icons.Filled.Style, null, Modifier.size(18.dp)) },
        )
      }
      error?.let { Text(stringResource(R.string.analysis_error, it), Modifier.padding(16.dp, 8.dp), color = MaterialTheme.colorScheme.error) }
      val v = view
      when {
        sideBySide -> Row(Modifier.weight(1f)) {
          Box(Modifier.weight(1f).fillMaxHeight()) { if (v != null) TableView(v, seat, names, interactive = false, maxHand = 52f) }
          Column(Modifier.width(460.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SectionHeader(stringResource(R.string.analysis_results))
            analysis?.let { AnalysisList(it, target.chosen, 34f, Modifier.padding(horizontal = 12.dp)) } ?: if (error == null) Loading(Modifier.padding(32.dp)) else Unit
          }
        }
        showTable -> Box(Modifier.weight(1f).fillMaxWidth()) { if (v != null) TableView(v, seat, names, interactive = false) }
        // A phone sideways: the seat's tiles at the left, the choices at the right, each scrolling on its own.
        sideways -> Row(Modifier.weight(1f).fillMaxWidth()) {
          Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (v != null) DecisionSummary(v, seat, names, half)
          }
          Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp, bottom = 16.dp)) {
            val a = analysis
            if (a != null) AnalysisList(a, target.chosen, maxOf(28f, half * 0.8f))
            else if (error == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
          }
        }
        else -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally) {
          Column(Modifier.readable().fillMaxWidth()) {
            if (v != null) DecisionSummary(v, seat, names, hw)
            SectionHeader(stringResource(R.string.analysis_results))
            val a = analysis
            if (a != null) AnalysisList(a, target.chosen, maxOf(30f, hw * 0.8f), Modifier.padding(horizontal = 12.dp))
            else if (error == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
          }
        }
      }
    }
  }
}

/** The deciding seat's situation: the round and wall, its tiles (the drawn one apart, or the discard decided on), its melds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecisionSummary(v: View, seat: Int, names: List<String>, hw: Float) {
  val pos = v.position
  val all = Tiles.parse(pos.hand)
  val drawn = if (v.phase == "self") pos.drawn else null
  val tiles = Tiles.sort(if (drawn != null) Tiles.without(all, listOf(drawn)) else all)
  val p = pos.players[seat]
  val head = stringResource(R.string.round_wind, Tiles.wind(pos.setup.round)) + "・" + stringResource(R.string.wall_left, pos.wallLeft)
  val claim = pos.claim
  val situation = when {
    claim?.tile != null -> "$head・${names[claim.seat]} ${stringResource(R.string.act_discard)} ${Tiles.name(claim.tile)}"
    drawn != null -> "$head・${stringResource(R.string.analysis_drawn)} ${Tiles.name(drawn)}"
    else -> head
  }
  Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(situation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Bottom) {
      for (t in tiles) TileView(t, hw.dp)
      if (drawn != null) Box(Modifier.padding(start = 10.dp)) { TileView(drawn, hw.dp, glow = true) }
      if (claim?.tile != null) Box(Modifier.padding(start = 10.dp)) { TileView(claim.tile, hw.dp, mark = true) }
    }
    val flowers = flowersOf(p)
    if (p.melds.isNotEmpty() || flowers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Bottom) {
      for (m in p.melds) Row { for (t in meldTiles(m)) TileView(t, (hw * 0.75f).dp) }
      for (f in flowers) TileView(f, (hw * 0.6f).dp)
    }
  }
}

/**
 * An analysis: each legal action with the engine's figures (tile efficiency now: shanten, the tiles that help, and
 * when ready the waits with copies left and their tai), the best ones and the one chosen marked. Fields a network adds
 * later are shown when present (docs/app.md). The web page's AnalysisList.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisList(analysis: Analysis, chosen: Action?, w: Float, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  val best = analysis.best()
  val order = analysis.actions.indices.sortedWith(compareBy({ analysis.actions[it].rank.first }, { analysis.actions[it].rank.second }, { it }))
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    for (i in order) {
      val f = analysis.actions[i]
      val isBest = i in best
      val isChosen = chosen != null && isTaken(f.action, chosen)
      Surface(
        shape = RoundedCornerShape(12.dp), color = if (isBest) cs.secondaryContainer else cs.surfaceContainer,
        contentColor = if (isBest) cs.onSecondaryContainer else cs.onSurface,
        modifier = Modifier.fillMaxWidth().let { if (isChosen) it.border(2.dp, cs.primary, RoundedCornerShape(12.dp)) else it },
      ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(actionText(f.action), style = MaterialTheme.typography.labelLarge)
            val ts = actionTiles(f.action)
            if (ts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) { for (t in ts) TileView(t, (w * 0.8f).dp) }
            Spacer(Modifier.weight(1f))
            if (isBest) Badge(stringResource(R.string.analysis_best), cs.primary, cs.onPrimary)
            if (isChosen) Badge(stringResource(R.string.analysis_chosen), cs.tertiaryContainer, cs.onTertiaryContainer)
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val body = MaterialTheme.typography.bodyMedium
            when {
              f.shanten < 0 -> Text(stringResource(R.string.analysis_win) + if (f.winTai >= 0) "・" + stringResource(R.string.analysis_win_tai, f.winTai) else "",
                style = body, color = LocalOutcome.current.bad, fontWeight = FontWeight.Bold)
              f.shanten == 0 -> {
                Text(stringResource(R.string.analysis_ready), style = body, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.analysis_ukeire, f.ukeire), style = body)
              }
              else -> {
                Text(stringResource(R.string.analysis_shanten, f.shanten), style = body)
                Text(stringResource(R.string.analysis_ukeire, f.ukeire), style = body)
              }
            }
            for ((k, x) in f.extra) Text("$k ${"%.3f".format(x)}", style = body)
          }
          if (f.waits.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.analysis_waits), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            for (wt in f.waits) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              TileView(wt.tile, (w * 0.75f).dp)
              Column {
                Text(stringResource(R.string.analysis_wait_left, wt.left), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.analysis_wait_tai, wt.ronTai, wt.tsumoTai), style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      }
    }
    if (analysis.actions.isEmpty()) Text(stringResource(R.string.analysis_no_actions), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
    Text(stringResource(R.string.analysis_source_efficiency), Modifier.padding(top = 8.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall,
      color = cs.onSurfaceVariant)
  }
}

