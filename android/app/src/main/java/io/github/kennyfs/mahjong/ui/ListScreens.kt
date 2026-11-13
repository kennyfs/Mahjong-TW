package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.BOTS
import io.github.kennyfs.mahjong.BattleGroup
import io.github.kennyfs.mahjong.CallFxMode
import io.github.kennyfs.mahjong.ColorStyle
import io.github.kennyfs.mahjong.BuildConfig
import io.github.kennyfs.mahjong.DarkMode
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.EngineException
import io.github.kennyfs.mahjong.HandRecord
import io.github.kennyfs.mahjong.Length
import io.github.kennyfs.mahjong.Level
import io.github.kennyfs.mahjong.ListRows
import io.github.kennyfs.mahjong.LossKind
import io.github.kennyfs.mahjong.Page
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.RULE_HELP
import io.github.kennyfs.mahjong.RecordKind
import io.github.kennyfs.mahjong.RuleChoice
import io.github.kennyfs.mahjong.Rules
import io.github.kennyfs.mahjong.RulesInfo
import io.github.kennyfs.mahjong.SoundLevel
import io.github.kennyfs.mahjong.Sounds
import io.github.kennyfs.mahjong.Speed
import io.github.kennyfs.mahjong.TableMarks
import io.github.kennyfs.mahjong.TextSize
import io.github.kennyfs.mahjong.levelOrWorse
import io.github.kennyfs.mahjong.mistakesOf
import io.github.kennyfs.mahjong.pts
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** A record's time (ISO 8601) here: "2026-11-06 13:00". */
fun localTime(stamp: String): String =
  runCatching { TIME.format(Instant.parse(stamp).atZone(ZoneId.systemDefault())) }.getOrDefault(stamp.take(16).replace('T', ' '))

/** A spec's presets in words, and "自訂" when it changes values. */
@Composable
fun rulesOf(spec: String): String {
  val parts = spec.split(',')
  val tai = parts.getOrNull(0)?.takeIf { it in Rules.TAI_PRESETS } ?: "simple1"
  val flowers = parts.getOrNull(1)?.takeIf { it in Rules.FLOWER_PRESETS } ?: "none"
  return rulesChip(tai, flowers, parts.size > 2)
}

/**
 * The records: every finished hand, newest first, in three tabs (practice, table battles, same-tiles contests); a line's
 * tap opens the hand's review. Favorites stay when the rest are cleared. Battles are listed as groups of their hands.
 * Over the list, what the network's reviews of these hands add up to: the points lost a hand, the mistakes a hand at
 * the settings' level, and which kind of decision lost them. The web page's Records.svelte.
 */
