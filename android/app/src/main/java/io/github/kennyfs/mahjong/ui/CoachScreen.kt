package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Rules
import io.github.kennyfs.mahjong.Words
import io.github.kennyfs.mahjong.coach.Coach
import io.github.kennyfs.mahjong.coach.CoachException
import io.github.kennyfs.mahjong.coach.CoachKeys
import io.github.kennyfs.mahjong.coach.CoachModel
import io.github.kennyfs.mahjong.coach.CoachSession
import io.github.kennyfs.mahjong.coach.CustomServer
import io.github.kennyfs.mahjong.coach.Effort
import io.github.kennyfs.mahjong.coach.Prepared
import io.github.kennyfs.mahjong.coach.Provider
import io.github.kennyfs.mahjong.coach.RulesGroup
import io.github.kennyfs.mahjong.coach.answerJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The AI coach's page (docs/coach.md), opened from the records: which hands (one set of rules, the latest 50, 100 or
// 200), which model, a question if any; the digest made and its size and price shown before anything is sent; the
// answer with its ids as links to the moments of the review; more questions after it; the last few answers kept.

private val COUNTS = listOf(50, 100, 200)

/** A provider's short name, for a choice among them. */
@Composable
fun providerName(p: Provider): String = when (p) {
  Provider.CLAUDE -> "Claude"
  Provider.GEMINI -> "Gemini"
  Provider.OPENAI -> "ChatGPT"
  Provider.CUSTOM -> stringResource(R.string.coach_custom_short)
}

