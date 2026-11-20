package io.github.kennyfs.mahjong.coach

import android.app.Application
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.EngineException
import io.github.kennyfs.mahjong.EngineJson
import io.github.kennyfs.mahjong.HandRecord
import io.github.kennyfs.mahjong.Level
import io.github.kennyfs.mahjong.Position
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Records
import io.github.kennyfs.mahjong.Rules
import io.github.kennyfs.mahjong.RulesInfo
import io.github.kennyfs.mahjong.reviewOf
import io.github.kennyfs.mahjong.summarize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant

// The AI coach (docs/coach.md): the player's recent hands of one set of rules, reviewed by the network, put in a digest
// (Digest.kt) and read by a language model with the player's own key (Providers.kt), which finds the player's habits
// and explains them. Its answers are kept (the last few), and can be asked more about.

/** The records of one set of rules: the same values of every parameter, whatever spec they were played under. `tai`,
 *  `flowers`: the presets nearest to them, with `changed` parameters set otherwise. */
data class RulesGroup(val key: String, val spec: String, val tai: String, val flowers: String, val changed: Int, val hands: List<HandRecord>)

/** A coach's review and the questions after it. `hands`: the records' ids, H1 the first; turns[0] the digest. `model`:
 *  the model as it answered (shown); `asked` and `effort`: the model asked and its thinking, which questions after it
 *  keep (empty in sessions from before the choice: the provider's default, medium). */
@Serializable
data class CoachSession(
  val id: String, val time: String, val provider: String, val model: String, val lang: String, val rules: String,
  val hands: List<String>, val system: String, val turns: List<Turn>, val input: Int = 0, val output: Int = 0, val cost: Double? = null,
  val asked: String = "", val effort: String = "",
)

@Serializable
private data class StoredSessions(val sessions: List<CoachSession> = emptyList())

@Serializable
private data class StoredCache(val hands: List<HandDigest> = emptyList())

/** The digest made ready to send: its text, the hands in it, the hands that could not be reviewed. */
data class Prepared(val group: RulesGroup, val rulesName: String, val hands: List<HandRecord>, val skipped: Int, val digest: String, val question: String) {
  /** The first message: the digest, then the player's question. */
  fun firstMessage(zh: Boolean): String =
    if (question.isBlank()) digest else digest + "\n" + (if (zh) "玩家想特別問：" else "The player asks: ") + question.trim()
}

class Coach(private val app: Application, private val scope: CoroutineScope, private val records: Records) {
  val keys = CoachKeys(app)

  sealed interface Phase {
    data object Idle : Phase
    /** Hands reviewed and put in the digest: done of total. */
    data class Gathering(val done: Int, val total: Int) : Phase
    data class Ready(val prepared: Prepared) : Phase
    /** Waiting for the model's answer (a follow-up question too). */
    data class Asking(val provider: Provider) : Phase
    /** An error; `prepared`: the digest it was to send, which closing the error brings back. */
    data class Failed(val kind: CoachException.Kind?, val detail: String, val prepared: Prepared? = null) : Phase
  }

  var phase by mutableStateOf<Phase>(Phase.Idle)
    private set

  var groups by mutableStateOf<List<RulesGroup>?>(null)
    private set

  private val sessionsFile = File(app.filesDir, "coach.json")
  var sessions by mutableStateOf(runCatching { EngineJson.decodeFromString<StoredSessions>(sessionsFile.readText()).sessions }.getOrDefault(emptyList()))
    private set

  private val cacheFile = File(app.filesDir, "coach-cache.json")
  private val cache: MutableMap<String, HandDigest> by lazy {
    runCatching { EngineJson.decodeFromString<StoredCache>(cacheFile.readText()).hands }.getOrDefault(emptyList())
      .associateByTo(LinkedHashMap()) { it.id }
  }

  private var job: Job? = null

  /** The records in groups by their rules (hands played with the analysis shown left out: 顯示分析), the groups with
   *  the most recent hand first. */
  fun loadGroups() {
    if (groups != null) return
    scope.launch {
      groups = try {
        group(records.hands.filter { !it.hints })
      } catch (e: EngineException) {
        emptyList()
      }
    }
  }

  fun refreshGroups() {
    groups = null
    loadGroups()
  }

