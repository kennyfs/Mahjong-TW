package io.github.kennyfs.mahjong

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

// The engine's JSON forms (cpp/command/guiengine.h, docs/gui-engine.md), as the app uses them: the same as the web
// page's web/src/lib/types.ts.

@Serializable
data class Setup(val dealer: Int = 0, val east: Int = -1, val round: Int = 0, val streak: Int = 0)

/** A hand: rules, setup, the whole wall and every decision ([seat, type, tile?]). Only the authority holds it. */
@Serializable
data class GameRecord(val rules: String, val setup: Setup, val wall: String, val actions: List<JsonArray> = emptyList()) {
  fun cut(n: Int) = copy(actions = actions.take(n))
}

/** A record's decision as an action and its seat. */
fun recordAction(a: JsonArray): Pair<Int, Action> {
  val seat = a[0].jsonPrimitive.int
  val type = a[1].jsonPrimitive.content
  return seat to Action(type, if (a.size > 2) a[2].jsonPrimitive.content else null)
}

@Serializable
data class Action(val type: String, val tile: String? = null, val tiles: List<String>? = null)

@Serializable
data class Meld(val type: String, val tile: String? = null, val tiles: List<String>? = null, val called: String? = null, val from: Int? = null)

@Serializable
data class Discard(val tile: String, val drawn: Boolean = false, val called: Boolean = false)

@Serializable
data class PlayerPos(val melds: List<Meld> = emptyList(), val flowers: String = "", val discards: List<Discard> = emptyList())

@Serializable
data class Claim(val seat: Int, val tile: String? = null, @SerialName("rob_kong") val robKong: Boolean = false)

@Serializable
data class Position(
  val setup: Setup,
  val me: Int = 0,
  val hand: String = "",
  val drawn: String? = null,
  @SerialName("after_call") val afterCall: Boolean = false,
  val claim: Claim? = null,
  val players: List<PlayerPos>,
  // The engine's default (-1: not known), so that a 0 is sent.
  @SerialName("wall_left") val wallLeft: Int = -1,
  /** The seat has passed a win this turn (過水): it may not win on a discard until its next draw. */
  val missed: Boolean = false,
)

@Serializable
data class GameEvent(
  val seat: Int = -1, val type: String, val tile: String? = null, val replacement: Boolean = false, val drawn: Boolean = false,
  val tiles: List<String>? = null,
)

@Serializable
data class TaiItem(val item: String, val name: String, val tai: Int)

@Serializable
data class Winner(val seat: Int, val tai: Int, val items: List<TaiItem> = emptyList())

@Serializable
data class HandResult(
  val exhausted: Boolean = false,
  val winners: List<Winner> = emptyList(),
  val loser: Int = -1,
  @SerialName("win_tile") val winTile: String? = null,
  @SerialName("self_draw") val selfDraw: Boolean = false,
  @SerialName("flower_win") val flowerWin: Boolean = false,
  @SerialName("dealer_tai") val dealerTai: Int = 0,
  val points: List<Double> = listOf(0.0, 0.0, 0.0, 0.0),
  @SerialName("next_dealer") val nextDealer: Int = 0,
  @SerialName("next_streak") val nextStreak: Int = 0,
)

@Serializable
data class View(
  val rules: String,
  val position: Position,
  val phase: String,
  val current: Int = 0,
  @SerialName("seat_winds") val seatWinds: List<Int> = listOf(0, 1, 2, 3),
  @SerialName("my_turn") val myTurn: Boolean = false,
  val legal: List<Action> = emptyList(),
  val events: List<GameEvent> = emptyList(),
  @SerialName("event_count") val eventCount: Int = 0,
  val result: HandResult? = null,
  val hands: List<String>? = null,
)

@Serializable
data class Pending(val phase: String, val current: Int = 0, val pending: List<Int> = emptyList(), val actions: Int = 0)

@Serializable
data class Wait(val tile: String, val left: Int = 0, @SerialName("ron_tai") val ronTai: Int = 0, @SerialName("tsumo_tai") val tsumoTai: Int = 0)

/**
 * One action's figures. Tile efficiency: the tiles from ready (shanten, -1: a win), the improving tiles left, and when
 * ready the waits. What the network adds: the expected result in points (q), how often it would choose the action
 * (policy), the chances of winning (by self-draw: tsumo), of dealing in and of another's self-draw, the tai of a win,
 * and the three parts of q (winning, dealing in, others' self-draws); for a discard, the chance that it deals in now
 * (danger), per opponent (the seats 1, 2, 3 places after the deciding one) with the tai it would pay for.
 */
