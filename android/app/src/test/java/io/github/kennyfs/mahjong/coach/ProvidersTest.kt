package io.github.kennyfs.mahjong.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The providers' requests as their APIs document them, and their answers read (coach/Providers.kt). */
class ProvidersTest {
  private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
  private fun JsonObject.o(k: String) = this[k]!!.jsonObject
  private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

  private fun kindOf(block: () -> Unit): CoachException.Kind {
    try {
      block()
    } catch (e: CoachException) {
      return e.kind
    }
    fail("no CoachException")
    error("")
  }

  private val claudeAnswer = Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":"{\"a\":1}"}]""")
  private val turns = listOf(Turn(true, "digest"), Turn(false, "{\"a\":1}", claudeAnswer), Turn(true, "why?"))

  @Test fun claudeRequest() {
    val b = claudeBody("claude-sonnet-5-5", Effort.MEDIUM, "sys", turns, COACH_SCHEMA)
    assertEquals("claude-sonnet-5-5", b.s("model"))
    assertEquals("medium", b.o("output_config").s("effort"))
    // Another model and thinking, as chosen.
    val h = claudeBody("claude-haiku-5-5", Effort.LOW, "sys", turns, null)
    assertEquals("claude-haiku-5-5", h.s("model"))
    assertEquals("low", h.o("output_config").s("effort"))
    assertNull(h.o("output_config")["format"])
    assertEquals("json_schema", b.o("output_config").o("format").s("type"))
    assertNull(b["temperature"])
    val m = b["messages"]!!.jsonArray
    // The digest as text (not marked for the cache); the answer sent back as it came, its thinking block with it.
    assertEquals("digest", m[0].jsonObject.s("content"))
    assertEquals(claudeAnswer, m[1].jsonObject["content"])
    assertEquals("why?", m[2].jsonObject.s("content"))
    assertEquals("sys", b["system"]!!.jsonArray[0].jsonObject.s("text"))
  }

  @Test fun claudeAnswer() {
    val r = parseClaude(obj("""{"model":"claude-sonnet-5-5","stop_reason":"end_turn","content":$claudeAnswer,
      "usage":{"input_tokens":10,"cache_creation_input_tokens":100,"cache_read_input_tokens":1000,"output_tokens":50}}"""), "claude-sonnet-5-5")
    assertEquals("{\"a\":1}", r.text)
    assertEquals(1110, r.input)
    assertEquals(1000, r.cached)
    assertEquals(50, r.output)
    assertEquals(CoachException.Kind.REFUSED, kindOf { parseClaude(obj("""{"stop_reason":"refusal","content":[]}"""), "claude-sonnet-5-5") })
    assertEquals(CoachException.Kind.TRUNCATED, kindOf { parseClaude(obj("""{"stop_reason":"max_tokens","content":[]}"""), "claude-sonnet-5-5") })
  }

  @Test fun geminiRequest() {
    val raw = obj("""{"role":"model","parts":[{"text":"{}","thoughtSignature":"xyz"}]}""")
    val b = geminiBody(Effort.MEDIUM, "sys", listOf(Turn(true, "digest"), Turn(false, "{}", raw), Turn(true, "why?")), COACH_SCHEMA)
    val g = b.o("generationConfig")
    assertEquals("MEDIUM", g.o("thinkingConfig").s("thinkingLevel"))
    assertEquals("HIGH", geminiBody(Effort.HIGH, "sys", listOf(Turn(true, "x")), null).o("generationConfig").o("thinkingConfig").s("thinkingLevel"))
    assertEquals("application/json", g.s("responseMimeType"))
    assertEquals(COACH_SCHEMA, g["responseJsonSchema"])
    val c = b["contents"]!!.jsonArray
    assertEquals("user", c[0].jsonObject.s("role"))
    assertEquals(raw, c[1])
    assertEquals("sys", b.o("systemInstruction")["parts"]!!.jsonArray[0].jsonObject.s("text"))
  }

  @Test fun geminiAnswer() {
    val r = parseGemini(obj("""{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking...","thought":true},{"text":"{\"a\":2}"}]},
      "finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":900,"cachedContentTokenCount":100,"candidatesTokenCount":40,"thoughtsTokenCount":60},
      "modelVersion":"gemini-4-hydrogen"}"""), "gemini-4-hydrogen")
    assertEquals("{\"a\":2}", r.text)
    assertEquals(900, r.input)
    assertEquals(100, r.output)
    assertTrue(r.raw is JsonObject)
    assertEquals(CoachException.Kind.TRUNCATED, kindOf { parseGemini(obj("""{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}"""), "gemini-4-hydrogen") })
    assertEquals(CoachException.Kind.REFUSED, kindOf { parseGemini(obj("""{"promptFeedback":{"blockReason":"SAFETY"}}"""), "gemini-4-hydrogen") })
  }

  @Test fun openaiRequest() {
    val b = openaiBody("gpt-6.1-sol", Effort.MEDIUM, "sys", turns, COACH_SCHEMA)
    assertEquals("gpt-6.1-sol", b.s("model"))
    assertEquals("sys", b.s("instructions"))
    assertEquals("medium", b.o("reasoning").s("effort"))
    assertEquals("low", openaiBody("gpt-6.1-sol", Effort.LOW, "sys", turns, null).o("reasoning").s("effort"))
    assertEquals("false", b["store"]!!.jsonPrimitive.content)
    val f = b.o("text").o("format")
    assertEquals("json_schema", f.s("type"))
    assertEquals("true", f["strict"]!!.jsonPrimitive.content)
    assertEquals("assistant", b["input"]!!.jsonArray[1].jsonObject.s("role"))
  }

  @Test fun openaiAnswer() {
    val r = parseOpenai(obj("""{"status":"completed","model":"gpt-6.1-sol","output":[{"type":"reasoning","summary":[]},
      {"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\"a\":3}"}]}],
      "usage":{"input_tokens":75,"input_tokens_details":{"cached_tokens":5},"output_tokens":1186}}"""), "gpt-6.1-sol")
    assertEquals("{\"a\":3}", r.text)
    assertEquals(5, r.cached)
    assertEquals(CoachException.Kind.TRUNCATED,
      kindOf { parseOpenai(obj("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}"""), "gpt-6.1-sol") })
    assertEquals(CoachException.Kind.REFUSED,
      kindOf { parseOpenai(obj("""{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"no"}]}]}"""), "gpt-6.1-sol") })
  }

  @Test fun chatCompletions() {
    val b = chatBody("m", "sys", turns, COACH_SCHEMA)
    assertEquals("system", b["messages"]!!.jsonArray[0].jsonObject.s("role"))
    assertEquals("json_schema", b.o("response_format").s("type"))
    assertNull(chatBody("m", "sys", turns, null)["response_format"])
    val r = parseChat(obj("""{"choices":[{"message":{"role":"assistant","content":"hi","refusal":null},"finish_reason":"stop"}],
      "usage":{"prompt_tokens":3,"completion_tokens":4}}"""), "m")
    assertEquals("hi", r.text)
    assertEquals(CoachException.Kind.TRUNCATED, kindOf { parseChat(obj("""{"choices":[{"message":{"content":"x"},"finish_reason":"length"}]}"""), "m") })
  }

  @Test fun errors() {
    val e = httpError(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
    assertEquals(CoachException.Kind.KEY, e.kind)
    assertTrue(e.detail.contains("invalid x-api-key"))
    assertEquals(CoachException.Kind.QUOTA, httpError(429, """{"error":{"message":"quota","code":"credit_balance_exhausted"}}""").kind)
    assertEquals(CoachException.Kind.SERVER, httpError(503, "<html>").kind)
    assertEquals(CoachException.Kind.KEY, httpError(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",
      "status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""").kind)
    assertEquals(CoachException.Kind.SERVER, httpError(400, """{"error":{"message":"bad schema"}}""").kind)
    assertEquals(CoachException.Kind.KEY, httpError(403, """{"error":{"code":403,"message":"PERMISSION_DENIED","status":"PERMISSION_DENIED"}}""").kind)
  }

  @Test fun answerFromText() {
    assertEquals("1", answerJson("""{"summary":"1","habits":[]}""")!!.s("summary"))
    assertEquals("2", answerJson("Here:\n```json\n{\"summary\":\"2\"}\n```")!!.s("summary"))
    assertNull(answerJson("no json here"))
  }

  @Test fun cost() {
    val r = Reply("", null, 1_000_000, 0, 100_000, "")
    val sonnet = Provider.CLAUDE.model(null)
    assertEquals("claude-sonnet-5-5", sonnet?.id)
    assertEquals(3.0, costOf(sonnet, r)!!, 1e-9)
    // The 2026-11-18 review and its question, each writing the whole prompt to the cache: about the US$0.15 billed.
    assertEquals(0.155, costOf(sonnet, Reply("", null, 54_155, 0, 1_995, "", written = 54_155))!!, 0.001)
    // Haiku: a twentieth of it.
    assertEquals(0.15, costOf(Provider.CLAUDE.model("claude-haiku-5-5"), r)!!, 1e-9)
    assertEquals("claude-sonnet-5-5", Provider.CLAUDE.model("no-such-model")?.id)
    assertNull(Provider.CUSTOM.model(null))
    assertNull(costOf(Provider.CUSTOM.model(null), r))
    assertNotNull(COACH_SCHEMA["properties"])
    assertTrue(COACH_SCHEMA.o("properties").o("habits").o("items")["required"] is JsonArray)
  }
}
