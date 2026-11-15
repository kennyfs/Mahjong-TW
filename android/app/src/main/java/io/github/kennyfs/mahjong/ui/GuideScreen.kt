package io.github.kennyfs.mahjong.ui

import io.github.kennyfs.mahjong.Words
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.EngineException
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.RULE_HELP
import io.github.kennyfs.mahjong.RuleChoice
import io.github.kennyfs.mahjong.Rules
import io.github.kennyfs.mahjong.RulesInfo
import io.github.kennyfs.mahjong.Tiles

private const val PAGES = 8

/** A winning hand's groups: five sets and a pair (the tiles, the name of the kind). */
private val GOAL = listOf("234m" to R.string.guide_chow, "678p" to R.string.guide_chow, "345s" to R.string.guide_chow,
  "111z" to R.string.guide_pung, "777z" to R.string.guide_pung, "99p" to R.string.guide_pair)
private val SUITS = listOf("123456789m" to R.string.guide_row_m, "123456789p" to R.string.guide_row_p, "123456789s" to R.string.guide_row_s,
  "1234567z" to R.string.guide_row_z)
private const val FLOWER_TILES = "12345678f"
/** A call: what it is, the tiles in hand, and the one taken. */
private val CALLS = listOf(Triple(R.string.guide_chi, "35m", "4m"), Triple(R.string.guide_pon, "77z", "7z"), Triple(R.string.guide_kan, "111p", "1p"))
/** A wait: its name, the tiles waiting, and the tiles waited for. */
private val WAITS = listOf(Triple(R.string.guide_wait_ryanmen, "34m", "25m"), Triple(R.string.guide_wait_penchan, "12s", "3s"),
  Triple(R.string.guide_wait_kanchan, "46p", "5p"), Triple(R.string.guide_wait_tanki, "7z", "7z"), Triple(R.string.guide_wait_shanpon, "11z99p", "1z9p"))

/** A quiz question: a ready hand, the suit of its waits (the nine candidates), the waits (the engine's), and why. */
private class Question(val hand: String, val suit: Char, val waits: List<String>, val why: Int)

private val QUIZ = listOf(
  Question("234567m333789p678s8s", 's', listOf("5s", "8s"), R.string.guide_try_why1),
  Question("2345m678p333456s111z", 'm', listOf("2m", "5m"), R.string.guide_try_why2),
  Question("34567m11678p333456s", 'm', listOf("2m", "5m", "8m"), R.string.guide_try_why3),
)

/** The tai items not listed (the dealer's are said in words). */
private val NOT_LISTED = listOf("dealer", "streak")

