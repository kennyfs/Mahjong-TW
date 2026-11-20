package io.github.kennyfs.mahjong

import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Before the player is asked about another's tile (a chow, a pung, a kong, a win on a discard or on an added kong): the
 * computer players that must decide on the same tile decide first. When one of their claims comes before every claim
 * the player could make (a pung before a chow, a win before either), it is made at once and the player is not asked:
 * the table shows it as taken from them (搶碰, 搶胡). Otherwise their decisions stay in the record and the player is
 * asked as before (and whatever they choose stands against them). A computer player's claim taken by another's shows
 * as an ordinary one: the computer players do not learn of claims that did not stand either. The web page's
 * lib/claims.ts.
 */
object Claims {
  /** The claim that came first, and the player's options it beat. */
  class Robbed(val seat: Int, val type: String, val options: List<String>)
  class Preclaim(val game: GameRecord, val robbed: Robbed?)

  /** How long the tile a claim is taken on stays on the table before the claim's moment, ms, by the pace (Timing; the
   *  manual pace: until the player's tap; a branch, which has none, takes the normal one): the player sees what was
   *  discarded before it is taken. */
  fun robPause(): Long = Timing.ms("rob_show")

  /** Whether a step of the game (its view, `before` events before it) is a computer player's call on another's tile (a
   *  chow, a pung, a kong, a win): that one shows after the tile's stay (`bot_claim`); a step with none shows at once, as
   *  there would be no pause where nobody could call, so that a pause never tells the player that someone could. */
  fun othersCall(view: View, before: Int, me: Int): Boolean =
    view.events.takeLast((view.eventCount - before).coerceIn(0, view.events.size)).any { it.seat != me && it.type in setOf("chi", "pon", "kan", "ron") }

  /** Whether `seat`, to play now, came to it by a chow or a pung (since the last discard): its discard then takes the
   *  pace that follows a call (bot_after_call), counted from the end of the call's moment (or words). */
  fun justCalled(game: GameRecord, seat: Int): Boolean {
    for (k in game.actions.indices.reversed()) {
      val a = game.actions[k]
      val type = a[1].jsonPrimitive.content
      if (type == "discard" || type == "kakan") return false
      if ((type == "chi" || type == "pon") && a[0].jsonPrimitive.int == seat) return true
    }
    return false
  }

  /** The type of the last action of `game` (a step of the computer players: what it did). */
  fun lastType(game: GameRecord): String? = game.actions.lastOrNull()?.get(1)?.jsonPrimitive?.content

  /** null: nothing to decide first (the player's own turn, or the player is not to decide). */
  suspend fun preclaim(game: GameRecord, me: Int, bots: List<String?>, seed: String): Preclaim? {
    val p = Engine.pending(game)
    if (p.phase == "self" || p.phase == "over" || me !in p.pending) return null
    // Those still to decide (a branch from the player's decision may have the others' answers already in its record:
    // they are weighed the same).
    val others = p.pending.filter { it != me && bots.getOrNull(it) != null }
    // Each decides on the game as it stands (none sees another's answer), then the answers go in.
    val answers = coroutineScope { others.map { s -> async { Engine.bot(game, s, bots[s]!!, "$seed/$s") } }.awaitAll() }
    var g = game
    for ((i, s) in others.withIndex()) g = Engine.act(g, s, answers[i])
    // Nobody else claims (now or before): the player is asked.
    val since = (game.actions.size - 1 downTo 0).firstOrNull { k ->
      game.actions[k][1].jsonPrimitive.content.let { it == "discard" || it == "kakan" }
    } ?: -1
    val before = game.actions.drop(since + 1).any { it[1].jsonPrimitive.content != "pass" }
    if (answers.all { it.type == "pass" } && !before) return Preclaim(g, null)
    // Would any of the player's claims stand?
    val v = Engine.view(g, me)
    val options = v.legal.filter { it.type != "pass" }
    for (o in options) {
      val tried = Engine.act(g, me, o)
      if (Engine.steps(tried).taken.getOrNull(tried.actions.size - 1) == true) return Preclaim(g, null)
    }
    val done = Engine.act(g, me, Action("pass"))
    val st = Engine.steps(done)
    val at = done.actions.indices.firstOrNull { i ->
      i > since && st.taken.getOrNull(i) == true && done.actions[i][1].jsonPrimitive.content != "pass"
    }
    val won = at?.let { done.actions[it] }
    return Preclaim(done, won?.let { Robbed(it[0].jsonPrimitive.int, it[1].jsonPrimitive.content, options.map { o -> o.type }.distinct()) })
  }

