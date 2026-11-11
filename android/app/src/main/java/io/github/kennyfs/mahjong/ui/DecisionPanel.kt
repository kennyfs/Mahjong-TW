package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Action
import io.github.kennyfs.mahjong.Analysis
import io.github.kennyfs.mahjong.EQUAL
import io.github.kennyfs.mahjong.GRADE_NAME
import io.github.kennyfs.mahjong.ListRows
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.Truth
import io.github.kennyfs.mahjong.actionText
import io.github.kennyfs.mahjong.actionTiles
import io.github.kennyfs.mahjong.costOf
import io.github.kennyfs.mahjong.efficiencyText
import io.github.kennyfs.mahjong.figureItems
import io.github.kennyfs.mahjong.gradeOf
import io.github.kennyfs.mahjong.isTaken
import io.github.kennyfs.mahjong.notableOf
import io.github.kennyfs.mahjong.notableText
import io.github.kennyfs.mahjong.pct
import io.github.kennyfs.mahjong.pts
import io.github.kennyfs.mahjong.readyLevel
import io.github.kennyfs.mahjong.reasonOf
import io.github.kennyfs.mahjong.tilesShort
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A grade's label on its color: "最佳", "一樣好", "疑問手", "失誤", "大失誤". */
@Composable
fun GradeBadge(grade: Int, text: String, modifier: Modifier = Modifier) {
  Text(
    text, modifier.background(if (grade > 0) Marks.danger(grade) else Marks.found, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
    color = if (grade > 0) Marks.on(grade) else Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1,
  )
}

/** The verdict's word for a played action: the best, as good as it, or its grade. */
@Composable
fun verdictWord(isBest: Boolean, loss: Double, grade: Int): String =
  stringResource(if (isBest) R.string.grade_best else if (loss < EQUAL) R.string.grade_equal else GRADE_NAME[grade])

/** A loss's color in a list: its grade's, toned for text on a surface. */
@Composable
private fun costColor(grade: Int): Color = when (grade) {
  1 -> lerp(MaterialTheme.colorScheme.onSurface, Marks.danger1, 0.6f)
  2 -> lerp(MaterialTheme.colorScheme.onSurface, Marks.danger2, 0.75f)
  3 -> Marks.danger3
  else -> Color.Unspecified
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

// The bars: an action's three parts against the best's, drawn to one scale from a line two thirds along.
private const val ZERO = 66f

/**
 * A decision's analysis by the network: every legal action from the best down, with what it costs against the best,
 * and what the network thinks of the three opponents. What a row shows for an action (the settings): `numbers`, its
 * figures; `bars`, where it differs from the best (what it wins, what it pays for dealing in, what it pays to others'
 * self-draws) with the few figures that matter most; `both`. Without a network (it failed to load) the list is tile
 * efficiency's. The web page's DecisionPanel.svelte.
 *
 * `played`: the index of the action taken (a review), or -1: its row comes first, the others from the best down. The
 * best and the one taken are both set off (a filled row, a tag each: 最佳, and `who` took it: 你 or the seat's name);
 * one row with both tags when they are the same. A row's figure at the right is always its expected result, with what
 * it loses against the best before it (smaller, in brackets) when that is more than a tie; an i button over the rows
 * says so. `picked`: the one whose tile is chosen in the hand.
 * `truth`: what the opponents really held, shown on request. `brief`: without the verdict on the action taken and the
 * note on a best action that is not the obvious one (the review says them itself).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecisionPanel(
  analysis: Analysis, modifier: Modifier = Modifier, played: Int = -1, unit: Double? = null, mode: ListRows = ListRows.numbers, w: Float = 32f,
  names: List<String>? = null, truth: List<Truth>? = null, showTruth: Boolean = false, picked: Int = -1, brief: Boolean = false,
  who: String = "",
) {
  if (!analysis.net) {
    AnalysisList(analysis, analysis.actions.getOrNull(played)?.action, w, modifier)
    return
  }
  val cs = MaterialTheme.colorScheme
  val res = LocalResources.current
  val small = MaterialTheme.typography.bodySmall
  val dim = cs.onSurfaceVariant
  val best = analysis.best ?: 0
  val rows = analysis.actions.indices.sortedWith(compareBy({ if (it == played) 0 else 1 }, { costOf(analysis, it) }, { it }))
  var help by remember { mutableStateOf(false) }
  val notable = notableOf(analysis, unit)
  val playedCost = if (played >= 0) costOf(analysis, played) else 0.0
  val relative = listOf(stringResource(R.string.rel_next), stringResource(R.string.rel_opposite), stringResource(R.string.rel_prev))
  val oppNames = (analysis.opponents ?: emptyList()).mapIndexed { r, o -> names?.getOrNull(o.seat) ?: relative.getOrElse(r) { "" } }
  val diffs = rows.map { i ->
    val b = analysis.actions[best].parts
    val p = analysis.actions[i].parts
    if (b != null && p != null && b.size >= 3 && p.size >= 3) listOf(b[0] - p[0], b[1] - p[1], b[2] - p[2]) else listOf(0.0, 0.0, 0.0)
  }
  var worse = 0.3
  var better = 0.15
  for (d in diffs) {
    worse = max(worse, d.sumOf { max(0.0, it) })
    better = max(better, d.sumOf { max(0.0, -it) })
  }
  val perPoint = min(ZERO / worse, (100 - ZERO) / better)
  val partColors = listOf(Marks.found, Marks.danger3, Marks.danger2)

  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (played >= 0 && !brief) {
      val grade = gradeOf(playedCost, unit)
      Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
          GradeBadge(if (played == best || playedCost < EQUAL) 0 else grade, verdictWord(played == best, playedCost, grade))
          if (playedCost >= EQUAL) Text(stringResource(R.string.review_loss, pts(playedCost, false)), fontWeight = FontWeight.Bold)
          if (played != best && playedCost >= EQUAL) Text(stringResource(R.string.review_best_was, actionText(res, analysis.actions[best].action)))
          val reason = reasonOf(res, analysis, played)
          if (reason.isNotEmpty()) Text(reason, color = dim)
        }
      }
    }
    if (notable != null && !brief) Surface(shape = RoundedCornerShape(12.dp), color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer,
      modifier = Modifier.fillMaxWidth()) {
      Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("★", color = Marks.found)
        Text(notableText(res, analysis, notable), fontWeight = FontWeight.Medium)
      }
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        if (mode != ListRows.numbers) {
          Text(stringResource(R.string.bars_legend), style = small, color = dim)
          for ((k, label) in listOf(R.string.bars_win, R.string.bars_deal, R.string.bars_tsumo).withIndex()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Box(Modifier.size(12.dp).background(partColors[k], RoundedCornerShape(3.dp)))
              Text(stringResource(label), style = small, color = dim)
            }
          }
        }
      }
      // What the figure at each row's right is: said here, not on every row.
      IconButton(onClick = { help = true }, Modifier.size(36.dp).testTag("list_help")) {
        Icon(Icons.Outlined.Info, stringResource(R.string.list_help), Modifier.size(20.dp), tint = dim)
      }
    }
    if (help) AlertDialog(
      onDismissRequest = { help = false },
      confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.close)) } },
      title = { Text(stringResource(R.string.list_help)) },
      text = { Text(stringResource(R.string.list_help_text), style = MaterialTheme.typography.bodyLarge) },
    )
    for ((k, i) in rows.withIndex()) {
      val f = analysis.actions[i]
      val cost = costOf(analysis, i)
      val g = gradeOf(cost, unit)
      val isBest = i == best
      val strong = isBest || (i == played && g == 0)
      val shape = RoundedCornerShape(12.dp)
      val soft = if (strong) cs.onSecondaryContainer.copy(alpha = 0.75f) else dim
      // What the action loses against the best, by its grade: a tint of yellow (questionable), orange (a mistake) or
      // red (a blunder), stronger on the one taken (which is not filled as the best is then).
      val tint = if (g > 0 && !isBest) lerp(cs.surfaceContainer, Marks.danger(g), if (i == played) listOf(0.45f, 0.42f, 0.34f)[g - 1] else listOf(0.18f, 0.18f, 0.15f)[g - 1]) else null
      Surface(
        shape = shape, color = tint ?: if (strong) cs.secondaryContainer else cs.surfaceContainer,
        contentColor = if (strong) cs.onSecondaryContainer else cs.onSurface,
        modifier = Modifier.fillMaxWidth().let { if (i == picked) it.border(2.dp, LocalTable.current.highlight, shape) else it },
      ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ts = actionTiles(f.action)
            if (ts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) { for (t in ts) TileView(t, (w * 0.82f).dp) }
            Text(if (ts.isNotEmpty()) stringResource(actionLabel(f.action.type)) else actionText(res, f.action), style = MaterialTheme.typography.labelLarge)
            if (isBest) Badge(stringResource(R.string.grade_best), Marks.found, Color.White)
            if (i == played) Badge(who.ifEmpty { stringResource(R.string.review_you) }, cs.primary, cs.onPrimary)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              if (!isBest && cost >= EQUAL) Text("(${pts(-cost)})", style = small, fontWeight = FontWeight.Bold,
                color = if (tint != null) lerp(cs.onSurface, Marks.danger3, 0.45f) else costColor(g))
              Text(pts(f.q ?: 0.0), fontWeight = FontWeight.Bold)
            }
          }
          if (mode != ListRows.numbers) {
            val track = cs.onSurface.copy(alpha = 0.08f)
            val line = cs.onSurface.copy(alpha = 0.55f)
            val d = diffs[k]
            Canvas(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(track)) {
              var l = ZERO.toDouble()
              var r = ZERO.toDouble()
              for (part in 0..2) {
                val wd = abs(d[part]) * perPoint
                if (wd < 0.4) continue
                val left = if (d[part] > 0) { l -= wd; l } else { val at = r; r += wd; at }
                drawRect(partColors[part], Offset((left / 100 * size.width).toFloat(), 0f), Size((wd / 100 * size.width).toFloat(), size.height))
              }
              val x = ZERO / 100 * size.width
              drawRect(line, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            }
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (mode == ListRows.bars) {
              Text("${stringResource(R.string.fig_win)} ${pct(f.win ?: 0.0)}", style = small)
              Text("${stringResource(R.string.fig_deal_in)} ${pct(f.dealIn ?: 0.0)}", style = small)
              val danger = f.danger
              if (danger != null && danger >= 0.01) Text("${stringResource(R.string.fig_danger)} ${pct(danger)}", style = small)
            } else {
              for ((label, value) in figureItems(res, f, null, oppNames).drop(1)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = small, color = soft)
                Text(value, style = small)
              }
            }
            Text(efficiencyText(res, f), style = small, color = soft)
          }
          if (f.waits.isNotEmpty() && f.shanten == 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.analysis_waits), style = small, color = soft)
            for (wt in f.waits) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
              TileView(wt.tile, (w * 0.6f).dp)
              Text(stringResource(R.string.analysis_wait_left, wt.left), style = small)
            }
          }
        }
      }
    }

    val opponents = analysis.opponents
    if (!opponents.isNullOrEmpty()) {
      Text(stringResource(R.string.opp_title), Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall,
        color = cs.primary)
      for ((r, o) in opponents.withIndex()) {
        val tr = if (showTruth) truth?.firstOrNull { it.seat == o.seat } else null
        val ready = o.ready.getOrElse(0) { 0.0 }
        Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
          Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
              itemVerticalAlignment = Alignment.CenterVertically) {
              Text(oppNames[r], style = MaterialTheme.typography.labelLarge)
              val level = readyLevel(ready)
              Text(
                stringResource(R.string.opp_ready, pct(ready)),
                Modifier.background(if (level > 0) Marks.danger(level) else cs.surfaceContainerHighest, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
                color = if (level > 0) Marks.on(level) else cs.onSurface, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
              )
              Text(stringResource(R.string.opp_one_away, pct(o.ready.getOrElse(1) { 0.0 })), style = small, color = dim)
              if (tr != null) Text(
                stringResource(R.string.opp_truth) + "：" + when {
                  tr.missed -> stringResource(R.string.opp_missed)
                  tr.shanten == 0 -> stringResource(R.string.opp_ready, tilesShort(tr.waits.map { it.tile }))
                  else -> stringResource(R.string.analysis_shanten, tr.shanten)
                },
                style = small, fontWeight = FontWeight.Bold,
              )
            }
            val dangers = o.danger.indices.filter { o.danger[it] >= 0.03 }.sortedByDescending { o.danger[it] }.take(5)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
              itemVerticalAlignment = Alignment.CenterVertically) {
              for (kind in dangers) {
                val p = o.danger[kind]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                  TileView(Tiles.KINDS[kind], (w * 0.6f).dp, danger = if (p >= 0.15) 3 else if (p >= 0.05) 2 else 1)
                  Text(pct(p), style = small)
                }
              }
              if (dangers.isEmpty()) Text(stringResource(R.string.opp_no_danger), style = small, color = dim)
            }
          }
        }
      }
    }
    Text(stringResource(R.string.analysis_source_network), Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp), style = small, color = dim)
  }
}

/**
 * An analysis by tile efficiency alone (the network did not load): each legal action with the tiles from ready, the
 * tiles that help, and when ready the waits with copies left and their tai, the best ones and the one chosen marked.
 * The web page's AnalysisList.svelte.
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