@Serializable
data class ActionFigures(
  val action: Action, val shanten: Int = 99, val ukeire: Int = 0, val waits: List<Wait> = emptyList(), @SerialName("win_tai") val winTai: Int = 0,
  val q: Double? = null, val policy: Double? = null, val win: Double? = null, val tsumo: Double? = null,
  @SerialName("deal_in") val dealIn: Double? = null, @SerialName("others_tsumo") val othersTsumo: Double? = null, val tai: Double? = null,
  val parts: List<Double>? = null, val danger: Double? = null, @SerialName("danger_each") val dangerEach: List<Double>? = null,
  @SerialName("danger_tai") val dangerTai: List<Double>? = null,
) {
  /** Lower is better: a win first (more tai first), then fewer steps to ready, then more tiles that help. */
  val rank: Pair<Int, Int> get() = if (shanten < 0) -1 to -winTai else shanten to -ukeire
}

/**
 * What the network thinks of an opponent: the chances that it is ready, 1, 2, 3 or more tiles away, and per kind
 * (Tiles.KINDS) the chance that a discard of it deals in to it now, with how many tai.
 */
@Serializable
data class Opponent(
  val seat: Int, val ready: List<Double> = emptyList(), val danger: List<Double> = emptyList(),
  @SerialName("danger_tai") val dangerTai: List<Double> = emptyList(),
)

/** `source` "efficiency": tile efficiency alone; "network": the network's figures too, the best action and its value. */
@Serializable
data class Analysis(
  val source: String = "efficiency", val actions: List<ActionFigures> = emptyList(), val best: Int? = null, val value: Double? = null,
  @SerialName("policy_value") val policyValue: Double? = null, val opponents: List<Opponent>? = null,
) {
  /** The network's figures are there. */
  val net: Boolean get() = source == "network" && best != null

  /** The indices of the best actions by tile efficiency. */
  fun best(): Set<Int> {
    val b = actions.minOfWithOrNull(compareBy<Pair<Int, Int>>({ it.first }, { it.second })) { it.rank } ?: return emptySet()
    return actions.indices.filter { actions[it].rank == b }.toSet()
  }
}

@Serializable
data class TruthWait(val tile: String, val tai: Int = 0)

/** What an opponent really held: the tiles from ready, whether it had passed a win this turn, and its waits. */
@Serializable
data class Truth(val seat: Int, val shanten: Int = 0, val missed: Boolean = false, val waits: List<TruthWait> = emptyList())

/**
 * A decision of a reviewed hand: the record's step, the index of the action taken in the analysis' actions, and what
 * it lost against the best (points).
 */
@Serializable
data class ReviewDecision(val step: Int, val played: Int = -1, val analysis: Analysis, val truth: List<Truth> = emptyList(), val loss: Double? = null)

/** A hand's review for a seat: result = firstValue - loss + luck; `unit`: a typical payment under its rules. */
@Serializable
data class Review(
  val seat: Int, val decisions: List<ReviewDecision> = emptyList(), val result: Double = 0.0,
  @SerialName("first_value") val firstValue: Double? = null, val loss: Double? = null, val luck: Double? = null, val unit: Double? = null,
)

/**
 * A record's actions as a review steps through them. `taken`: the action took effect (everything on a seat's own turn;
 * of the answers to a discard only the claim that was made; a pass never). `over`: for a claim that was not made, the
 * index of the one that took its place (-1 otherwise). `last`: the action the play ended with (the first win; without
 * one the last action taken; -1 without any).
 */
@Serializable
data class Steps(val taken: List<Boolean> = emptyList(), val over: List<Int> = emptyList(), val last: Int = -1)

/**
 * After a draw of `tile` (`left` copies unseen): the best action, its figures and value, the second best and how much
 * worse it is.
 */
@Serializable
data class WhatIfDraw(
  val tile: String, val left: Int = 0, val value: Double = 0.0, val figures: ActionFigures, val action: Action, val next: Action? = null,
  @SerialName("next_loss") val nextLoss: Double = 0.0,
)

/** On `seat`'s discard of `tile`: the options (a pass among them) and the best one's index. */
@Serializable
data class WhatIfClaim(val seat: Int, val tile: String, val left: Int = 0, val best: Int = 0, val options: List<ActionFigures> = emptyList())

@Serializable
data class WhatIf(val draws: List<WhatIfDraw> = emptyList(), val claims: List<WhatIfClaim> = emptyList())

@Serializable
data class RuleItem(val param: String, val name: String)

/** The rules of a spec: every parameter's value, the presets' names, and the tai items with their names. */
@Serializable
data class RulesInfo(
  val spec: String,
  // The engine writes the values as numbers with a fraction ("base": 2.0), which do not read as integers.
  @SerialName("values") private val numbers: Map<String, Double> = emptyMap(),
  @SerialName("tai_presets") val taiPresets: List<String> = emptyList(),
  @SerialName("flower_presets") val flowerPresets: List<String> = emptyList(),
  val items: List<RuleItem> = emptyList(),
) {
  @Transient
  val values: Map<String, Int> = numbers.mapValues { it.value.roundToInt() }
}