@Composable
private fun rulesName(g: RulesGroup): String {
  val base = stringResource(Rules.taiLabel(g.tai)) + Words.sep + stringResource(Rules.flowerLabel(g.flowers))
  return if (g.changed == 0) base else stringResource(R.string.coach_rules_changed, base, g.changed)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachScreen(vm: AppModel, onBack: () -> Unit) {
  val coach = vm.coach
  val keys = coach.keys
  val res = LocalResources.current
  LaunchedEffect(Unit) { coach.refreshGroups() }
  val groups = coach.groups
  var groupKey by rememberSaveable { mutableStateOf<String?>(null) }
  val group = groups?.firstOrNull { it.key == groupKey } ?: groups?.firstOrNull()
  var count by rememberSaveable { mutableIntStateOf(vm.settings.coachHands) }
  val ready = Provider.entries.filter { keys.has(it) }
  var providerId by rememberSaveable { mutableStateOf(vm.settings.coachProvider) }
  val provider = ready.firstOrNull { it.id == providerId } ?: ready.firstOrNull()
  var question by rememberSaveable { mutableStateOf("") }
  var shownId by rememberSaveable { mutableStateOf<String?>(null) }
  val shown = coach.sessions.firstOrNull { it.id == shownId } ?: coach.sessions.firstOrNull()
  var pickRules by remember { mutableStateOf(false) }
  var keysOpen by remember { mutableStateOf(false) }
  var preview by remember { mutableStateOf<String?>(null) }
  var tell by remember { mutableStateOf<Pair<Prepared, Provider>?>(null) }
  var confirmDelete by remember { mutableStateOf<String?>(null) }
  var choosingModel by remember { mutableStateOf(false) }
  // The model and the thinking chosen for each provider (the settings keep them).
  var models by remember { mutableStateOf(vm.settings.coachModels) }
  var efforts by remember { mutableStateOf(vm.settings.coachEffort) }
  fun modelOf(p: Provider) = p.model(models[p.id])
  fun effortOf(p: Provider) = Effort.of(efforts[p.id])
  val cs = MaterialTheme.colorScheme
  val phase = coach.phase
  val busy = phase is Coach.Phase.Gathering || phase is Coach.Phase.Asking

  fun send(p: Prepared, to: Provider) {
    if (!keys.told(to)) tell = p to to
    else {
      vm.settings.coachProvider = to.id
      vm.settings.persist()
      coach.send(res, p, to, modelOf(to), effortOf(to), vm.settings.level)
      shownId = null
    }
  }

  Column(Modifier.fillMaxSize()) {
    TitleBar(stringResource(R.string.coach_title), onBack)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(Modifier.readable(760.dp).fillMaxWidth().testTag("coach")) {
        item(key = "new") {
          Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.coach_intro), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            if (ready.isEmpty()) {
              Text(stringResource(R.string.coach_need_key), style = MaterialTheme.typography.bodyLarge)
              Button(onClick = { keysOpen = true }, Modifier.testTag("coach_keys")) { Text(stringResource(R.string.coach_set_keys)) }
            } else {
              // The hands: a set of rules, how many of its latest.
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.coach_rules), Modifier.width(72.dp), style = MaterialTheme.typography.bodyLarge)
                if (groups == null) Text("…") else if (group == null) Text(stringResource(R.string.coach_no_records), color = cs.onSurfaceVariant)
                else Chip(rulesName(group) + Words.sep + stringResource(R.string.coach_hands_n, group.hands.size), onClick = { pickRules = true },
                  menu = groups.size > 1, modifier = Modifier.testTag("coach_rules"))
              }
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.coach_latest), Modifier.width(72.dp), style = MaterialTheme.typography.bodyLarge)
                ToggleGroup(COUNTS.map { it to it.toString() }, count, { count = it; vm.settings.coachHands = it; vm.settings.persist() },
                  Modifier.weight(1f).testTag("coach_count"))
              }
              if (group != null && group.hands.size < 20) Text(stringResource(R.string.coach_few), style = MaterialTheme.typography.bodySmall, color = cs.error)
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.coach_provider), Modifier.width(72.dp), style = MaterialTheme.typography.bodyLarge)
                ToggleGroup(ready.map { it to providerName(it) }, provider ?: ready.first(), { providerId = it.id }, Modifier.weight(1f).testTag("coach_provider"))
              }
              // Its model and thinking, for those who want another (the default: the provider's, medium).
              val p = provider ?: ready.first()
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.coach_model), Modifier.width(72.dp), style = MaterialTheme.typography.bodyLarge)
                if (p == Provider.CUSTOM) Text(keys.custom.model, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                else {
                  Text(stringResource(R.string.coach_model_line, modelOf(p)?.label ?: "", effortName(effortOf(p))), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                  TextButton(onClick = { choosingModel = true }, Modifier.testTag("coach_model_change")) { Text(stringResource(R.string.coach_change)) }
                }
              }
              OutlinedTextField(
                question, { question = it.take(500) }, Modifier.fillMaxWidth().testTag("coach_question"),
                label = { Text(stringResource(R.string.coach_question)) }, placeholder = { Text(stringResource(R.string.coach_question_hint)) },
                maxLines = 4,
              )
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                  onClick = { if (group != null) coach.prepare(res, group, count, rulesName(res, group), question) },
                  enabled = group != null && !busy, modifier = Modifier.testTag("coach_prepare"),
                ) { Text(stringResource(R.string.coach_prepare)) }
                TextButton(onClick = { keysOpen = true }) { Text(stringResource(R.string.coach_set_keys)) }
              }
            }
          }
        }
        item(key = "phase") {
          Box(Modifier.padding(horizontal = 16.dp)) {
            when (phase) {
              is Coach.Phase.Idle -> {}
              is Coach.Phase.Gathering -> Progress(stringResource(R.string.coach_gathering, phase.done, phase.total),
                phase.done.toFloat() / maxOf(1, phase.total), onCancel = coach::cancel)
              is Coach.Phase.Asking -> Progress(stringResource(R.string.coach_asking, providerName(phase.provider)), null, onCancel = coach::cancel)
              is Coach.Phase.Ready -> ReadyCard(phase.prepared, provider, provider?.let(::modelOf), provider?.let(::effortOf) ?: Effort.MEDIUM,
                onPreview = { preview = phase.prepared.firstMessage(isZh(res)) },
                onSend = { p -> send(phase.prepared, p) }, onClose = coach::dismiss)
              is Coach.Phase.Failed -> ErrorCard(phase.kind, phase.detail, onClose = coach::dismiss)
            }
          }
        }
        if (shown != null) item(key = "session:${shown.id}") {
          SessionView(vm, shown, busy, onAsk = { q -> coach.followUp(shown, q) }, onDelete = { confirmDelete = shown.id })
        }
        val earlier = coach.sessions.filter { it.id != shown?.id }
        if (earlier.isNotEmpty()) item(key = "earlier") {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.coach_earlier), style = MaterialTheme.typography.titleSmall, color = cs.primary)
            for (s in earlier) TextButton(onClick = { shownId = s.id }) {
              Text(sessionLine(s), Modifier.fillMaxWidth())
            }
          }
        }
      }
    }
  }

  if (pickRules && groups != null) {
    PickDialog(stringResource(R.string.coach_rules), groups.map { it.key to (rulesName(it) + Words.sep + stringResource(R.string.coach_hands_n, it.hands.size)) },
      group?.key, onPick = { groupKey = it; pickRules = false }, onDismiss = { pickRules = false })
  }
  if (keysOpen) KeysDialog(keys, onDismiss = { keysOpen = false })
  if (choosingModel && provider != null && provider != Provider.CUSTOM) {
    ModelDialog(provider, modelOf(provider), effortOf(provider), onDismiss = { choosingModel = false }) { m, e ->
      models = models + (provider.id to m.id)
      efforts = efforts + (provider.id to e.id)
      vm.settings.coachModels = models
      vm.settings.coachEffort = efforts
      vm.settings.persist()
      choosingModel = false
    }
  }
  preview?.let { text ->
    AlertDialog(
      onDismissRequest = { preview = null },
      confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.close)) } },
      title = { Text(stringResource(R.string.coach_preview_title)) },
      text = {
        SelectionContainer {
          Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
      },
      modifier = Modifier.testTag("coach_preview"),
    )
  }
  tell?.let { (p, to) ->
    AlertDialog(
      onDismissRequest = { tell = null },
      confirmButton = {
        TextButton(onClick = { tell = null; keys.setTold(to); send(p, to) }, Modifier.testTag("coach_tell_ok")) { Text(stringResource(R.string.coach_send)) }
      },
      dismissButton = { TextButton(onClick = { tell = null }) { Text(stringResource(R.string.cancel)) } },
      title = { Text(stringResource(R.string.coach_tell_title, providerName(to))) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.coach_tell, p.hands.size, providerName(to)), style = MaterialTheme.typography.bodyLarge)
          when (to) {
            Provider.GEMINI -> Text(stringResource(R.string.coach_gemini_free), style = MaterialTheme.typography.bodyMedium, color = cs.error)
            Provider.CUSTOM -> Text(stringResource(R.string.coach_tell_custom, keys.custom.url), style = MaterialTheme.typography.bodyMedium)
            else -> {}
          }
        }
      },
      modifier = Modifier.testTag("coach_tell"),
    )
  }
  confirmDelete?.let { id ->
    ConfirmDialog(stringResource(R.string.coach_delete_confirm), onConfirm = { coach.delete(id); shownId = null }, onDismiss = { confirmDelete = null },
      confirm = stringResource(R.string.delete))
  }
}