/**
 * How to play (規則說明): pages for someone new to Taiwanese mahjong. First the rules to explain (a tai preset and a
 * flower preset, the settings' at the start); then the goal, the tiles and flowers, a turn and the calls, being
 * ready, tai and payments, a quiz on waits, and on to practice (under the rules explained, if they differ from the
 * settings') or the tour. Where the flowers and the tai come up, the same choice is there again, so a change shows at
 * once. The values (base, dealer, every tai) come from the engine ({"cmd":"rules"}). Opened from the settings, the
 * welcome card and the tour's end, over the screen it was opened from; Back leaves it. The web page's Guide.svelte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideScreen(vm: AppModel, onBack: () -> Unit, onPlay: () -> Unit, onTour: () -> Unit) {
  val settings = vm.settings
  var page by rememberSaveable { mutableIntStateOf(0) }
  var tai by rememberSaveable { mutableStateOf(settings.rules.tai) }
  var flowers by rememberSaveable { mutableStateOf(settings.rules.flowers) }
  var rules by remember { mutableStateOf<RulesInfo?>(null) }
  LaunchedEffect(tai, flowers) {
    try {
      rules = Rules.info("$tai,$flowers")
    } catch (e: EngineException) {
      vm.error = e.message
    }
  }
  val v = rules?.values ?: emptyMap()
  fun n(k: String) = (v[k] ?: 0).toString()
  val taiName = stringResource(Rules.taiLabel(tai))
  val rulesName = rulesChip(tai, flowers)
  val scroll = rememberScrollState()
  LaunchedEffect(page) { scroll.scrollTo(0) }
  val cs = MaterialTheme.colorScheme
  val table = LocalTable.current

  // The quiz.
  var q by rememberSaveable { mutableIntStateOf(0) }
  var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
  var checked by rememberSaveable { mutableStateOf(false) }
  // The tai item whose explanation is open.
  var helped by rememberSaveable { mutableStateOf<String?>(null) }

  Column(Modifier.fillMaxSize()) {
    TitleBar(stringResource(R.string.guide_title), onBack) {
      Text(stringResource(R.string.guide_page, page + 1, PAGES), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurfaceVariant)
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      // The tiles shown: nine in a row with their name fit across a phone; the quiz's candidates big enough to tap.
      val small = ((minOf(maxWidth, 760.dp).value - 120f) / 9f).coerceIn(24f, 36f).dp
      val cols = if (maxWidth < 560.dp) 5 else 9
      val pickW = ((minOf(maxWidth, 760.dp).value - 32f - 8f * (cols - 1)) / cols - 10f).coerceIn(40f, 48f).dp

      @Composable
      fun tiles(s: String, w: Dp, glow: Boolean = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
          for (t in Tiles.parse(s)) TileView(t, w, glow = glow)
        }
      }

      /** Tiles on the felt; `scroll`: sideways, if a large text leaves them no room. */
      @Composable
      fun felt(scroll: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
        Box(Modifier.background(table.felt, RoundedCornerShape(14.dp))) {
          Column(Modifier.then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
      }

      @Composable
      fun switch(onTai: Boolean) {
        Column(Modifier.fillMaxWidth().background(cs.surfaceContainer, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(if (onTai) R.string.guide_switch_tai else R.string.guide_switch_flowers), style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant)
          if (onTai) ToggleGroup(Rules.TAI_PRESETS.map { it to stringResource(Rules.taiLabel(it)) }, tai, { tai = it }, Modifier.fillMaxWidth())
          else ToggleGroup(Rules.FLOWER_PRESETS.map { it to stringResource(Rules.flowerLabel(it)) }, flowers, { flowers = it }, Modifier.fillMaxWidth())
        }
      }

      @Composable
      fun title(id: Int) = Text(stringResource(id), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineSmall)

      @Composable
      fun para(text: String, large: Boolean = true, color: Color = Color.Unspecified, modifier: Modifier = Modifier) =
        Text(text, modifier, style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium, color = color)

      Column(
        Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        when (page) {
          0 -> {
            title(R.string.guide_rules_title)
            para(stringResource(R.string.guide_rules_text))
            Text(stringResource(R.string.rules_tai), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, color = cs.primary)
            ToggleGroup(Rules.TAI_PRESETS.map { it to stringResource(Rules.taiLabel(it)) }, tai, { tai = it }, Modifier.fillMaxWidth().testTag("guide_tai"))
            para(stringResource(taiDesc(tai)), large = false, color = cs.onSurfaceVariant)
            Text(stringResource(R.string.rules_flowers), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, color = cs.primary)
            ToggleGroup(Rules.FLOWER_PRESETS.map { it to stringResource(Rules.flowerLabel(it)) }, flowers, { flowers = it },
              Modifier.fillMaxWidth().testTag("guide_flowers"))
            para(stringResource(flowerDesc(flowers)), large = false, color = cs.onSurfaceVariant)
            para(stringResource(R.string.guide_rules_note), large = false, color = cs.primary)
          }
          1 -> {
            title(R.string.guide_goal_title)
            para(stringResource(R.string.guide_goal_text))
            para(stringResource(R.string.guide_goal_sets))
            para(stringResource(R.string.guide_goal_example), large = false, color = cs.onSurfaceVariant)
            felt {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((s, label) in GOAL) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  tiles(s, small)
                  Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = table.onFelt)
                }
              }
            }
            para(stringResource(R.string.guide_goal_kong), large = false)
          }
          2 -> {
            title(R.string.guide_tiles_title)
            para(stringResource(R.string.guide_tiles_text))
            felt(scroll = true) {
              val rows = SUITS + if (flowers != "none") listOf(FLOWER_TILES to R.string.guide_row_f) else emptyList()
              for ((s, label) in rows) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(label), Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.labelLarge, color = table.onFelt)
                tiles(s, small)
              }
            }
            switch(onTai = false)
            if (flowers == "none") para(stringResource(R.string.guide_flowers_none))
            else {
              para(stringResource(R.string.guide_flowers_has))
              para(if (flowers == "see") stringResource(R.string.guide_flowers_see, n("flower_tai"))
                else stringResource(R.string.guide_flowers_seat, n("flower_tai"), n("flower_set")))
              if ((v["eight_flowers"] ?: 0) > 0) para(stringResource(R.string.guide_flower_wins, n("eight_flowers")), large = false)
            }
          }
          3 -> {
            title(R.string.guide_turn_title)
            para(stringResource(R.string.guide_turn_text))
            para(stringResource(R.string.guide_turn_claims))
            for ((label, hand, taken) in CALLS) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
              itemVerticalAlignment = Alignment.CenterVertically) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                tiles(hand, small)
                Text("+", Modifier.padding(horizontal = 4.dp), fontWeight = FontWeight.Bold)
                tiles(taken, small, glow = true)
              }
              para(stringResource(label), large = false)
            }
            para(stringResource(R.string.guide_hu), large = false)
            para(stringResource(R.string.guide_turn_show), large = false)
            para(stringResource(R.string.guide_turn_priority), large = false)
            para(stringResource(if ((v["multi_ron"] ?: 0) > 0) R.string.guide_multi_ron_on else R.string.guide_multi_ron_off, taiName), large = false)
            para(stringResource(R.string.guide_turn_end, v["keep"] ?: 16), large = false)
          }
          4 -> {
            title(R.string.guide_ready_title)
            para(stringResource(R.string.guide_ready_text))
            for ((label, hand, waits) in WAITS) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
              itemVerticalAlignment = Alignment.CenterVertically) {
              tiles(hand, small)
              Text(stringResource(R.string.analysis_waits), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
              tiles(waits, small, glow = true)
              para(stringResource(label), large = false, color = cs.onSurfaceVariant)
            }
            para(if ((v["duting"] ?: 0) > 0) stringResource(R.string.guide_duting_on, taiName, n("duting"))
              else stringResource(R.string.guide_duting_off, taiName), large = false)
            para(stringResource(R.string.guide_missed), large = false)
          }
          5 -> {
            title(R.string.guide_tai_title)
            switch(onTai = true)
            para(stringResource(R.string.guide_tai_text, n("base")))
            para(stringResource(R.string.guide_tai_who))
            para(stringResource(R.string.guide_tai_dealer, n("dealer"), n("streak"), ((v["dealer"] ?: 0) + (v["streak"] ?: 0)).toString()))
            para(stringResource(R.string.guide_tai_items), large = false, color = cs.onSurfaceVariant)
            val items = (rules?.items ?: emptyList()).filter {
              (v[it.param] ?: 0) > 0 && it.param !in NOT_LISTED && (flowers != "none" || it.param !in Rules.FLOWER_PARAMS)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainer)) {
              for ((i, it) in items.withIndex()) {
                val open = helped == it.param
                if (i > 0) HorizontalDivider(color = cs.surfaceContainerHighest)
                Row(
                  Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (open) cs.secondaryContainer else Color.Transparent)
                    .clickable { helped = if (open) null else it.param }.padding(horizontal = 14.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                  Text(stringResource(R.string.tai_unit, n(it.param)), style = MaterialTheme.typography.titleSmall)
                }
                val help = RULE_HELP[it.param]
                if (open && help != null) Text(stringResource(help), Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
                  style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
              }
            }
            val each = (v["base"] ?: 0) + (v["zimo"] ?: 0)
            val dealer = v["dealer"] ?: 0
            para(stringResource(R.string.guide_tai_example, n("zimo"), each.toString(), (each + dealer).toString(), (3 * each + dealer).toString()), large = false)
            if ((v["menqing_zimo"] ?: 0) > 0) para(stringResource(R.string.guide_tai_menqing, n("menqing"), n("zimo"), n("menqing_zimo"),
              ((v["menqing"] ?: 0) + (v["zimo"] ?: 0) + (v["menqing_zimo"] ?: 0)).toString()), large = false)
            para(if ((v["min_tai"] ?: 0) > 0) stringResource(R.string.guide_tai_min, n("min_tai")) else stringResource(R.string.guide_tai_min_none), large = false)
            para(stringResource(if ((v["draw_streak"] ?: 0) > 0) R.string.guide_tai_draw_on else R.string.guide_tai_draw_off), large = false)
            para(stringResource(R.string.guide_tai_next), large = false)
          }
          6 -> {
            val quiz = QUIZ[q]
            val right = checked && quiz.waits.size == picked.size && quiz.waits.all { it in picked }
            val good = LocalOutcome.current.good
            val bad = LocalOutcome.current.bad
            title(R.string.guide_try_title)
            para(stringResource(R.string.guide_try_text))
            Text(stringResource(R.string.guide_try_q, q + 1, QUIZ.size), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            felt {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (t in Tiles.parse(quiz.hand)) TileView(t, small)
              }
            }
            // The nine candidates: rows of five on a phone (big enough to tap), one row elsewhere.
            FlowRow(Modifier.widthIn(max = ((pickW.value + 10f) * cols + 8f * (cols - 1)).dp), maxItemsInEachRow = cols,
              horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              for (k in 1..9) {
                val x = "$k${quiz.suit}"
                val on = x in picked
                val waited = x in quiz.waits
                val shape = RoundedCornerShape(10.dp)
                val edge = when {
                  !checked -> if (on) cs.primary else Color.Transparent
                  waited -> good
                  on -> bad
                  else -> Color.Transparent
                }
                val missed = checked && waited && !on
                Box(
                  Modifier.clip(shape).background(if (on) cs.secondaryContainer else Color.Transparent)
                    .then(if (missed) Modifier.drawBehind {
                      val sw = 3.dp.toPx()
                      drawRoundRect(good, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(10.dp.toPx() - sw / 2),
                        style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))))
                    } else Modifier.border(3.dp, edge, shape))
                    .toggleable(on, enabled = !checked, role = Role.Checkbox) { picked = if (on) picked - x else picked + x }
                    .testTag("guide_pick_$x").padding(5.dp),
                ) {
                  TileView(x, pickW, dim = checked && !waited && !on)
                }
              }
            }
            if (checked) {
              Text(if (right) stringResource(R.string.guide_try_right) else stringResource(R.string.guide_try_wrong, quiz.waits.joinToString(Words.list, transform = Tiles::name)),
                style = MaterialTheme.typography.titleMedium, color = if (right) good else bad)
              para(stringResource(quiz.why))
              Text(stringResource(R.string.guide_try_legend), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
              FilledTonalButton(onClick = { q = (q + 1) % QUIZ.size; picked = emptyList(); checked = false }, Modifier.testTag("guide_quiz_next")) {
                Text(stringResource(if (q < QUIZ.size - 1) R.string.guide_try_next else R.string.guide_try_again))
              }
            } else Button(onClick = { checked = true }, enabled = picked.isNotEmpty(), modifier = Modifier.testTag("guide_check")) {
              Text(stringResource(R.string.guide_try_check))
            }
          }
          else -> {
            val same = tai == settings.rules.tai && flowers == settings.rules.flowers && settings.rules.overrides.isEmpty()
            title(R.string.guide_end_title)
            para(stringResource(R.string.guide_end_text))
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              val big = Modifier.fillMaxWidth().heightIn(min = BIG_BUTTON)
              if (!same) Button(onClick = {
                settings.rules = RuleChoice(tai, flowers)
                settings.persist()
                vm.practice.settingsChanged()
                onPlay()
              }, big.testTag("guide_play_rules")) { Text(stringResource(R.string.guide_end_play_rules, rulesName)) }
              if (same) Button(onClick = onPlay, big.testTag("guide_play")) { Text(stringResource(R.string.guide_end_play)) }
              else FilledTonalButton(onClick = onPlay, big.testTag("guide_play")) { Text(stringResource(R.string.guide_end_play)) }
              OutlinedButton(onClick = onTour, big.testTag("guide_tour")) { Text(stringResource(R.string.guide_end_tour)) }
            }
          }
        }
        if (page in 1 until PAGES - 1) Text(stringResource(R.string.guide_rules_now, rulesName), Modifier.padding(top = 6.dp),
          style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
    }
    HorizontalDivider(color = cs.surfaceContainerHighest)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.testTag("guide_prev")) { Text(stringResource(R.string.guide_prev)) }
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(PAGES) { i -> Box(Modifier.size(8.dp).background(if (i == page) cs.primary else cs.surfaceContainerHighest, CircleShape)) }
      }
      if (page < PAGES - 1) Button(onClick = { page++ }, Modifier.testTag("guide_next")) { Text(stringResource(R.string.guide_next)) }
      else Button(onClick = onBack, Modifier.testTag("guide_done")) { Text(stringResource(R.string.guide_done)) }
    }
  }
}
