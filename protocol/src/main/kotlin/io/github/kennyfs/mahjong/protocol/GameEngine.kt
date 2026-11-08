package io.github.kennyfs.mahjong.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class EngineError(message: String) : Exception(message)

/** Who must decide now (the engine's `pending`): the phase, whose turn, the seats, the record's length. */
data class Pending(val phase: String, val current: Int, val seats: List<Int>, val actions: Int, val eventCount: Int)

/** The setup of a hand: dealer seat, east seat (-1: the dealer), round wind (0 east ... 3 north), streak. */
data class Setup(val dealer: Int, val east: Int = -1, val round: Int = 0, val streak: Int = 0)

/**
 * What a room asks of the engine (cpp/command/guiengine.h, docs/gui-engine.md). A hand is its record (a JsonObject:
 * rules, setup, wall, actions), replayed by the engine on each request; rules are full specs as rules() returns them.
 */
interface GameEngine {
  /** The full spec of a checked spec; EngineError for rules the engine rejects. */
  suspend fun rules(spec: String): String
  suspend fun newHand(rules: String, setup: Setup, seed: String): JsonObject
  suspend fun pending(game: JsonObject): Pending
  /**
   * What `seat` sees, with the events from `since` on; `reveal` shows every hand (a finished hand); `hold` stops at a
   * last discard nobody may claim, before the next draw (the room's claim window).
   */
  suspend fun view(game: JsonObject, seat: Int, since: Int, reveal: Boolean = false, hold: Boolean = false): JsonObject
  /** The record with the seat's decision; EngineError when it is not legal. */
  suspend fun act(game: JsonObject, seat: Int, action: Action): JsonObject
  /** A bot's decision for a seat that must decide. */
  suspend fun bot(game: JsonObject, seat: Int, player: String, seed: String): Action
  /** Bots (non-null specs) decide: at most `steps` decision points, or until a person must decide or the hand ends. */
  suspend fun advance(game: JsonObject, players: List<String?>, seed: String, steps: Int? = null): JsonObject
  /** The next hand of a game (dealer and streak from this one's result), and whether the game is over. */
  suspend fun next(game: JsonObject, startDealer: Int, seed: String): Pair<JsonObject, Boolean>
}

/**
 * GameEngine over the engine's JSON requests, one request line to one answer line through `call`: a `mahjong gui`
 * process on the server, the JNI engine on a phone. Every request is built here from checked values; nothing a client
 * sends reaches the engine as text.
 */
class EngineClient(private val call: suspend (String) -> String) : GameEngine {
  private suspend fun ask(req: JsonObject): JsonObject {
    val answer = BattleJson.parseToJsonElement(call(req.toString())).jsonObject
    answer["error"]?.let { throw EngineError(it.jsonPrimitive.content) }
    return answer
  }

  private fun setupJson(s: Setup) = buildJsonObject { put("dealer", s.dealer); put("east", s.east); put("round", s.round); put("streak", s.streak) }

  private fun actionJson(a: Action) = buildJsonObject {
    put("type", a.type)
    if (a.tile != null) put("tile", a.tile)
    if (a.tiles != null) put("tiles", JsonArray(a.tiles.map { JsonPrimitive(it) }))
  }

  override suspend fun rules(spec: String): String =
    ask(buildJsonObject { put("cmd", "rules"); put("rules", spec) })["spec"]!!.jsonPrimitive.content

  override suspend fun newHand(rules: String, setup: Setup, seed: String): JsonObject =
    ask(buildJsonObject { put("cmd", "new"); put("rules", rules); put("setup", setupJson(setup)); put("seed", seed) })["game"]!!.jsonObject

  override suspend fun pending(game: JsonObject): Pending {
    val a = ask(buildJsonObject { put("cmd", "pending"); put("game", game) })
    return Pending(
      a["phase"]!!.jsonPrimitive.content, a["current"]!!.jsonPrimitive.int, a["pending"]!!.jsonArray.map { it.jsonPrimitive.int },
      a["actions"]!!.jsonPrimitive.int, a["event_count"]!!.jsonPrimitive.int,
    )
  }

  override suspend fun view(game: JsonObject, seat: Int, since: Int, reveal: Boolean, hold: Boolean): JsonObject =
    ask(buildJsonObject {
      put("cmd", "view"); put("game", game); put("seat", seat); put("since", since); put("reveal", reveal)
      if (hold) put("hold", true)
    })

  override suspend fun act(game: JsonObject, seat: Int, action: Action): JsonObject =
    ask(buildJsonObject { put("cmd", "act"); put("game", game); put("seat", seat); put("action", actionJson(action)) })["game"]!!.jsonObject

  override suspend fun bot(game: JsonObject, seat: Int, player: String, seed: String): Action {
    val a = ask(buildJsonObject { put("cmd", "bot"); put("game", game); put("seat", seat); put("player", player); put("seed", seed) })["action"]!!.jsonObject
    return Action(
      a["type"]!!.jsonPrimitive.content, (a["tile"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
      (a["tiles"] as? JsonArray)?.map { it.jsonPrimitive.content },
    )
  }

  override suspend fun advance(game: JsonObject, players: List<String?>, seed: String, steps: Int?): JsonObject =
    ask(buildJsonObject {
      put("cmd", "advance"); put("game", game); put("seed", seed)
      put("players", JsonArray(players.map { if (it == null) JsonNull else JsonPrimitive(it) }))
      if (steps != null) put("steps", steps)
    })["game"]!!.jsonObject

  override suspend fun next(game: JsonObject, startDealer: Int, seed: String): Pair<JsonObject, Boolean> {
    val a = ask(buildJsonObject { put("cmd", "next"); put("game", game); put("start_dealer", startDealer); put("seed", seed) })
    return a["game"]!!.jsonObject to (a["game_over"]?.jsonPrimitive?.boolean == true)
  }
}

/** The record's decisions: [seat, type, tile?] each. */
fun JsonObject.actions(): JsonArray = this["actions"]!!.jsonArray

/** The type of the record's last decision, or null. */
fun JsonObject.lastActionType(): String? = actions().lastOrNull()?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content

/** The record's setup. */
fun JsonObject.setup(): Setup {
  val s = this["setup"]!!.jsonObject
  return Setup(s["dealer"]!!.jsonPrimitive.int, s["east"]?.jsonPrimitive?.int ?: -1, s["round"]!!.jsonPrimitive.int, s["streak"]!!.jsonPrimitive.int)
}