private fun isZh(res: android.content.res.Resources) = res.getString(R.string.coach_lang) == "zh-TW"

private fun rulesName(res: android.content.res.Resources, g: RulesGroup): String {
  val base = res.getString(Rules.taiLabel(g.tai)) + Words.sep + res.getString(Rules.flowerLabel(g.flowers))
  return if (g.changed == 0) base else res.getString(R.string.coach_rules_changed, base, g.changed)
}

@Composable
private fun Progress(text: String, fraction: Float?, onCancel: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("coach_progress"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
    if (fraction != null) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
  }
}

@Composable
private fun Card(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainer, content: @Composable () -> Unit) {
  Surface(modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(14.dp), color = color) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyCard(p: Prepared, provider: Provider?, model: CoachModel?, effort: Effort, onPreview: () -> Unit, onSend: (Provider) -> Unit, onClose: () -> Unit) {
  val res = LocalResources.current
  val tokens = ((Coach.tokens(p.firstMessage(isZh(res))) + 1500) * (provider?.tokenScale ?: 1.0)).toInt()
  Card(Modifier.testTag("coach_ready")) {
    Text(stringResource(R.string.coach_ready, p.hands.size, p.rulesName), style = MaterialTheme.typography.titleSmall)
    if (p.skipped > 0) Text(stringResource(R.string.coach_skipped, p.skipped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val cost = model?.takeIf { it.inPrice > 0 }?.let { (tokens * it.inPrice + effort.outputGuess * it.outPrice) / 1e6 }
    Text(
      // (A cent or more to the cent; less, as Haiku's, to a tenth of one.)
      if (cost != null) stringResource(R.string.coach_estimate, tokens / 1000, String.format(Locale.ROOT, if (cost < 0.01) "%.3f" else "%.2f", cost))
      else stringResource(R.string.coach_estimate_tokens, tokens / 1000),
      style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (provider != null) Button(onClick = { onSend(provider) }, Modifier.testTag("coach_send")) {
        Text(stringResource(R.string.coach_send_to, providerName(provider)))
      }
      OutlinedButton(onClick = onPreview, Modifier.testTag("coach_preview_button")) { Text(stringResource(R.string.coach_preview)) }
      TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
    }
  }
}

@Composable
private fun ErrorCard(kind: CoachException.Kind?, detail: String, onClose: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Card(Modifier.testTag("coach_error"), color = cs.errorContainer) {
    Text(
      stringResource(
        when (kind) {
          CoachException.Kind.KEY -> R.string.coach_error_key
          CoachException.Kind.QUOTA -> R.string.coach_error_quota
          CoachException.Kind.REFUSED -> R.string.coach_error_refused
          CoachException.Kind.TRUNCATED -> R.string.coach_error_truncated
          CoachException.Kind.NETWORK -> R.string.coach_error_network
          CoachException.Kind.SERVER -> R.string.coach_error_server
          CoachException.Kind.BAD_ANSWER -> R.string.coach_error_bad
          null -> R.string.coach_error
        },
      ),
      style = MaterialTheme.typography.bodyLarge, color = cs.onErrorContainer,
    )
    if (detail.isNotBlank()) SelectionContainer {
      Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
    }
    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
  }
}

@Composable
private fun sessionLine(s: CoachSession): String {
  val time = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.parse(s.time).atZone(ZoneId.systemDefault()))
  }.getOrDefault(s.time)
  val effort = if (s.effort.isEmpty()) "" else stringResource(R.string.coach_session_effort, effortName(Effort.of(s.effort)))
  return "$time" + Words.sep + s.model + effort + Words.sep + s.rules + Words.sep + stringResource(R.string.coach_hands_n, s.hands.size)
}

@Composable
private fun effortName(e: Effort) = stringResource(
  when (e) {
    Effort.LOW -> R.string.coach_effort_low
    Effort.MEDIUM -> R.string.coach_effort_medium
    Effort.HIGH -> R.string.coach_effort_high
  },
)

/** A provider's model (each with its price) and how much it thinks: the choice of those who want another. */
@Composable
private fun ModelDialog(p: Provider, model: CoachModel?, effort: Effort, onDismiss: () -> Unit, onDone: (CoachModel, Effort) -> Unit) {
  val colors = MaterialTheme.colorScheme
  var m by remember { mutableStateOf(model ?: p.models.first()) }
  var e by remember { mutableStateOf(effort) }
  fun price(x: Double) = if (x >= 1) String.format(Locale.ROOT, "%.0f", x) else String.format(Locale.ROOT, "%.2f", x)
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = { onDone(m, e) }, Modifier.testTag("coach_model_ok")) { Text(stringResource(R.string.ok)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    title = { Text(stringResource(R.string.coach_model_title)) },
    text = {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.coach_model_help), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          for (x in p.models) {
            val on = x.id == m.id
            Surface(Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { m = x }.testTag("coach_model_${x.id}"), shape = RoundedCornerShape(16.dp),
              color = if (on) colors.secondaryContainer else Color.Transparent) {
              Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                  Text(x.label, style = MaterialTheme.typography.bodyLarge)
                  Text(stringResource(R.string.coach_model_price, price(x.inPrice), price(x.outPrice)), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
                }
                RadioButton(selected = on, onClick = null)
              }
            }
          }
        }
        Text(stringResource(R.string.coach_effort), style = MaterialTheme.typography.titleSmall)
        ToggleGroup(Effort.entries.map { it to effortName(it) }, e, { e = it }, Modifier.fillMaxWidth().testTag("coach_effort"))
        Text(stringResource(R.string.coach_effort_help), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
    },
    modifier = Modifier.testTag("coach_model_dialog"),
  )
}

