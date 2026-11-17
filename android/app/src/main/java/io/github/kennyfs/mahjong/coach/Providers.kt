package io.github.kennyfs.mahjong.coach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The language models the AI coach can ask (docs/coach.md): with the player's own key, straight from the app to the
// provider; nothing goes through a server of ours. Raw HTTP on OkHttp (already the battles'): one request and its
// answer each, so three providers' SDKs (and their dependencies) would add much to the app for little.
//
// Each provider's request asks for the answer as JSON of COACH_SCHEMA (its structured output), at a medium depth of
// thinking: the reading of a hundred hands' digest is analysis, not a quick reply, and higher levels cost more for
// little (the network has done the mahjong; the model finds habits and explains them).

enum class Provider(
  val id: String, val label: String, val model: String,
  /** US$ per million tokens: input, input read from the provider's cache, output (thinking included); the providers'
   *  published prices of 2026-11-17, for the estimate only (their bills decide). */
  val inPrice: Double, val cachedPrice: Double, val outPrice: Double,
  /** Where a key is made, as words (not a link: the providers move their pages). */
  val keySite: String,
  /** Its tokenizer's count of a digest against Coach.tokens: Claude's, measured on a 48-hand digest in Chinese
   *  (2026-11-18: 26k tokens, estimated 22k); the others' are not known. */
  val tokenScale: Double = 1.0,
) {
  CLAUDE("claude", "Claude Sonnet 5.5", "claude-sonnet-5-5", 2.0, 0.10, 10.0, "platform.claude.com", 1.2),
  GEMINI("gemini", "Gemini 4 Hydrogen", "gemini-4-hydrogen", 1.5, 0.075, 7.5, "aistudio.google.com"),
  OPENAI("openai", "ChatGPT (GPT-6.1 Sol)", "gpt-6.1-sol", 2.0, 0.10, 10.0, "platform.openai.com"),
  /** Any server that speaks OpenAI's Chat Completions (OpenRouter, a company's gateway, a local model behind HTTPS):
   *  its address and model are the player's. No price is known. */
  CUSTOM("custom", "", "", 0.0, 0.0, 0.0, ""),
}

/** A server of the Chat Completions kind: its base address (…/v1) and the model to ask. */
data class CustomServer(val url: String, val model: String) {
  val ready: Boolean get() = url.isNotBlank() && model.isNotBlank()
}

/** A turn of the conversation. `raw`: the provider's own form of an answer, sent back as it came (Claude's content
 *  blocks with their thinking, Gemini's content with its thought signatures). */
@kotlinx.serialization.Serializable
data class Turn(val user: Boolean, val text: String, val raw: JsonElement? = null)

/** An answer: its text, its own form (Turn.raw), the tokens it took (input, of which read from cache and written to
 *  it, output). */
data class Reply(val text: String, val raw: JsonElement?, val input: Int, val cached: Int, val output: Int, val model: String, val written: Int = 0)

class CoachException(val kind: Kind, val detail: String) : Exception(detail) {
  enum class Kind { KEY, QUOTA, REFUSED, TRUNCATED, NETWORK, SERVER, BAD_ANSWER }
}

/** What the coach answers first: JSON of this form, in every provider's structured output. */
val COACH_SCHEMA: JsonObject = buildJsonObject {
  put("type", "object")
  putJsonObject("properties") {
    putJsonObject("summary") { put("type", "string") }
    putJsonObject("habits") {
      put("type", "array")
      putJsonObject("items") {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("title") { put("type", "string") }
          putJsonObject("body") { put("type", "string") }
          putJsonObject("refs") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
          putJsonObject("rule") { put("type", "string") }
        }
        putJsonArray("required") { add("title"); add("body"); add("refs"); add("rule") }
        put("additionalProperties", false)
      }
    }
    putJsonObject("strengths") { put("type", "string") }
    putJsonObject("practice") { put("type", "string") }
  }
  putJsonArray("required") { add("summary"); add("habits"); add("strengths"); add("practice") }
  put("additionalProperties", false)
}

private val JSON = Json { ignoreUnknownKeys = true }
private val MEDIA = "application/json".toMediaType()

/** The tokens one answer may take, its thinking included. */
private const val MAX_OUTPUT = 16000