@Composable
fun RecordsScreen(vm: AppModel) {
  val records = vm.records
  val level = vm.settings.level
  var tab by remember { mutableStateOf(RecordKind.practice) }
  var favOnly by remember { mutableStateOf(false) }
  var confirmClear by remember { mutableStateOf(false) }
  var openBattle by remember { mutableStateOf<String?>(null) }
  val hands = records.hands.filter { it.kind == tab && (!favOnly || it.fav) }.asReversed()
  val battles = records.battles.filter { b -> b.kind == tab && (!favOnly || b.fav || records.ofGroup(b.id).any { it.fav }) }.asReversed()
  val all = records.hands.filter { it.kind == tab }
  // The reviews of this tab's hands, added up; the hands not reviewed by this network can be, one after another.
  val reviewed = all.filter { it.rev?.m == Engine.MODEL_ID }
  val pending = all.filter { it.rev?.m != Engine.MODEL_ID }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var confirmReset by remember { mutableStateOf(false) }
  val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri != null) scope.launch {
      val text = records.exportText(BuildConfig.VERSION_NAME ?: "")
      runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } } }
        .onFailure { vm.error = it.message }
    }
  }
  val stats = stringResource(
    R.string.records_stats, all.size, all.count { it.me in it.winners }, all.count { it.loser == it.me }, signed(all.sumOf { it.points[it.me] }),
  )
  Column(Modifier.fillMaxSize()) {
    TitleBar(stringResource(R.string.records_title)) {
      TextButton(onClick = { confirmClear = true }, enabled = records.hands.any { !it.fav }) { Text(stringResource(R.string.records_clear)) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(Modifier.readable(760.dp).fillMaxWidth()) {
        item {
          Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleGroup(
              listOf(RecordKind.practice to stringResource(R.string.records_practice), RecordKind.table to stringResource(R.string.records_table),
                RecordKind.contest to stringResource(R.string.records_contest)),
              tab, { tab = it }, Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(stats, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Chip(stringResource(R.string.records_favorites_only), onClick = { favOnly = !favOnly }, on = favOnly,
                leading = { Icon(if (favOnly) Icons.Filled.Star else Icons.Filled.StarBorder, null, Modifier.size(18.dp)) })
            }
          }
        }
        if (reviewed.isNotEmpty() || pending.isNotEmpty()) item {
          ReviewStats(reviewed, pending.size, level, vm.reviewing, onRun = { vm.reviewAll(pending.asReversed()) }, onStop = vm::stopReviews)
        }
        if (tab == RecordKind.practice) {
          items(hands, key = { it.id }) { h -> HandLine(h, onOpen = { vm.review(h.id) }) { records.setFav(h.id, !h.fav) } }
          if (hands.isEmpty()) item { Empty() }
        } else {
          for (b in battles) {
            item(key = b.id) {
              BattleLine(b, open = openBattle == b.id, onOpen = { openBattle = if (openBattle == b.id) null else b.id }) {
                records.setBattleFav(b.id, !b.fav)
              }
            }
            if (openBattle == b.id) items(records.ofGroup(b.id), key = { "${b.id}/${it.id}" }) { h ->
              Box(Modifier.padding(start = 16.dp)) { HandLine(h, onOpen = { vm.review(h.id) }) { records.setFav(h.id, !h.fav) } }
            }
          }
          if (battles.isEmpty()) item { Empty() }
        }
        item(key = "dev") {
          ForDeveloper(records.hands.size, onShare = { scope.launch { shareRecords(context, records.exportText(BuildConfig.VERSION_NAME ?: "")) } },
            onSave = { save.launch(exportName()) }, onReset = { confirmReset = true })
        }
      }
    }
  }
  if (confirmReset) ConfirmDialog(stringResource(R.string.records_reset_scores_confirm), onConfirm = { vm.practice.newGame() },
    onDismiss = { confirmReset = false }, confirm = stringResource(R.string.records_reset_scores_ok))
  if (confirmClear) ConfirmDialog(stringResource(R.string.records_clear_confirm), onConfirm = { records.clear() }, onDismiss = { confirmClear = false },
    confirm = stringResource(R.string.delete))
}

