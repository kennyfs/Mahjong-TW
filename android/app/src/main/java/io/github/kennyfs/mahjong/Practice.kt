package io.github.kennyfs.mahjong

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.random.Random

/**
 * Practice: the player (seat 0) against three bots, for one hand, one round (一圈) or a full game (一將). The app is the
 * authority here: it holds the record, lets the bots decide one decision point at a time (so each bot's turn shows on
 * its own), and shows the player only the view of seat 0. The web page's lib/practice.svelte.ts.
 */
class Practice(context: Context, private val scope: CoroutineScope, private val settings: Settings, private val records: Records,
  private val names: () -> List<String>, private val onError: (String) -> Unit) {

  @Serializable
  data class Meta(
    val id: String, val length: Length, val rules: String, val bot: String, val startDealer: Int, val startRound: Int = 0,
    /** Hands finished in this game. */
    val hands: Int = 0, val totals: List<Double> = listOf(0.0, 0.0, 0.0, 0.0), val over: Boolean = false,
    /** The hand already recorded (its wall's start and its record's length), so a restart does not record it again. */
    val recorded: String? = null,
  )

  @Serializable
  private data class Saved(val game: GameRecord, val meta: Meta)

  enum class Stage { PLAYING, RESULT, OVER }

  private val prefs = context.getSharedPreferences("practice", Context.MODE_PRIVATE)

  var game by mutableStateOf<GameRecord?>(null)
    private set
  var meta by mutableStateOf<Meta?>(null)
    private set
  /** What the player sees (seat 0's view, every event from the start). */
  var view by mutableStateOf<View?>(null)
    private set
  /** The finished hand, everything shown, on its result. */
  var final by mutableStateOf<View?>(null)
    private set
  var stage by mutableStateOf(Stage.PLAYING)
    private set
  /** The record of the hand just finished (its review and favorite). */
  var lastRecord by mutableStateOf<String?>(null)
    private set
  var busy by mutableStateOf(false)
    private set

  private var running = false
  private var epoch = 0

  /** Per seat: the bot (Settings' BOTS), or null for the player. */
  val bots: List<String?> get() = (meta?.bot ?: settings.bot).let { listOf(null, it, it, it) }

  /** The game in play from the last visit, or a new one. */
  fun resume() = scope.launch {
    val s = runCatching { EngineJson.decodeFromString<Saved>(prefs.getString("game", null) ?: "") }.getOrNull()
    if (s != null && !s.meta.over && s.meta.rules == settings.spec) {
      game = s.game
      meta = s.meta
      stage = Stage.PLAYING
      refresh()
      if (view?.phase == "over") finish() else run()
    } else newGameNow()
  }

  private fun persist() {
    val g = game
    val m = meta
    if (g != null && m != null) prefs.edit().putString("game", EngineJson.encodeToString(Saved(g, m))).apply()
  }

  fun newGame() = scope.launch { newGameNow() }

  private suspend fun newGameNow() {
    epoch++
    val startDealer = Random.nextInt(4)
    meta = Meta(Records.newId(), settings.length, settings.spec, settings.bot, startDealer)
    final = null
    lastRecord = null
    stage = Stage.PLAYING
    try {
      game = Engine.newHand(settings.spec, Setup(dealer = startDealer))
    } catch (e: EngineException) {
      onError(e.message ?: "")
      return
    }
    persist()
    refresh()
    run()
  }

  private suspend fun refresh() {
    val g = game ?: return
    view = Engine.view(g, ME)
  }

  /** The bots decide, a decision point at a time, until the player must decide or the hand is over. */
  private fun run() {
    if (running) return
    running = true
    val e = epoch
    scope.launch {
      try {
        while (e == epoch) {
          val g = game ?: break
          val p = Engine.pending(g)
          if (p.phase == "over") {
            finish()
            break
          }
          if (ME in p.pending) break
          busy = true
          // A bot's own turn takes the pace; deciding on a discard (nobody else may) takes a short beat.
          delay(if (p.phase == "self") settings.speed.ms else minOf(250L, settings.speed.ms / 2))
          if (e != epoch || game == null) break
          val seed = "${meta?.id}/${meta?.hands}/${g.actions.size}"
          game = Engine.advance(g, bots, seed, 1)
          persist()
          refresh()
        }
      } catch (ex: EngineException) {
        onError(ex.message ?: "")
      } finally {
        busy = false
        running = false
      }
    }
  }

  /** The player's decision (one of view.legal). */
  fun act(action: Action) = scope.launch {
    val g = game ?: return@launch
    if (view?.myTurn != true || running) return@launch
    try {
      game = Engine.act(g, ME, action)
    } catch (e: EngineException) {
      onError(e.message ?: "")
      return@launch
    }
    persist()
    refresh()
    run()
  }

  private suspend fun finish() {
    val g = game ?: return
    val m = meta ?: return
    val f = Engine.view(g, ME, 0, reveal = true)
    final = f
    val result = f.result ?: return
    if (stage != Stage.PLAYING) return
    val key = "${g.wall.take(24)}/${g.actions.size}"
    if (m.recorded == key) {
      // After a restart: the hand's record (this game's, by its number) for the review and favorite on the result, and
      // the summary if the restart cut it short.
      val rec = records.ofGroup(m.id).firstOrNull { it.index == m.hands }
      lastRecord = rec?.id
      if (rec != null) scope.launch { keepSummary(records, rec) }
      stage = Stage.RESULT
      return
    }
    val hands = m.hands + 1
    val rec = Records.handRecord(RecordKind.practice, g, result, ME, names(), bots, m.id, hands)
    records.add(rec)
    lastRecord = rec.id
    // The network's review of the hand, in short, for the result's sheet and the records.
    scope.launch { keepSummary(records, rec) }
    meta = m.copy(recorded = key, hands = hands, totals = m.totals.mapIndexed { i, t -> t + result.points[i] },
      over = m.length == Length.HAND)
    stage = Stage.RESULT
    persist()
  }

  /** The next hand of the game, or its end. */
  fun nextHand() = scope.launch {
    val g = game ?: return@launch
    val m = meta ?: return@launch
    if (stage != Stage.RESULT) return@launch
    if (m.over) {
      stage = Stage.OVER
      persist()
      return@launch
    }
    val (next, gameOver) = Engine.nextHand(g, m.startDealer)
    val roundOver = next.setup.round != g.setup.round
    if (gameOver || (m.length == Length.ROUND && roundOver)) {
      meta = m.copy(over = true)
      stage = Stage.OVER
      persist()
      return@launch
    }
    epoch++
    game = next
    final = null
    stage = Stage.PLAYING
    persist()
    refresh()
    run()
  }

  /** The settings changed (rules, length, bot): a new game when they differ from this game's. */
  fun settingsChanged() {
    val m = meta
    if (m == null || m.rules != settings.spec || m.length != settings.length || m.bot != settings.bot) newGame()
  }

  companion object {
    const val ME = 0
  }
}
