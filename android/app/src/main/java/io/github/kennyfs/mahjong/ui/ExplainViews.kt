package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.kennyfs.mahjong.Discard
import io.github.kennyfs.mahjong.Explain
import io.github.kennyfs.mahjong.Explanation
import io.github.kennyfs.mahjong.R
import java.util.Locale

// A decision explained by rules (解說, Explain.kt) and the table tips (打牌要訣) its figures come from; the web page's
// ExplainDialog.svelte, TipsContent.svelte and Tips.svelte.

/** A decision's explanation: the lesson, the opponent's signs when it is about defense, the reason in this hand, and
 *  a rule of thumb; technical terms are clickable links that open the beginner glossary scrolled to that term. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExplainDialog(e: Explanation, onTips: () -> Unit, onDismiss: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  var glossaryTerm by remember { mutableStateOf<String?>(null) }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("explain_dialog"),
    title = { Text(e.title) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (e.found) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("★", color = Marks.found, style = MaterialTheme.typography.bodyMedium)
          Text(
            linkedGlossaryText(stringResource(R.string.explain_found), onTerm = { glossaryTerm = it }),
            color = cs.primary, style = MaterialTheme.typography.bodyMedium,
          )
        }
        if (e.you.isNotEmpty() && e.alt.isNotEmpty()) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip(stringResource(R.string.explain_you), e.you, alt = false)
            ChoiceChip(stringResource(if (e.found) R.string.explain_eff_choice else R.string.explain_better), e.alt, alt = true)
          }
        } else {
          Text(e.choice, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        e.signs?.let {
          Text(
            linkedGlossaryText(it, onTerm = { id -> glossaryTerm = id }),
            Modifier.fillMaxWidth().background(cs.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
          )
        }
        for (line in e.text) {
          Text(
            linkedGlossaryText(line, onTerm = { id -> glossaryTerm = id }),
            style = MaterialTheme.typography.bodyLarge, color = cs.onSurface,
          )
        }
        e.rule?.let {
          Column(Modifier.fillMaxWidth().background(cs.secondaryContainer, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.explain_rule), style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer)
            Text(
              linkedGlossaryText(it, onTerm = { id -> glossaryTerm = id }, linkColor = cs.onSecondaryContainer),
              style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer,
            )
          }
        }
      }
    },
    confirmButton = { FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    dismissButton = { TextButton(onClick = onTips, modifier = Modifier.testTag("explain_tips")) { Text(stringResource(R.string.tips_title)) } },
  )
  glossaryTerm?.let { termId ->
    GlossaryDialog(focusId = termId, onDismiss = { glossaryTerm = null })
  }
}

@Composable
private fun ChoiceChip(label: String, value: String, alt: Boolean) {
  val cs = MaterialTheme.colorScheme
  val bg = if (alt) cs.secondaryContainer else cs.surfaceContainerHigh
  val fg = if (alt) cs.onSecondaryContainer else cs.onSurface
  Surface(shape = RoundedCornerShape(10.dp), color = bg) {
    Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.8f), maxLines = 1)
      Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
  }
}

/** The tips over a review (from an explanation): Close comes back to it. */
@Composable
fun TipsDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
    modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 600.dp),
    title = { Text(stringResource(R.string.tips_title)) },
    text = { Column(Modifier.verticalScroll(rememberScrollState())) { TipsContent() } },
    confirmButton = { FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
  )
}

/** The tips on a page of their own, from the settings. */
@Composable
fun TipsScreen(onBack: () -> Unit) {
  Column(Modifier.fillMaxSize()) {
    TitleBar(stringResource(R.string.tips_title), onBack)
    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
      Box(Modifier.widthIn(max = 640.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) { TipsContent() }
    }
  }
}

private val SAMPLE = listOf(
  Discard("1z"), Discard("9m", drawn = true), Discard("2p", called = true), Discard("7s", afterCall = true), Discard("4z", drawn = true), Discard("8p", drawn = true, called = true),
)

/** "9%", "0.7%", "0.1%", "0%". */
private fun pc(p: Double): String = when {
  p < 0.0005 -> "0%"
  p < 0.0095 -> String.format(Locale.ROOT, "%.1f%%", p * 100)
  else -> "${Math.round(p * 100)}%"
}

