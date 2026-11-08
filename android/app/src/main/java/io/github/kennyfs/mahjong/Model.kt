package io.github.kennyfs.mahjong

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

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

/** One action's figures: tile efficiency now; a network adds its own numbers later (`extra`, shown when present). */
data class ActionFigures(
  val action: Action, val shanten: Int, val ukeire: Int, val waits: List<Wait>, val winTai: Int, val extra: Map<String, Double>,
) {
  /** Lower is better: a win first (more tai first), then fewer steps to ready, then more tiles that help. */
  val rank: Pair<Int, Int> get() = if (shanten < 0) -1 to -winTai else shanten to -ukeire
}

data class Analysis(val source: String, val actions: List<ActionFigures>) {
  /** The indices of the best actions by their figures. */
  fun best(): Set<Int> {
    val b = actions.minOfWithOrNull(compareBy<Pair<Int, Int>>({ it.first }, { it.second })) { it.rank } ?: return emptySet()
    return actions.indices.filter { actions[it].rank == b }.toSet()
  }

  companion object {
    private val KNOWN = setOf("action", "shanten", "ukeire", "waits", "win_tai")

    fun of(o: JsonObject): Analysis = Analysis(
      o["source"]?.jsonPrimitive?.content ?: "efficiency",
      (o["actions"]?.jsonArray ?: JsonArray(emptyList())).map { e ->
        val a = e as JsonObject
        ActionFigures(
          EngineJson.decodeFromJsonElement(a["action"]!!),
          a["shanten"]?.jsonPrimitive?.int ?: 99,
          a["ukeire"]?.jsonPrimitive?.int ?: 0,
          a["waits"]?.let { EngineJson.decodeFromJsonElement<List<Wait>>(it) } ?: emptyList(),
          a["win_tai"]?.jsonPrimitive?.int ?: 0,
          a.filterKeys { it !in KNOWN }.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap(),
        )
      },
    )
  }
}

@Serializable
data class RuleItem(val param: String, val name: String)

@Serializable
data class RulesInfo(
  val spec: String,
  val values: Map<String, Int> = emptyMap(),
  @SerialName("tai_presets") val taiPresets: List<String> = emptyList(),
  @SerialName("flower_presets") val flowerPresets: List<String> = emptyList(),
  val items: List<RuleItem> = emptyList(),
)
