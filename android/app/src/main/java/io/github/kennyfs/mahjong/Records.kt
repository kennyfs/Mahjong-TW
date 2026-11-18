package io.github.kennyfs.mahjong

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.SecureRandom
import java.time.Instant

// Every finished hand, kept as its record (rules, setup, wall, decisions) with who played which seat, so that it can
// be replayed and each decision analyzed; battles as groups of their hands. In a file of the app's, newest last. The
// web page's lib/records.svelte.ts, in the same form.

enum class RecordKind { practice, table, contest }

/** A hand's review by the network, in short (Review.kt), kept with the record for the lists and the statistics. */
@Serializable
data class ReviewSummary(
  /** The network that reviewed it (Engine.MODEL_ID): another one reviews it again. */
  val m: String,
  /** The expected result at the first decision, the points lost by decisions, and the rest of the result: luck. */
  val first: Double, val loss: Double, val luck: Double,
  /** A typical payment under the hand's rules, which the grades are scaled by. */
  val unit: Double,
  /** Decisions that had a choice; how many were questionable, mistakes, blunders. */
  val n: Int, val grades: List<Int> = listOf(0, 0, 0),
  /** The points lost by kind of decision (LossKind: tile, danger, call, win). */
  val kinds: List<Double> = listOf(0.0, 0.0, 0.0, 0.0),
)

@Serializable
data class HandRecord(
  val id: String,
  /** When the hand ended (ISO 8601). */
  val time: String,
  val kind: RecordKind,
  val game: GameRecord,
  /** The player's seat. */
  val me: Int,
  val names: List<String>,
  /** Per seat: the bot's spec, or null for a person. */
  val bots: List<String?>,
  val points: List<Double>,
  val winners: List<Int>,
  val loser: Int,
  val selfDraw: Boolean,
  val exhausted: Boolean,
  /** The first winner's tai. */
  val tai: Int,
  /** The practice game or the battle it belongs to, and its number there (from 1). */
  val group: String? = null,
  val index: Int? = null,
  val fav: Boolean = false,
  val rev: ReviewSummary? = null,
  /** Played with the analysis shown (practice's 顯示分析): kept to review, out of the statistics and the game's totals. */
  val hints: Boolean = false,
)

@Serializable
data class BattleGroup(
  val id: String,
  val time: String,
  val kind: RecordKind,
  val via: String,
  /** The rules' full spec. */
  val rules: String,
  val names: List<String>,
  /** The player's place in `names`. */
  val me: Int,
  val totals: List<Double>,
  val hands: Int,
  val left: Boolean = false,
  val fav: Boolean = false,
)

/** The records as exported (the records page's share and save buttons): the same on the web page. */
@Serializable
data class ExportedRecords(
  val app: String, val version: String, val time: String, val hands: List<HandRecord>, val battles: List<BattleGroup>,
)

/** What an import did (Records.import): the hands added, the hands already here, the oldest past MAX not kept. */
data class Imported(val added: Int, val had: Int, val dropped: Int)

/** A file that is not records exported by the app or the page. */
class BadRecordsFile(cause: Throwable? = null) : IllegalArgumentException(cause)

@Serializable
private data class StoredRecords(val hands: List<HandRecord> = emptyList(), val battles: List<BattleGroup> = emptyList())

class Records(context: Context) {
  private val file = File(context.filesDir, "records.json")
  private val stored = runCatching { EngineJson.decodeFromString<StoredRecords>(file.readText()) }.getOrDefault(StoredRecords())

  var hands by mutableStateOf(stored.hands)
    private set
  var battles by mutableStateOf(stored.battles)
    private set

  fun add(r: HandRecord) {
    if (hands.any { it.id == r.id }) return
    var list = hands + r
    if (list.size > MAX) {
      var drop = list.size - MAX
      list = list.filter { it.fav || drop-- <= 0 }
    }
    hands = list
    persist()
  }

  fun addBattle(b: BattleGroup) {
    battles = battles.filter { it.id != b.id } + b
    persist()
  }

  fun setFav(id: String, fav: Boolean) {
    hands = hands.map { if (it.id == id) it.copy(fav = fav) else it }
    persist()
  }

