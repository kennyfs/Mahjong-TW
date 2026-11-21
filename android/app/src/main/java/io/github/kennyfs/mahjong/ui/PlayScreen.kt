package io.github.kennyfs.mahjong.ui

import io.github.kennyfs.mahjong.Words
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
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import io.github.kennyfs.mahjong.FxClock
import io.github.kennyfs.mahjong.Practice
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Rules
import io.github.kennyfs.mahjong.Speed
import androidx.compose.ui.graphics.Color
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.tileMarks
import io.github.kennyfs.mahjong.opponentNotes
import io.github.kennyfs.mahjong.choiceMarks
import io.github.kennyfs.mahjong.GameRecord
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.Analysis
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext

/** The rules in a few words: "簡單1・無花", and "自訂" when values were changed; `short`: fewer (見花見台 as 見花). */
@Composable
fun rulesChip(tai: String, flowers: String, custom: Boolean = false, short: Boolean = false): String =
  stringResource(R.string.rules_chip, stringResource(Rules.taiLabel(tai)),
    stringResource(if (short) Rules.flowerShort(flowers) else Rules.flowerLabel(flowers))) +
    if (custom) Words.sep + stringResource(R.string.rules_custom) else ""

/**
 * Practice: the table against three bots; a bar over it (a phone upright) with the hand, the rules and a new game; the
 * hand's result over the table when it ends (with the network's review of it in a line, once it is there), and the
 * game's totals when the game does. The review (and each decision's analysis) is a button on the result. At the manual
 * pace (the settings) the computer players' steps wait for the player's button (Practice.kt), and so does the table at
 * a hand's end. The web page's Play.svelte.
 *
 * 顯示分析 (the settings' 練習輔助): the player's decisions with the analysis shown as a branch shows it: what each
 * discard costs and its danger on the hand's tiles, what each choice costs on its button, how likely each opponent is
 * ready on the name plates (and the tile it most likely waits on), a chosen tile's figures over the hand. (The list of
 * every action is the web page's on a large screen only.) A hand that had it on at any point counts for nothing and
 * keeps it on till its end (Practice.markHinted).
 */
