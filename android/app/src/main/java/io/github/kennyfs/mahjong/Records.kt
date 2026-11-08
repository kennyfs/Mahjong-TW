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
