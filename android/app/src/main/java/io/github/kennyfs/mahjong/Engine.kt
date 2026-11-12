package io.github.kennyfs.mahjong

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
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
import java.io.File
import java.util.concurrent.Executors

class EngineException(message: String) : Exception(message)

/** The engine's JSON, as the app reads it: unknown fields are kept out of the types. */
val EngineJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

/**
 * The native engine (cpp/command/guiengine.h, docs/gui-engine.md): one JSON request, one JSON answer. Requests run
 * one at a time on a thread of their own, in the order they are made. The same requests as the web page's
 * (web/src/lib/engine.ts).
 *
 * The network (the asset actor.onnx) is copied into the app's files when a request first needs it, since ONNX Runtime
 * opens a path: the analysis' figures, a hand's review, the tables of draws and claims, and the bot NET_BOT.
 */
object Engine {
  init {
    System.loadLibrary("mahjong_jni")
  }

  /** The bot that plays the network's most valued action, as the settings and the records name it. */
  const val NET_BOT = "net"
  /** Which network this build has (the start of its file's SHA-256): another one reviews a record again. */
  val MODEL_ID: String = BuildConfig.MODEL_ID

  private val handle = nativeCreate()
  private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "engine") }.asCoroutineDispatcher()
  private var appContext: Context? = null
  private var modelPath: String? = null

  /** The app, for its assets and files (the network). */
  fun setup(context: Context) {
    appContext = context.applicationContext
  }

  /** The network's file, copied from the assets once; older builds' networks are removed. On the engine's thread. */
  private fun model(): String {
    modelPath?.let { return it }
    val context = appContext ?: throw EngineException("The engine has no app context")
    val name = "actor-$MODEL_ID.onnx"
    val target = File(context.filesDir, name)
    try {
      if (!target.exists() || target.length() == 0L) {
        val tmp = File(context.filesDir, "$name.tmp")
        context.assets.open("actor.onnx").use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(target)) throw EngineException("Could not write $name")
      }
      context.filesDir.listFiles { f -> f.name.startsWith("actor-") && f.name != name }?.forEach { it.delete() }
    } catch (e: java.io.IOException) {
      throw EngineException(e.message ?: "The network could not be read")
    }
    return target.path.also { modelPath = it }
  }

  private fun handleOn(req: JsonObject): JsonObject {
    val answer = EngineJson.parseToJsonElement(nativeHandle(handle, req.toString())).jsonObject
    answer["error"]?.let { throw EngineException(it.jsonPrimitive.content) }
    return answer
  }

  suspend fun request(req: JsonObject): JsonObject = withContext(dispatcher) { handleOn(req) }

  /** A request that names the network: `build` gets its path. */
  private suspend fun withModel(build: (String) -> JsonObject): JsonObject = withContext(dispatcher) { handleOn(build(model())) }

  /** One request line to one answer line, errors as {"error": ...}: for the battle rooms' EngineClient. */
  suspend fun raw(line: String): String = withContext(dispatcher) { nativeHandle(handle, line) }

  /** An answer as the app's type; one that does not read is an engine error like any other (the screens catch those). */
  private inline fun <reified T> decode(e: JsonElement): T = try {
    EngineJson.decodeFromJsonElement(e)
  } catch (x: SerializationException) {
    throw EngineException("The engine's answer does not read: ${x.message}")
  }

  private inline fun <reified T> JsonObject.field(name: String): T = decode(this[name] ?: JsonNull)
  private inline fun <reified T> json(v: T): JsonElement = EngineJson.encodeToJsonElement(v)

  suspend fun newHand(rules: String, setup: Setup, seed: String? = null): GameRecord =
    request(buildJsonObject { put("cmd", "new"); put("rules", rules); put("setup", json(setup)); seed?.let { put("seed", it) } }).field("game")

  suspend fun view(game: GameRecord, seat: Int, since: Int = 0, reveal: Boolean = false): View =
    decode(request(buildJsonObject {
      put("cmd", "view"); put("game", json(game)); put("seat", seat); put("since", since); put("reveal", reveal)
    }))

  suspend fun act(game: GameRecord, seat: Int, action: Action): GameRecord =
    request(buildJsonObject { put("cmd", "act"); put("game", json(game)); put("seat", seat); put("action", json(action)) }).field("game")

  /** The bots decide (`players`: per seat a bot, NET_BOT among them, or null for a person). The network's bots play
   *  the action the policy head likes best: it played stronger than the best composed Q (docs/training.md,
   *  2026-11-12: +0.04 to +0.08 a hand), at the user's word whatever the analysis shows as best. */
  suspend fun advance(game: GameRecord, players: List<String?>, seed: String? = null, steps: Int? = null): GameRecord {
    fun req(specs: List<String?>) = buildJsonObject {
      put("cmd", "advance"); put("game", json(game)); put("players", json(specs)); seed?.let { put("seed", it) }
      steps?.let { put("steps", it) }
    }
    val a = if (NET_BOT in players) withModel { path -> req(players.map { if (it == NET_BOT) "net:model=$path,mode=policy" else it }) } else request(req(players))
    return a.field("game")
  }

  suspend fun pending(game: GameRecord): Pending =
    decode(request(buildJsonObject { put("cmd", "pending"); put("game", json(game)) }))

  suspend fun nextHand(game: GameRecord, startDealer: Int, seed: String? = null): Pair<GameRecord, Boolean> {
    val a = request(buildJsonObject {
      put("cmd", "next"); put("game", json(game)); put("start_dealer", startDealer); seed?.let { put("seed", it) }
    })
    return a.field<GameRecord>("game") to a.field<Boolean>("game_over")
  }

  /**
   * A decision's figures: the network's (`net`), or tile efficiency alone. `hand` (TILES, with `drawn` the tile drawn
   * of it on the seat's own turn): the seat's position in the game with that hand in place of its own; the table and
   * its history stay the game's.
   */
  suspend fun analyzeGame(game: GameRecord, seat: Int, net: Boolean = true, hand: String? = null, drawn: String? = null): Analysis {
    fun req(path: String?) = buildJsonObject {
      put("cmd", "analyze"); put("game", json(game)); put("seat", seat); path?.let { put("model", it) }
      hand?.let { put("hand", it); put("seed", "analysis") }
      drawn?.let { put("drawn", it) }
    }
    return decode(if (net) withModel { req(it) } else request(req(null)))
  }

  suspend fun analyzePosition(rules: String, position: Position, seed: String? = null, net: Boolean = true): Analysis {
    fun req(path: String?) = buildJsonObject {
      put("cmd", "analyze"); put("rules", rules); put("position", json(position)); seed?.let { put("seed", it) }; path?.let { put("model", it) }
    }
    return decode(if (net) withModel { req(it) } else request(req(null)))
  }

  /** Every decision of `seat` in a finished hand, with the network's figures and what the seat could not see. */
  suspend fun review(game: GameRecord, seat: Int): Review =
    decode(withModel { path -> buildJsonObject { put("cmd", "review"); put("game", json(game)); put("seat", seat); put("model", path) } })

  /** Which of a hand's actions took effect (a claim that another seat's claim came before did not), for a review. */
  suspend fun steps(game: GameRecord): Steps =
    decode(request(buildJsonObject { put("cmd", "steps"); put("game", json(game)) }))

  /** What a seat would do after each tile it may draw, and on each discard of the others it could claim: in a game
   *  (with `hand`, and `drawn`, in place of its own as in analyzeGame), or in a position of its own. */
  suspend fun whatIfGame(game: GameRecord, seat: Int, hand: String? = null, drawn: String? = null): WhatIf =
    decode(withModel { path ->
      buildJsonObject {
        put("cmd", "whatif"); put("game", json(game)); put("seat", seat); put("model", path); put("seed", "whatif")
        hand?.let { put("hand", it) }
        drawn?.let { put("drawn", it) }
      }
    })

  suspend fun whatIfPosition(rules: String, position: Position): WhatIf =
    decode(withModel { path ->
      buildJsonObject { put("cmd", "whatif"); put("rules", rules); put("position", json(position)); put("model", path); put("seed", "whatif") }
    })

  suspend fun rules(spec: String? = null): RulesInfo =
    decode(request(buildJsonObject { put("cmd", "rules"); spec?.let { put("rules", it) } }))

  @JvmStatic private external fun nativeCreate(): Long
  @JvmStatic private external fun nativeHandle(handle: Long, request: String): String
}
