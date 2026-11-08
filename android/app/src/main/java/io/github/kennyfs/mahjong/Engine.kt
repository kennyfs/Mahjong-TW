package io.github.kennyfs.mahjong

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

class EngineException(message: String) : Exception(message)

/** The engine's JSON, as the app reads it: unknown fields (a network's, later) are kept out of the types. */
val EngineJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

/**
 * The native engine (cpp/command/guiengine.h, docs/gui-engine.md): one JSON request, one JSON answer. Requests run
 * one at a time on a thread of their own, in the order they are made. The same requests as the web page's
 * (web/src/lib/engine.ts).
 */
object Engine {
  init {
    System.loadLibrary("mahjong_jni")
  }

  private val handle = nativeCreate()
  private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "engine") }.asCoroutineDispatcher()

  suspend fun request(req: JsonObject): JsonObject = withContext(dispatcher) {
    val answer = EngineJson.parseToJsonElement(nativeHandle(handle, req.toString())).jsonObject
    answer["error"]?.let { throw EngineException(it.jsonPrimitive.content) }
    answer
  }

  /** One request line to one answer line, errors as {"error": ...}: for the battle rooms' EngineClient. */
  suspend fun raw(line: String): String = withContext(dispatcher) { nativeHandle(handle, line) }

  private inline fun <reified T> JsonObject.field(name: String): T = EngineJson.decodeFromJsonElement(this[name] ?: JsonNull)
  private inline fun <reified T> json(v: T): JsonElement = EngineJson.encodeToJsonElement(v)

  suspend fun newHand(rules: String, setup: Setup, seed: String? = null): GameRecord =
    request(buildJsonObject { put("cmd", "new"); put("rules", rules); put("setup", json(setup)); seed?.let { put("seed", it) } }).field("game")

  suspend fun view(game: GameRecord, seat: Int, since: Int = 0, reveal: Boolean = false): View =
    EngineJson.decodeFromJsonElement(request(buildJsonObject {
      put("cmd", "view"); put("game", json(game)); put("seat", seat); put("since", since); put("reveal", reveal)
    }))

  suspend fun act(game: GameRecord, seat: Int, action: Action): GameRecord =
    request(buildJsonObject { put("cmd", "act"); put("game", json(game)); put("seat", seat); put("action", json(action)) }).field("game")

  suspend fun advance(game: GameRecord, players: List<String?>, seed: String? = null, steps: Int? = null): GameRecord =
    request(buildJsonObject {
      put("cmd", "advance"); put("game", json(game)); put("players", json(players)); seed?.let { put("seed", it) }
      steps?.let { put("steps", it) }
    }).field("game")

  suspend fun pending(game: GameRecord): Pending =
    EngineJson.decodeFromJsonElement(request(buildJsonObject { put("cmd", "pending"); put("game", json(game)) }))

  suspend fun nextHand(game: GameRecord, startDealer: Int, seed: String? = null): Pair<GameRecord, Boolean> {
    val a = request(buildJsonObject {
      put("cmd", "next"); put("game", json(game)); put("start_dealer", startDealer); seed?.let { put("seed", it) }
    })
    return a.field<GameRecord>("game") to a.field<Boolean>("game_over")
  }

  suspend fun analyzeGame(game: GameRecord, seat: Int): Analysis =
    Analysis.of(request(buildJsonObject { put("cmd", "analyze"); put("game", json(game)); put("seat", seat) }))

  suspend fun analyzePosition(rules: String, position: Position): Analysis =
    Analysis.of(request(buildJsonObject { put("cmd", "analyze"); put("rules", rules); put("position", json(position)) }))

  suspend fun rules(spec: String? = null): RulesInfo =
    EngineJson.decodeFromJsonElement(request(buildJsonObject { put("cmd", "rules"); spec?.let { put("rules", it) } }))

  @JvmStatic private external fun nativeCreate(): Long
  @JvmStatic private external fun nativeHandle(handle: Long, request: String): String
}
