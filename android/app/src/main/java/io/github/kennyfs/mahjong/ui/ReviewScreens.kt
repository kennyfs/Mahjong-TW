package io.github.kennyfs.mahjong.ui

import io.github.kennyfs.mahjong.Explain
import io.github.kennyfs.mahjong.Words
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import io.github.kennyfs.mahjong.Practice
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Action
import io.github.kennyfs.mahjong.Analysis
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.EQUAL
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.FxClock
import io.github.kennyfs.mahjong.EngineException
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Review
import io.github.kennyfs.mahjong.Screen
import io.github.kennyfs.mahjong.Steps
import io.github.kennyfs.mahjong.TileMark
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.Truth
import io.github.kennyfs.mahjong.View
import io.github.kennyfs.mahjong.actionText
import io.github.kennyfs.mahjong.costOf
import io.github.kennyfs.mahjong.efficiencyText
import io.github.kennyfs.mahjong.FigureKey
import io.github.kennyfs.mahjong.figureCells
import io.github.kennyfs.mahjong.flowersOf
import io.github.kennyfs.mahjong.fullAction
import io.github.kennyfs.mahjong.gradeOf
import io.github.kennyfs.mahjong.isKey
import io.github.kennyfs.mahjong.isTaken
import io.github.kennyfs.mahjong.keysOf
import io.github.kennyfs.mahjong.meldTiles
import io.github.kennyfs.mahjong.mistakesParts
import io.github.kennyfs.mahjong.notableText
import io.github.kennyfs.mahjong.pct
import io.github.kennyfs.mahjong.pts
import io.github.kennyfs.mahjong.readyLevel
import io.github.kennyfs.mahjong.reasonOf
import io.github.kennyfs.mahjong.recordAction
import io.github.kennyfs.mahjong.reviewOf
import io.github.kennyfs.mahjong.summarize
import io.github.kennyfs.mahjong.tagCost
import io.github.kennyfs.mahjong.tileMarks
import io.github.kennyfs.mahjong.opponentNote
import io.github.kennyfs.mahjong.tilesShort
import kotlinx.coroutines.launch

/** An action in words: "打出 五萬", "吃 三四五萬". */
@Composable
fun actionText(a: Action): String = actionText(LocalResources.current, a)

/** The decision a review shows: whose it is, its analysis, the action taken (its index), what the others really held. */
private class Focus(val seat: Int, val analysis: Analysis, val played: Int, val truth: List<Truth>?)

/** How near its end a decision's list must be scrolled to keep its distance from the end over a step. */
private val NEAR_END = 360.dp

/**
 * A finished hand's review: the table as the player saw it (or everyone's tiles), step by step through the record's
 * actions, with the network's analysis of each of the player's decisions and the result at the end. Only after the
 * hand: never while it is played. The web page's Replay.svelte.
 *
 * The steps shown are the actions that took effect and the player's own decisions: others' passes are skipped, and so
 * are others' claims that another seat's claim came before (a chow when someone else won on the tile); the engine says
 * which ({"cmd":"steps"}). The button to the end goes to the last of them (the win, with who dealt in; without a win
 * the last discard), then to the table at the end; the result's sheet opens from its own button, never by itself.
 *
 * The review (docs/analysis.md): the hand in short (the expected result at the first decision, what the decisions
 * lost, the rest as luck), a graph of the expected result to jump from, steps between the decisions worth a look (the
 * mistakes of the settings' level, and the best actions that are not the obvious ones), and at a decision what each
 * action costs against the best: on the hand's tiles (what each discard costs, its danger as a band along its foot, a
 * tap for a tile's line), and in a list of every action one tap away (beside the table on a large window), its rows
 * numbers, bars or both (the settings). The list keeps its place from its end over a step when it is scrolled near it
 * (the opponents' figures are there, to watch them change). A step can be taken to the analysis screen to try changes.
 */