object Providers {
  // A medium depth of thinking over a long digest can take a few minutes.
  private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(1, TimeUnit.MINUTES).callTimeout(8, TimeUnit.MINUTES).build()

  /**
   * Asks `provider` with `key`: `system`, then the turns (the last one the user's). With `schema`, the answer is JSON
   * of it. Throws CoachException.
   */
  suspend fun ask(provider: Provider, key: String, custom: CustomServer?, system: String, turns: List<Turn>, schema: JsonObject?): Reply =
    try {
      when (provider) {
        Provider.CLAUDE -> parseClaude(post("https://api.anthropic.com/v1/messages", claudeBody(system, turns, schema),
          "x-api-key" to key, "anthropic-version" to "2026-10-21"))
        Provider.GEMINI -> parseGemini(post("https://generativelanguage.googleapis.com/v1beta/models/${provider.model}:generateContent",
          geminiBody(system, turns, schema), "x-goog-api-key" to key))
        Provider.OPENAI -> parseOpenai(post("https://api.openai.com/v1/responses", openaiBody(system, turns, schema), "Authorization" to "Bearer $key"))
        Provider.CUSTOM -> {
          val server = custom?.takeIf { it.ready } ?: throw CoachException(CoachException.Kind.KEY, "")
          val url = server.url.trim().trimEnd('/') + "/chat/completions"
          if (!url.startsWith("https://")) throw CoachException(CoachException.Kind.NETWORK, "https:// only")
          val auth = if (key.isNotBlank()) arrayOf("Authorization" to "Bearer $key") else emptyArray()
          try {
            parseChat(post(url, chatBody(server.model, system, turns, schema), *auth), server.model)
          } catch (e: HttpError) {
            // Not every such server knows a JSON schema: once more without it (the prompt asks for the JSON too).
            if (schema != null && e.code in listOf(400, 422)) parseChat(post(url, chatBody(server.model, system, turns, null), *auth), server.model)
            else throw e
          }
        }
      }
    } catch (e: HttpError) {
      throw httpError(e.code, e.body)
    }

  private class HttpError(val code: Int, val body: String) : Exception()

  /** The request's answer as JSON (an HTTP error: HttpError, which ask turns into a CoachException). */
  private suspend fun post(url: String, body: JsonObject, vararg headers: Pair<String, String>): JsonObject {
    val req = Request.Builder().url(url).post(body.toString().toRequestBody(MEDIA)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    val (code, text) = withContext(Dispatchers.IO) { execute(client.newCall(req)) }
    if (code !in 200..299) throw HttpError(code, text)
    return try {
      JSON.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
      throw CoachException(CoachException.Kind.BAD_ANSWER, text.take(300))
    }
  }

  /** Runs a call, cancelled with the coroutine. */
  private suspend fun execute(call: Call): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        cont.resumeWithException(CoachException(CoachException.Kind.NETWORK, e.message ?: e.javaClass.simpleName))
      }

      override fun onResponse(call: Call, response: Response) {
        val r = response.use { it.code to (it.body.string()) }
        cont.resume(r)
      }
    })
  }
}

/** An HTTP error's kind by its status, with the provider's own words for it (each puts them in "error"). */
fun httpError(code: Int, body: String): CoachException {
  val words = runCatching {
    val e = JSON.parseToJsonElement(body).jsonObject["error"]
    when (e) {
      is JsonObject -> e["message"]?.jsonPrimitive?.contentOrNull
      is JsonPrimitive -> e.contentOrNull
      else -> null
    }
  }.getOrNull() ?: body.take(300)
  val kind = when {
    // (Gemini answers a key it does not know with 400 INVALID_ARGUMENT, reason API_KEY_INVALID.)
    code == 401 || code == 403 || (code == 400 && ("API_KEY_INVALID" in body || "API key not valid" in body)) -> CoachException.Kind.KEY
    code == 402 || code == 429 -> CoachException.Kind.QUOTA
    else -> CoachException.Kind.SERVER
  }
  return CoachException(kind, "HTTP $code: $words")
}

// ---- Claude (the Messages API) ----