  private suspend fun group(hands: List<HandRecord>): List<RulesGroup> {
    val presets = Rules.TAI_PRESETS.flatMap { t -> Rules.FLOWER_PRESETS.map { f -> Triple(t, f, Rules.info("$t,$f").values) } }
    val bySpec = hands.groupBy { it.game.rules }
    val infos = bySpec.keys.associateWith { Rules.info(it) }
    val byKey = LinkedHashMap<String, MutableList<HandRecord>>()
    val specOf = HashMap<String, String>()
    for ((spec, hs) in bySpec) {
      val key = keyOf(infos.getValue(spec))
      byKey.getOrPut(key) { ArrayList() } += hs
      specOf.putIfAbsent(key, spec)
    }
    return byKey.map { (key, hs) ->
      val values = infos.getValue(specOf.getValue(key)).values
      val (t, f, base) = presets.minBy { (_, _, v) -> diff(v, values) }
      RulesGroup(key, specOf.getValue(key), t, f, diff(base, values), hs.sortedByDescending { it.time })
    }.sortedByDescending { it.hands.first().time }
  }

  private fun keyOf(info: RulesInfo) = info.values.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }

  private fun diff(a: Map<String, Int>, b: Map<String, Int>) = (a.keys + b.keys).count { a[it] != b[it] }

  /** Reviews the group's latest `count` hands and puts them in a digest (Phase.Ready), in the app's language. */
  fun prepare(res: Resources, group: RulesGroup, count: Int, rulesName: String, question: String) {
    if (job?.isActive == true) return
    val zh = res.getString(R.string.coach_lang) == "zh-TW"
    val w = DigestWords(zh)
    val chosen = group.hands.take(count).reversed()
    job = scope.launch {
      phase = Phase.Gathering(0, chosen.size)
      try {
        val parts = ArrayList<Pair<HandRecord, HandDigest>>()
        var skipped = 0
        for ((i, h) in chosen.withIndex()) {
          val d = handDigest(res, w, h)
          if (d != null) parts += h to d else skipped++
          phase = Phase.Gathering(i + 1, chosen.size)
        }
        saveCache()
        if (parts.isEmpty()) {
          phase = Phase.Failed(null, res.getString(R.string.coach_no_hands))
          return@launch
        }
        val info = Rules.info(group.spec)
        val digest = Digest.assemble(res, w, Digest.rulesText(res, w, info, rulesName), parts.map { it.second })
        phase = Phase.Ready(Prepared(group, rulesName, parts.map { it.first }, skipped, digest, question))
      } catch (e: CancellationException) {
        phase = Phase.Idle
        throw e
      } catch (e: Exception) {
        phase = Phase.Failed(null, e.message ?: e.javaClass.simpleName)
      }
    }
  }

  /** A hand's part of the digest: kept from before for this network and language, or reviewed now (its summary kept
   *  with the record too). Null when it cannot be: no network, or a record the engine cannot replay. */
  private suspend fun handDigest(res: Resources, w: DigestWords, h: HandRecord): HandDigest? {
    val lang = if (w.zh) "zh" else "en"
    cache[h.id]?.let { if (it.m == Engine.REVIEW_ID && it.lang == lang) return it }
    return try {
      val r = reviewOf(h)
      if (r.firstValue == null) return null
      if (records.get(h.id)?.rev?.m != Engine.REVIEW_ID) records.setReview(h.id, summarize(r))
      val positions = HashMap<Int, Position>()
      for (step in Digest.keySteps(r)) positions[step] = Engine.view(h.game.cut(step), h.me).position
      Digest.hand(res, w, h, r, positions, Engine.REVIEW_ID).also { cache[h.id] = it }
    } catch (e: EngineException) {
      null
    }
  }

  private suspend fun saveCache() {
    // The latest few hundred hands' parts (a hand's is a few kB).
    while (cache.size > 400) cache.remove(cache.keys.first())
    val text = EngineJson.encodeToString(StoredCache(cache.values.toList()))
    withContext(Dispatchers.IO) { runCatching { write(cacheFile, text) } }
  }

  fun cancel() {
    job?.cancel()
    if (phase !is Phase.Ready) phase = Phase.Idle
  }

  fun dismiss() {
    if (job?.isActive == true) return
    phase = (phase as? Phase.Failed)?.prepared?.let { Phase.Ready(it) } ?: Phase.Idle
  }

  /** Sends the prepared digest to `provider`'s `model` (null: a custom server's), thinking at `effort`; the answer
   *  becomes the newest session. */
  fun send(res: Resources, prepared: Prepared, provider: Provider, model: CoachModel?, effort: Effort, level: Level) {
    if (job?.isActive == true) return
    val zh = res.getString(R.string.coach_lang) == "zh-TW"
    val key = keys.get(provider) ?: if (provider == Provider.CUSTOM) "" else run {
      phase = Phase.Failed(CoachException.Kind.KEY, "", prepared)
      return
    }
    val system = systemPrompt(level, zh)
    val first = Turn(true, prepared.firstMessage(zh))
    job = scope.launch {
      phase = Phase.Asking(provider)
      try {
        val asked = model?.id ?: keys.custom.model
        val r = Providers.ask(provider, asked, effort, key, keys.custom, system, listOf(first), COACH_SCHEMA)
        if (r.text.isBlank()) throw CoachException(CoachException.Kind.BAD_ANSWER, "")
        val shown = if (provider == Provider.CUSTOM) keys.custom.model else r.model
        val s = CoachSession(
          io.github.kennyfs.mahjong.Records.newId(), Instant.now().toString(), provider.id, shown, if (zh) "zh" else "en", prepared.rulesName,
          prepared.hands.map { it.id }, system, listOf(first, Turn(false, r.text, r.raw)), r.input, r.output, costOf(model, r),
          asked = asked, effort = effort.id,
        )
        sessions = (listOf(s) + sessions).take(KEEP)
        persist()
        phase = Phase.Idle
      } catch (e: CancellationException) {
        phase = Phase.Ready(prepared)
        throw e
      } catch (e: CoachException) {
        phase = Phase.Failed(e.kind, e.detail, prepared)
      }
    }
  }

  /** Asks more about a session, of the same model at the same thinking: the answer is added to it. */
  fun followUp(session: CoachSession, question: String) {
    if (job?.isActive == true || question.isBlank()) return
    val provider = Provider.entries.firstOrNull { it.id == session.provider } ?: return
    val model = provider.model(session.asked.ifEmpty { null })
    val asked = if (provider == Provider.CUSTOM) session.asked.ifEmpty { keys.custom.model } else model?.id ?: return
    val key = keys.get(provider) ?: if (provider == Provider.CUSTOM) "" else run {
      phase = Phase.Failed(CoachException.Kind.KEY, "")
      return
    }
    val turns = session.turns + Turn(true, question.trim())
    job = scope.launch {
      phase = Phase.Asking(provider)
      try {
        val r = Providers.ask(provider, asked, Effort.of(session.effort), key, keys.custom, session.system, turns, null)
        val cost = costOf(model, r)
        val s = session.copy(
          turns = turns + Turn(false, r.text, r.raw), input = session.input + r.input, output = session.output + r.output,
          cost = if (session.cost != null && cost != null) session.cost + cost else null,
        )
        sessions = sessions.map { if (it.id == s.id) s else it }
        persist()
        phase = Phase.Idle
      } catch (e: CancellationException) {
        phase = Phase.Idle
        throw e
      } catch (e: CoachException) {
        phase = Phase.Failed(e.kind, e.detail)
      }
    }
  }

  fun delete(id: String) {
    sessions = sessions.filter { it.id != id }
    persist()
  }

  private fun persist() {
    val text = EngineJson.encodeToString(StoredSessions(sessions))
    scope.launch(Dispatchers.IO) { runCatching { write(sessionsFile, text) } }
  }

  private fun write(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    tmp.renameTo(file)
  }

  companion object {
    /** Sessions kept: the latest. */
    const val KEEP = 5

    /** Rough tokens of a text: about one a CJK character and one per 3.5 other characters (the providers' tokenizers
     *  differ: Provider.tokenScale; an estimate). */
    fun tokens(text: String): Int {
      val cjk = text.count { it.code >= 0x2E80 }
      return (cjk * 1.3 + (text.length - cjk) / 3.5).toInt()
    }

  }
}

