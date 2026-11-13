package io.github.kennyfs.mahjong.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Action
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.BotPace
import io.github.kennyfs.mahjong.Claims
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.EngineException
import io.github.kennyfs.mahjong.EngineJson
import io.github.kennyfs.mahjong.FxClock
import io.github.kennyfs.mahjong.GameRecord
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Timing
import io.github.kennyfs.mahjong.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A branch of a reviewed hand (the review's 分支): from one of the player's decisions the hand is played on, the player
 * at their seat and the computer players (the settings' bot) at the others, however the hand was first played.
 * Nothing is recorded or scored. The web page's Branch.svelte.
 *
 * The tiles are the hand's own (its wall, the others' hands). With 重洗看不到的牌 what the player could not see there
 * (the others' concealed tiles, the wall) is dealt again by the branch's seed (the engine's record from a base
 * position, docs/gui-engine.md).
 *
 * The computer players go on by themselves at the settings' pace (the manual pace: the middle one), until the player
 * decides or the hand is over; on a tile the player could claim they decide first (Claims). 上一步 goes back a step, as
 * far as the branch's start, and the player may choose otherwise where they decided: what came after is then dropped.
 * 下一步 goes forward again over what was played. The seed stays for as long as the branch does; only a new branch
 * (or turning 重洗 on or off) deals with another. Back, or the end, returns to the review at the step it was taken from.
 */
class BranchRun(private val scope: CoroutineScope, private val source: GameRecord, val me: Int, private val bot: () -> String) {
  var reshuffle by mutableStateOf(false)
  var seed by mutableStateOf(newSeed())
  var game by mutableStateOf<GameRecord?>(null)
  /** The actions shown (of `game`): less than all after 上一步. */
  var cursor by mutableIntStateOf(0)
  var view by mutableStateOf<View?>(null)
  var final by mutableStateOf<View?>(null)
  var error by mutableStateOf<String?>(null)
  var showResult by mutableStateOf(true)
  /** The computer players deciding first on a tile the player could claim: no buttons meanwhile. */
  var deciding by mutableStateOf(false)
  /** A claim taken from the player: its event (TableView's `robbed`). */
  var robbed by mutableIntStateOf(-1)
  private var epoch = 0
  /** The computer players' loop (`run`): its number and its epoch. One of another epoch (waiting out a pace when the
   *  branch started again) ends at its next look: it holds back neither this one nor its state. */
  private var running = false
  /** The computer players' pace (Claims.kt BotPace): their turns, their discards' flights, their answers to a tile. */
  private val pace = BotPace()
  private var loop = 0
  private var loopEpoch = -1

  private val root: GameRecord get() = if (!reshuffle) source else GameRecord(
    source.rules, source.setup, actions = emptyList(),
    base = buildJsonObject {
      put("game", EngineJson.encodeToJsonElement(GameRecord.serializer(), source))
      put("seat", me)
      put("seed", seed)
    },
  )
  val start: Int get() = if (reshuffle) 0 else source.actions.size
  private fun bots() = (0..3).map { if (it == me) null else bot() }

  /** From the decision again (the first time, 從分支點再打一次, or 重洗 turned on or off). */
  fun restart() {
    epoch++
    pace.reset()
    game = root
    cursor = start
    final = null
    showResult = true
    robbed = -1
    show()
  }

  /** Another seed. */
  fun newBranch() {
    seed = newSeed()
    restart()
  }

  fun toggleReshuffle(on: Boolean) {
    reshuffle = on
    restart()
  }

  private fun show() {
    val g = game ?: return
    val k = cursor
    val e = epoch
    scope.launch {
      try {
        val v = Engine.view(g.cut(k), me)
        if (e != epoch || k != cursor || g !== game) return@launch
        view = v
        error = null
        final = if (v.phase == "over") Engine.view(g.cut(k), me, 0, reveal = true) else null
        // At the newest step the computer players go on, and decide first on a tile the player could claim.
        if (k == g.actions.size && v.phase != "over" && (!v.myTurn || v.phase != "self")) run()
      } catch (x: EngineException) {
        error = x.message ?: ""
      }
    }
  }

  /** The computer players, a decision point at a time, until the player decides or the hand is over. */
  private fun run() {
    if (running && loopEpoch == epoch) return
    val my = ++loop
    running = true
    val e = epoch
    loopEpoch = e
    scope.launch {
      try {
        while (e == epoch) {
          val g = game ?: break
          if (cursor != g.actions.size) break
          val p = Engine.pending(g)
          if (p.phase == "over") break
          val bots = bots()
          val seedNow = "$seed/${g.actions.size}"
          if (me in p.pending) {
            deciding = true
            val pc = Claims.preclaim(g, me, bots, seedNow)
            if (e != epoch || pc == null) break
            val r = pc.robbed
            if (r == null) {
              game = pc.game
              cursor = pc.game.actions.size
              view = Engine.view(pc.game, me)
              break
            }
            val before = view?.eventCount ?: 0
            // The discard stays a while first (rob_show, from when it came: BotPace), its words done.
            pace.settle(before)
            pace.rob()
            if (e != epoch) break
            val v = Engine.view(pc.game, me)
            if (e != epoch) break
            robbed = Claims.robbedEvent(v.events, before, r.seat)
            game = pc.game
            cursor = pc.game.actions.size
            view = v
            deciding = false
            if (v.phase == "over") final = Engine.view(pc.game, me, 0, reveal = true)
            continue
          }
          // A computer player's turn at its pace (Timing; the manual pace: the normal one), its discard flying in to the
          // river in the last of it, over when the tile lands; their answers to a tile: a call after the tile's stay,
          // none at once (Claims.othersCall). Each counts once a call's moment or words are over. (BotPace.)
          val claim = p.phase != "self"
          val before = view?.eventCount ?: 0
          pace.settle(before)
          if (e != epoch) break
          val from = android.os.SystemClock.uptimeMillis()
          val next = Engine.advance(g, bots, seedNow, 1)
          if (e != epoch) break
          val v = Engine.view(next, me)
          if (e != epoch) break
          var fly = 0L
          if (claim) {
            if (Claims.othersCall(v, before, me)) pace.call()
          } else fly = pace.turn(g, p.current, next, from)
          if (e != epoch) break
          game = next
          pace.came(next, fly)
          cursor = next.actions.size
          view = v
          if (v.phase == "over") final = Engine.view(next, me, 0, reveal = true)
          if (fly > 0) pace.land()
        }
      } catch (x: EngineException) {
        error = x.message ?: ""
      } finally {
        if (loop == my) {
          running = false
          deciding = false
        }
      }
    }
  }

  /** The player's decision at the step shown: what came after it is dropped. */
  fun act(a: Action) {
    val g0 = game ?: return
    val v0 = view ?: return
    if (!v0.myTurn) return
    epoch++
    val e = epoch
    scope.launch {
      try {
        val before = v0.eventCount
        val g = Engine.act(g0.cut(cursor), me, a)
        if (e != epoch) return@launch
        // The others decided first: a claim of the player's that another's came before is shown as taken (搶).
        if (a.type != "pass" && v0.phase != "self") {
          val v = Engine.view(g, me)
          val fresh = v.events.drop(before)
          val over = if (fresh.any { it.seat == me }) null else fresh.firstOrNull { it.seat != me && it.type in setOf("chi", "pon", "kan", "ron") }
          if (over != null) robbed = v.events.indexOf(over)
        }
        game = g
        // The player's discard flies to the river: the computer player after them discards once it has landed.
        pace.came(g, pace.flight())
        cursor = g.actions.size
        showResult = true
        show()
      } catch (x: EngineException) {
        error = x.message ?: ""
      }
    }
  }

  /** A step: the actions that show (others' passes do not). */
  private fun isStop(k: Int): Boolean {
    val a = game?.actions?.getOrNull(k) ?: return false
    return a[0].jsonPrimitive.content.toInt() == me || a[1].jsonPrimitive.content != "pass"
  }

  fun go(k: Int) {
    val g = game ?: return
    epoch++
    cursor = k.coerceIn(start, g.actions.size)
    showResult = true
    show()
  }

  fun prev() {
    var k = cursor - 1
    while (k > start && !isStop(k - 1)) k--
    go(k)
  }

  fun next() {
    val g = game ?: return
    var k = cursor + 1
    while (k < g.actions.size && !isStop(k - 1)) k++
    go(k)
  }

  fun toNewest() = game?.let { go(it.actions.size) }

  val atHead: Boolean get() = game?.let { cursor == it.actions.size } ?: false
  val steps: Int get() = (start until cursor).count(::isStop)

  /** Stops the computer players (the branch is left). */
  fun close() {
    epoch++
  }

  private companion object {
    fun newSeed() = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BranchScreen(vm: AppModel, source: GameRecord, me: Int, names: List<String>, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  val settings = vm.settings
  val b = remember(source) { BranchRun(scope, source, me) { settings.bot } }
  LaunchedEffect(b) { b.restart() }
  val close = {
    b.close()
    onClose()
  }
  BackHandler { close() }
  val view = b.view
  val final = b.final
  val over = view?.phase == "over"
  // The end: the result's moment waits for the player's tap (TableView), then the result; without one (the words
  // alone) the table as it ended waits for it (TapOn).
  val (held, letGo) = endHold(over)
  val fxOn by FxClock.on.collectAsState()
  val sheet = over && final?.result != null && b.showResult && !fxOn && !held
  val cs = MaterialTheme.colorScheme

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val side = maxWidth > maxHeight
    val w = maxWidth.value
    val reshuffle = @Composable {
      Row(Modifier.clickable { b.toggleReshuffle(!b.reshuffle) }.padding(end = 8.dp).testTag("branch_reshuffle"),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = b.reshuffle, onCheckedChange = { b.toggleReshuffle(it) })
        Text(stringResource(R.string.branch_reshuffle), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
      }
    }
    val table = @Composable { mod: Modifier ->
      Box(mod) {
        if (view == null) Loading()
        else TableView(
          view, me, names, revealed = if (over) final?.hands else null, interactive = view.myTurn && !b.deciding, onAct = { b.act(it) },
          maxHand = 56f, robbed = b.robbed,
          overlay = {
            if (over && (held || fxOn)) TapOn(letGo)
            if (sheet && final != null) Sheet {
              HandResultCard(final, names, me, resultTileW(side, w), next = false) {
                TextButton(onClick = { b.showResult = false }) { Text(stringResource(R.string.result_hands)) }
                FilledTonalButton(onClick = { b.restart() }, Modifier.testTag("branch_again")) { Text(stringResource(R.string.branch_again)) }
                Button(onClick = close, Modifier.testTag("branch_back")) { Text(stringResource(R.string.branch_back)) }
              }
            } else if (over && !b.showResult) Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
              Button(onClick = { b.showResult = true }) { Text(stringResource(R.string.back)) }
            }
          },
        )
      }
    }
    val info = @Composable {
      FlowRow(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.branch_step, b.steps), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("branch_where"))
        Text(
          b.error ?: stringResource(
            when {
              over -> R.string.branch_over
              view?.myTurn == true && !b.deciding -> R.string.branch_your_turn
              b.atHead -> R.string.branch_others
              else -> R.string.branch_back_note
            },
          ),
          style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
        Text(stringResource(R.string.branch_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
    }
    val navButton = @Composable { tag: String, label: Int, enabled: Boolean, filled: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector ->
      val mod = Modifier.size(42.dp).testTag(tag)
      if (filled) FilledIconButton(onClick = onClick, enabled = enabled, modifier = mod) { Icon(icon, stringResource(label)) }
      else FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = mod) { Icon(icon, stringResource(label)) }
    }
    val atStart = b.cursor <= b.start
    val buttons = @Composable {
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        navButton("branch_start", R.string.branch_to_start, !atStart, false, { b.go(b.start) }, Icons.Filled.SkipPrevious)
        navButton("branch_prev", R.string.replay_prev, !atStart, false, { b.prev() }, Icons.AutoMirrored.Filled.NavigateBefore)
        navButton("branch_next", R.string.replay_next, !b.atHead, !b.atHead, { b.next() }, Icons.AutoMirrored.Filled.NavigateNext)
        navButton("branch_newest", R.string.branch_to_newest, !b.atHead, false, { b.toNewest() }, Icons.Filled.SkipNext)
        FilledTonalButton(onClick = { b.newBranch() }, Modifier.testTag("branch_new")) { Text(stringResource(R.string.branch_new)) }
      }
    }
    if (side) Row(Modifier.fillMaxSize()) {
      table(Modifier.weight(1f).fillMaxHeight().background(LocalTable.current.felt).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)))
      Column(
        Modifier.fillMaxHeight().background(cs.surfaceContainer).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)).width(208.dp)
          .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, modifier = Modifier.testTag("branch_close")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.branch_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
          }
          reshuffle()
          info()
        }
        buttons()
      }
    } else Column(Modifier.fillMaxSize()) {
      TitleBar(stringResource(R.string.branch_title), close) { reshuffle() }
      table(Modifier.weight(1f).fillMaxWidth())
      // (Not under the result: on a low window it takes the room the result needs.)
      if (!sheet) Column(Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        info()
        buttons()
      }
    }
  }
}