fun claudeBody(system: String, turns: List<Turn>, schema: JsonObject?): JsonObject = buildJsonObject {
  put("model", Provider.CLAUDE.model)
  put("max_tokens", MAX_OUTPUT)
  putJsonArray("system") { addJsonObject { put("type", "text"); put("text", system) } }
  putJsonArray("messages") {
    turns.forEach { t ->
      addJsonObject {
        put("role", if (t.user) "user" else "assistant")
        when {
          !t.user && t.raw is JsonArray -> put("content", t.raw)
          // Not marked for the prompt cache: a question after the review is asked without the review's format, which
          // changes the prompt, so it could not read the cache, and writing one costs a quarter more (seen 2026-11-18).
          else -> put("content", t.text)
        }
      }
    }
  }
  putJsonObject("output_config") {
    put("effort", "medium")
    if (schema != null) putJsonObject("format") { put("type", "json_schema"); put("schema", schema) }
  }
}

fun parseClaude(o: JsonObject): Reply {
  val stop = o["stop_reason"]?.jsonPrimitive?.contentOrNull
  if (stop == "refusal") throw CoachException(CoachException.Kind.REFUSED, o["stop_details"]?.toString() ?: "")
  if (stop == "max_tokens") throw CoachException(CoachException.Kind.TRUNCATED, "max_tokens")
  val content = o["content"]?.jsonArray ?: throw CoachException(CoachException.Kind.BAD_ANSWER, o.toString().take(300))
  val text = content.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
    .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
  val u = o["usage"]?.jsonObject
  fun n(k: String) = u?.get(k)?.jsonPrimitive?.intOrNull ?: 0
  val cached = n("cache_read_input_tokens")
  val written = n("cache_creation_input_tokens")
  return Reply(text, content, n("input_tokens") + written + cached, cached, n("output_tokens"),
    o["model"]?.jsonPrimitive?.contentOrNull ?: Provider.CLAUDE.model, written)
}

// ---- Gemini (generateContent) ----

fun geminiBody(system: String, turns: List<Turn>, schema: JsonObject?): JsonObject = buildJsonObject {
  putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
  putJsonArray("contents") {
    for (t in turns) {
      // A model turn goes back as it came, with its thought signatures (the API asks for them unchanged).
      if (!t.user && t.raw is JsonObject) add(t.raw)
      else addJsonObject {
        put("role", if (t.user) "user" else "model")
        putJsonArray("parts") { addJsonObject { put("text", t.text) } }
      }
    }
  }
  putJsonObject("generationConfig") {
    put("maxOutputTokens", MAX_OUTPUT)
    putJsonObject("thinkingConfig") { put("thinkingLevel", "MEDIUM") }
    if (schema != null) {
      put("responseMimeType", "application/json")
      put("responseJsonSchema", schema)
    }
  }
}

