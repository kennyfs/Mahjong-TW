package io.github.kennyfs.mahjong.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Action
import io.github.kennyfs.mahjong.Analysis
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.BotPace
import io.github.kennyfs.mahjong.Claims
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.EngineException
import io.github.kennyfs.mahjong.EngineJson
import io.github.kennyfs.mahjong.FxClock
import io.github.kennyfs.mahjong.GameRecord
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.View
import io.github.kennyfs.mahjong.choiceMarks
import io.github.kennyfs.mahjong.fullAction
import io.github.kennyfs.mahjong.isTaken
import io.github.kennyfs.mahjong.opponentNotes
import io.github.kennyfs.mahjong.pts
import io.github.kennyfs.mahjong.recordAction
import io.github.kennyfs.mahjong.tileMarks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A branch of a reviewed hand (the review's 分支), or a position of the analysis played on (從這裡打): from a decision
 * the hand is played on, the player at their seat and the computer players (the settings' bot) at the others, however
 * the hand was first played. Nothing is recorded or scored. The web page's Branch.svelte.
 *
 * With 重洗看不到的牌 (on at first, the user's) what the player could not see there (the others' concealed tiles, the
 * wall) is dealt again by the branch's seed (the engine's record from a base position, docs/gui-engine.md). A position
 * of the analysis has no hand of its own: what the player cannot see there is always dealt by the seed. Turned off,
 * the tiles are the hand's own: the branch says what would have come of another choice.
 *
 * The analysis is there as the review shows it, before the player decides (BranchRun.look): on the hand's tiles what
 * each discard costs and its danger, on each choice's button what it costs, on the name plates how likely each
 * opponent is ready, and the list of every action: beside the table on a large window, else one tap away in place of
 * the table, the deciding seat's tiles over it (the player may play there too; DecisionView), its shape in grey while
 * the computer players play. A tap on a tile shows its figures over the hand; a second tap discards it. A step gone
 * back to shows the decision made there the same way, the action made there marked; another seat's step shows that
 * seat's decision in the list.
 *
 * The computer players go on by themselves at the settings' pace (the manual pace: the middle one), until the player
 * decides or the hand is over; on a tile the player could claim they decide first (Claims). 上一步 goes back a step, as
 * far as the branch's start, and the player may choose otherwise where they decided: what came after is then dropped.
 * 下一步 goes forward again over what was played; 上一個決定 and 下一個決定 go to the player's own decisions. The seed
 * stays for as long as the branch does; only a new branch (or turning 重洗 on or off, which asks first when anything
 * was played) deals with another. Back, or the end, returns to where the branch was taken from.
 */
class BranchRun(private val scope: CoroutineScope, private val source: GameRecord, val me: Int, private val bot: () -> String) {
  /** A step's decision as the analysis shows it: whose it is, what was done there (a step gone back to; null: the
   *  decision to make now), the network's figures, and with the list in place of the table the seat's own view. */
  data class Look(val seat: Int, val action: Action?, val analysis: Analysis?, val seatView: View?)

  // (On by default, the user's: a branch is for trying another way, not for knowing what comes.)
  var reshuffle by mutableStateOf(true)
  /** A position with no hand of its own (the analysis'): what the player cannot see is always dealt by the seed. */
  val dealt = source.base != null
  var seed by mutableStateOf(newSeed())
  var game by mutableStateOf<GameRecord?>(null)
  /** The actions shown (of `game`): less than all after 上一步. */
  var cursor by mutableIntStateOf(0)
  var view by mutableStateOf<View?>(null)
  var look by mutableStateOf<Look?>(null)
  var final by mutableStateOf<View?>(null)
  var error by mutableStateOf<String?>(null)
  var showResult by mutableStateOf(true)
  /** The computer players deciding first on a tile the player could claim: no buttons meanwhile. */
  var deciding by mutableStateOf(false)
  /** A claim taken from the player: its event (TableView's `robbed`). */
  var robbed by mutableIntStateOf(-1)
  /** Counts the starts (the screen's kept notes go with each). */
  var starts by mutableIntStateOf(0)
  /** What the list needs (the screen's): any seat's decision analyzed (the list shown), the seat's own view (the list
   *  in place of the table). */
  var listShown = false
  var listView = false
  private var epoch = 0
  /** The computer players' loop (`run`): its number and its epoch. One of another epoch (waiting out a pace when the
   *  branch started again) ends at its next look: it holds back neither this one nor its state. */
  private var running = false
  /** The computer players' pace (Claims.kt BotPace): their turns, their discards' flights, their answers to a tile. */
  private val pace = BotPace()
  private var loop = 0
  private var loopEpoch = -1

  /** The branch's start: the hand cut at the decision, or (reshuffled, or a position) its position with the unseen
   *  tiles dealt by the seed. */
  private val root: GameRecord get() = when {
    source.base != null -> source.copy(base = buildJsonObject {
      for ((k, v) in source.base) if (k != "seed") put(k, v)
      put("seed", JsonPrimitive(seed))
    })
    reshuffle -> GameRecord(
      source.rules, source.setup, actions = emptyList(),
      base = buildJsonObject {
        put("game", EngineJson.encodeToJsonElement(GameRecord.serializer(), source))
        put("seat", me)
        put("seed", seed)
      },
    )
    else -> source
  }
  val start: Int get() = if (reshuffle && !dealt) 0 else source.actions.size
  private fun bots() = (0..3).map { if (it == me) null else bot() }

  /** The decision at step k (its table `v`): the player's, always analyzed; another seat's, when the list is shown. At
   *  the newest step only the player's: the computer players go on by themselves. */
  private suspend fun lookAt(g: GameRecord, k: Int, v: View): Look? {
    if (v.phase == "over") return null
    val d = g.actions.getOrNull(k)?.let { recordAction(it) } ?: if (v.myTurn) me to null else return null
    val cut = g.cut(k)
    val analysis = if (d.first == me || listShown) try { Engine.analyzeGame(cut, d.first) } catch (_: EngineException) { null } else null
    val seatView = if (!listView) null else if (d.first == me) v else try { Engine.view(cut, d.first) } catch (_: EngineException) { null }
    return Look(d.first, d.second?.let { fullAction(it) }, analysis, seatView)
  }

  /** The list opened or closed: the step's decision again, with what the list needs. */
  fun refreshLook() {
    val g = game ?: return
    val v = view ?: return
    val k = cursor
    if (deciding) return
    scope.launch {
      val l = lookAt(g, k, v)
      if (g === game && v === view && k == cursor && !deciding) look = l
    }
  }

  /** From the decision again (the first time, 從分支點再打一次, or 重洗 turned on or off). */
  fun restart() {
    epoch++
    starts++
    pace.reset()
    game = root
    cursor = start
    final = null
    look = null
    showResult = true
    robbed = -1
    show()
  }

  /** Another seed. */
  fun newBranch() {
    seed = newSeed()
    restart()
  }

  fun toggleReshuffle() {
    reshuffle = !reshuffle
    restart()
  }

  /** Whether anything was played since the start (turning 重洗 on or off asks first then). */
  val played: Boolean get() = (game?.actions?.size ?: 0) > start

  private fun show() {
    val g = game ?: return
    val k = cursor
    val e = epoch
    scope.launch {
      try {
        val v = Engine.view(g.cut(k), me)
        if (e != epoch || k != cursor || g !== game) return@launch
        val head = k == g.actions.size
        // At the newest step the computer players decide first on a tile the player could claim (run): the player's
        // buttons and figures come after.
        val claimFirst = head && v.myTurn && v.phase != "self" && v.phase != "over"
        val l = if (claimFirst) null else lookAt(g, k, v)
        val f = if (v.phase == "over") Engine.view(g.cut(k), me, 0, reveal = true) else null
        if (e != epoch || k != cursor || g !== game) return@launch
        if (claimFirst) deciding = true
        view = v
        look = l
        final = f
        error = null
        if (head && v.phase != "over" && (!v.myTurn || v.phase != "self")) run()
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
              val v = Engine.view(pc.game, me)
              val l = lookAt(pc.game, pc.game.actions.size, v)
              if (e != epoch) break
              game = pc.game
              cursor = pc.game.actions.size
              view = v
              look = l
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
            look = null
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
          // The player's own turn next: its figures are worked out within the turn's pace before it.
          val l = if (v.myTurn && v.phase == "self") lookAt(next, next.actions.size, v) else null
          if (e != epoch) break
          var fly = 0L
          if (claim) {
            if (Claims.othersCall(v, before, me)) pace.call()
          } else fly = pace.turn(g, p.current, next, from)
          if (e != epoch) break
          // A tile the player could claim: no buttons until the computer players have answered (above).
          if (v.myTurn && v.phase != "self" && v.phase != "over") deciding = true
          game = next
          pace.came(next, fly)
          cursor = next.actions.size
          view = v
          look = l
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
        look = null
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

  /** The player's own decisions. */
  private fun isMine(k: Int): Boolean = game?.actions?.getOrNull(k)?.let { it[0].jsonPrimitive.content.toInt() == me } ?: false

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

  /** The step at which the player decides before or after this one (the newest step when none is after). */
  fun prevMine() {
    var k = cursor - 1
    while (k > start && !isMine(k)) k--
    go(k)
  }

  fun nextMine() {
    val g = game ?: return
    var k = cursor + 1
    while (k < g.actions.size && !isMine(k)) k++
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

/**
 * The branch's screen. `unit`: a typical payment under the hand's rules (the review's), to grade what an action costs
 * by. `title`, `backLabel`: the page's title and the result's way back (a branch's unless given: 從這裡打, 回到分析).
 *
 * The controls as the review has them: under the table upright (the words there keep the room of their longest, so
 * that the table does not move from step to step), in a column at the right sideways (the table the full height),
 * with the list under them on a large window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BranchScreen(
  vm: AppModel, source: GameRecord, me: Int, names: List<String>, unit: Double? = null, title: String? = null, backLabel: String? = null,
  onClose: () -> Unit,
) {
  val settings = vm.settings
  val res = LocalResources.current
  // (The app's model keeps the branch: turned sideways, the screen is made again and the branch goes on.)
  val b = vm.branchRun(source, me)
  LaunchedEffect(b) { if (b.game == null) b.restart() }
  val close = {
    vm.endBranch()
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
  var confirmShuffle by remember { mutableStateOf(false) }
  var panelOpen by rememberSaveable { mutableStateOf(false) }

  // What the analysis shows: on the table the player's own decision; in the list any seat's.
  val look = b.look
  /** The index of the action made at a step gone back to, in its analysis; -1 at the decision to make now. */
  val played = look?.analysis?.let { a -> look.action?.let { x -> a.actions.indexOfFirst { isTaken(it.action, x) } } } ?: -1
  /** The player's own decision at this step, with the network's figures. */
  val mine = look?.takeIf { it.seat == me }?.analysis?.takeIf { it.best != null }
  val marks = remember(mine, played, unit, res) { mine?.let { tileMarks(res, it, played, unit) } ?: emptyMap() }
  val choices = remember(mine, played, unit, res) { mine?.let { choiceMarks(res, it, played, unit) } }
  // The opponents' notes stay as the player's last decision left them while the others play on from it (at the newest
  // step), till the next decision's; a step gone back to shows its own, or none.
  var keptNotes by remember { mutableStateOf<List<SeatNote?>?>(null) }
  LaunchedEffect(mine, b.atHead) { if (mine != null && b.atHead) keptNotes = opponentNotes(res, mine) }
  LaunchedEffect(b.starts) { keptNotes = null }
  val notes = if (mine != null) opponentNotes(res, mine) else if (b.atHead && !over) keptNotes else null
  var picked by remember { mutableStateOf<String?>(null) }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val side = maxWidth > maxHeight
    val w = maxWidth.value
    // The list beside the table on a large window, else in place of the table when asked for.
    val inlinePanel = side && maxWidth >= 1180.dp && maxHeight >= 600.dp
    val listShown = inlinePanel || panelOpen
    val listView = panelOpen && !inlinePanel && !over
    b.listShown = listShown
    b.listView = listView
    LaunchedEffect(listShown, listView) { b.refreshLook() }
    val hw = if (side) minOf(44f, ((w - 208) * 0.46f - 24) / 9.3f) else minOf(44f, (minOf(w, 720f) - 28) / 9.3f)
    val pickedIndex = look?.analysis?.let { a -> picked?.let { t -> a.actions.indexOfFirst { it.action.type == "discard" && it.action.tile == t } } } ?: -1

    // `short`: its words in a few characters (重洗), where the title bar has no room for them (the title was cut).
    val reshuffle: @Composable (Boolean) -> Unit = { short ->
      // One control, the row (a screen reader hears one check box with its words, not two stops).
      if (!b.dealt) Row(Modifier.toggleable(b.reshuffle, role = Role.Checkbox) { if (b.played) confirmShuffle = true else b.toggleReshuffle() }
        .padding(end = 8.dp).testTag("branch_reshuffle"), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = b.reshuffle, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())  // (the room it had)
        Text(stringResource(if (short) R.string.branch_reshuffle_short else R.string.branch_reshuffle), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
      }
    }
    val toggleColors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = cs.secondaryContainer, checkedContentColor = cs.onSecondaryContainer)
    val listToggle = @Composable {
      if (!inlinePanel) IconToggleButton(checked = panelOpen, onCheckedChange = { panelOpen = it }, modifier = Modifier.testTag("branch_panel"),
        colors = toggleColors) {
        Icon(Icons.Filled.Assessment, stringResource(R.string.review_panel))
      }
    }
    // Over the hand on the table: the figures of the tile chosen (with the list beside the table its row there is lit
    // instead).
    val tile = picked
    val strip: (@Composable () -> Unit)? = if (mine != null && tile != null && !inlinePanel) ({ PickedFigures(mine, me, names, tile) }) else null
    val table = @Composable { mod: Modifier ->
      Box(mod) {
        if (view == null) TableSkeleton()
        else TableView(
          view, me, names, revealed = if (over) final?.hands else null, interactive = view.myTurn && !b.deciding, onAct = { b.act(it) },
          maxHand = 56f, robbed = b.robbed, readyHint = settings.readyHint, marks = marks, notes = notes, strip = strip,
          onPick = { picked = it }, choiceMarks = choices, tagRoom = true,
          overlay = {
            if (over && (held || fxOn)) TapOn(letGo)
            if (sheet && final != null) Sheet {
              HandResultCard(final, names, me, resultTileW(side, w), next = false) {
                TextButton(onClick = { b.showResult = false }) { Text(stringResource(R.string.result_hands)) }
                FilledTonalButton(onClick = { b.restart() }, Modifier.testTag("branch_again")) { Text(stringResource(R.string.branch_again)) }
                Button(onClick = close, Modifier.testTag("branch_back")) { Text(backLabel ?: stringResource(R.string.branch_back)) }
              }
            } else if (over && !b.showResult) Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
              Button(onClick = { b.showResult = true }) { Text(stringResource(R.string.back)) }
            }
          },
        )
      }
    }
    // The list in place of the table: the deciding seat's own tiles over it; the player's own while the others play.
    val list = @Composable { mod: Modifier ->
      val seat = look?.seat
      DecisionView(
        look?.seatView ?: view, if (seat == null) "" else if (seat == me) stringResource(R.string.analysis_your_turn) else stringResource(R.string.analysis_turn_of, names[seat]),
        look?.analysis, names, { b.act(it) }, mod, played = played, unit = unit, who = if (seat != null && seat != me) names[seat] else "",
        canPlay = view?.myTurn == true && !b.deciding && !over && seat == me, choices = choices, readyHint = settings.readyHint, wide = side, hw = hw,
      )
    }
    // A decision's list beside the table: its shape in grey while there is none (the computer players playing).
    val panel = @Composable {
      val a = look?.analysis
      if (a != null) DecisionPanel(a, played = played, unit = unit, w = 34f, names = names, picked = pickedIndex, who = if (look.seat == me) "" else names[look.seat])
      else PanelSkeleton(w = 34f)
    }
    // The step, the expected result at the player's decision, and what to do: the words keep the room of their longest
    // (two lines), so that the table does not move from step to step.
    val info = @Composable {
      val status = b.error ?: stringResource(
        when {
          over -> R.string.branch_over
          view?.myTurn == true && !b.deciding -> if (view.legal.any { it.type == "discard" }) R.string.branch_your_turn else R.string.branch_your_call
          b.atHead -> R.string.branch_others
          else -> R.string.branch_back_note
        },
      )
      val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
      val infoMod = if (side) Modifier.fillMaxWidth()
      else Modifier.fillMaxWidth().height((68f * fontScale).dp.coerceIn(66.dp, 100.dp)).verticalScroll(rememberScrollState())
      Column(infoMod, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.branch_step, b.steps), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("branch_where"))
          mine?.value?.let { v ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(stringResource(R.string.review_now), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
              Text(pts(v), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
          }
          Text(stringResource(R.string.branch_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // (Two lines' room: the longest status.)
        Text(status, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, minLines = 2, modifier = Modifier.testTag("branch_status"))
      }
    }
    val navButton = @Composable { tag: String, label: Int, enabled: Boolean, filled: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector ->
      val mod = Modifier.size(42.dp).testTag(tag)
      if (filled) FilledIconButton(onClick = onClick, enabled = enabled, modifier = mod) { Icon(icon, stringResource(label)) }
      else FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = mod) { Icon(icon, stringResource(label)) }
    }
    val atStart = b.cursor <= b.start
    val nav = mapOf<String, @Composable () -> Unit>(
      "start" to { navButton("branch_start", R.string.branch_to_start, !atStart, false, { b.go(b.start) }, Icons.Filled.SkipPrevious) },
      "prevMine" to { navButton("branch_prev_mine", R.string.replay_prev_decision, !atStart, false, { b.prevMine() }, Icons.AutoMirrored.Filled.Undo) },
      "prev" to { navButton("branch_prev", R.string.replay_prev, !atStart, false, { b.prev() }, Icons.AutoMirrored.Filled.NavigateBefore) },
      "next" to { navButton("branch_next", R.string.replay_next, !b.atHead, !b.atHead, { b.next() }, Icons.AutoMirrored.Filled.NavigateNext) },
      "nextMine" to { navButton("branch_next_mine", R.string.replay_next_decision, !b.atHead, false, { b.nextMine() }, Icons.AutoMirrored.Filled.Redo) },
      "newest" to { navButton("branch_newest", R.string.branch_to_newest, !b.atHead, false, { b.toNewest() }, Icons.Filled.SkipNext) },
    )
    val newBranch = @Composable { mod: Modifier ->
      FilledTonalButton(onClick = { b.newBranch() }, mod.testTag("branch_new")) { Text(stringResource(R.string.branch_new)) }
    }
    val buttons = @Composable {
      // The narrow column beside the table: the big jumps over the small steps, a new branch under them.
      if (side && !inlinePanel) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { for (k in listOf("start", "prevMine", "nextMine", "newest")) nav.getValue(k)() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) { for (k in listOf("prev", "next")) nav.getValue(k)() }
        newBranch(Modifier.fillMaxWidth())
      } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        for (k in listOf("start", "prevMine", "prev", "next", "nextMine", "newest")) nav.getValue(k)()
        newBranch(Modifier)
      }
    }
    val heading = title ?: stringResource(R.string.branch_title)
    if (side) Row(Modifier.fillMaxSize()) {
      val startInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
      if (listView) list(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(startInset))
      else table(Modifier.weight(1f).fillMaxHeight().background(LocalTable.current.felt).windowInsetsPadding(startInset))
      Column(
        Modifier.fillMaxHeight().background(cs.surfaceContainer).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
          .width(if (inlinePanel) 420.dp else 208.dp).padding(horizontal = if (inlinePanel) 12.dp else 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // No title bar beside the table: the way back at the column's top-left, clear of the table.
        val top = @Composable {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, modifier = Modifier.testTag("branch_close")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(heading, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            listToggle()
          }
        }
        if (inlinePanel) {
          top()
          reshuffle(false)
          info()
          buttons()
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 12.dp)) { panel() }
        } else {
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            top()
            Shorten(2) { reshuffle(it == 1) }
            info()
          }
          buttons()
        }
      }
    } else Column(Modifier.fillMaxSize()) {
      Shorten(2) { level ->
        TitleBar(heading, close) {
          reshuffle(level == 1)
          listToggle()
        }
      }
      if (listView) list(Modifier.weight(1f).fillMaxWidth()) else table(Modifier.weight(1f).fillMaxWidth())
      // (Not under the result: on a low window it takes the room the result needs.)
      if (!sheet) Column(Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        info()
        buttons()
      }
    }
  }

  if (confirmShuffle) AlertDialog(
    onDismissRequest = { confirmShuffle = false },
    confirmButton = {
      TextButton(onClick = { confirmShuffle = false; b.toggleReshuffle() }, Modifier.testTag("branch_restart")) { Text(stringResource(R.string.branch_restart)) }
    },
    dismissButton = { TextButton(onClick = { confirmShuffle = false }) { Text(stringResource(R.string.cancel)) } },
    text = { Text(stringResource(R.string.branch_reshuffle_confirm), style = MaterialTheme.typography.bodyLarge) },
  )
}