/**
 * What the reviews of a tab's hands add up to: how many are reviewed (and a button to review the rest), the points
 * lost a hand, the mistakes a hand at the settings' level, and the loss by kind of decision.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewStats(reviewed: List<HandRecord>, pending: Int, level: Level, progress: Pair<Int, Int>?, onRun: () -> Unit, onStop: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  val res = LocalResources.current
  val dim = cs.onSurfaceVariant
  val k = reviewed.size
  Column(
    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).background(cs.surfaceContainer, RoundedCornerShape(14.dp))
      .padding(horizontal = 12.dp, vertical = 8.dp).testTag("records_stats"),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.labelLarge)
      Text(stringResource(R.string.stats_reviewed, k), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = dim)
      if (progress != null) TextButton(onClick = onStop, modifier = Modifier.testTag("stats_stop")) {
        Text(stringResource(R.string.stats_running, progress.first, progress.second))
      } else if (pending > 0) TextButton(onClick = onRun, modifier = Modifier.testTag("stats_run")) { Text(stringResource(R.string.stats_run, pending)) }
    }
    if (k > 0) {
      val loss = reviewed.sumOf { it.rev?.loss ?: 0.0 }
      val mistakes = reviewed.sumOf { h -> h.rev?.let { mistakesOf(it, level) } ?: 0 }.toDouble() / k
      FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // One text, so the note wraps as the words of a line do and is not squeezed beside the loss.
        val lossText = stringResource(R.string.stats_loss, pts(loss / k, false))
        val note = stringResource(R.string.stats_net_note)
        Text(
          buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lossText) }
            withStyle(SpanStyle(color = dim)) { append(note) }
          },
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          stringResource(R.string.stats_per_hand, stringResource(R.string.review_mistakes, levelOrWorse(res, level), "%.2f".format(Locale.ROOT, mistakes))),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.stats_kinds), style = MaterialTheme.typography.bodySmall, color = dim)
        for (kind in LossKind.entries) {
          val x = reviewed.sumOf { it.rev?.kinds?.getOrNull(kind.ordinal) ?: 0.0 }
          val share = if (loss > 0) (x / loss).toFloat().coerceIn(0f, 1f) else 0f
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // The words, the figure, then the bar of its share.
            Text(stringResource(kind.label) + " " + pts(x / k, false), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.width(48.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainerHighest)) {
              // A loss's share: in a loss's color, not the brand's green.
              Box(Modifier.fillMaxHeight().fillMaxWidth(share).background(Marks.danger2))
            }
          }
        }
      }
    }
  }
}

private fun exportName() = "mahjong-records-${LocalDate.now()}.json"

/** The records through the system's share sheet (a chat app, a drive, Quick Share): a copy in the cache, which
 *  res/xml/file_paths.xml makes the FileProvider's. */
private suspend fun shareRecords(context: Context, text: String) {
  val file = withContext(Dispatchers.IO) {
    File(context.cacheDir, "shared").also { it.mkdirs() }.let { File(it, exportName()) }.also { it.writeText(text) }
  }
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
  val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  context.startActivity(Intent.createChooser(send, null))
}

/** The records' end: every record as a file for the developer (shared or saved), and practice's scores set to zero. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForDeveloper(count: Int, onShare: () -> Unit, onSave: () -> Unit, onReset: () -> Unit) {
  SectionHeader(stringResource(R.string.records_dev_title), Modifier.padding(top = 8.dp))
  Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(R.string.records_dev_info), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.records_dev_count, count), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onShare, Modifier.heightIn(min = 48.dp).testTag("records_share"), enabled = count > 0) {
        Icon(Icons.Outlined.Share, null, Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.records_share))
      }
      OutlinedButton(onClick = onSave, Modifier.heightIn(min = 48.dp).testTag("records_save"), enabled = count > 0) {
        Icon(Icons.Outlined.Save, null, Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.records_export))
      }
    }
  }
  TextButton(onClick = onReset, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 24.dp).heightIn(min = 48.dp).testTag("records_reset_scores")) {
    Text(stringResource(R.string.records_reset_scores))
  }
}

@Composable
private fun Empty() {
  Text(stringResource(R.string.records_empty), Modifier.fillMaxWidth().padding(32.dp), style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun HandLine(h: HandRecord, onOpen: () -> Unit, onFav: () -> Unit) {
  val me = h.points[h.me]
  val summary = when {
    h.exhausted || h.winners.isEmpty() -> stringResource(R.string.record_draw)
    h.me in h.winners -> stringResource(if (h.selfDraw) R.string.record_tsumo_you else R.string.record_win_you, h.tai)
    h.loser == h.me -> stringResource(R.string.record_dealt_in)
    else -> stringResource(R.string.record_win_other, h.names[h.winners[0]])
  }
  val outcome = LocalOutcome.current
  Column {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
        Text(summary, style = MaterialTheme.typography.bodyLarge,
          color = if (h.me in h.winners) outcome.good else if (h.loser == h.me) outcome.bad else MaterialTheme.colorScheme.onSurface)
        Text(
          localTime(h.time) + "・" + rulesOf(h.game.rules) + (h.index?.let { "・" + stringResource(R.string.record_hand_of, it) } ?: "") +
            (h.rev?.let { "・" + stringResource(R.string.record_loss, pts(it.loss, false)) } ?: ""),
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(signed(me), color = pointsColor(me), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
      IconButton(onClick = onFav) { Icon(if (h.fav) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.favorite)) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  }
}

@Composable
private fun BattleLine(b: BattleGroup, open: Boolean, onOpen: () -> Unit, onFav: () -> Unit) {
  val total = b.totals.getOrNull(b.me) ?: 0.0
  Column {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(Icons.Filled.ArrowDropDown, null, Modifier.rotate(if (open) 0f else -90f))
      Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
        Text(b.names.joinToString("・"), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
          localTime(b.time) + "・" + stringResource(if (b.via == "nearby") R.string.records_kind_nearby else R.string.records_kind_online) + "・" +
            stringResource(R.string.records_battle_hands, b.hands) + if (b.left) "・" + stringResource(R.string.records_battle_left) else "",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(signed(total), color = pointsColor(total), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
      IconButton(onClick = onFav) { Icon(if (b.fav) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.favorite)) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  }
}

/**
 * The rules: a tai preset and a flower preset, chosen apart (簡單/標準/大師; 無花/見花見台/正花), the base (底), and
 * every parameter in a list, the changed ones marked, each with what it means behind an i button (RULE_HELP, in a
 * dialog). No button changes the page's height: the mark and the reset are always in their place, shown or not. Names
 * and values come from the engine ({"cmd":"rules"}). Practice deals a new game under new rules when the page is left.
 * The web page's Rules.svelte.
 */