fun parseGemini(o: JsonObject): Reply {
  o["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
    throw CoachException(CoachException.Kind.REFUSED, it)
  }
  val cand = o["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw CoachException(CoachException.Kind.BAD_ANSWER, o.toString().take(300))
  when (val finish = cand["finishReason"]?.jsonPrimitive?.contentOrNull) {
    null, "STOP" -> {}
    "MAX_TOKENS" -> throw CoachException(CoachException.Kind.TRUNCATED, finish)
    else -> throw CoachException(CoachException.Kind.REFUSED, finish)
  }
  val content = cand["content"]?.jsonObject ?: throw CoachException(CoachException.Kind.BAD_ANSWER, cand.toString().take(300))
  val text = (content["parts"]?.jsonArray ?: JsonArray(emptyList()))
    .filter { p -> p.jsonObject["thought"]?.jsonPrimitive?.contentOrNull != "true" }
    .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
  val u = o["usageMetadata"]?.jsonObject
  fun n(k: String) = u?.get(k)?.jsonPrimitive?.intOrNull ?: 0
  return Reply(text, content, n("promptTokenCount"), n("cachedContentTokenCount"), n("candidatesTokenCount") + n("thoughtsTokenCount"),
    o["modelVersion"]?.jsonPrimitive?.contentOrNull ?: Provider.GEMINI.model)
}

// ---- OpenAI (the Responses API) ----

fun openaiBody(system: String, turns: List<Turn>, schema: JsonObject?): JsonObject = buildJsonObject {
  put("model", Provider.OPENAI.model)
  put("instructions", system)
  putJsonArray("input") {
    for (t in turns) addJsonObject { put("role", if (t.user) "user" else "assistant"); put("content", t.text) }
  }
  putJsonObject("reasoning") { put("effort", "medium") }
  put("max_output_tokens", MAX_OUTPUT)
  // Not kept on OpenAI's side for later retrieval (its default is to keep it 30 days).
  put("store", false)
  if (schema != null) putJsonObject("text") {
    putJsonObject("format") { put("type", "json_schema"); put("name", "coach_review"); put("strict", true); put("schema", schema) }
  }
}

fun parseOpenai(o: JsonObject): Reply {
  if (o["status"]?.jsonPrimitive?.contentOrNull == "incomplete") {
    val why = o["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull ?: ""
    throw CoachException(if (why == "max_output_tokens") CoachException.Kind.TRUNCATED else CoachException.Kind.REFUSED, why)
  }
  val parts = (o["output"]?.jsonArray ?: throw CoachException(CoachException.Kind.BAD_ANSWER, o.toString().take(300)))
    .map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
    .flatMap { it["content"]?.jsonArray ?: JsonArray(emptyList()) }.map { it.jsonObject }
  parts.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "refusal" }?.let {
    throw CoachException(CoachException.Kind.REFUSED, it["refusal"]?.jsonPrimitive?.contentOrNull ?: "")
  }
  val text = parts.filter { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }.joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull ?: "" }
  val u = o["usage"]?.jsonObject
  fun n(k: String) = u?.get(k)?.jsonPrimitive?.intOrNull ?: 0
  val cached = u?.get("input_tokens_details")?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
  return Reply(text, null, n("input_tokens"), cached, n("output_tokens"), o["model"]?.jsonPrimitive?.contentOrNull ?: Provider.OPENAI.model)
}

// ---- Chat Completions (CUSTOM) ----

fun chatBody(model: String, system: String, turns: List<Turn>, schema: JsonObject?): JsonObject = buildJsonObject {
  put("model", model)
  putJsonArray("messages") {
    addJsonObject { put("role", "system"); put("content", system) }
    for (t in turns) addJsonObject { put("role", if (t.user) "user" else "assistant"); put("content", t.text) }
  }
  if (schema != null) putJsonObject("response_format") {
    put("type", "json_schema")
    putJsonObject("json_schema") { put("name", "coach_review"); put("strict", true); put("schema", schema) }
  }
}

fun parseChat(o: JsonObject, model: String): Reply {
  val choice = o["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw CoachException(CoachException.Kind.BAD_ANSWER, o.toString().take(300))
  val msg = choice["message"]?.jsonObject
  msg?.get("refusal")?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.let { throw CoachException(CoachException.Kind.REFUSED, it) }
  if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length") throw CoachException(CoachException.Kind.TRUNCATED, "length")
  val text = msg?.get("content")?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull ?: ""
  val u = o["usage"]?.jsonObject
  fun n(k: String) = u?.get(k)?.jsonPrimitive?.intOrNull ?: 0
  val cached = u?.get("prompt_tokens_details")?.takeIf { it is JsonObject }?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
  return Reply(text, null, n("prompt_tokens"), cached, n("completion_tokens"), o["model"]?.jsonPrimitive?.contentOrNull ?: model)
}

/** US$ an answer cost by the provider's prices (null without them: a custom server). A write to the prompt cache costs
 *  a quarter more than input (Claude's 5-minute cache; the others write theirs for nothing). */
fun costOf(p: Provider, r: Reply): Double? =
  if (p.inPrice <= 0) null
  else ((r.input - r.cached + r.written * 0.25) * p.inPrice + r.cached * p.cachedPrice + r.output * p.outPrice) / 1e6

/** The answer's JSON object, from its text: as it is, or the outermost {…} of it (a model that wrapped it in words or
 *  a code fence, when its server took no schema). */
fun answerJson(text: String): JsonObject? {
  runCatching { return JSON.parseToJsonElement(text.trim()).jsonObject }
  val a = text.indexOf('{')
  val b = text.lastIndexOf('}')
  if (a < 0 || b <= a) return null
  return runCatching { JSON.parseToJsonElement(text.substring(a, b + 1)).jsonObject }.getOrNull()
}
