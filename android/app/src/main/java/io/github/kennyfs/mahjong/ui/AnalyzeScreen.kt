package io.github.kennyfs.mahjong.ui

import io.github.kennyfs.mahjong.Words
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Analysis
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.EditMode
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.EngineException
import io.github.kennyfs.mahjong.Page
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Section
import io.github.kennyfs.mahjong.TileMark
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.WhatIf
import io.github.kennyfs.mahjong.meldOf
import io.github.kennyfs.mahjong.tileMarks
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.material3.Button
import io.github.kennyfs.mahjong.GameRecord
import io.github.kennyfs.mahjong.Position
import io.github.kennyfs.mahjong.EngineJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A small field of the position: its name over a chip that opens the choices. */
@Composable
private fun <T> Pick(label: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Column {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box {
      Chip(options.firstOrNull { it.first == selected }?.second ?: "", onClick = { open = true }, menu = true)
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for ((v, name) in options) DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(v) })
      }
    }
  }
}

/** The tiles left in the wall: a chip with the number, which opens a slider (and one tile fewer or more). */
@Composable
private fun WallPick(label: String, value: Int, onChange: (Int) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Column {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box {
      Chip("$value", onClick = { open = true }, menu = true, modifier = Modifier.testTag("analyze_wall"))
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Row(Modifier.width(300.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }) { Icon(Icons.Filled.Remove, "−") }
          Text("$value", Modifier.widthIn(min = 32.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
          Slider(value.toFloat().coerceIn(0f, 120f), { onChange(it.roundToInt()) }, Modifier.weight(1f), valueRange = 0f..120f)
          IconButton(onClick = { onChange((value + 1).coerceAtMost(120)) }) { Icon(Icons.Filled.Add, "+") }
        }
      }
    }
  }
}