@Composable
fun RulesScreen(vm: AppModel, onBack: (() -> Unit)?) {
  val settings = vm.settings
  val scope = rememberCoroutineScope()
  var full by remember { mutableStateOf<RulesInfo?>(null) }
  var presets by remember { mutableStateOf<RulesInfo?>(null) }
  var advanced by remember { mutableStateOf(false) }
  /** The parameter whose explanation is open (in a dialog, so that the page keeps its height). */
  var helped by remember { mutableStateOf<String?>(null) }
  val c = settings.rules
  LaunchedEffect(c) {
    try {
      full = Rules.info(c.spec)
      presets = Rules.info("${c.tai},${c.flowers}")
    } catch (e: EngineException) {
      vm.error = e.message
    }
  }
  DisposableEffect(Unit) { onDispose { vm.practice.settingsChanged() } }
  fun choose(x: RuleChoice) {
    settings.rules = x
    settings.persist()
  }
  fun set(param: String, value: Int) {
    scope.launch {
      try {
        choose(Rules.withValue(settings.rules, param, value))
      } catch (e: EngineException) {
        vm.error = e.message
      }
    }
  }
  val f = full
  Column(Modifier.fillMaxSize()) {
    TitleBar(stringResource(R.string.rules_title), onBack)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.readable().fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SectionHeader(stringResource(R.string.rules_tai))
        for (p in Rules.TAI_PRESETS) PresetCard(stringResource(Rules.taiLabel(p)), stringResource(taiDesc(p)), c.tai == p) { choose(c.copy(tai = p)) }
        SectionHeader(stringResource(R.string.rules_flowers))
        for (p in Rules.FLOWER_PRESETS) PresetCard(stringResource(Rules.flowerLabel(p)), stringResource(flowerDesc(p)), c.flowers == p) {
          choose(c.copy(flowers = p))
        }
        SectionHeader(stringResource(R.string.rules_base))
        val base = f?.values?.get("base") ?: 1
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.result_base, base.toString()), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.rules_base_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Stepper(base, { set("base", it) }, min = 1, max = 20)
        }
        Text(stringResource(R.string.rules_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.testTag("rules_advanced").padding(16.dp, 14.dp),
          verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.rules_advanced), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rules_advanced_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Icon(Icons.Filled.ArrowDropDown, null, Modifier.rotate(if (advanced) 180f else 0f))
        }
        if (advanced && f != null) {
          Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { choose(c.copy(overrides = emptyMap())) }, enabled = c.overrides.isNotEmpty()) { Text(stringResource(R.string.rules_reset)) }
          }
          val names = f.items.associate { it.param to it.name }
          for ((title, params) in ruleGroups(f)) {
            SectionHeader(stringResource(title))
            for (p in params) {
              val v = f.values[p] ?: 0
              val changed = presets != null && presets?.values?.get(p) != v
              val label = names[p] ?: paramName(p)
              val help = RULE_HELP[p]
              Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                    if (help != null) IconButton(onClick = { helped = p }, modifier = Modifier.testTag("rules_help_$p")) {
                      Icon(Icons.Outlined.Info, stringResource(R.string.rules_help) + "：" + label, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                  // Always in its place, seen or not: a change moves nothing on the page.
                  Badge(stringResource(R.string.rules_changed), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                    Modifier.alpha(if (changed) 1f else 0f).clearAndSetSemantics { })
                }
                if (p in Rules.FLAG_PARAMS) Switch(checked = v == 1, onCheckedChange = { set(p, if (it) 1 else 0) })
                else Stepper(v, { set(p, it) }, min = if (p == "base") 1 else 0, max = when (p) { "flowers" -> 2; "keep" -> 64; else -> 99 })
              }
            }
          }
        }
      }
    }
  }
  val open = helped
  val openHelp = open?.let { RULE_HELP[it] }
  if (open != null && openHelp != null) {
    val label = f?.items?.firstOrNull { it.param == open }?.name ?: paramName(open)
    AlertDialog(
      onDismissRequest = { helped = null },
      confirmButton = { TextButton(onClick = { helped = null }) { Text(stringResource(R.string.close)) } },
      title = { Text(label) },
      text = { Text(stringResource(openHelp), style = MaterialTheme.typography.bodyLarge) },
    )
  }
}