/** The instructions: one text in English (the models follow it best), the answer's language by the app's. */
fun systemPrompt(level: Level, zh: Boolean): String {
  val levelText = when (level) {
    Level.beginner -> "The player calls themself a beginner: write about blunders and mistakes only (losses of 0.25 and more), at most two habits, and explain any mahjong term you use."
    Level.mid -> "The player is intermediate: write about mistakes and blunders; questionable decisions only where they make a clear habit."
    Level.expert -> "The player is strong: include questionable decisions where they make a habit, and be precise about the margins."
  }
  val language = if (zh) {
    "Write everything in Traditional Chinese as used in Taiwan (zh-TW), with the usual Taiwanese mahjong words (聽牌, 放槍, 自摸, 向聽, 進張, 生張, 棄胡, 門清, 獨聽, 吃, 碰, 槓), and name tiles as the digest does (三萬, 五筒, 東, 白)."
  } else {
    "Write everything in US English. Name tiles as the digest does (3 Characters, 5 Dots, East, White). Use plain words for the terms: ready, deal in, self-draw, shanten, useful tiles, fold, concealed hand, single wait."
  }
  return """
You are a coach for Taiwanese 16-tile mahjong (台灣十六張麻將). The player uses a practice app whose neural network, trained by self-play and much stronger than the player, has reviewed every decision of the hands in the digest you receive. The digest gives the statistics the app computed and the decisions where the network found a loss or a best action that is not the obvious one; its header explains every figure and gives the rules of these hands. Your job: find the player's recurring habits across the hands, explain them in terms a person can use at the table, and suggest what to practise. You do not judge tiles yourself.

How to treat the data
- When the network says one action is better, take that as the answer. Your value is in explaining why in table terms (danger against an opponent likely ready, a pair that can be called and is worth a tai, a wait with more tiles left, speed against value) and in seeing patterns across many hands.
- Every figure is the network's estimate. Losses under 0.05 are its noise. A hand's result is mostly luck: judge decisions by their loss, never by whether the hand was won.
- Use only what the digest states. Do not invent tiles, waits, opponents' hands or positions, and do not work out tile efficiency or waits yourself: the digest gives them.
- A habit needs at least two decisions as evidence; something seen once is a single case, and say so.
- Cite decisions by their id exactly as written, such as [H9-73]: the app turns each into a link to that moment. Only ids that appear in the digest. A hand as a whole is H9, without brackets (it is not a link).
- The hands are numbered oldest first; if the later hands show a change, say so.

What the app's self-play showed (prefer these to common lore when you explain why)
- Reading an opponent: the number of melds and several discards in a row straight from the draw (摸切) are the signs of a ready hand; a call just made adds little by itself.
- Falling back: against an opponent very likely ready the network folds only about 20% of the time when one away and under 10% when ready; it turns to safety mostly when two or more away with fewer than 30 tiles left, or one away with fewer than 10.
- Safety (rates, not guarantees): a tile the opponent discarded deals in under 1%, an honor already seen about as rarely; a suji tile about a third to a half as often as an unseen tile of its rank; an unseen non-suji 4 to 6 is the most dangerous.
- Calls: one that brings the hand no closer mostly costs chances to win; late in the hand, one that does raises the chance of dealing in more than the chance of winning. Concealed and three or more away the network takes only about 60% of chows; two or fewer away it takes nearly every call that brings it closer. A pung of an honor pair helps to win (and may score a tai); it does not help defense.
- Early, with many tiles to win on, it often passes up a win on a discard to wait for a self-draw, mostly with a concealed hand.

Rules (these differ from Japanese riichi mahjong and from many house rules)
- 16 tiles: five sets and a pair. No riichi, no dora. Flowers are set aside and replaced.
- No furiten: a player may win on a tile they discarded themselves, so a tile an opponent discarded earlier is only less likely to deal in to them, not safe. The one lock is 過水: after passing up a win, a player cannot win on a discard until their own next draw.
- Only the tai items the digest lists score, with the values it gives; anything else is 0 tai here, even where other rules score it.

What to write
$levelText
Answer the first message with a JSON object:
- summary: one or two sentences on how the hands went, telling luck (results) apart from decisions (losses). If the player asked something, answer it here or in a habit.
- habits: two to four, the costliest first. Each: title, a few words; body, what the player tends to do and why the network's choice is better, with the ids in the text; refs, the ids of the evidence; rule, one rule of thumb to try at the table.
- strengths: one thing the player did well, judged by the decisions (few losses, or a best action that is not the obvious one which the player found), never by a hand won; with an id if there is one.
- practice: one concrete suggestion for practising in the app (for example, practice with the analysis shown and watch the opponents' chances of being ready).
Keep each habit under about 120 words (or 300 Chinese characters), in plain words: the reader may be an older casual player. No talk of money or betting.
$language
Later questions from the player: answer in plain text (not JSON), in the same language, short, citing ids the same way.
""".trim()
}
