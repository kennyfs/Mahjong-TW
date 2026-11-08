package io.github.kennyfs.mahjong.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.Analysis
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.EditMode
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Section
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.meldOf
import kotlinx.coroutines.launch

/**
 * 分析: a position set up by hand (the player's hand and the tile drawn, or another player's discard to decide on;
 * every seat's melds, flowers and discards; the dealer, the round wind, the wall), then the engine's figures for each
 * legal action. A section is chosen by a tap, and the palette's tiles go into it; a tile tapped in a section leaves.
 * The web page's Analyze.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyzeScreen(vm: AppModel) {
  val ed = vm.editor
  val scope = rememberCoroutineScope()
  var result by remember { mutableStateOf<Analysis?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var running by remember { mutableStateOf(false) }
  var meldTile by remember { mutableStateOf<String?>(null) }
  // An upright phone: the figures on a page of their own, over the editor (Back returns to it).
  var showResults by remember { mutableStateOf(false) }
  BackHandler(showResults) { showResults = false }
  val seatName = listOf(stringResource(R.string.analysis_seat_you), stringResource(R.string.rel_next), stringResource(R.string.rel_opposite),
    stringResource(R.string.rel_prev))
  val seatShort = listOf(stringResource(R.string.you), stringResource(R.string.rel_next), stringResource(R.string.rel_opposite),
    stringResource(R.string.rel_prev))
  val missing = ed.missing()?.let { stringResource(R.string.analysis_need_tiles, it.first, it.second) }
    ?: if (ed.mode == EditMode.SELF && ed.drawn == null) stringResource(R.string.analysis_drawn)
    else if (ed.mode == EditMode.CLAIM && ed.claimTile == null) stringResource(R.string.analysis_claim_tile) else null
  fun pick(t: String) {
    if (ed.section.kind == Section.Kind.MELDS) {
      meldTile = t
      return
    }
    ed.add(t)
    result = null
  }
  fun run() {
    if (missing != null) return
    running = true
    error = null
    scope.launch {
      try {
        result = Engine.analyzePosition(vm.settings.spec, ed.position())
        showResults = true
      } catch (e: Exception) {
        result = null
        error = e.message ?: ""
      } finally {
        running = false
      }
    }
  }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth > maxHeight
    val w = maxWidth.value
    val pw = if (wide) ((w * 0.42f - 24) / 9.4f).coerceIn(34f, 48f) else minOf(48f, (w - 24) / 9.4f)
    val sw = maxOf(26f, pw * 0.8f)

    @Composable
    fun tiles(list: List<String>, s: Section, tw: Float) {
      FlowRow(Modifier.fillMaxWidth().heightIn(min = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.forEachIndexed { i, t ->
          Box(Modifier.clickable { ed.section = s; ed.removeAt(s, i); result = null }) { TileView(t, tw.dp) }
        }
      }
    }

    @Composable
    fun section(s: Section, title: String, body: @Composable () -> Unit) {
      val on = ed.section == s
      val cs = MaterialTheme.colorScheme
      Surface(
        onClick = { ed.section = s }, shape = RoundedCornerShape(12.dp), color = if (on) cs.secondaryContainer else cs.surfaceContainerLow,
        border = BorderStroke(2.dp, if (on) cs.primary else Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
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

    val runRow = @Composable {
      Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        if (missing != null) Text(missing, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = ::run, enabled = missing == null && !running, modifier = Modifier.testTag("analyze_run")) {
          Text(stringResource(R.string.analysis_run))
        }
      }
      error?.let { Text(stringResource(R.string.analysis_error, it), Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
    }

    val palette = @Composable {
      Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.analysis_palette_hint), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        val list = if (ed.section.kind == Section.Kind.FLOWERS) Tiles.FLOWERS else Tiles.KINDS
        for (row in list.chunked(9)) Row {
          for (t in row) {
            val left = ed.left(t)
            Box(Modifier.let { if (left > 0) it.clickable { pick(t) } else it }) { TileView(t, pw.dp, dim = left <= 0) }
          }
        }
      }
    }

    val edit = @Composable { mod: Modifier ->
      Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
        Text(stringResource(R.string.analysis_situation), style = MaterialTheme.typography.labelLarge)
        ToggleGroup(
          listOf(EditMode.SELF to stringResource(R.string.analysis_my_turn), EditMode.CLAIM to stringResource(R.string.analysis_on_discard)), ed.mode,
          { ed.mode = it; result = null; ed.section = Section(if (it == EditMode.SELF) Section.Kind.DRAWN else Section.Kind.CLAIM) },
          Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Text(stringResource(R.string.analysis_dealer), style = MaterialTheme.typography.labelLarge)
        ToggleGroup((0..3).map { it to seatShort[it] }, ed.dealer, { ed.dealer = it; result = null }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Text(stringResource(R.string.analysis_round), style = MaterialTheme.typography.labelLarge)
        ToggleGroup((0..3).map { it to Tiles.wind(it) }, ed.round, { ed.round = it; result = null }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
        if (ed.mode == EditMode.CLAIM) {
          Text(stringResource(R.string.analysis_claim_from), style = MaterialTheme.typography.labelLarge)
          ToggleGroup((1..3).map { it to seatShort[it] }, ed.claimSeat, { ed.claimSeat = it; result = null }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.analysis_wall_left) + " " + ed.wallLeft, style = MaterialTheme.typography.labelLarge)
          Slider(ed.wallLeft.toFloat(), { ed.wallLeft = it.toInt(); result = null }, Modifier.weight(1f), valueRange = 0f..100f)
        }
        section(Section(Section.Kind.HAND), stringResource(R.string.analysis_hand)) {
          tiles(ed.hand, Section(Section.Kind.HAND), sw)
          Text(stringResource(R.string.analysis_need_tiles, ed.handSize, ed.hand.size), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ed.mode == EditMode.SELF) section(Section(Section.Kind.DRAWN), stringResource(R.string.analysis_drawn)) {
          tiles(listOfNotNull(ed.drawn), Section(Section.Kind.DRAWN), sw)
        } else section(Section(Section.Kind.CLAIM), stringResource(R.string.analysis_claim_tile)) {
          tiles(listOfNotNull(ed.claimTile), Section(Section.Kind.CLAIM), sw)
        }
        for (seat in 0..3) {
          Text(seatName[seat] + if (ed.dealer == seat) "・" + stringResource(R.string.dealer_mark) else "", Modifier.padding(top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
          section(Section(Section.Kind.MELDS, seat), stringResource(R.string.analysis_melds)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              ed.melds[seat].forEachIndexed { i, m ->
                Row(Modifier.clickable { ed.section = Section(Section.Kind.MELDS, seat); ed.removeAt(Section(Section.Kind.MELDS, seat), i); result = null }) {
                  meldOf(m).forEachIndexed { k, t -> TileView(if (m.type == "ankan" && (k == 0 || k == 3)) null else t, (sw * 0.85f).dp) }
                }
              }
            }
          }
          if (vm.settings.rules.flowers != "none" || ed.flowers[seat].isNotEmpty())
            section(Section(Section.Kind.FLOWERS, seat), stringResource(R.string.analysis_flowers)) {
              tiles(ed.flowers[seat], Section(Section.Kind.FLOWERS, seat), sw * 0.85f)
            }
          section(Section(Section.Kind.DISCARDS, seat), stringResource(R.string.analysis_discards)) {
            tiles(ed.discards[seat], Section(Section.Kind.DISCARDS, seat), sw * 0.85f)
          }
        }
      }
    }

    val r = result
    // Sideways: the figures come under the palette in its column, which turns to them once they are in.
    val side = rememberScrollState()
    var resultsTop by remember { mutableIntStateOf(0) }
    LaunchedEffect(r, wide) {
      if (r != null && wide) {
        withFrameNanos { }
        side.animateScrollTo(resultsTop)
      }
    }
    if (!wide && showResults && r != null) Column(Modifier.fillMaxSize()) {
      TitleBar(stringResource(R.string.analysis_results), { showResults = false })
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
          itemVerticalAlignment = Alignment.Bottom) {
          for (t in ed.hand) TileView(t, sw.dp)
          (if (ed.mode == EditMode.SELF) ed.drawn else ed.claimTile)?.let {
            Box(Modifier.padding(start = 10.dp)) { TileView(it, sw.dp, glow = ed.mode == EditMode.SELF, mark = ed.mode == EditMode.CLAIM) }
          }
        }
        AnalysisList(r, null, sw, Modifier.padding(horizontal = 12.dp))
      }
    } else Column(Modifier.fillMaxSize()) {
      TitleBar(stringResource(R.string.analyze_title)) {
        TextButton(onClick = { ed.random(); result = null }, Modifier.testTag("analyze_random")) { Text(stringResource(R.string.analysis_random)) }
        TextButton(onClick = { ed.clear(); result = null }) { Text(stringResource(R.string.analysis_clear)) }
      }
      if (wide) Row(Modifier.weight(1f)) {
        edit(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.width(minOf(w * 0.46f, 520f).dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer)
          .verticalScroll(side).padding(bottom = 16.dp)) {
          palette()
          runRow()
          r?.let {
            AnalysisList(it, null, sw, Modifier.padding(horizontal = 12.dp).onGloballyPositioned { c -> resultsTop = c.positionInParent().y.toInt() })
          }
        }
      } else {
        edit(Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
          runRow()
          palette()
        }
      }
    }
  }
  meldTile?.let { t ->
    val s = ed.section
    fun add(type: String) {
      if (s.kind == Section.Kind.MELDS) ed.addMeld(s.seat, type, t)
      meldTile = null
      result = null
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