fun taiDesc(p: String) = when (p) {
  "simple2" -> R.string.tai_simple2_desc; "standard" -> R.string.tai_standard_desc; "masters" -> R.string.tai_masters_desc
  else -> R.string.tai_simple1_desc
}
fun flowerDesc(p: String) = when (p) { "see" -> R.string.flowers_see_desc; "seat" -> R.string.flowers_seat_desc; else -> R.string.flowers_none_desc }

@Composable
private fun paramName(p: String): String = when (p) {
  "flowers" -> stringResource(R.string.param_flowers)
  "base" -> stringResource(R.string.param_base)
  "draw_streak" -> stringResource(R.string.param_draw_streak)
  "multi_ron" -> stringResource(R.string.param_multi_ron)
  "keep" -> stringResource(R.string.param_keep)
  "min_tai" -> stringResource(R.string.param_min_tai)
  "duting_loose" -> stringResource(R.string.param_duting_loose)
  "quanqiu_duting" -> stringResource(R.string.param_quanqiu_duting)
  "dasixi_winds" -> stringResource(R.string.param_dasixi_winds)
  else -> p
}

/** The parameters in three groups: the play, the flowers, the tai items (each related flag right after its item). */
private fun ruleGroups(f: RulesInfo): List<Pair<Int, List<String>>> {
  val after = mapOf("quanqiu_duting" to "quanqiu", "dasixi_winds" to "dasixi")
  val tai = ArrayList<String>()
  for (it in f.items) {
    if (it.param in Rules.FLOWER_PARAMS) continue
    tai += it.param
    for ((flag, a) in after) if (a == it.param && flag in f.values) tai += flag
  }
  val listed = (tai + Rules.FLOWER_PARAMS + Rules.PLAY_PARAMS).toSet()
  for (p in f.values.keys) if (p !in listed) tai += p
  return listOf(
    R.string.rules_group_play to Rules.PLAY_PARAMS.filter { it in f.values },
    // The flowers' mode itself is the cards' choice above, not a number here.
    R.string.rules_group_flowers to Rules.FLOWER_PARAMS.filter { it in f.values && it != "flowers" },
    R.string.rules_group_tai to tai.filter { it !in Rules.PLAY_PARAMS },
  )
}