@OptIn(ExperimentalLayoutApi::class)
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
  val res = LocalResources.current
  val scope = rememberCoroutineScope()
  val cs = MaterialTheme.colorScheme
  val settings = vm.settings
  val level = settings.level
  val n = rec.game.actions.size
  /** The step shown, and the one asked for (go): everything a step shows comes from the engine first, and the step is
   *  shown when all of it is there (the load below), so that nothing of it comes in a moment later and moves the rest. */
  var step by remember(rec.id) { mutableIntStateOf(vm.reviewStep.coerceIn(0, n)) }
  var want by remember(rec.id) { mutableIntStateOf(step) }
  LaunchedEffect(step) { vm.reviewStep = step }
  // (Saveable: kept when the phone turns, as the step is in vm.reviewStep.)
  var showAll by rememberSaveable { mutableStateOf(false) }
  var showResult by rememberSaveable(rec.id) { mutableStateOf(false) }
  /** A phone: the list in place of the table. */
  var panelOpen by rememberSaveable(rec.id) { mutableStateOf(false) }
  var view by remember(rec.id) { mutableStateOf<View?>(null) }
  var final by remember(rec.id) { mutableStateOf<View?>(null) }
  var rev by remember(rec.id) { mutableStateOf<Review?>(null) }
  var revError by remember(rec.id) { mutableStateOf<String?>(null) }
  var steps by remember(rec.id) { mutableStateOf<Steps?>(null) }
  // The hand's end, which of its actions took effect, and its review when the network has gone through it.
  LaunchedEffect(rec.id) {
    launch { final = try { Engine.view(rec.game, rec.me, 0, reveal = true) } catch (e: EngineException) { null } }
    launch { steps = try { Engine.steps(rec.game) } catch (e: EngineException) { null } }
    try {
      val x = reviewOf(rec)
      rev = x
      if (x.firstValue != null && vm.records.get(rec.id)?.rev?.m != Engine.REVIEW_ID) vm.records.setReview(rec.id, summarize(x))
    } catch (e: EngineException) {
      revError = e.message ?: ""
    }
  }

  val you = stringResource(R.string.you)
  val names = rec.names.mapIndexed { i, x -> if (i == rec.me) you else x }
  val actions = remember(rec.id) { rec.game.actions.map(::recordAction) }
  /** A step is shown when its action took effect or is the player's own (until the engine has said which took effect:
   *  when it is not another seat's pass). */
  val taken = steps?.taken
  fun isStop(k: Int) = actions[k].first == rec.me || (taken?.getOrNull(k) ?: (actions[k].second.type != "pass"))
  fun isMine(k: Int) = actions[k].first == rec.me
  val stopList = (0 until n).filter(::isStop)
  val stepNumber = stopList.count { it <= step }
  /** The step the play ended with: the win (the first, when a discard made several winners); without a win the last
   *  action that took effect. */
  val last = steps?.last?.takeIf { it >= 0 } ?: (stopList.lastOrNull() ?: 0)
  fun go(k: Int) {
    want = k.coerceIn(0, n)
    showResult = false
  }
  // (From the step asked for last: steps asked for quickly add up.)
  fun next() { var k = want + 1; while (k < n && !isStop(k)) k++; go(k) }
  fun prev() { var k = want - 1; while (k > 0 && !isStop(k)) k--; go(k) }
  fun nextMine() { var k = want + 1; while (k < n && !isMine(k)) k++; go(k) }
  fun prevMine() { var k = want - 1; while (k > 0 && !isMine(k)) k--; go(k) }
  /** To the last step; from there to the table at the end. */
  fun toEnd() = go(if (want < last) last else n)
  fun openResult() {
    go(n)
    showResult = true
  }
  /** Whose tile a claim at step k answers: the discard (or added kong) before it. */
  fun claimedFrom(k: Int): Int {
    for (i in k - 1 downTo 0) if (actions[i].second.type == "discard" || actions[i].second.type == "kakan") return actions[i].first
    return -1
  }
  /** How the hand ended, in a few words. */
  val outcome = final?.result?.let { r ->
    if (r.exhausted || r.winners.isEmpty()) res.getString(R.string.result_exhausted)
    else {
      val who = r.winners.joinToString(Words.list) { names[it.seat] }
      when {
        r.flowerWin -> res.getString(R.string.result_flower_win, who)
        r.selfDraw -> res.getString(R.string.result_tsumo, who)
        else -> res.getString(R.string.result_ron, who) + if (r.loser >= 0) Words.sep + res.getString(R.string.result_ron_from, names[r.loser]) else ""
      }
    }
  } ?: ""

  // The review: its decisions by step, and the ones to stop at.
  val r = rev
  val unit = r?.unit
  val hasNet = r?.firstValue != null
  // The steps whose explanation calls the player's decision well played (a 解說 with ★), with its title: from each such
  // step's view, once a review is in. They are stars and steps worth a look, as a notable best played is.
  var praised by remember(rec.id) { mutableStateOf<Map<Int, String>>(emptyMap()) }
  LaunchedEffect(rec.id, r) {
    praised = emptyMap()
    if (r == null || r.firstValue == null) return@LaunchedEffect
    val m = mutableMapOf<Int, String>()
    for (d in r.decisions) {
      if (!Explain.mayPraise(d.analysis, d.played, r.unit)) continue
      val v = try { Engine.view(rec.game.cut(d.step), rec.me) } catch (e: EngineException) { continue }
      val e = Explain.explain(res, d.analysis, d.played, v, r.unit)
      if (e?.found == true) m[d.step] = e.title
    }
    praised = m
  }
  val keys = remember(r, praised) { if (r != null && r.firstValue != null) keysOf(r, praised) else emptyList() }
  val stops = keys.filter { isKey(it, level) }
  val points = remember(r) { if (r != null && r.firstValue != null) r.decisions.map { it.step to (it.analysis.value ?: 0.0) } else emptyList() }
  /** How many decisions were questionable, mistakes, blunders. */
  val grades = (1..3).map { g -> keys.count { it.grade == g } }
  val mistakes = grades.drop(level.grade - 1).sum()
  val nextKeyStep = stops.firstOrNull { it.step > step }?.step ?: -1
  val prevKeyStep = stops.lastOrNull { it.step < step }?.step ?: -1
  val mine = r?.decisions?.firstOrNull { it.step == step }
  val key = keys.firstOrNull { it.step == step }
  /** The player's decision here explained by rules (解說, Explain.kt), when there is something to say. */
  val explanation = remember(mine, view, unit) {
    val v = view
    if (mine != null && v != null && hasNet) Explain.explain(res, mine.analysis, mine.played, v, unit) else null
  }
  var explaining by rememberSaveable { mutableStateOf(false) }
  var tipsOpen by rememberSaveable { mutableStateOf(false) }
  val deciding = if (step < n) actions[step].let { (s, a) -> s to fullAction(a) } else null
  /** What the step's line adds to the action: who dealt in to a win; for a claim that was not made, whose came first. */
  val decidingNote = if (deciding == null) "" else {
    val over = steps?.over?.getOrNull(step) ?: -1
    when {
      over >= 0 -> res.getString(R.string.replay_overridden, names[actions[over].first], res.getString(actionLabel(actions[over].second.type)))
      deciding.second.type == "ron" -> claimedFrom(step).let { if (it >= 0) res.getString(R.string.result_ron_from, names[it]) else "" }
      else -> ""
    }
  }
  val stepKey = "${rec.id}/$step"

  // The review's branch (BranchScreen): from the step of one of the player's decisions; back at that step after.
  val canBranch = step < n && isMine(step)
  vm.branchFrom?.takeIf { it.first == rec.id && it.second in 0..n }?.let { (_, k) ->
    BranchScreen(vm, rec.game.cut(k), rec.me, names, unit = unit) {
      vm.branchFrom = null
      go(k)
    }
    return
  }
  val fxOn by FxClock.on.collectAsState()

  BoxWithConstraints(Modifier.fillMaxSize()) {
    /** A wide window: the controls in a column at the right, the table the full height; with the list of a decision's
     *  actions under them when the window is large. */
    val side = maxWidth > maxHeight
    val inlinePanel = side && maxWidth >= 1180.dp && maxHeight >= 600.dp
    val w = maxWidth.value
    val hw = if (side) minOf(44f, ((w - 208) * 0.46f - 24) / 9.3f) else minOf(44f, (minOf(w, 720f) - 28) / 9.3f)

    // Another seat's decision (or one of the player's without a choice): analyzed when its list is shown. The list in
    // place of the table (a phone): the deciding seat's own tiles over it.
    val listShown = inlinePanel || panelOpen
    val listView = panelOpen && !inlinePanel && deciding != null
    var other by remember { mutableStateOf<Pair<String, Analysis>?>(null) }
    var seatViewOf by remember { mutableStateOf<Pair<String, View>?>(null) }
    // A step asked for (or the list opened, or every hand shown): what it shows, all of it, then the step at once. (A
    // newer request cancels an older one's load.)
    val panelWanted = panelOpen && !inlinePanel
    LaunchedEffect(rec.id, want, showAll, listShown, panelWanted) {
      val k = want
      val d = if (k < n) actions[k].first else null
      val own = r?.decisions?.any { it.step == k } ?: false
      val cut = rec.game.cut(k)
      val key = "${rec.id}/$k"
      try {
        val v = Engine.view(cut, rec.me, 0, reveal = showAll || k == n)
        val sv = if (panelWanted && d != null) try { Engine.view(cut, d) } catch (e: EngineException) { null } else null
        val a = if (listShown && d != null && !own) other?.takeIf { it.first == key }?.second
          ?: try { Engine.analyzeGame(cut, d) } catch (e: EngineException) { null } else null
        view = v
        seatViewOf = sv?.let { key to it }
        if (a != null) other = key to a
        step = k
      } catch (e: EngineException) {
        vm.engineError(e)
      }
    }
    val focus: Focus? = when {
      deciding == null -> null
      mine != null -> Focus(rec.me, mine.analysis, mine.played, mine.truth)
      else -> other?.takeIf { it.first == stepKey }?.second?.let { a -> Focus(deciding.first, a, a.actions.indexOfFirst { isTaken(it.action, deciding.second) }, null) }
    }

    // On the table: the tiles' marks, the name plates' notes, the options that are not discards, the tile tapped.
    val marks: Map<String, TileMark>? = if (!hasNet) null else if (mine != null) tileMarks(res, mine.analysis, mine.played, unit) else emptyMap()
    val notes: List<SeatNote?>? = mine?.analysis?.opponents?.let { opponents ->
      val out = arrayOfNulls<SeatNote>(4)
      for (o in opponents) {
        val tr = if (showAll) mine.truth.firstOrNull { it.seat == o.seat } else null
        out[o.seat] = when {
          tr != null && tr.missed -> SeatNote(res.getString(R.string.opp_missed), 0)
          tr != null && tr.shanten == 0 -> SeatNote(res.getString(R.string.opp_ready, tilesShort(tr.waits.map { it.tile })), 3)
          tr != null -> SeatNote(res.getString(R.string.analysis_shanten, tr.shanten), 0)
          else -> opponentNote(res, o)
        }
      }
      out.toList()
    }
    val options = if (mine != null && mine.analysis.best != null) mine.analysis.actions.indices.filter { mine.analysis.actions[it].action.type != "discard" }
    else emptyList()
    var picked by remember(stepKey) { mutableStateOf<String?>(null) }
    val pickedTile = picked
    val pickedIndex = if (focus != null && pickedTile != null) focus.analysis.actions.indexOfFirst { it.action.type == "discard" && it.action.tile == pickedTile } else -1
    val oppNames = focus?.let { f -> (1..3).map { names[(f.seat + it) % 4] } } ?: emptyList()

    val seatView = seatViewOf?.takeIf { it.first == stepKey }?.second

    // The list over a step: scrolled near its end (where the opponents' figures are, to watch them change from step
    // to step) it keeps its distance from the end; else it starts from its top.
    val listScroll = rememberScrollState()
    val nearEnd = with(LocalDensity.current) { NEAR_END.roundToPx() }
    /** The distance kept from the list's end, or null; what was placed, and until when scrolls are the page's own. */
    val keep = remember { object { var fromEnd: Int? = null; var placed = ""; var quietUntil = 0L } }
    LaunchedEffect(listScroll) {
      snapshotFlow { listScroll.isScrollInProgress }.collect { moving ->
        if (moving || System.currentTimeMillis() < keep.quietUntil || listScroll.maxValue == Int.MAX_VALUE) return@collect
        val d = listScroll.maxValue - listScroll.value
        keep.fromEnd = if (listScroll.value > 0 && d <= nearEnd) d else null
      }
    }
    // The step's list, and on a phone the seat's hand over it, are there (both come from the engine).
    val listReady = deciding == null || (focus != null && (!listView || seatView != null))
    LaunchedEffect(stepKey, listReady, showAll, listShown) {
      if (!listShown) {
        keep.fromEnd = null
        return@LaunchedEffect
      }
      keep.quietUntil = System.currentTimeMillis() + 300
      if (!listReady) return@LaunchedEffect
      withFrameNanos { }
      val d = keep.fromEnd
      if (d != null) listScroll.scrollTo((listScroll.maxValue - d).coerceAtLeast(0)) else if (keep.placed != stepKey) listScroll.scrollTo(0)
      keep.placed = stepKey
      keep.quietUntil = System.currentTimeMillis() + 300
    }

    fun toAnalysis() {
      val d = deciding ?: return
      scope.launch {
        try {
          val cut = rec.game.cut(step)
          vm.editor.load(Engine.view(cut, d.first).position, rec.game.rules, cut)
          vm.go(Screen.ANALYZE)
        } catch (e: EngineException) {
          vm.engineError(e)
        }
      }
    }

    val toggleColors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = cs.secondaryContainer, checkedContentColor = cs.onSecondaryContainer)
    val branchButton = @Composable {
      TextButton(onClick = { vm.branchFrom = rec.id to step }, enabled = canBranch, modifier = Modifier.testTag("replay_branch")) {
        Text(stringResource(R.string.branch_title))
      }
    }
    val topButtons = @Composable {
      // (The narrow column beside the table has no room for it here: it has it under.)
      if (!side || inlinePanel) branchButton()
      IconToggleButton(rec.fav, { vm.records.setFav(rec.id, it) }) {
        Icon(if (rec.fav) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.favorite))
      }
      IconToggleButton(checked = showAll, onCheckedChange = { showAll = it }, modifier = Modifier.testTag("replay_all"), colors = toggleColors) {
        Icon(Icons.Filled.Visibility, stringResource(R.string.replay_show_all))
      }
      if (!inlinePanel) IconToggleButton(checked = panelOpen, onCheckedChange = { panelOpen = it }, modifier = Modifier.testTag("replay_panel"),
        colors = toggleColors) {
        Icon(Icons.Filled.Assessment, stringResource(R.string.review_panel))
      }
    }

    // The hand in short, and the graph.
    val summary = @Composable {
      val small = MaterialTheme.typography.bodySmall
      val outcomeColors = LocalOutcome.current
      if (r != null && hasNet) {
        val item = @Composable { label: Int, value: String, bad: Boolean ->
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(label), style = small, color = cs.onSurfaceVariant, maxLines = 1)
            Text(value, style = small, maxLines = 1, fontWeight = if (bad) FontWeight.Bold else null, color = if (bad) outcomeColors.bad else Color.Unspecified)
          }
        }
        val first = @Composable { item(R.string.review_first, pts(r.firstValue), false) }
        val lost = @Composable { item(R.string.review_decisions, pts(-(r.loss ?: 0.0)), (r.loss ?: 0.0) >= EQUAL) }
        val luck = @Composable { item(R.string.review_luck, pts(r.luck ?: 0.0), false) }
        val count = @Composable {
          // (Several kinds: a line may end after a "・", never inside "大失誤 1".)
          val parts = mistakesParts(res, level, grades)
          FlowRow(Modifier.testTag("replay_mistakes")) {
            parts.forEachIndexed { i, p ->
              Text(if (i < parts.size - 1) p + Words.sep else p, style = small, fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (mistakes > 0) outcomeColors.bad else outcomeColors.good)
            }
          }
        }
        val graph = @Composable { mod: Modifier -> ValueGraph(points, stops, step, n, if (side) 44.dp else 38.dp, mod) { go(it) } }
        if (inlinePanel) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { first(); lost(); luck(); count() }
          graph(Modifier)
        } else {
          val grid = @Composable {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Column { first(); luck() }
              Column { lost(); count() }
            }
          }
          if (side) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { grid(); graph(Modifier) }
          // (The graph keeps two fifths of the row at least: many kinds of mistakes take more lines, not its room.)
          else BoxWithConstraints {
            val most = maxWidth * 0.6f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Box(Modifier.widthIn(max = most)) { grid() }
              graph(Modifier.weight(1f))
            }
          }
        }
      } else if (revError != null) Text(stringResource(R.string.review_failed, revError ?: ""), style = small, color = outcomeColors.bad)
      else Text(stringResource(R.string.review_loading), style = small, color = cs.onSurfaceVariant)
    }

    // The step, the choice made there, and what the review says of it.
    // Under the table the words keep one height, the room of four lines (at the text's size; scrolled in the rare step
    // with more), so that the table (and the step buttons under them) stays as it is from step to step.
    val fontScale = LocalDensity.current.fontScale
    // The buttons (解說, 看結果) at the top right, over the room of the two lines beside them, which are narrower by
    // their width: no line made taller by them.
    var buttonsW by remember { mutableIntStateOf(0) }
    val beside = Modifier.padding(end = with(LocalDensity.current) { if (buttonsW > 0) buttonsW.toDp() + 8.dp else 0.dp })
    val info = @Composable {
      val infoScroll = rememberScrollState()
      // (Under the table the room is fixed: more words below fade at its foot.)
      Box(if (side) Modifier.fillMaxWidth().heightIn(min = 40.dp)
        else Modifier.fillMaxWidth().height((104f * fontScale).dp.coerceIn(98.dp, 148.dp)).fadeFoot(infoScroll).verticalScroll(infoScroll)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(beside, verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
              Text(if (step >= n) stringResource(R.string.replay_over) else stringResource(R.string.replay_step, stepNumber, stopList.size),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("replay_where"))
              // The player's expected result at this step, as the review knows it: at a decision its best action's,
              // after it the action played's (until the next decision), before the first the first decision's; at the
              // end the result.
              if (r != null && hasNet) {
                val before = r.decisions.lastOrNull { it.step < step }
                val value = when {
                  step >= n -> r.result
                  mine != null -> mine.analysis.value ?: 0.0
                  before == null -> r.firstValue ?: 0.0
                  else -> before.analysis.actions.getOrNull(before.played)?.q ?: before.analysis.value ?: 0.0
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("replay_now")) {
                  Text(stringResource(if (step >= n) R.string.review_result else R.string.review_now), style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant)
                  Text(pts(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
              }
              if (deciding != null) Text(
                (if (deciding.first == rec.me) stringResource(R.string.replay_you_chose, actionText(res, deciding.second))
                else stringResource(R.string.replay_seat_chose, names[deciding.first], actionText(res, deciding.second))) +
                  if (decidingNote.isNotEmpty()) Words.paren(decidingNote) else "",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.testTag("replay_choice"),
              ) else if (step >= n && outcome.isNotEmpty()) Text(outcome, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                modifier = Modifier.testTag("replay_choice"))
              // The hand's rules, at its start (the hand may be old, or played under other rules than now).
              if (step == 0) Text(stringResource(R.string.rules_title) + stringResource(R.string.colon) + rulesOf(rec.game.rules),
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.testTag("replay_rules"))
            }
          }
          val best = mine?.analysis?.best
          if (mine != null && key != null && best != null && mine.played >= 0) {
            FlowRow(beside, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
              itemVerticalAlignment = Alignment.CenterVertically) {
              GradeBadge(if (mine.played == best || key.loss < EQUAL) 0 else key.grade, verdictWord(mine.played == best, key.loss, key.grade),
                Modifier.testTag("replay_grade"))
              if (key.loss >= EQUAL) {
                Text(stringResource(R.string.review_loss, pts(key.loss, false)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.review_best_was, actionText(res, mine.analysis.actions[best].action)), style = MaterialTheme.typography.bodyMedium)
              }
            }
            val reason = if (key.loss >= EQUAL) reasonOf(res, mine.analysis, mine.played) else ""
            if (reason.isNotEmpty()) Text(reason, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            val note = key.notable?.let { (if (key.found) stringResource(R.string.note_found) + Words.colon else "") + notableText(res, mine.analysis, it) }
              ?: key.praise?.let { stringResource(R.string.note_found) + Words.colon + it }
            if (note != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("replay_notable")) {
              Text("★", color = Marks.found, style = MaterialTheme.typography.bodySmall)
              Text(note, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
          }
        }
        val toResult = step >= last && n > 0 && !(step >= n && showResult)
        if (explanation != null || toResult) Row(Modifier.align(Alignment.TopEnd).onSizeChanged { buttonsW = it.width },
          horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          if (explanation != null) FilledTonalButton(
            onClick = { explaining = true }, modifier = Modifier.testTag("replay_explain").heightIn(min = 34.dp), contentPadding = PaddingValues(horizontal = 12.dp),
          ) { Text(stringResource(R.string.explain_button)) }
          if (toResult) FilledTonalButton(
            onClick = ::openResult, modifier = Modifier.testTag("replay_result").heightIn(min = 34.dp), contentPadding = PaddingValues(horizontal = 12.dp),
          ) { Text(stringResource(R.string.replay_result)) }
        }
        else SideEffect { buttonsW = 0 }
      }
    }

    // The steps: the start, the decisions worth a look, the player's decisions, one step, the last move.
    val navButton = @Composable { tag: String, label: Int, enabled: Boolean, filled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit ->
      val mod = Modifier.size(42.dp).testTag(tag)
      val name = stringResource(label)
      Box(Modifier.clip(CircleShape)) {
        if (filled) FilledIconButton(onClick = onClick, enabled = enabled, modifier = mod) { content() }
        else FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = mod) { content() }
      }
      // The name is the icon's description.
      name.length
    }
    val keyMark = @Composable { Text("!", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
    val navButtons: List<@Composable () -> Unit> = listOf(
      { navButton("replay_start", R.string.replay_start, step > 0, false, { go(0) }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.replay_start)) } },
      { navButton("replay_prev_key", R.string.review_prev_key, prevKeyStep >= 0, false, { go(prevKeyStep) }) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.review_prev_key), Modifier.size(18.dp)); keyMark() }
      } },
      { navButton("replay_prev_mine", R.string.replay_prev_decision, step > 0, false, { prevMine() }) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.replay_prev_decision)) } },
      { navButton("replay_prev", R.string.replay_prev, step > 0, false, { prev() }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.replay_prev)) } },
      { navButton("replay_next", R.string.replay_next, step < n, true, { next() }) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.replay_next)) } },
      { navButton("replay_next_mine", R.string.replay_next_decision, step < n, false, { nextMine() }) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.replay_next_decision)) } },
      { navButton("replay_next_key", R.string.review_next_key, nextKeyStep >= 0, false, { go(nextKeyStep) }) {
        Row(verticalAlignment = Alignment.CenterVertically) { keyMark(); Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.review_next_key), Modifier.size(18.dp)) }
      } },
      { navButton("replay_last", R.string.replay_last, step < n, false, { toEnd() }) { Icon(Icons.Filled.SkipNext, stringResource(R.string.replay_last)) } },
    )
    // One row, back at the left and on at the right; in the narrow column beside the table two such rows at its foot,
    // the big jumps (the start, the player's decisions, the last move) over the small ones (the decisions worth a
    // look, one step: the step on, the one most pressed, lowest).
    val buttons = @Composable {
      if (side && !inlinePanel) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in listOf(listOf(0, 2, 5, 7), listOf(1, 3, 4, 6))) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          for (b in row) navButtons[b]()
        }
      } else Row(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        for (b in navButtons) b()
      }
    }

    // A decision's list, with the way to the analysis screen.
    val panel = @Composable {
      if (focus != null) {
        DecisionPanel(focus.analysis, played = focus.played, unit = unit, w = if (inlinePanel) 34f else maxOf(30f, hw * 0.8f),
          names = names, truth = focus.truth, showTruth = showAll, picked = pickedIndex, brief = focus.seat == rec.me && mine != null,
          who = if (focus.seat == rec.me) "" else names[focus.seat])
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
          if (explanation != null && focus.seat == rec.me) OutlinedButton(onClick = { explaining = true }, modifier = Modifier.testTag("panel_explain")) {
            Text(stringResource(R.string.explain_button))
          }
          FilledTonalButton(onClick = ::toAnalysis, modifier = Modifier.testTag("replay_to_analysis")) {
            Icon(Icons.Filled.Assessment, null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.review_to_analysis))
          }
        }
      } else if (deciding != null) PanelSkeleton(w = if (inlinePanel) 34f else maxOf(30f, hw * 0.8f))
    }

    // Over the hand on the table: the options that are not discards, and the figures of the tile tapped (with the
    // list beside the table its row there is lit instead, and comes into sight).
    val highlight = LocalTable.current.highlight
    val detail = pickedIndex >= 0 && !inlinePanel
    // The figures of every discard of the decision in fixed columns, so that the line over the hand keeps each figure
    // in its place from one tile to the next (Figures.kt).
    val discardLines = remember(mine, oppNames, res) {
      val a = mine?.analysis ?: return@remember emptyMap()
      val best = a.best?.let { a.actions[it] }
      a.actions.indices.filter { a.actions[it].action.type == "discard" }.associateWith { figureCells(res, a.actions[it], costOf(a, it), oppNames, best) }
    }
    val detailStyle = MaterialTheme.typography.bodySmall.tabular()
    // (Measured in bold: a green or red figure is bold there.)
    val detailWidths = rememberFigureWidths(discardLines.values.toList(), FigureKey.entries, detailStyle.copy(fontWeight = FontWeight.Bold))
    val strip: (@Composable () -> Unit)? = if (mine != null && (options.isNotEmpty() || detail)) ({
      val a = mine.analysis
      if (options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in options) {
          val cost = costOf(a, i)
          val isBest = i == a.best
          val shape = RoundedCornerShape(14.dp)
          Row(
            Modifier.background(if (isBest) Marks.foundFill else Color.Black.copy(alpha = 0.55f), shape)
              .border(2.dp, if (i == mine.played) highlight else Color.Transparent, shape).padding(horizontal = 12.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(actionText(res, a.actions[i].action), color = Color.White, fontWeight = FontWeight.Medium)
            Text(
              if (isBest) stringResource(R.string.grade_best) else if (cost < EQUAL) "≈" else tagCost(cost), fontWeight = FontWeight.Bold,
              color = if (isBest) Color.White else when (gradeOf(cost, unit)) { 1 -> Marks.danger1; 2 -> Marks.danger2; 3 -> Color(0xFFFF8F8F); else -> Color.White },
            )
          }
        }
      }
      if (detail && pickedTile != null) {
        val small = MaterialTheme.typography.bodySmall
        // The words as light as the figures on the black strip (dimmed, they were hard to read there).
        val soft = Color(0xFFD6EBDF)
        FlowRow(
          Modifier.testTag("replay_detail").background(Color(0xDB080E0B), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
          // As wide for every tile (二萬 or 東).
          Text("${stringResource(R.string.act_discard)} ${Tiles.name(pickedTile)}", Modifier.widthIn(min = with(LocalDensity.current) { (small.fontSize * 5.2f).toDp() }),
            style = small, color = Color.White, fontWeight = FontWeight.Bold)
          FigureCells(discardLines[pickedIndex] ?: emptyList(), FigureKey.entries, detailWidths, detailStyle, Color.White, soft,
            Color(0xFFB9F6C2), Color(0xFFFFBDB5), boldTones = true)
        }
      }
    }) else null

    val table = @Composable { mod: Modifier ->
      Box(mod) {
        val v = view
        if (v == null) TableSkeleton()
        else TableView(
          v, rec.me, names, revealed = if (showAll || step == n) v.hands else null, interactive = false, maxHand = 56f, review = true,
          marks = marks, notes = notes, strip = strip, onPick = { picked = it },
          // (The tags' room kept at every step, not only at the player's decisions: the table stays put.)
          tagRoom = hasNet,
          overlay = {
            val f = final
            // (After a win's moment, when it plays: CallFx.)
            if (step == n && f?.result != null && showResult && !fxOn) Sheet {
              HandResultCard(f, names, rec.me, resultTileW(side, w), rev = rec.rev, level = level, rules = rulesOf(rec.game.rules)) {
                TextButton(onClick = { showResult = false }) { Text(stringResource(R.string.result_hands)) }
                if (stops.isNotEmpty()) FilledTonalButton(onClick = { go(stops.first().step) }) { Text(stringResource(R.string.review_next_key)) }
                // After the result: the next hand. Practice's, when this is the hand just played (the review
                // closes); else the game's next in the records.
                val practice = vm.practice
                val practiceNext = practice.stage == Practice.Stage.RESULT && practice.lastRecord == rec.id
                val nextRecord = rec.group?.let { g -> vm.records.ofGroup(g).firstOrNull { (it.index ?: 0) == (rec.index ?: 0) + 1 } }
                if (practiceNext || nextRecord != null) Button(
                  onClick = {
                    if (practiceNext) {
                      practice.nextHand()
                      onBack()
                    } else if (nextRecord != null) {
                      vm.reviewId = nextRecord.id
                      vm.reviewStep = 0
                    }
                  },
                  modifier = Modifier.testTag("replay_next_hand"),
                ) { Text(stringResource(if (practiceNext && practice.meta?.over == true) R.string.see_totals else R.string.next_hand)) }
              }
            }
          },
        )
      }
    }

    // The deciding seat's own tiles, over the list (a phone).
    val seatHand = @Composable {
      val sv = seatView
      if (sv != null && deciding != null) {
        val pos = sv.position
        val all = Tiles.parse(pos.hand)
        val drawn = if (sv.phase == "self") pos.drawn else null
        val tiles = Tiles.sort(if (drawn != null) Tiles.without(all, listOf(drawn)) else all)
        val p = pos.players[deciding.first]
        Text(
          stringResource(R.string.round_wind, Tiles.wind(pos.setup.round)) + Words.sep + stringResource(R.string.wall_left, pos.wallLeft) + Words.sep +
            (if (deciding.first == rec.me) stringResource(R.string.analysis_your_turn) else stringResource(R.string.analysis_turn_of, names[deciding.first])),
          style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
        var selected by remember(stepKey) { mutableStateOf<Int?>(null) }
        MyHand(tiles, drawn, hw, HAND_ROW, emptySet(), {}, selected, { i -> selected = i; picked = i?.let { tiles.getOrNull(it) ?: drawn } },
          marks = focus?.let { tileMarks(res, it.analysis, it.played, unit) } ?: emptyMap(),
          tagH = 17f)
        val claim = pos.claim
        if (claim?.tile != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("${names[claim.seat]} ${stringResource(R.string.act_discard)}", style = MaterialTheme.typography.bodyMedium)
          TileView(claim.tile, hw.dp, mark = true)
        }
        val flowers = flowersOf(p)
        if (p.melds.isNotEmpty() || flowers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Bottom) {
          for (m in p.melds) Row { for (t in meldTiles(m)) TileView(t, (hw * 0.75f).dp) }
          for (f in flowers) TileView(f, (hw * 0.6f).dp)
        }
      }
    }

    // Sideways the screen reaches the window's sides (MainActivity): the felt and the controls' column lie under a
    // camera's cutout, what is on them keeps clear of it.
    val startInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
    val endInset = WindowInsets.safeDrawing.only(WindowInsetsSides.End)
    if (side) Row(Modifier.fillMaxSize()) {
      if (listView) Row(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(startInset).padding(start = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
          seatHand()
        }
        Column(Modifier.weight(0.54f).fillMaxHeight().verticalScroll(listScroll).padding(top = 8.dp, bottom = 16.dp)) { panel() }
      } else table(Modifier.weight(1f).fillMaxHeight().background(LocalTable.current.felt).windowInsetsPadding(startInset))
      Column(
        Modifier.fillMaxHeight().background(cs.surfaceContainer).windowInsetsPadding(endInset)
          .width(if (inlinePanel) 420.dp else (208f * fontScale).dp.coerceIn(208.dp, 280.dp))
          .padding(horizontal = if (inlinePanel) 12.dp else 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // No title bar beside the table: the way back at the column's top-left, clear of the table.
        val top = @Composable {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("replay_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            topButtons()
          }
        }
        // The step buttons stay in their place whatever the words' height from step to step (a button stays under
        // the finger): the narrow column has them at its foot; with the list in the column they come before the words.
        if (inlinePanel) {
          top()
          summary()
          buttons()
          info()
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(listScroll).padding(top = 4.dp, bottom = 12.dp)) { panel() }
        } else {
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            top()
            branchButton()
            summary()
            info()
          }
          buttons()
        }
      }
    } else Column(Modifier.fillMaxSize()) {
      TitleBar(stringResource(R.string.replay_title), onBack) { topButtons() }
      if (listView) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(listScroll).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
          seatHand()
          panel()
        }
      } else table(Modifier.weight(1f).fillMaxWidth())
      Column(Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        summary()
        info()
        buttons()
      }
    }
  }
  explanation?.takeIf { explaining }?.let { e ->
    ExplainDialog(e, onTips = { explaining = false; tipsOpen = true }) { explaining = false }
  }
  if (tipsOpen) TipsDialog { tipsOpen = false }
}
