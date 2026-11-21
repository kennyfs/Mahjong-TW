package io.github.kennyfs.mahjong

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.random.Random

/**
 * Practice: the player (seat 0) against three bots, for one hand, one round (一圈) or a full game (一將). The app is the
 * authority here: it holds the record, lets the bots decide one decision point at a time (so each bot's turn shows on
 * its own), and shows the player only the view of seat 0. The web page's lib/practice.svelte.ts.
 *
 * The computer players' pace is the settings': a pause before each of their turns, or MANUAL: nothing of theirs happens
 * until the player says so (`step`). One step is one thing that shows on the table, as the table settles it: a discard
 * (with the draw before it), a call, a declaration, a win. What the three answer to a discard is settled together, by
 * the rules' precedence, before any of it shows (a chow that a pung or a win comes before never shows), so what the
 * next step will be is worked out ahead and held. Only what shows waits: when all three let a discard go, the game
 * goes on by itself (a wait there would say that one of them could have called). The player's own call is answered
 * at once, also when another's call comes before it (`notice` says so).
 */
class Practice(private val context: Context, private val scope: CoroutineScope, private val settings: Settings, private val records: Records,
  private val names: () -> List<String>, private val onError: (String) -> Unit) {

  @Serializable
  data class Meta(
    val id: String, val length: Length, val rules: String, val bot: String, val startDealer: Int, val startRound: Int = 0,
    /** Hands finished in this game. */
    val hands: Int = 0, val totals: List<Double> = listOf(0.0, 0.0, 0.0, 0.0), val over: Boolean = false,
    /** The hand already recorded (its wall's start and its record's length), so a restart does not record it again. */
    val recorded: String? = null,
    /** The hand in play had the analysis shown (Settings.practiceHints) at some point: it counts for nothing (finish). */
    val hinted: Boolean = false,
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
  /** The manual pace: the computer players' next step is ready and waits for `step`. */
  var waiting by mutableStateOf(false)
    private set
  /** A few words on the table for a moment: the player's call did not stand, or was taken before they were asked. */
  var notice by mutableStateOf<String?>(null)
    private set
  /** The event (an index into the view's events) that was a claim taken from the player (Claims): its moment says
   *  搶碰, 搶胡. -1: none. */
  var robbed by mutableStateOf(-1)
    private set
  /** The hand just begun (its id), for the table to deal (TableView `deal`); null for a hand taken up again. */
  var deal by mutableStateOf<String?>(null)
    private set

  private var running = false
  private var epoch = 0
  /** The loop running (`run`): its number and its game's epoch. One of a game gone (another dealt while it waited out a
   *  pace or a moment) ends at its next look; it holds back neither another game's loop nor its state. (It held back
   *  the new game's: after 重新開始 the dealer, a computer player, never played.) */
  private var loop = 0
  private var loopEpoch = -1
  /** This game's loop is running. */
  private val looping: Boolean get() = running && loopEpoch == epoch
  /** The step held (the game after it, and the player's view of it), for the game of this many actions. */
  private class Ahead(val from: Int, val game: GameRecord, val view: View, val robbed: Int = -1, val say: String? = null)
  private var ahead: Ahead? = null
  /** The call the player just made on another's tile, until what came of it is known. */
  private var claimed: String? = null
  /** The computer players' pace (Claims.kt BotPace): their turns, their discards' flights, their answers to a tile. */
  private val pace = BotPace()
  private var noticeJob: Job? = null
  private var forcedJob: Job? = null

  /** Another game or hand: nothing held from the last. */
  private fun reset() {
    epoch++
    forcedJob?.cancel()
    forcedJob = null
    // (What the last game's loop waits for goes at once: it sees the game is gone.)
    CalloutClock.release()
    pace.reset()
    ahead = null
    waiting = false
    claimed = null
    robbed = -1
    say(null)
  }

  /** The words once the moment that shows the same is over (they would be under its veil). */
  private fun sayAfterFx(text: String) {
    val e = epoch
    scope.launch {
      FxClock.done()
      if (e == epoch) say(text)
    }
  }

  private fun say(text: String?) {
    noticeJob?.cancel()
    notice = text
    if (text != null) noticeJob = scope.launch {
      delay(Timing.ms("notice"))
      notice = null
    }
  }

  /** What of `view`'s events since `before` of them shows on the table as a player's doing. */
  private fun shownSince(view: View, before: Int) = view.events.takeLast((view.eventCount - before).coerceIn(0, view.events.size)).filter { it.type in SHOWN }

  /** Another's call that came before the player's own (among what just showed), said. */
  private fun sayOverridden(fresh: List<GameEvent>): Boolean {
    val mine = claimed ?: return false
    if (fresh.any { it.seat == ME }) return false
    val over = fresh.firstOrNull { it.seat != ME && it.type != "discard" } ?: return false
    sayAfterFx(context.getString(R.string.claim_overridden, context.getString(actionWord(mine)), names().getOrElse(over.seat) { "" },
      context.getString(actionWord(over.type))))
    return true
  }

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
    reset()
    // (The store's screenshots: a fixed game, the player dealing first. StoreMode.)
    val store = StoreMode.seed
    val startDealer = if (store != null) 0 else Random.nextInt(4)
    meta = Meta(store ?: Records.newId(), settings.length, settings.spec, settings.bot, startDealer)
    final = null
    lastRecord = null
    stage = Stage.PLAYING
    try {
      game = Engine.newHand(settings.spec, Setup(dealer = startDealer), StoreMode.firstDeal())
    } catch (e: EngineException) {
      onError(e.message ?: "")
      return
    }
    persist()
    refresh()
    dealt("${meta?.id}/0")
    run()
  }

  /** A hand begun: the table deals it (DealFx), and the computer players wait until the player's hand has turned
   *  face up there (DealClock): however long that is, also when the table is not on the screen yet (a new game from
   *  the settings: dealt when the player comes back to it). */
  private fun dealt(id: String) {
    deal = id
    if (FxClock.enabled()) DealClock.arm(id) else DealClock.reset()
  }

  private suspend fun refresh() {
    val g = game ?: return
    view = Engine.view(g, ME)
  }

  /** A declared ready hand (天聽, 地聽, 報聽) whose only choice is the tile it drew: discarded by itself at a computer
   *  player's pace (bot_turn), as there is nothing to decide. A win or a concealed kong it may make waits for the
   *  player. */
  private fun forcedDiscard(e: Int) {
    val v = view ?: return
    val g = game ?: return
    val a = Claims.forcedDiscardAction(v, ME) ?: return
    val n = g.actions.size
    forcedJob?.cancel()
    forcedJob = scope.launch {
      DealClock.awaitReady()
      if (e != epoch) return@launch
      delay(Timing.ms("bot_turn"))
      if (e == epoch && game?.actions?.size == n && view?.myTurn == true) act(a)
    }
  }

  /** The bots decide, a decision point at a time, until the player must decide or the hand is over. Each bot's turn
   *  waits the pace of the settings (Timing), and the words or moment of a call (CalloutClock), so it can be followed;
   *  at the manual pace, what shows waits for `step` (see the top). */
  private fun run() {
    if (looping) return
    val my = ++loop
    running = true
    val e = epoch
    loopEpoch = e
    scope.launch {
      try {
        while (e == epoch) {
          val g = game ?: break
          val p = Engine.pending(g)
          if (p.phase == "over") {
            finish()
            break
          }
          val seed = "${meta?.id}/${meta?.hands}/${g.actions.size}"
          val before = view?.eventCount ?: 0
          if (ME in p.pending) {
            claimed = null
            if (p.phase == "self") {
              forcedDiscard(e)
              break
            }
            // Another's tile the player could claim: the computer players decide on it first (Claims), the player's
            // buttons held back meanwhile.
            busy = true
            val pc = Claims.preclaim(g, ME, bots, seed) ?: break
            if (e != epoch || game !== g) break
            val r = pc.robbed
            if (r == null) {
              game = pc.game
              persist()
              refresh()
              break
            }
            // A claim came before all of the player's: made at once, as the computer players' step, after the discard
            // has stayed a while (counted from when it showed).
            val v = Engine.view(pc.game, ME)
            if (e != epoch || game !== g) break
            val at = Claims.robbedEvent(v.events, before, r.seat)
            val text = context.getString(R.string.claim_robbed, names().getOrElse(r.seat) { "" }, context.getString(actionWord(r.type)),
              r.options.joinToString(Words.list) { context.getString(actionWord(it)) })
            if (settings.speed == Speed.MANUAL) {
              ahead = Ahead(g.actions.size, pc.game, v, at, text)
              waiting = true
              break
            }
            // The discard stays a while first (rob_show, from when it came: BotPace), its words done.
            pace.settle(before)
            pace.rob()
            if (e != epoch || game !== g) break
            robbed = at
            game = pc.game
            view = v
            sayAfterFx(text)
            persist()
            continue
          }
          if (settings.speed == Speed.MANUAL) {
            val next = Engine.advance(g, bots, seed, 1)
            val v = Engine.view(next, ME)
            if (e != epoch || game !== g) break
            val fresh = shownSince(v, before)
            val mine = fresh.any { it.seat == ME }
            // Another's call came before the player's own: said at once, as the answer to the player's tap.
            val over = !mine && sayOverridden(fresh)
            if (fresh.isNotEmpty() && !mine && !over) {
              ahead = Ahead(g.actions.size, next, v)
              waiting = true
              break
            }
            claimed = null
            game = next
            view = v
            persist()
            continue
          }
          busy = true
          // A bot's own turn takes its pace (bot_turn; after its own chow or pung bot_after_call), its discard flying
          // in to the river in the last of it, and is over when the tile lands. Deciding on a discard (nobody else may):
          // a call shows after the tile's stay (bot_claim), no call at once (Claims.othersCall). Each counts once a
          // call's moment or words (碰, 槓, 天聽) are over (a touch anywhere lets them go). (BotPace.)
          val claim = p.phase != "self"
          DealClock.awaitReady()
          if (e != epoch || game == null) break
          pace.settle(before)
          if (e != epoch || game == null) break
          val from = android.os.SystemClock.uptimeMillis()
          val next = Engine.advance(g, bots, seed, 1)
          var nextView: View? = null
          var fly = 0L
          if (claim) {
            val v = Engine.view(next, ME)
            if (e != epoch) break
            if (Claims.othersCall(v, before, ME)) pace.call()
            nextView = v
          } else fly = pace.turn(g, p.current, next, from)
          // (Another game came meanwhile: this one's step is no more.)
          if (e != epoch) break
          game = next
          pace.came(next, fly)
          persist()
          if (nextView != null) view = nextView else refresh()
          if (claimed != null) {
            view?.let { sayOverridden(shownSince(it, before)) }
            claimed = null
          }
          if (fly > 0) {
            pace.land()
            if (e != epoch) break
          }
        }
      } catch (ex: EngineException) {
        onError(ex.message ?: "")
      } finally {
        if (loop == my) {
          busy = false
          running = false
        }
      }
    }
  }

  /** The manual pace: the computer players' next step, now. */
  fun step() {
    val a = ahead ?: return
    val g = game ?: return
    if (!waiting || looping || a.from != g.actions.size) return
    ahead = null
    waiting = false
    if (a.robbed >= 0) robbed = a.robbed
    game = a.game
    view = a.view
    a.say?.let { sayAfterFx(it) }
    persist()
    run()
  }

  /** The pace changed: a step held for the manual pace goes on by itself at another. */
  fun paceChanged() {
    if (settings.speed != Speed.MANUAL && waiting) {
      ahead = null
      waiting = false
      run()
    }
  }

  /** The player's decision (one of view.legal). */
  fun act(action: Action) = scope.launch {
    val g = game ?: return@launch
    val v = view
    if (v?.myTurn != true || looping) return@launch
    say(null)
    // A call on another's tile may yet give way to a call that comes before it.
    claimed = if (v.phase != "self" && action.type != "pass") action.type else null
    try {
      val next = Engine.act(g, ME, action)
      // The others decided first (Claims): the claim is settled now. Another's that came first is the moment's 搶.
      val w = Engine.view(next, ME)
      if (claimed != null) {
        val fresh = shownSince(w, v.eventCount)
        if (fresh.none { it.seat == ME }) fresh.firstOrNull { it.seat != ME && it.type != "discard" }?.let { robbed = w.events.indexOf(it) }
        sayOverridden(fresh)
        claimed = null
      }
      game = next
      // The player's discard flies to the river: the computer player after them discards once it has landed (BotPace).
      pace.came(next, pace.flight())
      view = w
    } catch (e: EngineException) {
      onError(e.message ?: "")
      return@launch
    }
    persist()
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
    // A hand played with the analysis shown counts for nothing: not in the totals, nor the records' statistics.
    val rec = Records.handRecord(RecordKind.practice, g, result, ME, names(), bots, m.id, hands).let { if (m.hinted) it.copy(hints = true) else it }
    records.add(rec)
    lastRecord = rec.id
    // The network's review of the hand, in short, for the result's sheet and the records.
    scope.launch { keepSummary(records, rec) }
    meta = m.copy(recorded = key, hands = hands, totals = if (m.hinted) m.totals else m.totals.mapIndexed { i, t -> t + result.points[i] },
      over = m.length == Length.HAND)
    stage = Stage.RESULT
    persist()
  }

  /** The analysis is shown on the hand in play (Settings.practiceHints): it counts for nothing (finish), and keeps it
   *  till its end (turned off meanwhile it would only have been a peek at one turn). */
  fun markHinted() {
    val m = meta ?: return
    if (m.hinted || stage != Stage.PLAYING) return
    meta = m.copy(hinted = true)
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
    val (next, gameOver) = Engine.nextHand(g, m.startDealer, StoreMode.nextDeal(m.hands))
    val roundOver = next.setup.round != g.setup.round
    if (gameOver || (m.length == Length.ROUND && roundOver)) {
      meta = m.copy(over = true)
      stage = Stage.OVER
      persist()
      return@launch
    }
    reset()
    game = next
    meta = m.copy(hinted = false)
    final = null
    stage = Stage.PLAYING
    persist()
    refresh()
    dealt("${m.id}/${m.hands}")
    run()
  }

  /** The settings changed (rules, length, bot): a new game when they differ from this game's. */
  fun settingsChanged() {
    val m = meta
    if (m == null || m.rules != settings.spec || m.length != settings.length || m.bot != settings.bot) newGame()
  }

  companion object {
    const val ME = 0
    /** What shows on the table as a player's doing (a draw and a flower are not waited for). */
    private val SHOWN = setOf("discard", "chi", "pon", "kan", "ankan", "kakan", "declare", "tsumo", "ron", "flower_win")

    private fun actionWord(type: String) = when (type) {
      "ron" -> R.string.act_ron
      "tsumo" -> R.string.act_tsumo
      "kan" -> R.string.act_kan
      "ankan" -> R.string.act_ankan
      "kakan" -> R.string.act_kakan
      "pon" -> R.string.act_pon
      "chi" -> R.string.act_chi
      "declare" -> R.string.act_declare
      else -> R.string.act_pass
    }
  }
}