@Composable
private fun PresetCard(title: String, desc: String, on: Boolean, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Surface(
    onClick = onClick, shape = RoundedCornerShape(16.dp), color = if (on) cs.secondaryContainer else cs.surfaceContainerLow,
    contentColor = if (on) cs.onSecondaryContainer else cs.onSurface,
    border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
  ) {
    Row(Modifier.padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
      RadioButton(selected = on, onClick = null, modifier = Modifier.padding(12.dp))
      Column(Modifier.padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(desc, style = MaterialTheme.typography.bodyMedium, color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant)
      }
    }
  }
}

/**
 * The settings: help (the rules explained, the tour), the game (the rules, practice's length, the computer players and
 * their pace), the analysis (what a row of a decision's list shows, and from which loss on a decision is a mistake),
 * the look, and about. A wide window shows the list at the left and the rules beside it. The web page's
 * Settings.svelte.
 */
@Composable
fun SettingsScreen(vm: AppModel) {
  val s = vm.settings
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val desk = maxWidth >= 900.dp && maxWidth > maxHeight
    if (desk) Row(Modifier.fillMaxSize()) {
      Box(Modifier.width(380.dp).fillMaxHeight()) { SettingsList(vm, desk = true) }
      Box(Modifier.weight(1f).fillMaxHeight()) { RulesScreen(vm, null) }
    } else SettingsList(vm, desk = false)
  }

}

/** The advanced settings (設定 > 進階): ways of showing the analysis that are a matter of taste, each on its own: what a
 *  row of a decision's list shows, and what a review's hand shows on its tiles. The web page's Advanced.svelte. */