  /** The event (an index into a view's events) the claim taken became: the first call or win of its seat after `from`
   *  events. */
  fun robbedEvent(events: List<GameEvent>, from: Int, seat: Int): Int =
    (from until events.size).firstOrNull { events[it].seat == seat && events[it].type in setOf("chi", "pon", "kan", "ron") } ?: -1
}

/**
 * The computer players' pace as the table shows it (Practice, BranchRun; the web page's lib/claims.ts BotPace). A
 * computer player's own turn takes its pace (bot_turn; after its own chow or pung bot_after_call), counted from when it
 * came, once the words or moment of what came before are over. A discard of theirs flies in to their river in the last
 * discard_move of it (when the moments show: ui/HandMotion.kt), never before the tile before it has landed, and the turn
 * is over when it lands (with a pace of 900 and a flight of 500: still for 400, then 500 in flight). What they answer
 * to a tile, a call (bot_claim) or a claim taken from the player (rob_show), shows that long after the tile came, its
 * flight included, or after the words or moment over it.
 */
class BotPace {
  /** When the last discard (or added kong) came, or the words or moment over it ended; when the last discard lands. */
  private var shownAt = 0L
  private var landAt = 0L
  private fun now() = SystemClock.uptimeMillis()

  /** A discard's flight to its river, ms: discard_move when the moments show; else none (it is there at once). */
  fun flight(): Long = if (FxClock.enabled()) Timing.ms("discard_move") else 0L

  /** Another game: nothing of the last one's tiles to wait for. */
  fun reset() {
    shownAt = 0L
    landAt = 0L
  }

  /** The table's view of `count` events: returns once the table has it and the words and moments it brings are over,
   *  their sounds heard (Sounds.awaitSynced). */
  suspend fun settle(count: Int) {
    CalloutClock.settle(count)
    val words = CalloutClock.done()
    val t = now()
    Sounds.awaitSynced()
    if (words || now() - t > 15L) shownAt = now()
  }

  /** What `next` added came now: a discard (in flight for `fly`) or an added kong is a tile the others may answer. */
  fun came(next: GameRecord, fly: Long) {
    val type = Claims.lastType(next)
    if (type != "discard" && type != "kakan") return
    shownAt = now()
    landAt = shownAt + (if (type == "discard") fly else 0L)
  }

  /** A computer player's own turn (`seat`, in `game`; `next`: what it does), come at `from`: waits its pace, less the
   *  flight of its discard. Returns that flight (0: none, or not a discard). */
  suspend fun turn(game: GameRecord, seat: Int, next: GameRecord, from: Long): Long {
    val fly = if (Claims.lastType(next) == "discard") flight() else 0L
    val pace = Timing.ms(if (Claims.justCalled(game, seat)) "bot_after_call" else "bot_turn")
    delay(maxOf(0L, from + pace - fly - now(), landAt - now()))
    return fly
  }

  /** A computer player's call on the tile: after its stay (bot_claim). */
  suspend fun call() = delay(maxOf(0L, shownAt + Timing.ms("bot_claim") - now(), landAt - now()))

  /** A claim taken from the player: after the tile's stay (rob_show; Claims.robPause). */
  suspend fun rob() = delay(maxOf(0L, shownAt + Claims.robPause() - now(), landAt - now()))

  /** The tile just discarded lands, and is heard landing: a computer player's turn is over then. */
  suspend fun land() {
    delay(maxOf(0L, landAt - now()))
    Sounds.awaitSynced()
  }
}