/**
 * 分析: a position set up by hand (the player's hand and the tile drawn, or another player's discard to decide on;
 * every seat's melds, flowers and discards; the dealer, the round wind, the wall), or taken from a step of a hand's
 * review. A section is chosen by a tap, and the palette's tiles go into it; a tile tapped in a section leaves.
 *
 * The network's figures for each legal action follow every change at once (so the tile drawn can be changed with a tap
 * to see what it does): on the hand's tiles what each discard costs and its danger, then the list of every action; and
 * on request its tables of what the hand would do after each tile it may draw next and on each discard of the others
 * (Engine.whatIfPosition). A step of a review keeps its hand's history while only the hand's own tiles are changed
 * (PositionEditor.source), so its figures are the review's. The web page's Analyze.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyzeScreen(vm: AppModel) {
  val ed = vm.editor
  val settings = vm.settings
  val res = LocalResources.current
  val cs = MaterialTheme.colorScheme
  var result by remember { mutableStateOf<Analysis?>(null) }
  var tables by remember { mutableStateOf<WhatIf?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var running by remember { mutableStateOf(false) }
  var meldTile by remember { mutableStateOf<String?>(null) }
  var showTables by remember { mutableStateOf(false) }
  /** The melds, flowers and discards of the table (a tall window folds them away while they are empty). */
  var tableOpen by remember { mutableStateOf(false) }
  /** A tall window: the palette can be put away to read the figures (it is, when a whole position comes from a review). */
  var paletteOpen by remember { mutableStateOf(ed.hand.size != ed.handSize || (if (ed.mode == EditMode.SELF) ed.drawn else ed.claimTile) == null) }

  val seatName = listOf(stringResource(R.string.analysis_seat_you), stringResource(R.string.rel_next), stringResource(R.string.rel_opposite),
    stringResource(R.string.rel_prev))
  val seatShort = listOf(stringResource(R.string.you), stringResource(R.string.rel_next), stringResource(R.string.rel_opposite),
    stringResource(R.string.rel_prev))
  val missing = ed.missing()?.let { stringResource(R.string.analysis_need_tiles, it.first, it.second) }
    ?: if (ed.mode == EditMode.SELF && ed.drawn == null) stringResource(R.string.analysis_drawn)
    else if (ed.mode == EditMode.CLAIM && ed.claimTile == null) stringResource(R.string.analysis_claim_tile) else null
  // The rules: the settings', or those of the hand the position came from.
  val spec = ed.rules ?: settings.spec
  val tableCount = (0..3).sumOf { ed.melds[it].size + ed.flowers[it].size + ed.discards[it].size }

  // The figures follow the position: asked again a moment after each change, the last answer kept until the next.
  // A position from a hand's review, with nothing but the hand's own tiles changed, is asked of that hand's game.
  val position = if (missing == null) ed.position() else null
  val source = ed.source
  val asked = remember { intArrayOf(0) }
  LaunchedEffect(spec, position, source, showTables) {
    val mine = ++asked[0]
    if (position == null) {
      result = null
      tables = null
      error = null
      running = false
      return@LaunchedEffect
    }
    running = true
    delay(120)
    try {
      suspend fun figures(net: Boolean) =
        if (source != null) Engine.analyzeGame(source.first, source.second, net, position.hand, position.drawn)
        else Engine.analyzePosition(spec, position, "analysis", net)
      // Without the network (it did not load) the figures are tile efficiency's.
      val a = try {
        figures(true)
      } catch (e: EngineException) {
        try { figures(false) } catch (again: EngineException) { throw e }
      }
      result = a
      error = null
      tables = if (!showTables || !a.net) null
      else if (source != null) Engine.whatIfGame(source.first, source.second, position.hand, position.drawn)
      else Engine.whatIfPosition(spec, position)
    } catch (e: EngineException) {
      result = null
      tables = null
      error = e.message ?: ""
    } finally {
      if (mine == asked[0]) running = false
    }
  }
  val marks: Map<String, TileMark>? = result?.takeIf { it.net }?.let { tileMarks(res, it, -1, null) }

  // 從這裡打: the position played on (BranchScreen, with the analysis shown at each decision), the player at `me` (a
  // review's step: its seat; else 0), the computer players at the others, what the player cannot see dealt at random.
  // A step of a review is played on from its hand's position, with its history and the hand as it stands here; a
  // position of its own from itself. (The branch gives the base its own seed.)
  fun playFrom() {
    val p = position ?: return
    val src = source
    vm.playFrom = if (src != null) GameRecord(src.first.rules, src.first.setup, actions = emptyList(), base = buildJsonObject {
      put("game", EngineJson.encodeToJsonElement(GameRecord.serializer(), src.first))
      put("seat", src.second)
      put("seed", "")
      put("hand", p.hand)
      p.drawn?.let { put("drawn", it) }
    }) to src.second
    else GameRecord(spec, p.setup, actions = emptyList(), base = buildJsonObject {
      put("position", EngineJson.encodeToJsonElement(Position.serializer(), p))
      put("seed", "")
    }) to 0
  }
  vm.playFrom?.let { (g, me) ->
    BranchScreen(vm, g, me, (0..3).map { seatShort[(it - me + 4) % 4] }, title = stringResource(R.string.analysis_play_from),
      backLabel = stringResource(R.string.analysis_play_back)) { vm.playFrom = null }
    return
  }

  fun pick(t: String) {
    if (ed.section.kind == Section.Kind.MELDS) {
      meldTile = t
      return
    }
    ed.add(t)
  }
  fun choose(s: Section) {
    ed.section = s
    paletteOpen = true
  }
  fun remove(s: Section, i: Int) {
    choose(s)
    ed.removeAt(s, i)
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth > maxHeight
    val w = maxWidth.value
    // Tiles: the palette's large enough to tap (a phone's 9 a row), the sections' a little smaller.
    val pw = if (wide) ((w * 0.42f - 24) / 9.4f).coerceIn(34f, 48f) else minOf(48f, (w - 24) / 9.4f)
    val sw = maxOf(26f, pw * 0.8f)
    val tableShown = wide || tableOpen || tableCount > 0

    @Composable
    fun tiles(list: List<String>, s: Section, tw: Float, marked: Boolean = false) {
      val ms = if (marked) marks else null
      val tagged = ms != null && list.any { it in ms }
      FlowRow(Modifier.fillMaxWidth().heightIn(min = 10.dp).padding(top = if (tagged) 15.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(if (tagged) 17.dp else 2.dp)) {
        list.forEachIndexed { i, t ->
          val m = ms?.get(t)
          Box(Modifier.clickable { remove(s, i) }) {
            TileView(t, tw.dp, danger = m?.danger ?: 0)
            if (m != null) TileTag(m, tw, 17f, Modifier.align(Alignment.TopCenter))
          }
        }
      }
    }

    @Composable
    fun section(s: Section, title: String, tag: String, body: @Composable () -> Unit) {
      val on = ed.section == s
      Surface(
        onClick = { choose(s) }, shape = RoundedCornerShape(12.dp), color = if (on) cs.secondaryContainer else cs.surfaceContainerLow,
        border = BorderStroke(2.dp, if (on) cs.primary else Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag(tag),
      ) {
        Column(Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            if (on) Text(stringResource(R.string.analysis_editing), style = MaterialTheme.typography.labelSmall, color = cs.primary)
          }
          body()
        }
      }
    }

    // The figures: what is missing or wrong, then the list of every action and the tables.
    val results = @Composable {
      val note = missing ?: error?.let { stringResource(R.string.analysis_error, it) } ?: if (running && result == null) stringResource(R.string.review_loading) else null
      if (note != null) Text(note, Modifier.padding(vertical = 6.dp).testTag("analyze_note"), style = MaterialTheme.typography.bodyMedium,
        color = if (missing == null && error != null) LocalOutcome.current.bad else cs.onSurfaceVariant)
      result?.let { r ->
        Column(Modifier.fillMaxWidth().alpha(if (running) 0.7f else 1f).testTag("analyze_results"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.analysis_play_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Button(onClick = ::playFrom, enabled = !running && position != null, modifier = Modifier.testTag("analyze_play")) {
              Text(stringResource(R.string.analysis_play_from))
            }
          }
          DecisionPanel(r, w = sw, names = seatShort)
          if (r.net) {
            val t = tables
            if (!showTables) FilledTonalButton(onClick = { showTables = true }, modifier = Modifier.testTag("analyze_whatif")) {
              Text(stringResource(R.string.whatif_show))
            } else if (t != null) WhatIfTables(t, maxOf(26f, sw * 0.82f), { seatShort[it] }, Modifier.testTag("analyze_tables"))
            else Text(stringResource(R.string.review_loading), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
          }
        }
      }
    }

    val rulesChip = @Composable {
      val own = ed.rules != null
      Chip(
        rulesOf(spec), onClick = { if (own) ed.rules = null else vm.open(Page.RULES) }, modifier = Modifier.testTag("analyze_rules"), on = own, menu = !own,
        leading = if (own) ({
          Icon(Icons.Filled.Close, stringResource(R.string.analysis_rules_of_hand) + Words.colon + stringResource(R.string.analysis_rules_reset), Modifier.size(18.dp))
        }) else null,
      )
    }
    val titleActions = @Composable {
      TextButton(onClick = { ed.random() }, Modifier.testTag("analyze_random")) { Text(stringResource(R.string.analysis_random), maxLines = 1) }
      TextButton(onClick = { ed.clear() }, Modifier.testTag("analyze_clear")) { Text(stringResource(R.string.analysis_clear), maxLines = 1) }
    }

    val palette = @Composable {
      Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (wide) 8.dp else 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
          if (wide || paletteOpen) Text(stringResource(R.string.analysis_palette_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant) else Spacer(Modifier.weight(1f))
          if (!wide) TextButton(onClick = { paletteOpen = !paletteOpen }, Modifier.testTag("analyze_palette_toggle")) {
            Text(stringResource(if (paletteOpen) R.string.analysis_palette_hide else R.string.analysis_palette_show))
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.rotate(if (paletteOpen) 0f else 180f))
          }
        }
        if (wide || paletteOpen) {
          val list = if (ed.section.kind == Section.Kind.FLOWERS) Tiles.FLOWERS else Tiles.KINDS
          for (row in list.chunked(9)) Row {
            for (t in row) {
              val left = ed.left(t)
              Box(Modifier.testTag("palette_$t").let { if (left > 0) it.clickable { pick(t) } else it }) { TileView(t, pw.dp, dim = left <= 0) }
            }
          }
          Spacer(Modifier.size(2.dp))
        }
      }
    }

    val edit = @Composable { mod: Modifier ->
      Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
        Text(stringResource(R.string.analysis_situation), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        ToggleGroup(
          listOf(EditMode.SELF to stringResource(R.string.analysis_my_turn), EditMode.CLAIM to stringResource(R.string.analysis_on_discard)), ed.mode,
          { ed.mode = it; ed.section = Section(if (it == EditMode.SELF) Section.Kind.DRAWN else Section.Kind.CLAIM) },
          Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("analyze_mode"),
        )
        FlowRow(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Pick(stringResource(R.string.analysis_dealer), (0..3).map { it to seatShort[it] }, ed.dealer) { ed.dealer = it }
          Pick(stringResource(R.string.analysis_round), (0..3).map { it to Tiles.wind(it) }, ed.round) { ed.round = it }
          WallPick(stringResource(R.string.analysis_wall_left), ed.wallLeft) { ed.wallLeft = it }
          if (ed.mode == EditMode.CLAIM) Pick(stringResource(R.string.analysis_claim_from), (1..3).map { it to seatShort[it] }, ed.claimSeat) { ed.claimSeat = it }
        }
        section(Section(Section.Kind.HAND), stringResource(R.string.analysis_hand), "analyze_hand") {
          tiles(ed.hand, Section(Section.Kind.HAND), sw, marked = ed.mode == EditMode.SELF)
          if (ed.hand.size != ed.handSize) Text(stringResource(R.string.analysis_need_tiles, ed.handSize, ed.hand.size),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (ed.mode == EditMode.SELF) section(Section(Section.Kind.DRAWN), stringResource(R.string.analysis_drawn), "analyze_drawn") {
          tiles(listOfNotNull(ed.drawn), Section(Section.Kind.DRAWN), sw, marked = true)
        } else section(Section(Section.Kind.CLAIM), stringResource(R.string.analysis_claim_tile), "analyze_claim") {
          tiles(listOfNotNull(ed.claimTile), Section(Section.Kind.CLAIM), sw)
        }

        if (!wide) results()

        if (!tableShown) TextButton(onClick = { tableOpen = true }, Modifier.testTag("analyze_table_open")) {
          Text(stringResource(R.string.analysis_melds) + Words.sep + stringResource(R.string.analysis_flowers) + Words.sep + stringResource(R.string.analysis_discards))
        } else for (seat in 0..3) {
          Text(seatName[seat] + if (ed.dealer == seat) Words.sep + stringResource(R.string.dealer_mark) else "", Modifier.padding(top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall, color = cs.primary)
          section(Section(Section.Kind.MELDS, seat), stringResource(R.string.analysis_melds), "analyze_melds_$seat") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              ed.melds[seat].forEachIndexed { i, m ->
                Row(Modifier.clickable { remove(Section(Section.Kind.MELDS, seat), i) }) {
                  // Another player's concealed kong, of a tile that was not seen: four backs.
                  val shown: List<String?> = if (m.tile != null) meldOf(m) else listOf(null, null, null, null)
                  shown.forEachIndexed { k, t -> TileView(if (m.type == "ankan" && (k == 0 || k == 3)) null else t, (sw * 0.85f).dp) }
                }
              }
            }
          }
          if (!spec.contains(",none") || ed.flowers[seat].isNotEmpty())
            section(Section(Section.Kind.FLOWERS, seat), stringResource(R.string.analysis_flowers), "analyze_flowers_$seat") {
              tiles(ed.flowers[seat], Section(Section.Kind.FLOWERS, seat), sw * 0.85f)
            }
          section(Section(Section.Kind.DISCARDS, seat), stringResource(R.string.analysis_discards), "analyze_discards_$seat") {
            tiles(ed.discards[seat], Section(Section.Kind.DISCARDS, seat), sw * 0.85f)
          }
        }
      }
    }

    Column(Modifier.fillMaxSize()) {
      if (wide) TitleBar(stringResource(R.string.analyze_title)) {
        rulesChip()
        titleActions()
      } else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        // The rules' chip gives way (its words are cut short) before the buttons do.
        Text(stringResource(R.string.analyze_title), style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Box(Modifier.weight(1f).padding(start = 10.dp)) { rulesChip() }
        titleActions()
      }
      if (wide) Row(Modifier.weight(1f)) {
        edit(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.width(minOf(w * 0.48f, 560f).dp).fillMaxHeight().background(cs.surfaceContainer).verticalScroll(rememberScrollState())
          .padding(bottom = 16.dp)) {
          palette()
          Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { results() }
        }
      } else {
        edit(Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.fillMaxWidth().background(cs.surfaceContainer)) { palette() }
      }
    }
  }
  meldTile?.let { t ->
    val s = ed.section
    fun add(type: String) {
      if (s.kind == Section.Kind.MELDS) ed.addMeld(s.seat, type, t)
      meldTile = null
    }
    val chiOk = t[1] != 'z' && t[1] != 'f' && t[0] - '0' <= 7
    AlertDialog(
      onDismissRequest = { meldTile = null },
      title = { Text(stringResource(R.string.analysis_meld_pick)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (chiOk) FilledTonalButton(onClick = { add("chi") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.analysis_chi_from, Tiles.name(t))) }
          FilledTonalButton(onClick = { add("pon") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.analysis_pon_of, Tiles.name(t))) }
          FilledTonalButton(onClick = { add("kan") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.analysis_kan_of, Tiles.name(t))) }
          FilledTonalButton(onClick = { add("ankan") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.analysis_ankan_of, Tiles.name(t))) }
        }
      },
      confirmButton = { TextButton(onClick = { meldTile = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}
