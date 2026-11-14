package io.github.kennyfs.mahjong.protocol

/** What a room may be set to, and the time limits every room is held to whatever its settings. */
object Limits {
  /** Seconds per decision a room may choose. */
  val TIME_LIMITS = listOf(5, 10, 15, 20, 30, 60)
  const val MIN_HANDS = 1
  const val MAX_HANDS = 16
  const val TABLE = 4
  /** A disconnected player is waited for this long on each decision; after that the bot decides at once. */
  const val AWAY_GRACE_MS = 30_000L
  /** The result of a hand stays up this long at most before the next one. */
  const val REVIEW_MS = 90_000L
  /** A contest's hand is finished by the bots for those still playing after this long. */
  const val CONTEST_HAND_CAP_MS = 30 * 60_000L
  const val NAME_LENGTH = 16
  /** The largest rules value: far above any real scoring, small enough that no sum overflows anything. */
  const val MAX_RULE_VALUE = 100
}

object Validation {
  val TAI_PRESETS = listOf("simple1", "simple2", "standard", "masters")
  val FLOWER_PRESETS = listOf("none", "see", "seat")
  /** The bots a room may be set to: tile efficiency, and the network (where the hub has it: HubLimits.bots). */
  val BOTS = listOf("greedy", "net")
  private val PARAM = Regex("[a-z_]{1,24}")
  private val TILE = Regex("[1-9][mps]|[1-7]z")
  private val TYPES = setOf("discard", "tsumo", "ankan", "kakan", "pass", "ron", "pon", "kan", "chi")

  /**
   * The engine's spec of a rules choice ("standard,see,base=2"), or null when it names an unknown preset, a name that
   * is not a parameter's form or a value out of range. The engine then checks the names (Hub.create, ChangeSettings).
   */
  fun ruleSpec(choice: RuleChoice): String? {
    if (choice.tai !in TAI_PRESETS || choice.flowers !in FLOWER_PRESETS || choice.overrides.size > 64) return null
    val parts = mutableListOf(choice.tai, choice.flowers)
    for ((name, value) in choice.overrides.toSortedMap()) {
      if (!PARAM.matches(name) || value !in 0..Limits.MAX_RULE_VALUE) return null
      parts += "$name=$value"
    }
    return parts.joinToString(",")
  }

  /** The settings with everything checked but the rules' names, or null. */
  fun settings(s: RoomSettings): RoomSettings? {
    if (s.timeLimit !in Limits.TIME_LIMITS || s.bot !in BOTS) return null
    if (s.mode == Mode.CONTEST && s.hands !in Limits.MIN_HANDS..Limits.MAX_HANDS) return null
    if (ruleSpec(s.rules) == null) return null
    return s
  }

  /** An action as the engine takes it, or null: a known type, tiles by their names, a chow's three. */
  fun action(a: Action): Action? {
    if (a.type !in TYPES) return null
    if (a.tile != null && !TILE.matches(a.tile)) return null
    if (a.type == "chi") {
      val ts = a.tiles ?: return null
      if (ts.size != 3 || ts.any { !TILE.matches(it) }) return null
    } else if (a.tiles != null) return null
    return Action(a.type, a.tile, a.tiles)
  }

  /**
   * A display name: without control or format characters (no bidirectional overrides), spaces collapsed, at most
   * NAME_LENGTH characters; `fallback` when nothing is left.
   */
  fun name(raw: String, fallback: String = "Player"): String {
    val cleaned = StringBuilder()
    var i = 0
    while (i < raw.length && cleaned.codePointCount(0, cleaned.length) < Limits.NAME_LENGTH) {
      val cp = raw.codePointAt(i)
      i += Character.charCount(cp)
      val type = Character.getType(cp)
      val bad = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
        type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
        type == Character.PARAGRAPH_SEPARATOR.toInt()
      if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
        if (cleaned.isNotEmpty() && cleaned.last() != ' ') cleaned.append(' ')
      } else if (!bad) cleaned.appendCodePoint(cp)
    }
    return cleaned.toString().trim().ifEmpty { fallback }
  }
}
