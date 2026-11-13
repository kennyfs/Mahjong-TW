package io.github.kennyfs.mahjong

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * The rules as the settings choose them: a tai preset and a flower preset, chosen apart, and values changed on top
 * (docs/rules.md section 11). Names and values of the parameters come from the engine ({"cmd":"rules"}).
 */
@Serializable
data class RuleChoice(val tai: String = "simple1", val flowers: String = "none", val overrides: Map<String, Int> = emptyMap()) {
  val spec: String get() = (listOf(tai, flowers) + overrides.keys.sorted().map { "$it=${overrides[it]}" }).joinToString(",")
}

object Rules {
  val TAI_PRESETS = listOf("simple1", "simple2", "standard", "masters")
  val FLOWER_PRESETS = listOf("none", "see", "seat")
  /** The parameters of the flowers, and of the play (not tai); every other one is a tai item. */
  val FLOWER_PARAMS = listOf("flowers", "flower_tai", "flower_set", "eight_flowers", "seven_rob_one")
  val PLAY_PARAMS = listOf("base", "draw_streak", "multi_ron", "keep", "min_tai", "duting_loose")
  /** Parameters that are 0 or 1. */
  val FLAG_PARAMS = setOf("draw_streak", "multi_ron", "duting_loose", "dasixi_winds", "quanqiu_duting")

  private val cache = HashMap<String, RulesInfo>()

  suspend fun info(spec: String): RulesInfo = cache[spec] ?: Engine.rules(spec).also { cache[spec] = it }

  /** The overrides that differ from the presets' values, with one value changed. */
  suspend fun withValue(c: RuleChoice, name: String, value: Int): RuleChoice {
    val base = info("${c.tai},${c.flowers}").values
    val o = (c.overrides + (name to value)).filter { (k, v) -> base[k] != v }
    return c.copy(overrides = o)
  }

  /** The values of a full spec ("flowers=0,flower_tai=1,..."). */
  fun values(spec: String): Map<String, Int> =
    spec.split(',').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }.toMap()

  fun taiLabel(p: String) = when (p) {
    "simple2" -> R.string.tai_simple2; "standard" -> R.string.tai_standard; "masters" -> R.string.tai_masters; else -> R.string.tai_simple1
  }
  fun flowerLabel(p: String) = when (p) { "see" -> R.string.flowers_see; "seat" -> R.string.flowers_seat; else -> R.string.flowers_none }
}

enum class Length { HAND, ROUND, GAME }
/** The computer players' pace (its times: Timing, res/raw/timing.json); MANUAL: each of their steps waits for the
 *  player (Practice.kt). */
enum class Speed { SLOW, NORMAL, FAST, MANUAL }
enum class ColorStyle { TABLE, MINIMAL }
enum class DarkMode { SYSTEM, LIGHT, DARK }
enum class TextSize(val scale: Float) { SMALL(0.88f), NORMAL(1f), LARGE(1.15f) }

/** The bots: the network (the built-in one, playing the action it values most) and tile efficiency. */
val BOTS = listOf(Engine.NET_BOT to R.string.bot_net, "greedy" to R.string.bot_greedy)

/**
 * What a row of a decision's list shows for an action: its figures as numbers, bars of where its value differs from
 * the best action's, or both. (The hand's tiles always carry each discard's cost and danger.) Until it is chosen: both
 * where there is room for it (a tablet), the bars on a phone.
 */
enum class ListRows { numbers, bars, both }

/** What a review's hand shows on its tiles (the advanced settings): each discard's cost over it and its danger along its
 *  foot, the cost alone, or nothing (the list says it all the same). */
enum class TableMarks { both, loss, none }

/** The table's sounds (Sounds.kt): off, soft, or full, and how loud each is. */
enum class SoundLevel(val gain: Float) { off(0f), soft(0.4f), full(1f) }

/** A chow's, a pung's or a win's moment (the advanced settings): over the whole screen (ui/CallFx.kt), or the words
 *  over the player alone. */
enum class CallFxMode { anim, words }

/**
 * Which losses the review counts as mistakes and stops at: a beginner's only the large ones, an expert's the small
 * ones too (Review.kt). `grade`: the least grade that counts.
 */
enum class Level(val grade: Int) { beginner(3), mid(2), expert(1) }

@Serializable
private data class StoredSettings(
  val rules: RuleChoice = RuleChoice(), val length: Length = Length.GAME, val bot: String = Engine.NET_BOT, val speed: Speed = Speed.NORMAL,
  val colors: ColorStyle = ColorStyle.TABLE, val dark: DarkMode = DarkMode.SYSTEM, val text: TextSize = TextSize.NORMAL,
  val name: String = "", val server: String = "", val listRows: ListRows? = null, val level: Level = Level.mid,
  val tableMarks: TableMarks = TableMarks.both,
  val callFx: CallFxMode = CallFxMode.anim,
  /** 聽牌提示: marks on the discards that leave the hand ready (off unless asked for). */
  val readyHint: Boolean = false,
  val sound: SoundLevel = SoundLevel.full,
  /** 1: saved by a version with the network (before it, the bot could only be tile efficiency). */
  val version: Int = 0,
)

/** The settings, kept in the app's preferences. The web page's lib/settings.svelte.ts. */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  private val stored: StoredSettings = runCatching { EngineJson.decodeFromString<StoredSettings>(prefs.getString("settings", null) ?: "{}") }.getOrDefault(StoredSettings())

  var rules by mutableStateOf(stored.rules)
  var length by mutableStateOf(stored.length)
  var bot by mutableStateOf(if (stored.version >= 1 && BOTS.any { it.first == stored.bot }) stored.bot else Engine.NET_BOT)
  var speed by mutableStateOf(stored.speed)
  var colors by mutableStateOf(stored.colors)
  var dark by mutableStateOf(stored.dark)
  var text by mutableStateOf(stored.text)
  var name by mutableStateOf(stored.name)
  var server by mutableStateOf(stored.server)
  var listRows by mutableStateOf(stored.listRows ?: if (context.resources.configuration.smallestScreenWidthDp >= 600) ListRows.both else ListRows.bars)
  var level by mutableStateOf(stored.level)
  var tableMarks by mutableStateOf(stored.tableMarks)
  var callFx by mutableStateOf(stored.callFx)
  var readyHint by mutableStateOf(stored.readyHint)
  var sound by mutableStateOf(stored.sound)

  val spec: String get() = rules.spec

  /** The welcome card was shown (and answered, or put away): it comes once. */
  var welcomeSeen: Boolean
    get() = prefs.getBoolean("welcome.seen", false)
    set(v) = prefs.edit().putBoolean("welcome.seen", v).apply()

  fun persist() {
    val s = StoredSettings(rules, length, bot, speed, colors, dark, text, name, server, listRows, level, tableMarks, callFx, readyHint, sound, version = 1)
    prefs.edit().putString("settings", EngineJson.encodeToString(s)).apply()
  }
}
