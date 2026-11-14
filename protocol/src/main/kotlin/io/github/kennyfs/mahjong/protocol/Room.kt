package io.github.kennyfs.mahjong.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Where a room sends a player's messages: a WebSocket, a Nearby endpoint, or the hosting phone's own screen. */
interface Peer {
  fun send(msg: ServerMsg)
  /** The phone's own player, on a room hosted on a phone. */
  val local: Boolean get() = false
}

/**
 * One room: its players, its tables and their results. Every change runs on one coroutine, in the order the events
 * arrive (an actor), so the state needs no locks. What keeps the game fair:
 *
 * - The wall is shuffled by the engine from a seed made here with a SecureRandom, and stays here: a player is only
 *   ever sent the engine's view of their own seat (their tiles, what everyone may see, their legal actions).
 * - A decision is accepted only from the seat that must decide now, at the decision point it was shown (`step`),
 *   with a checked action the engine finds legal. Points are the engine's, never a client's.
 * - A player who runs out of time, or left, gets the room's bot's decision. At a table of two or more people, after
 *   every discard the room waits the same time (Pace.claimWindowMs) whether anyone may claim it or not, so the pause
 *   tells nobody who could. Bots play at the pace the screens show practice at (Pace).
 * - The engine is only asked what the room needs, within a budget per hand.
 */
