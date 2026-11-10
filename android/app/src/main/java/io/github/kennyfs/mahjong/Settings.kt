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
enum class Speed(val ms: Long) { SLOW(1100), NORMAL(650), FAST(250) }
enum class ColorStyle { TABLE, MINIMAL }
enum class DarkMode { SYSTEM, LIGHT, DARK }
enum class TextSize(val scale: Float) { SMALL(0.88f), NORMAL(1f), LARGE(1.15f) }

/** The bots: one now (tile efficiency); a network later, as a spec "net:..." (docs/app.md). */
val BOTS = listOf("greedy" to R.string.bot_greedy)

@Serializable
private data class StoredSettings(
  val rules: RuleChoice = RuleChoice(), val length: Length = Length.GAME, val bot: String = "greedy", val speed: Speed = Speed.NORMAL,
  val colors: ColorStyle = ColorStyle.TABLE, val dark: DarkMode = DarkMode.SYSTEM, val text: TextSize = TextSize.NORMAL,
  val name: String = "", val server: String = "",
)

/** The settings, kept in the app's preferences. The web page's lib/settings.svelte.ts. */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  private val stored: StoredSettings = runCatching { EngineJson.decodeFromString<StoredSettings>(prefs.getString("settings", null) ?: "{}") }.getOrDefault(StoredSettings())

  // A tai preset no longer listed (the old "simple") reads as the default.
  var rules by mutableStateOf(stored.rules.let { if (it.tai in Rules.TAI_PRESETS) it else it.copy(tai = RuleChoice().tai) })
  var length by mutableStateOf(stored.length)
  var bot by mutableStateOf(if (BOTS.any { it.first == stored.bot }) stored.bot else "greedy")
  var speed by mutableStateOf(stored.speed)
  var colors by mutableStateOf(stored.colors)
  var dark by mutableStateOf(stored.dark)
  var text by mutableStateOf(stored.text)
  var name by mutableStateOf(stored.name)
  var server by mutableStateOf(stored.server)

  val spec: String get() = rules.spec

  fun persist() {
    val s = StoredSettings(rules, length, bot, speed, colors, dark, text, name, server)
    prefs.edit().putString("settings", EngineJson.encodeToString(s)).apply()
  }
}