  fun setReview(id: String, rev: ReviewSummary) {
    hands = hands.map { if (it.id == id) it.copy(rev = rev) else it }
    persist()
  }

  fun setBattleFav(id: String, fav: Boolean) {
    battles = battles.map { if (it.id == id) it.copy(fav = fav) else it }
    persist()
  }

  /** Everything but the favorites (and the battles holding one). */
  fun clear() {
    hands = hands.filter { it.fav }
    val keep = hands.mapNotNull { it.group }.toSet()
    battles = battles.filter { it.fav || it.id in keep }
    persist()
  }

  /** Every record, as a file to hand on (to find a bug in a hand played, or to another phone or version). */
  fun exportText(version: String): String =
    EngineJson.encodeToString(ExportedRecords(APP, version, Instant.now().toString(), hands, battles))

  /** The records of a file (`parse`) added to these: see `merge`. */
  fun import(file: ExportedRecords): Imported {
    val m = merge(hands, battles, file)
    if (m.hands != hands || m.battles != battles) {
      hands = m.hands
      battles = m.battles
      persist()
    }
    return m.imported
  }

  fun get(id: String) = hands.firstOrNull { it.id == id }

  fun ofGroup(group: String) = hands.filter { it.group == group }.sortedBy { it.index ?: 0 }

  private fun persist() {
    val text = EngineJson.encodeToString(StoredRecords(hands, battles))
    runCatching {
      val tmp = File(file.parentFile, "records.json.tmp")
      tmp.writeText(text)
      tmp.renameTo(file)
    }
  }

  companion object {
    /** Records kept at most: the oldest that are not favorites go first. */
    const val MAX = 3000

    /** The exported file's `app`. */
    const val APP = "mahjong-tw"

    /** A records file's text (exportText, or the page's): BadRecordsFile when it is not one. */
    fun parse(text: String): ExportedRecords {
      val file = try {
        EngineJson.decodeFromString<ExportedRecords>(text)
      } catch (e: Exception) {
        throw BadRecordsFile(e)
      }
      if (file.app != APP) throw BadRecordsFile()
      return file
    }

    class Merged(val hands: List<HandRecord>, val battles: List<BattleGroup>, val imported: Imported)

    /**
     * A file's records added to these. A hand or a battle already here (its id) stays as it is, the file's copy
     * skipped; the rest go in by when they ended, so the lists stay newest last. Past MAX the oldest that are not
     * favorites go, as when playing. A hand's review comes with it (`rev`): kept when the same network made it.
     */
    fun merge(hands: List<HandRecord>, battles: List<BattleGroup>, file: ExportedRecords): Merged {
      val ids = hands.mapTo(HashSet()) { it.id }
      val new = file.hands.filter { ids.add(it.id) }
      val battleIds = battles.mapTo(HashSet()) { it.id }
      val newBattles = file.battles.filter { battleIds.add(it.id) }
      if (new.isEmpty() && newBattles.isEmpty()) return Merged(hands, battles, Imported(0, file.hands.size, 0))
      var list = (hands + new).sortedBy { instantOf(it.time) }
      val all = list.size
      if (all > MAX) {
        var drop = all - MAX
        list = list.filter { it.fav || drop-- <= 0 }
      }
      return Merged(list, (battles + newBattles).sortedBy { instantOf(it.time) }, Imported(new.size, file.hands.size - new.size, all - list.size))
    }

    /** A record's time; one that cannot be read as the oldest. */
    private fun instantOf(time: String): Instant = runCatching { Instant.parse(time) }.getOrDefault(Instant.EPOCH)

    private val random = SecureRandom()

    fun newId(): String = ByteArray(9).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** A record of a finished hand. */
    fun handRecord(
      kind: RecordKind, game: GameRecord, result: HandResult, me: Int, names: List<String>, bots: List<String?>,
      group: String? = null, index: Int? = null,
    ) = HandRecord(
      newId(), Instant.now().toString(), kind, game, me, names, bots, result.points, result.winners.map { it.seat }, result.loser,
      result.selfDraw, result.exhausted, result.winners.firstOrNull()?.tai ?: 0, group, index,
    )
  }
}