@Composable
fun AdvancedScreen(vm: AppModel, onBack: () -> Unit) {
  val s = vm.settings
  Column(Modifier.fillMaxSize()) {
    TitleBar(stringResource(R.string.advanced_title), onBack)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.readable().fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Block(stringResource(R.string.settings_list_rows)) {
          ToggleGroup(
            listOf(ListRows.numbers to stringResource(R.string.list_rows_numbers), ListRows.bars to stringResource(R.string.list_rows_bars),
              ListRows.both to stringResource(R.string.list_rows_both)),
            s.listRows, { s.listRows = it; s.persist() }, Modifier.fillMaxWidth().testTag("settings_list_rows"),
          )
          Text(stringResource(R.string.list_rows_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Block(stringResource(R.string.settings_table_marks)) {
          ToggleGroup(
            listOf(TableMarks.both to stringResource(R.string.table_marks_both), TableMarks.loss to stringResource(R.string.table_marks_loss),
              TableMarks.none to stringResource(R.string.table_marks_none)),
            s.tableMarks, { s.tableMarks = it; s.persist() }, Modifier.fillMaxWidth().testTag("settings_table_marks"),
          )
          Text(stringResource(R.string.table_marks_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Block(stringResource(R.string.settings_call_fx)) {
          ToggleGroup(
            listOf(CallFxMode.anim to stringResource(R.string.call_fx_anim), CallFxMode.words to stringResource(R.string.call_fx_words)),
            s.callFx, { s.callFx = it; s.persist() }, Modifier.fillMaxWidth().testTag("settings_call_fx"),
          )
          Text(stringResource(R.string.call_fx_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun SettingsList(vm: AppModel, desk: Boolean) {
  val s = vm.settings
  fun save() = s.persist()
  Column(Modifier.fillMaxSize()) {
    TitleBar(stringResource(R.string.settings_title))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.readable().fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionHeader(stringResource(R.string.settings_help))
        SettingRow(stringResource(R.string.guide_title), stringResource(R.string.guide_row), Modifier.testTag("settings_guide")) { vm.open(Page.GUIDE) }
        SettingRow(stringResource(R.string.tour_title), stringResource(R.string.tour_row), Modifier.testTag("settings_tour")) { vm.tour.start() }
        SectionHeader(stringResource(R.string.settings_game))
        SettingRow(stringResource(R.string.settings_rules), rulesChip(s.rules.tai, s.rules.flowers, s.rules.overrides.isNotEmpty()),
          onClick = if (desk) null else ({ vm.open(Page.RULES) }))
        Block(stringResource(R.string.settings_length)) {
          ToggleGroup(
            listOf(Length.HAND to stringResource(R.string.length_hand), Length.ROUND to stringResource(R.string.length_round),
              Length.GAME to stringResource(R.string.length_game)),
            s.length, { s.length = it; save(); vm.practice.settingsChanged() }, Modifier.fillMaxWidth(),
          )
        }
        Block(stringResource(R.string.settings_bot)) {
          ToggleGroup(BOTS.map { it.first to stringResource(it.second) }, s.bot, { s.bot = it; save(); vm.practice.settingsChanged() },
            Modifier.fillMaxWidth().testTag("settings_bot"))
        }
        Block(stringResource(R.string.settings_speed)) {
          ToggleGroup(
            listOf(Speed.SLOW to stringResource(R.string.speed_slow), Speed.NORMAL to stringResource(R.string.speed_normal),
              Speed.FAST to stringResource(R.string.speed_fast), Speed.MANUAL to stringResource(R.string.speed_manual)),
            s.speed, { s.speed = it; save(); vm.practice.paceChanged() }, Modifier.fillMaxWidth().testTag("settings_speed"),
          )
          Text(stringResource(R.string.speed_manual_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Block(stringResource(R.string.settings_sound)) {
          ToggleGroup(
            listOf(SoundLevel.off to stringResource(R.string.sound_off), SoundLevel.soft to stringResource(R.string.sound_soft),
              SoundLevel.full to stringResource(R.string.sound_full)),
            s.sound, { s.sound = it; save(); Sounds.play(Sounds.Name.DISCARD) }, Modifier.fillMaxWidth().testTag("settings_sound"),
          )
        }
        Block(stringResource(R.string.settings_ready_hint)) {
          ToggleGroup(listOf(false to stringResource(R.string.sound_off), true to stringResource(R.string.ready_hint_on)),
            s.readyHint, { s.readyHint = it; save() }, Modifier.fillMaxWidth().testTag("settings_ready_hint"))
          Text(stringResource(R.string.ready_hint_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionHeader(stringResource(R.string.settings_analysis))
        Block(stringResource(R.string.settings_level)) {
          ToggleGroup(
            listOf(Level.beginner to stringResource(R.string.level_beginner), Level.mid to stringResource(R.string.level_mid),
              Level.expert to stringResource(R.string.level_expert)),
            s.level, { s.level = it; save() }, Modifier.fillMaxWidth().testTag("settings_level"),
          )
          Text(stringResource(R.string.level_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingRow(stringResource(R.string.settings_advanced), stringResource(R.string.settings_advanced_row), Modifier.testTag("settings_advanced")) {
          vm.open(Page.ADVANCED)
        }
        SectionHeader(stringResource(R.string.settings_look))
        Block(stringResource(R.string.settings_text)) {
          ToggleGroup(
            listOf(TextSize.SMALL to stringResource(R.string.text_small), TextSize.NORMAL to stringResource(R.string.text_normal),
              TextSize.LARGE to stringResource(R.string.text_large)),
            s.text, { s.text = it; save() }, Modifier.fillMaxWidth(),
          )
        }
        Block(stringResource(R.string.settings_colors)) {
          ToggleGroup(
            listOf(ColorStyle.TABLE to stringResource(R.string.colors_table), ColorStyle.MINIMAL to stringResource(R.string.colors_minimal)),
            s.colors, { s.colors = it; save() }, Modifier.fillMaxWidth(),
          )
        }
        Block(stringResource(R.string.settings_dark)) {
          ToggleGroup(
            listOf(DarkMode.SYSTEM to stringResource(R.string.dark_system), DarkMode.LIGHT to stringResource(R.string.dark_light),
              DarkMode.DARK to stringResource(R.string.dark_dark)),
            s.dark, { s.dark = it; save() }, Modifier.fillMaxWidth(),
          )
        }
        SectionHeader(stringResource(R.string.settings_about))
        Text(stringResource(R.string.about_text_net), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, Engine.MODEL_ID),
          Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("about_version"), style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
  Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    content()
  }
}