@Composable
fun PlayScreen(vm: AppModel) {
  val practice = vm.practice
  val settings = vm.settings
  val names = listOf(stringResource(R.string.you)) + (1..3).map { stringResource(R.string.cpu_n, it) }
  var confirmNew by remember { mutableStateOf(false) }
  var hideResult by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(Unit) { if (practice.game == null) practice.resume() }
  val meta = practice.meta
  val view = practice.view
  val final = practice.final
  val stage = practice.stage
  LaunchedEffect(stage) { if (stage == Practice.Stage.PLAYING) hideResult = false }
  // A hand's end: the result's moment waits for the player's tap (TableView), then the result; without one (the words
  // alone) the table as it ended waits for it (TapOn). At the manual pace the computer players' steps wait for the
  // player's button.
  val manual = settings.speed == Speed.MANUAL
  val (held, letGo) = endHold(stage != Practice.Stage.PLAYING)
  val fxOn by FxClock.on.collectAsState()
  val onNext: (() -> Unit)? = if (stage == Practice.Stage.PLAYING && practice.waiting) ({ practice.step() }) else null
  val notice = practice.notice
  val handLabel = meta?.let { stringResource(R.string.hand_n, it.hands + if (stage == Practice.Stage.PLAYING) 1 else 0) } ?: ""
  val chip = rulesChip(settings.rules.tai, settings.rules.flowers, settings.rules.overrides.isNotEmpty())
  val chipShort = rulesChip(settings.rules.tai, settings.rules.flowers, settings.rules.overrides.isNotEmpty(), short = true)
  // The hand in a few characters: the round's label as 東-3 where the full one has no room.
  val handNo = meta?.let { "${it.hands + if (stage == Practice.Stage.PLAYING) 1 else 0}" } ?: ""
  val totals = meta?.totals ?: listOf(0.0, 0.0, 0.0, 0.0)
  val shown = if (stage != Practice.Stage.PLAYING && final != null) final else view
  val last = practice.lastRecord?.let { vm.records.get(it) }
  val lastFav = last?.fav ?: false
  val res = LocalContext.current.resources
  // The analysis shown: from when it is turned on (the hand in play keeps it, Practice.markHinted).
  val playing = stage == Practice.Stage.PLAYING
  val hintsOn = settings.practiceHints || (playing && meta?.hinted == true)
  LaunchedEffect(settings.practiceHints, playing, meta?.id, meta?.hands) { if (settings.practiceHints && playing) practice.markHinted() }
  // The player's decision, analyzed (the game it was for: a later one does not show it).
  var hint by remember { mutableStateOf<Pair<GameRecord, Analysis>?>(null) }
  val game = practice.game
  LaunchedEffect(game, hintsOn, view?.myTurn) {
    val g = game
    if (!hintsOn || g == null || view?.myTurn != true || hint?.first === g) return@LaunchedEffect
    hint = runCatching { g to Engine.analyzeGame(g, Practice.ME) }.getOrNull()
  }
  val mine = hint?.takeIf { hintsOn && playing && it.first === game && view?.myTurn == true }?.second
  val marks = if (hintsOn && playing) mine?.let { tileMarks(res, it, -1, null) } ?: emptyMap() else null
  var picked by remember { mutableStateOf<String?>(null) }
  // The opponents' notes and the player's expected result stay as the player's last decision left them while the
  // others play (a short while: a little out of date at most), till the next decision's; a new hand starts without.
  var keptNotes by remember { mutableStateOf<List<SeatNote?>?>(null) }
  var keptValue by remember { mutableStateOf<Double?>(null) }
  /** Whether the hand has had its first analysis yet. */
  var analyzed by remember { mutableStateOf(false) }
  LaunchedEffect(mine) { if (mine != null) { keptNotes = opponentNotes(res, mine); keptValue = mine.value; analyzed = true } }
  LaunchedEffect(practice.deal) { keptNotes = null; keptValue = null; analyzed = false }
  // The analysis on its way (the network still loading, the first time): grey shapes where its figures will be, the
  // tags' after a moment of the player's turn (the engine's few milliseconds show none), the expected result's till
  // the hand's first.
  val waiting = hintsOn && playing && view?.myTurn == true && mine == null
  var slow by remember { mutableStateOf(false) }
  LaunchedEffect(waiting) {
    slow = false
    if (waiting) { delay(250); slow = true }
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth > maxHeight
    val w = maxWidth.value
    Column(Modifier.fillMaxSize()) {
      if (!wide) {
        // The round's label, then the rules', in a few characters where the bar has no room for them (東-3).
        Shorten(3, Modifier.fillMaxWidth().heightIn(min = 48.dp).tourAnchor("top").padding(horizontal = 8.dp)) { level ->
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view != null) {
              val wind = Tiles.wind(view.position.setup.round)
              val mark = Tiles.windMark(view.position.setup.round)
              Text(if (level == 0) stringResource(R.string.round_wind, wind) + " " + handLabel else stringResource(R.string.round_short, mark, handNo),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f)) { Chip(if (level < 2) chip else chipShort, onClick = { vm.open(Page.RULES) }, menu = true) }
            TextButton(onClick = { confirmNew = true }) { Text(stringResource(R.string.practice_new)) }
          }
        }
      }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        if (shown == null) TableSkeleton()
        else TableView(
          shown, Practice.ME, names, scores = totals,
          revealed = if (stage != Practice.Stage.PLAYING) final?.hands else null,
          interactive = stage == Practice.Stage.PLAYING && !practice.busy,
          onAct = { practice.act(it) }, handLabel = if (wide) handLabel else "", handShort = if (wide) handNo else "", onNext = onNext, stay = manual, robbed = practice.robbed,
          readyHint = settings.readyHint, deal = if (stage == Practice.Stage.PLAYING) practice.deal else null,
          marks = marks, notes = if (hintsOn && playing) keptNotes else null, tagRoom = hintsOn && playing,
          value = if (hintsOn && playing) keptValue else null, pending = waiting && slow, valuePending = hintsOn && playing && !analyzed,
          choiceMarks = mine?.let { choiceMarks(res, it, -1, null) }, onPick = { picked = it },
          strip = if (mine != null && picked != null) ({ PickedFigures(mine, Practice.ME, names, picked!!) }) else null,
          status = if (notice != null) ({
            Text(notice, Modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
              color = Color.White, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
          }) else null,
          // The table's top-right corner: the buttons one over the other (the tour lights them as one).
          corner = if (wide) ({
            Column(Modifier.tourAnchor("top"), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Shorten(2) { MiniButton(if (it == 0) chip else chipShort) { vm.open(Page.RULES) } }
              MiniButton(stringResource(R.string.practice_new)) { confirmNew = true }
            }
          }) else null,
          overlay = {
            when {
              stage == Practice.Stage.RESULT && (held || fxOn) -> TapOn(letGo)
              stage == Practice.Stage.RESULT && final?.result != null && !hideResult -> Sheet {
                HandResultCard(final, names, Practice.ME, resultTileW(wide, w), rev = last?.rev, level = settings.level) {
                  if (last?.hints == true) Text(stringResource(R.string.result_hints_note), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  IconToggleButton(lastFav, { on -> practice.lastRecord?.let { vm.records.setFav(it, on) } }) {
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
  if (confirmNew) ConfirmDialog(stringResource(R.string.practice_new_confirm), onConfirm = { practice.newGame() }, onDismiss = { confirmNew = false },
    confirm = stringResource(R.string.practice_new))
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