/**
 * What self-play says about attack and defense, as tables a player can carry to the table: the river's marks (摸切, the
 * first discard after a call, claimed tiles), whether an opponent is ready by its discards and melds, when the network
 * falls back, and which tiles deal in least (Explain.kt, docs/explain.md).
 */
@Composable
fun TipsContent() {
  val res = LocalResources.current
  val cs = MaterialTheme.colorScheme
  val heading = @Composable { id: Int -> Text(stringResource(id), style = MaterialTheme.typography.titleMedium) }
  val para = @Composable { s: String -> Text(s, style = MaterialTheme.typography.bodyMedium) }
  val note = @Composable { s: String -> Text(s, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
  val cellBorder = BorderStroke(0.5.dp, cs.outlineVariant.copy(alpha = 0.55f))
  // A table: optional grouped super-header over numeric columns, header row, and heat-shaded rows.
  val grid = @Composable { head: List<String>, rows: List<Pair<String, List<Double?>>>, top: Double, superHead: String? ->
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      if (superHead != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          Box(Modifier.weight(1.25f))
          Text(superHead, Modifier.weight((head.size - 1).toFloat()).padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        head.forEachIndexed { i, h ->
          Text(h, Modifier.weight(if (i == 0) 1.25f else 1f).padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
        }
      }
      for ((label, xs) in rows) Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.25f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
          color = cs.onSurfaceVariant, maxLines = 1)
        for (p in xs) {
          val bg = if (p == null) cs.surfaceContainerHigh else lerp(cs.surfaceContainerHigh, cs.error, (minOf(1.0, p / top) * 0.48).toFloat())
          Text(if (p == null) "–" else pc(p),
            Modifier.weight(1f).background(bg, RoundedCornerShape(6.dp)).border(cellBorder, RoundedCornerShape(6.dp)).padding(vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = if (p == null) cs.outline else cs.onSurface)
        }
      }
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      heading(R.string.tips_river_title)
      Box(Modifier.background(LocalTable.current.felt, RoundedCornerShape(12.dp)).padding(8.dp)) {
        River(SAMPLE, w = 34f, cols = 6, rows = 0, last = false)
      }
      para(stringResource(R.string.tips_river_text))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      heading(R.string.tips_ready_title)
      para(stringResource(R.string.tips_ready_text))
      grid(listOf(stringResource(R.string.tips_discards), "0", "1", "2", "3", "4"),
        listOf("3–5", "6–8", "9–11", "12+").zip(Explain.TIPS_THREAT), 0.9, stringResource(R.string.tips_melds_head))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      heading(R.string.tips_fold_title)
      para(stringResource(R.string.tips_fold_text))
      grid(listOf(stringResource(R.string.tips_wall_head), "30+", "20–29", "10–19", "<10"),
        listOf(Explain.steps(res, 0), Explain.steps(res, 1), Explain.steps(res, 2), stringResource(R.string.tips_away_more))
          .map { x -> x.replaceFirstChar { it.uppercaseChar() } }.zip(Explain.TIPS_FOLD), 0.7, null)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      heading(R.string.tips_safe_title)
      para(stringResource(R.string.tips_safe_text))
      grid(listOf("", stringResource(R.string.tips_col_genbutsu), stringResource(R.string.tips_col_suji),
        res.getString(R.string.tips_col_seen, 2), res.getString(R.string.tips_col_seen, 1), stringResource(R.string.tips_col_unseen)),
        listOf(stringResource(R.string.tips_row_honor), stringResource(R.string.explain_rank_19), stringResource(R.string.explain_rank_28),
          stringResource(R.string.explain_rank_37), stringResource(R.string.explain_rank_456)).zip(Explain.TIPS_SAFETY), 0.14, null)
      note(stringResource(R.string.tips_safe_note))
      note(res.getString(R.string.tips_half_note, Explain.percent(res, Explain.TIPS_HALF)))
    }
    note(stringResource(R.string.tips_source))
    note(stringResource(R.string.tips_more))
  }
}
