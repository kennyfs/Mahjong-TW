package io.github.kennyfs.mahjong

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

  /** How long the tile a claim is taken on stays on the table before the claim's moment, ms, by the pace (the manual
   *  pace: until the player's tap; a branch, which has none, takes the middle one). */
  fun pauseMs(speed: Speed): Long = when (speed) { Speed.SLOW -> 2400L; Speed.FAST -> 900L; else -> 1600L }

  /** Whether `seat`, to play now, came to it by a chow or a pung (since the last discard): its discard then follows the
   *  call's moment (or words) at once, without the pace of a turn of its own; the moment already showed what it took. */
  fun justCalled(game: GameRecord, seat: Int): Boolean {
    for (k in game.actions.indices.reversed()) {
      val a = game.actions[k]
      val type = a[1].jsonPrimitive.content
      if (type == "discard" || type == "kakan") return false
      if ((type == "chi" || type == "pon") && a[0].jsonPrimitive.int == seat) return true
    }
    return false
  }

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