class Room(
  val id: String,
  val pin: String,
  settings: RoomSettings,
  rules: String,
  private val engine: GameEngine,
  private val scope: CoroutineScope,
  private val random: SecureRandom,
  private val now: () -> Long,
  private val maxPlayers: Int,
  private val onClosed: (Room) -> Unit,
  private val pace: Pace,
  /** The bots this room can play (RoomSettings.bot to the engine's spec; HubLimits.bots). */
  private val bots: Map<String, String>,
) {
  var settings = settings; private set
  var rules = rules; private set
  val createdAt = now()
  @Volatile var lastActivity = createdAt; private set
  @Volatile var phase = Phase.LOBBY; private set
  @Volatile var closed = false; private set

  /** `tokenHash`: SHA-256 of the player's secret token (Base64), which only the player has. */
  private class Player(val id: Int, val name: String, val tokenHash: String, val bot: Boolean) {
    var peer: Peer? = null
    /** Left for good: the bot decides for them from now on. */
    var left = false
    /** Since when the connection is gone (null: connected). */
    var awaySince: Long? = null
    var total = 0.0
    var ready = false
    /** TABLE: the seat at the table. */
    var seat = -1
  }

  /** A table: a hand's record being played, and who sits where (a contest's bots are null). */
  private inner class Table(val key: Int, var game: JsonObject, val seats: List<Player?>, val owner: Player?) {
    /** The current decision point (a TableView's and an Act's `step`). */
    var point = 0
    /** Seats of people who must decide at this point, and whether on another's tile (a claim) or on their own turn. */
    var waiting = setOf<Int>()
    var claiming = false
    var deadline: Long? = null
    /** The claim window of the last discard (or added kong), at a table of two or more people: nothing after it is
     *  shown before this time. */
    var windowUntil = 0L
    /** The last discard has been shown (held views, before what follows it). */
    var shownDiscard = true
    /** The pace (Pace): when the last discard (or added kong) came and when it lands in its river on the screens;
     *  when a bot's own turn counts from; nobody decides before the deal is over on the screens. */
    var shownAt = 0L
    var landAt = 0L
    var turnFrom = 0L
    var notBefore = 0L
    /** The record's actions the pace has gone through (changed). */
    var paced = game.actions().size
    /** The bots' next step, decided at once and shown when it is due (step). */
    var ahead: JsonObject? = null
    /** Each seat's person: the engine's event count they were sent up to. */
    val sent = IntArray(4)
    val timeouts = IntArray(4)
    var over = false
    var points: List<Double>? = null
    /** Played to its end by the bots (a contest's hand that ran out of time). */
    var auto = false
    var drive: Job? = null
    /** The scheduled drive's number: an older one's event is dropped. */
    var driveSeq = 0
    var drivePending = false
    var timer: Job? = null
  }

  private val players = mutableListOf<Player>()
  private var host = -1
  private var nextId = 0
  private var hand = -1
  private var tables = listOf<Table>()
  private val outcomes = mutableListOf<HandOutcome>()
  /** TABLE: the first hand's dealer (the engine's `next` needs it), and the next hand when there is one. */
  private var startDealer = 0
  private var startRound = 0
  private var nextGame: JsonObject? = null
  private var seedBase = ""
  private var reviewDeadline: Long? = null
  private var reviewTimer: Job? = null
  private var capTimer: Job? = null
  private var engineCalls = 0

  private sealed interface Event
  private class JoinEvent(val peer: Peer, val name: String, val reply: CompletableDeferred<Joined?>) : Event
  private class ResumeEvent(val peer: Peer, val token: String, val reply: CompletableDeferred<Joined?>) : Event
  private class MessageEvent(val player: Int, val peer: Peer, val msg: ClientMsg) : Event
  private class DisconnectEvent(val player: Int, val peer: Peer) : Event
  private class DriveEvent(val hand: Int, val table: Int, val seq: Int) : Event
  private class DecisionTimeout(val hand: Int, val table: Int, val point: Int) : Event
  private class ReviewTimeout(val hand: Int) : Event
  private class ContestCap(val hand: Int) : Event
  private class CloseEvent(val reason: String) : Event

  private val events = Channel<Event>(Channel.UNLIMITED)

  init {
    scope.launch {
      for (e in events) {
        if (closed) break
        try {
          handle(e)
        } catch (ex: CancellationException) {
          throw ex
        } catch (ex: Exception) {
          // An engine that fails or times out, or the room's budget used up: the room cannot go on.
          close(if (ex is EngineError) "engine" else "error")
        }
      }
      while (true) {
        val e = events.tryReceive().getOrNull() ?: break
        if (e is JoinEvent) e.reply.complete(null)
        if (e is ResumeEvent) e.reply.complete(null)
      }
    }
  }

  // ---- The Hub's side: each call queues an event. ----

  /** A new player; null when the room is full or already started. */
  suspend fun join(peer: Peer, name: String): Joined? = ask { JoinEvent(peer, name, it) }

  /** The seat with this token, now on `peer`; null for an unknown token. */
  suspend fun resume(peer: Peer, token: String): Joined? = ask { ResumeEvent(peer, token, it) }

  private suspend fun ask(event: (CompletableDeferred<Joined?>) -> Event): Joined? {
    val reply = CompletableDeferred<Joined?>()
    if (events.trySend(event(reply)).isFailure) return null
    return reply.await()
  }

  /** A message from `peer`, which must still be the player's connection. */
  fun message(player: Int, peer: Peer, msg: ClientMsg) {
    events.trySend(MessageEvent(player, peer, msg))
  }

  fun disconnected(player: Int, peer: Peer) {
    events.trySend(DisconnectEvent(player, peer))
  }

  fun requestClose(reason: String) {
    events.trySend(CloseEvent(reason))
  }

  // ---- Events ----

  private suspend fun handle(e: Event) {
    lastActivity = now()
    when (e) {
      is JoinEvent -> e.reply.complete(join(e))
      is ResumeEvent -> e.reply.complete(resume(e))
      is MessageEvent -> players.firstOrNull { it.id == e.player && it.peer === e.peer }?.let { message(it, e.msg) }
      is DisconnectEvent -> players.firstOrNull { it.id == e.player && it.peer === e.peer }?.let { p ->
        p.peer = null
        p.awaySince = now()
        if (phase == Phase.LOBBY) removeFromLobby(p) else {
          broadcastState()
          checkReady()
        }
      }
      is DriveEvent -> if (e.hand == hand && phase == Phase.PLAYING) tables.getOrNull(e.table)?.let { t ->
        if (t.drivePending && t.driveSeq == e.seq) {
          t.drivePending = false
          t.drive = null
          step(t)
        }
      }
      is DecisionTimeout -> if (e.hand == hand && phase == Phase.PLAYING) tables.getOrNull(e.table)?.let { t ->
        if (t.point == e.point && t.waiting.isNotEmpty() && !t.over) decideFor(t, t.waiting)
      }
      is ReviewTimeout -> if (e.hand == hand && phase == Phase.REVIEW) nextHand()
      is ContestCap -> if (e.hand == hand && phase == Phase.PLAYING) for (t in tables.filter { !it.over }) {
        t.auto = true
        if (t.waiting.isNotEmpty()) decideFor(t, t.waiting) else if (!t.drivePending) drive(t)
      }
      is CloseEvent -> close(e.reason)
    }
  }

  private fun humans() = players.filter { !it.bot }

  private fun join(e: JoinEvent): Joined? {
    if (phase != Phase.LOBBY) {
      e.peer.send(Error(ErrorCode.STARTED))
      return null
    }
    if (humans().size >= maxPlayers) {
      e.peer.send(Error(ErrorCode.ROOM_FULL))
      return null
    }
    val token = token()
    val p = Player(nextId++, Validation.name(e.name), hash(token), bot = false)
    p.peer = e.peer
    players += p
    if (host < 0) host = p.id
    val joined = Joined(id, p.id, token)
    e.peer.send(joined)
    broadcastState()
    return joined
  }

  private suspend fun resume(e: ResumeEvent): Joined? {
    val h = hash(e.token)
    val p = humans().firstOrNull { !it.left && constantTimeEquals(it.tokenHash, h) } ?: return null
    if (p.peer !== e.peer) p.peer?.send(Closed("resumed elsewhere"))
    p.peer = e.peer
    p.awaySince = null
    val joined = Joined(id, p.id, e.token)
    e.peer.send(joined)
    broadcastState()
    if (outcomes.isNotEmpty()) e.peer.send(History(outcomes.toList()))
    if (phase == Phase.PLAYING) tableOf(p)?.let { t ->
      val seat = t.seats.indexOf(p)
      t.sent[seat] = 0
      sendView(t, seat)
    }
    return joined
  }

  private suspend fun message(p: Player, msg: ClientMsg) {
    val peer = p.peer ?: return
    when (msg) {
      is ChangeSettings -> {
        if (p.id != host || phase != Phase.LOBBY) return peer.send(Error(ErrorCode.NOT_ALLOWED))
        val s = Validation.settings(msg.settings.copy(mode = settings.mode))?.let { if (it.bot in bots) it else it.copy(bot = "greedy") }
          ?: return peer.send(Error(ErrorCode.BAD_SETTINGS))
        rules = try {
          useEngine { engine.rules(Validation.ruleSpec(s.rules)!!) }
        } catch (ex: EngineError) {
          return peer.send(Error(ErrorCode.BAD_SETTINGS, ex.message ?: ""))
        }
        settings = s
        broadcastState()
      }
      Start -> {
        if (p.id != host || phase != Phase.LOBBY || humans().isEmpty()) return peer.send(Error(ErrorCode.NOT_ALLOWED))
        start()
      }
      is Act -> act(p, msg)
      is Ready -> if (phase == Phase.REVIEW && msg.hand == hand && !p.ready) {
        p.ready = true
        broadcastState()
        checkReady()
      }
      Leave -> {
        peer.send(Closed("left"))
        p.peer = null
        if (phase == Phase.LOBBY) removeFromLobby(p) else {
          p.left = true
          if (humans().all { it.left }) return close("everyone left")
          if (host == p.id) host = humans().firstOrNull { !it.left }?.id ?: host
          broadcastState()
          // Whatever the player must decide now, the bot decides.
          tableOf(p)?.let { t ->
            val seat = t.seats.indexOf(p)
            if (seat in t.waiting) decideFor(t, setOf(seat))
          }
          checkReady()
        }
      }
      is Hello, is Create, is Join, is Resume -> peer.send(Error(ErrorCode.NOT_ALLOWED))
    }
  }

  private fun removeFromLobby(p: Player) {
    players.remove(p)
    if (humans().isEmpty()) return close("empty")
    if (host == p.id) host = humans().first().id
    broadcastState()
  }

  // ---- Hands ----

  private suspend fun start() {
    seedBase = token()
    if (settings.mode == Mode.TABLE) {
      // Bots in the empty seats, then everyone at a seat drawn at random.
      var n = 1
      while (players.size < Limits.TABLE) players += Player(nextId++, "${n++}", "", bot = true)
      val order = players.toMutableList().also { it.shuffle(random) }
      order.forEachIndexed { seat, p -> p.seat = seat }
      startDealer = random.nextInt(Limits.TABLE)
      startRound = 0
      nextGame = useEngine { engine.newHand(rules, Setup(startDealer), "$seedBase/wall/0") }
    }
    startHand(0)
  }

  private suspend fun startHand(k: Int) {
    hand = k
    phase = Phase.PLAYING
    engineCalls = 0
    reviewTimer?.cancel()
    reviewDeadline = null
    for (p in players) p.ready = false
    tables = if (settings.mode == Mode.TABLE) {
      val seats = (0 until Limits.TABLE).map { s -> players.first { it.seat == s } }
      listOf(Table(0, nextGame!!, seats, null))
    } else {
      // The same wall, dealer and seat for everyone; bots in the other seats.
      val dealer = random.nextInt(Limits.TABLE)
      val seat = random.nextInt(Limits.TABLE)
      val game = useEngine { engine.newHand(rules, Setup(dealer), "$seedBase/wall/$k") }
      humans().mapIndexed { i, p -> Table(i, game, (0 until Limits.TABLE).map { if (it == seat) p else null }, p) }
    }
    if (settings.mode == Mode.CONTEST) {
      capTimer?.cancel()
      val h = hand
      capTimer = scope.launch {
        delay(Limits.CONTEST_HAND_CAP_MS)
        events.send(ContestCap(h))
      }
    }
    broadcastState()
    for (t in tables) {
      t.notBefore = now() + pace.dealMs
      t.turnFrom = t.notBefore
      drive(t)
    }
  }

  /** The table a player plays at now. */
  private fun tableOf(p: Player): Table? = tables.firstOrNull { p in it.seats }

  /** The engine's spec of the room's bot (its settings name one of `bots`). */
  private fun botSpec(): String = bots[settings.bot] ?: "greedy"

  /** The bot's spec for a seat: a bot's, one who left or is away past the grace, or every seat of an auto table. */
  private fun specOf(t: Table, seat: Int): String? {
    val p = t.seats[seat] ?: return botSpec()
    if (p.bot || p.left || t.auto) return botSpec()
    val away = p.awaySince
    if (p.peer == null && away != null && now() - away >= Limits.AWAY_GRACE_MS) return botSpec()
    return null
  }

  /** Whether someone connected watches the table (else its bots play without pausing). */
  private fun watched(t: Table) = !t.auto && t.seats.any { it != null && !it.bot && !it.left && it.peer != null }

  /** Two or more people play at the table: a pause after a discard could tell one of them that another may claim it. */
  private fun crowded(t: Table) = t.seats.count { it != null && !it.bot && !it.left } >= 2

  private fun seed(t: Table) = "$seedBase/$hand/${t.game.actions().size}"

  /**
   * The table goes on: who must decide now? People are shown it and given the time limit; bots decide at once, and
   * what they did is shown when the pace says (Pace, `due`). A discard is shown at once; at a table of two or more
   * people what follows it (the next draw, a claim) only when its claim window is over, whoever could claim it: the
   * people who may claim decide meanwhile, on views held at the discard.
   */
  private suspend fun drive(t: Table) {
    if (t.over || phase != Phase.PLAYING || t.ahead != null) return
    // A discard (or an added kong) is shown as soon as it is made.
    pace(t, lastTile(t))
    val p = useEngine { engine.pending(t.game) }
    val watched = watched(t)
    val inWindow = watched && now() < t.windowUntil
    if (p.phase == "over") {
      if (!inWindow) return finishTable(t)
      showDiscard(t)
      return schedule(t, t.windowUntil)
    }
    val specs = (0 until Limits.TABLE).map { specOf(t, it) }
    val people = p.seats.filter { specs[it] == null }
    val claim = p.phase == "react" || p.phase == "rob_kong"
    if (people.isNotEmpty() && claim) {
      // A claim on the discard: bots deciding on it decide now (the engine keeps it from the others until all
      // have, and so does the pace), the people are asked.
      if (p.seats.size > people.size) t.game = useEngine { engine.advance(t.game, specs, seed(t), steps = 1) }
      ask(t, people, claim = true)
      t.shownDiscard = true
      return sendViews(t, hold = inWindow)
    }
    if (inWindow) {
      // What follows the discard waits for the window's end; the discard itself is shown now.
      showDiscard(t)
      return schedule(t, t.windowUntil)
    }
    // What happened is shown now.
    pace(t, t.game.actions().size)
    if (people.isNotEmpty()) {
      ask(t, people, claim = false, from = if (watched) t.notBefore else 0L)
      return sendViews(t)
    }
    sendViews(t)
    // Only bots decide: at once, shown when due. Unwatched: to the end, or until a person must decide.
    val before = t.game.actions().size
    val next = useEngine { engine.advance(t.game, specs, seed(t), steps = if (watched) 1 else null) }
    t.ahead = next
    schedule(t, if (watched) due(t, p, next, before) else now())
  }

  /** When the bots' step `next` (from `before` actions; `p`: who decided) is shown (Pace). */
  private fun due(t: Table, p: Pending, next: JsonObject, before: Int): Long {
    val acts = next.actions()
    val at = if (p.phase == "react" || p.phase == "rob_kong") {
      // A call after the tile's stay; none at once, so that no pause tells anyone that a bot could have called.
      if ((before until acts.size).all { acts[it].jsonArray[1].jsonPrimitive.content == "pass" }) now()
      else maxOf(t.shownAt + pace.botClaimMs, t.landAt)
    } else {
      // An own turn: its discard flies in the last of it, after the tile before it has landed.
      val fly = if (next.lastActionType() == "discard") pace.discardMs else 0L
      val turn = if (justCalled(t.game, p.current)) pace.botAfterCallMs else pace.botTurnMs
      maxOf(t.turnFrom + turn - fly, t.landAt)
    }
    return maxOf(at, t.notBefore)
  }

  /** The actions to the last discard or added kong (how many). */
  private fun lastTile(t: Table): Int {
    val acts = t.game.actions()
    for (k in acts.indices.reversed()) {
      val type = acts[k].jsonArray[1].jsonPrimitive.content
      if (type == "discard" || type == "kakan") return k + 1
    }
    return 0
  }

  /** Whether `seat`, to play now, came to it by a chow or a pung (since the last discard): its pace is bot_after_call. */
  private fun justCalled(game: JsonObject, seat: Int): Boolean {
    val acts = game.actions()
    for (k in acts.indices.reversed()) {
      val a = acts[k].jsonArray
      val type = a[1].jsonPrimitive.content
      if (type == "discard" || type == "kakan") return false
      if ((type == "chi" || type == "pon") && a[0].jsonPrimitive.int == seat) return true
    }
    return false
  }

  /**
   * The pace of the actions shown now (to `upTo`): a discard comes and flies to its river (a bot's turn is over when it
   * lands, a person's when they made it), and opens the claim window at a table of two or more people; an added kong
   * comes too; a call's or a declaration's moment is let finish before its bot goes on.
   */
  private fun pace(t: Table, upTo: Int) {
    val acts = t.game.actions()
    val end = minOf(upTo, acts.size)
    val n = now()
    for (k in t.paced until end) {
      val a = acts[k].jsonArray
      val seat = a[0].jsonPrimitive.int
      when (a[1].jsonPrimitive.content) {
        "discard" -> {
          t.shownAt = n
          t.landAt = n + pace.discardMs
          t.turnFrom = if (t.seats[seat]?.bot == false) n else t.landAt
          tileCame(t, n)
        }
        "kakan" -> {
          t.shownAt = n
          t.landAt = n
          t.turnFrom = n + pace.callMs
          tileCame(t, n)
        }
        "chi", "pon", "kan", "ankan" -> t.turnFrom = n + pace.callMs
        "declare" -> t.turnFrom = n + pace.declareMs
      }
    }
    if (end > t.paced) t.paced = end
  }

  /** A tile the others may claim came: at a table of two or more people, its claim window. */
  private fun tileCame(t: Table, n: Long) {
    if (crowded(t)) t.windowUntil = n + pace.claimWindowMs
    t.shownDiscard = false
  }

  /** A person decided on another's tile (or the bot for them) at `n`: past the claim window, a bot's turn after it
   *  counts from then. Within the window nothing changes, so that nothing shows who decided. */
  private fun decidedOnTile(t: Table, n: Long) {
    if (n >= t.windowUntil) t.turnFrom = maxOf(t.turnFrom, n)
  }

  /** The people at `seats` must decide now (on another's tile: `claim`), within the time limit (counted from `from`
   *  when it is later: the deal's end). */
  private fun ask(t: Table, seats: List<Int>, claim: Boolean, from: Long = 0L) {
    t.point++
    t.waiting = seats.toSet()
    t.claiming = claim
    val limit = settings.timeLimit * 1000L
    val wait = maxOf(0L, from - now()) + limit
    t.deadline = now() + wait
    t.timer?.cancel()
    val (h, key, point) = Triple(hand, t.key, t.point)
    t.timer = scope.launch {
      delay(wait)
      events.send(DecisionTimeout(h, key, point))
    }
  }

  /** The last discard, held: shown once, before what follows it. */
  private suspend fun showDiscard(t: Table) {
    if (t.shownDiscard) return
    t.shownDiscard = true
    sendViews(t, hold = true)
  }

  /** The table goes on at `at` (the bots' step shown, or the claim window over). */
  private fun schedule(t: Table, at: Long) {
    t.drive?.cancel()
    t.driveSeq++
    t.drivePending = true
    val (h, key, seq) = Triple(hand, t.key, t.driveSeq)
    val wait = at - now()
    if (wait <= 0) {
      t.drive = null
      events.trySend(DriveEvent(h, key, seq))
      return
    }
    t.drive = scope.launch {
      delay(wait)
      events.send(DriveEvent(h, key, seq))
    }
  }

  /** The bots' step decided before is shown now (or, without one, the table goes on). */
  private suspend fun step(t: Table) {
    if (t.over) return
    val next = t.ahead ?: return drive(t)
    t.ahead = null
    val before = t.game.actions().size
    t.game = next
    for (a in next.actions().drop(before)) {
      val seat = a.jsonArray[0].jsonPrimitive.int
      val person = t.seats[seat]
      if (person != null && !person.bot) t.timeouts[seat]++
    }
    drive(t)
  }

  /** The bot decides for these people (they ran out of time, or left). */
  private suspend fun decideFor(t: Table, seats: Set<Int>) {
    val specs = (0 until Limits.TABLE).map { if (it in seats) botSpec() else specOf(t, it) }
    val claim = t.claiming
    t.game = useEngine { engine.advance(t.game, specs, seed(t), steps = 1) }
    for (s in seats) t.timeouts[s]++
    t.waiting = t.waiting - seats
    if (claim) decidedOnTile(t, now())
    if (t.waiting.isEmpty()) {
      t.timer?.cancel()
      t.deadline = null
      drive(t)
    } else sendViews(t, hold = now() < t.windowUntil)
  }

  private suspend fun act(p: Player, msg: Act) {
    val peer = p.peer ?: return
    val t = tableOf(p)
    val seat = t?.seats?.indexOf(p) ?: -1
    if (t == null || phase != Phase.PLAYING || msg.hand != hand || msg.step != t.point || seat !in t.waiting)
      return peer.send(Error(ErrorCode.NOT_ALLOWED))
    val action = Validation.action(msg.action) ?: return peer.send(Error(ErrorCode.BAD_ACTION))
    val claim = t.claiming
    t.game = try {
      useEngine { engine.act(t.game, seat, action) }
    } catch (ex: EngineError) {
      return peer.send(Error(ErrorCode.BAD_ACTION, ex.message ?: ""))
    }
    t.waiting = t.waiting - seat
    if (claim) decidedOnTile(t, now())
    if (t.waiting.isEmpty()) {
      t.timer?.cancel()
      t.deadline = null
      drive(t)
    // Others still deciding on the same discard: the player sees their decision taken (nothing has followed yet).
    } else sendView(t, seat, hold = now() < t.windowUntil)
  }

  private suspend fun sendViews(t: Table, hold: Boolean = false) {
    for (seat in 0 until Limits.TABLE) sendView(t, seat, hold = hold)
  }

  private suspend fun sendView(t: Table, seat: Int, reveal: Boolean = false, hold: Boolean = false) {
    val p = t.seats[seat] ?: return
    if (p.bot) return
    val peer = p.peer ?: return
    val since = t.sent[seat]
    val v = useEngine { engine.view(t.game, seat, since, reveal, hold && !reveal) }
    t.sent[seat] = v["event_count"]!!.jsonPrimitive.int
    val remaining = if (seat in t.waiting) t.deadline?.let { (it - now()).coerceAtLeast(0) } else null
    peer.send(TableView(hand, seat, t.point, v, since == 0, remaining, t.seats.map { it?.id ?: -1 }))
  }

  private suspend fun finishTable(t: Table) {
    t.over = true
    t.waiting = emptySet()
    t.timer?.cancel()
    t.drive?.cancel()
    t.drivePending = false
    val v = useEngine { engine.view(t.game, 0, 0, reveal = true) }
    t.points = v["result"]!!.jsonObject["points"]!!.jsonArray.map { it.jsonPrimitive.double }
    for (seat in 0 until Limits.TABLE) sendView(t, seat, reveal = true)
    if (tables.all { it.over }) closeHand() else broadcastState()
  }

  private suspend fun closeHand() {
    capTimer?.cancel()
    val outcome = HandOutcome(
      hand,
      tables.map { t -> TablePlay(t.seats.map { it?.id ?: -1 }, t.game, t.points!!, t.timeouts.toList()) },
      if (settings.mode == Mode.CONTEST) tables.map { it.owner!!.id } else emptyList(),
    )
    for (t in tables) t.seats.forEachIndexed { seat, p ->
      if (p != null && (settings.mode == Mode.TABLE || p === t.owner)) p.total += t.points!![seat]
    }
    outcomes += outcome
    val over = if (settings.mode == Mode.TABLE) {
      val (next, gameOver) = useEngine { engine.next(tables[0].game, startDealer, "$seedBase/wall/${hand + 1}") }
      nextGame = next
      when (settings.length) {
        Length.HAND -> true
        Length.ROUND -> gameOver || next.setup().round != startRound
        Length.GAME -> gameOver
      }
    } else hand + 1 >= settings.hands
    if (over) {
      phase = Phase.OVER
      reviewDeadline = null
    } else {
      phase = Phase.REVIEW
      reviewTimer?.cancel()
      reviewDeadline = now() + Limits.REVIEW_MS
      val h = hand
      reviewTimer = scope.launch {
        delay(Limits.REVIEW_MS)
        events.send(ReviewTimeout(h))
      }
    }
    broadcast(HandResult(outcome))
    broadcastState()
    checkReady()
  }

  private suspend fun checkReady() {
    if (phase == Phase.REVIEW && humans().all { it.ready || it.left || it.peer == null }) nextHand()
  }

  private suspend fun nextHand() {
    if (phase == Phase.REVIEW) startHand(hand + 1)
  }

  /** Counts the room's engine requests against its budget for the hand. */
  private suspend fun <T> useEngine(block: suspend () -> T): T {
    if (++engineCalls > budget()) throw IllegalStateException("engine budget")
    return block()
  }

  /** A hand's decision points are a few hundred at most; each asks the engine a few times per person at the table. */
  private fun budget() = 200 + maxOf(1, tables.size) * 5_000

  // ---- Output ----

  private fun view() = RoomView(
    pin, settings, rules, phase, hand, host,
    players.map { p ->
      PlayerView(
        p.id, p.name, p.bot, p.bot || p.peer != null, p.seat, p.ready,
        tables.firstOrNull { it.owner === p }?.over == true, p.total, p.left,
      )
    },
    reviewDeadline?.let { (it - now()).coerceAtLeast(0) },
  )

  private fun broadcast(msg: ServerMsg) {
    for (p in players) p.peer?.send(msg)
  }

  private fun broadcastState() = broadcast(State(view()))

  private fun close(reason: String) {
    if (closed) return
    closed = true
    reviewTimer?.cancel()
    capTimer?.cancel()
    for (t in tables) {
      t.timer?.cancel()
      t.drive?.cancel()
    }
    broadcast(Closed(reason))
    for (p in players) p.peer = null
    events.close()
    onClosed(this)
  }

  private fun token(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

  companion object {
    fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun hash(token: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
  }
}