/** Text with its ids ([H9-73], H9-73) as links to the moments, where the hand and the step are there. */
@Composable
private fun linked(vm: AppModel, s: CoachSession, text: String): AnnotatedString {
  val color = MaterialTheme.colorScheme.primary
  return remember(text, s.id, color) {
    buildAnnotatedString {
      var at = 0
      for (m in REF.findAll(text)) {
        append(text.substring(at, m.range.first))
        val h = m.groupValues[1].toInt()
        val step = m.groupValues[2].toInt()
        val rec = s.hands.getOrNull(h - 1)?.let { vm.records.get(it) }
        if (rec != null && step in 0..rec.game.actions.size) {
          withLink(LinkAnnotation.Clickable("ref:$h-$step", TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))) {
            vm.review(rec.id, step)
          }) { append(m.value) }
        } else append(m.value)
        at = m.range.last + 1
      }
      append(text.substring(at))
    }
  }
}

private val REF = Regex("""H(\d{1,3})-(\d{1,4})""")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionView(vm: AppModel, s: CoachSession, busy: Boolean, onAsk: (String) -> Unit, onDelete: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  var more by rememberSaveable(s.id) { mutableStateOf("") }
  Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("coach_session"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(sessionLine(s), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = cs.onSurfaceVariant) }
    }
    val answer = s.turns.getOrNull(1)?.text ?: ""
    val o = answerJson(answer)
    if (o == null) {
      Text(linked(vm, s, answer), style = MaterialTheme.typography.bodyLarge)
    } else {
      Answer(vm, s, o)
    }
    // Questions after it, and their answers.
    for (i in 2 until s.turns.size) {
      val t = s.turns[i]
      if (t.user) Text(t.text, Modifier.fillMaxWidth().background(cs.secondaryContainer, RoundedCornerShape(12.dp)).padding(10.dp),
        style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
      else Text(linked(vm, s, t.text), style = MaterialTheme.typography.bodyLarge)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(more, { more = it.take(500) }, Modifier.weight(1f).testTag("coach_more"), label = { Text(stringResource(R.string.coach_more)) },
        maxLines = 4, enabled = !busy)
      IconButton(onClick = { onAsk(more); more = "" }, enabled = !busy && more.isNotBlank()) {
        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.coach_send))
      }
    }
    val cost = s.cost?.let { String.format(Locale.ROOT, "%.3f", it) }
    Text(
      if (cost != null) stringResource(R.string.coach_used_cost, s.input, s.output, cost) else stringResource(R.string.coach_used, s.input, s.output),
      style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Answer(vm: AppModel, s: CoachSession, o: JsonObject) {
  val cs = MaterialTheme.colorScheme
  fun str(k: String, from: JsonObject = o) = runCatching { from[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim().orEmpty()
  str("summary").takeIf { it.isNotEmpty() }?.let { Text(linked(vm, s, it), style = MaterialTheme.typography.bodyLarge) }
  val habits = runCatching { o["habits"]?.jsonArray?.map { it.jsonObject } }.getOrNull().orEmpty()
  for ((i, h) in habits.withIndex()) {
    Card(Modifier.testTag("coach_habit")) {
      Text("${i + 1}. " + str("title", h), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(linked(vm, s, str("body", h)), style = MaterialTheme.typography.bodyLarge)
      str("rule", h).takeIf { it.isNotEmpty() }?.let {
        Text(stringResource(R.string.coach_try, it), style = MaterialTheme.typography.bodyLarge, color = cs.primary, fontWeight = FontWeight.Medium)
      }
      val refs = runCatching { h["refs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()
      if (refs.isNotEmpty()) Text(linked(vm, s, refs.joinToString("  ")), style = MaterialTheme.typography.bodyMedium)
    }
  }
  str("strengths").takeIf { it.isNotEmpty() }?.let {
    Text(stringResource(R.string.coach_strengths), style = MaterialTheme.typography.titleSmall, color = cs.primary)
    Text(linked(vm, s, it), style = MaterialTheme.typography.bodyLarge)
  }
  str("practice").takeIf { it.isNotEmpty() }?.let {
    Text(stringResource(R.string.coach_practice), style = MaterialTheme.typography.titleSmall, color = cs.primary)
    Text(linked(vm, s, it), style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun <T> PickDialog(title: String, options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    title = { Text(title) },
    text = {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((v, label) in options) {
          val on = v == selected
          Surface(Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { onPick(v) }, shape = RoundedCornerShape(16.dp),
            color = if (on) colors.secondaryContainer else Color.Transparent) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
              RadioButton(selected = on, onClick = null)
            }
          }
        }
      }
    },
  )
}

/** The providers' keys: each one's row (set or not), a tap to set or remove it. In the settings and on the coach's page. */
@Composable
fun CoachKeyRows(keys: CoachKeys) {
  var editing by rememberSaveable { mutableStateOf<Provider?>(null) }
  for (p in Provider.entries) {
    val label = if (p == Provider.CUSTOM) stringResource(R.string.coach_custom) else p.label
    SettingRow(label, stringResource(if (keys.has(p)) R.string.coach_key_set else R.string.coach_key_none), Modifier.testTag("coach_key_${p.id}")) {
      editing = p
    }
  }
  editing?.let { p -> KeyDialog(keys, p) { editing = null } }
}

@Composable
private fun KeysDialog(keys: CoachKeys, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    title = { Text(stringResource(R.string.coach_keys_title)) },
    text = { Column(Modifier.verticalScroll(rememberScrollState())) { CoachKeyRows(keys) } },
  )
}

/** A provider's key: pasted in (never shown again), or removed. The custom server: its address and model too. */
@Composable
private fun KeyDialog(keys: CoachKeys, p: Provider, onDismiss: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  // (The key typed is not saveable: the saved state can be written to storage. The phone turned, it is typed again.)
  var key by remember { mutableStateOf("") }
  val custom = keys.custom
  var url by rememberSaveable { mutableStateOf(custom.url) }
  var model by rememberSaveable { mutableStateOf(custom.model) }
  val had = keys.has(p)
  val name = if (p == Provider.CUSTOM) stringResource(R.string.coach_custom) else p.label
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(
        onClick = {
          if (p == Provider.CUSTOM) keys.custom = CustomServer(url, model)
          if (key.isNotBlank()) keys.set(p, key)
          onDismiss()
        },
        enabled = if (p == Provider.CUSTOM) url.trim().startsWith("https://") && model.isNotBlank() else key.isNotBlank(),
        modifier = Modifier.testTag("coach_key_save"),
      ) { Text(stringResource(R.string.coach_key_save)) }
    },
    dismissButton = {
      Row {
        if (had) TextButton(onClick = {
          keys.clear(p)
          if (p == Provider.CUSTOM) keys.custom = CustomServer("", "")
          onDismiss()
        }, Modifier.testTag("coach_key_delete")) { Text(stringResource(R.string.delete), color = cs.error) }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
      }
    },
    title = { Text(stringResource(R.string.coach_key_title, name)) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p == Provider.CUSTOM) {
          Text(stringResource(R.string.coach_custom_help), style = MaterialTheme.typography.bodyMedium)
          OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("coach_custom_url"), label = { Text(stringResource(R.string.coach_custom_url)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
          OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth().testTag("coach_custom_model"), label = { Text(stringResource(R.string.coach_custom_model)) },
            singleLine = true)
        } else {
          Text(stringResource(R.string.coach_key_help, p.keySite, p.label), style = MaterialTheme.typography.bodyMedium)
          if (p == Provider.GEMINI) Text(stringResource(R.string.coach_gemini_free), style = MaterialTheme.typography.bodyMedium, color = cs.error)
        }
        if (had && p != Provider.CUSTOM) Text(stringResource(R.string.coach_key_replace), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        OutlinedTextField(
          key, { key = it.trim() }, Modifier.fillMaxWidth().testTag("coach_key_field"),
          label = { Text(stringResource(if (p == Provider.CUSTOM) R.string.coach_key_optional else R.string.coach_key)) },
          singleLine = true, visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )
      }
    },
    modifier = Modifier.testTag("coach_key_dialog"),
  )
}
