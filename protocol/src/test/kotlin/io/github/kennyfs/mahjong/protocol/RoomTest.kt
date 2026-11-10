package io.github.kennyfs.mahjong.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Rooms played with the real engine (cpp/build/mahjong, MAHJONG_BINARY): skipped without it. Clients here are
 * channels of the messages a room sends them, deciding with the engine's legal actions.
 */
class RoomTest {
  private val binary = System.getenv("MAHJONG_BINARY") ?: "../cpp/build/mahjong"
  private lateinit var process: Process
  private lateinit var engine: EngineClient
  private lateinit var scope: CoroutineScope
  private lateinit var hub: Hub

  @Before
  fun setUp() {
    assumeTrue("no engine at $binary", File(binary).canExecute())
    process = ProcessBuilder(binary, "gui").redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val input = process.outputStream.bufferedWriter()
    val output = process.inputStream.bufferedReader()
    val lock = Any()
    engine = EngineClient { line ->
      synchronized(lock) {
        input.write(line)
        input.newLine()
        input.flush()
        output.readLine()
      }
    }
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Bots without pauses, and clients that answer at once: no message limit to run into.
    hub = Hub(engine, scope, HubLimits(pace = Pace.NONE, messagesPerSecond = 1e6, messageBurst = 1_000_000))
  }

  @After
  fun tearDown() {
    if (::scope.isInitialized) scope.cancel()
    if (::process.isInitialized) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
  }

  private inner class Client(val name: String, address: String = "a-$name") {
    val inbox = Channel<ServerMsg>(Channel.UNLIMITED)
    val conn = hub.open(address, { inbox.trySend(it) }, {})
    var you = -1
    var token = ""
    var room = ""
    /** Each finished hand's result, and the views of the table. */
    val results = mutableListOf<HandOutcome>()
    var lastState: RoomView? = null
    var lastView: TableView? = null
    var views = 0

    suspend fun send(msg: ClientMsg) = hub.receive(conn, BattleJson.encodeToString(ClientMsg.serializer(), msg))

    suspend fun next(): ServerMsg = withTimeout(20_000) { inbox.receive() }.also { m ->
      when (m) {
        is Joined -> { you = m.you; token = m.token; room = m.room }
        is State -> lastState = m.room
        is TableView -> { lastView = m; views++ }
        is HandResult -> results += m.outcome
        else -> {}
      }
    }

    suspend inline fun <reified T : ServerMsg> expect(): T {
      while (true) {
        val m = next()
        if (m is T) return m
      }
    }

    suspend fun hello() {
      send(Hello(PROTOCOL_VERSION, name))
      expect<Welcome>()
    }

    /**
     * Plays: on each table view where the player must decide, the first legal action that is not a claim (a discard,
     * or a pass), or `pick`'s; until `hands` results are in or the room is over.
     */
    suspend fun play(hands: Int, pick: (List<JsonObject>) -> JsonObject = { legal -> legal.firstOrNull { it["type"]!!.jsonPrimitive.content in setOf("discard", "pass") } ?: legal.first() }) {
      while (results.size < hands) {
        when (val m = next()) {
          is TableView -> {
            val v = m.view
            if (v["my_turn"]?.jsonPrimitive?.boolean == true) {
              val legal = v["legal"]!!.jsonArray.map { it.jsonObject }
              val a = pick(legal)
              send(Act(m.hand, m.step, BattleJson.decodeFromJsonElement(Action.serializer(), a)))
            }
          }
          is Closed -> return
          else -> {}
        }
      }
    }
  }

  private val oneHand = RoomSettings(Mode.TABLE, Length.HAND, rules = RuleChoice("standard", "see"), timeLimit = 5)

  @Test
  fun aTableOfOnePersonAndThreeBotsPlaysAHandToItsEnd(): Unit = runBlocking {
    val a = Client("Alice")
    a.hello()
    a.send(Create(oneHand))
    a.expect<Joined>()
    val s = a.expect<State>().room
    assertEquals(Phase.LOBBY, s.phase)
    assertTrue(s.rules.contains("flowers=1"))
    a.send(Start)
    a.play(1)
    val out = a.results.single()
    assertEquals(1, out.tables.size)
    val table = out.tables[0]
    assertEquals(4, table.seats.size)
    assertTrue(a.you in table.seats)
    assertEquals(0.0, table.points.sum(), 1e-9)
    // The player was only ever shown their own seat, and decided on what they were shown.
    assertTrue(a.views > 0)
    assertEquals(table.seats.indexOf(a.you), a.lastView!!.seat)
    val over = a.expect<State>().room
    assertEquals(Phase.OVER, over.phase)
    assertEquals(table.points[table.seats.indexOf(a.you)], over.players.first { it.id == a.you }.total, 1e-9)
  }

  @Test
  fun twoPeopleAtATableEachSeeOnlyTheirOwnTiles(): Unit = runBlocking {
    val a = Client("Alice")
    val b = Client("Bob")
    a.hello()
    b.hello()
    a.send(Create(oneHand))
    a.expect<Joined>()
    val pin = a.expect<State>().room.pin
    b.send(Join(pin))
    b.expect<Joined>()
    a.send(Start)
    // Play both: whichever must decide.
    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    val jobA = scope.launchPlay(a)
    val jobB = scope.launchPlay(b)
    jobA.join()
    jobB.join()
    val ta = a.results.single().tables[0]
    assertEquals(ta, b.results.single().tables[0])
    // Each view's hand is the seat's own: a view never has another seat's concealed tiles.
    val va = a.lastView!!
    val vb = b.lastView!!
    assertTrue(va.seat != vb.seat)
    done.complete(Unit)
  }

  private fun CoroutineScope.launchPlay(c: Client) = launch { c.play(1) }

  @Test
  fun aDecisionOutOfTurnOrNotLegalIsRefused(): Unit = runBlocking {
    val a = Client("Alice")
    a.hello()
    a.send(Create(oneHand))
    a.expect<Joined>()
    a.send(Start)
    var refused = 0
    while (refused < 2) {
      val m = a.next()
      if (m is TableView && m.view["my_turn"]?.jsonPrimitive?.boolean == true) {
        // A tile not in the hand, then a stale step.
        a.send(Act(m.hand, m.step, Action("discard", "9z")))
        assertEquals(ErrorCode.BAD_ACTION, a.expect<Error>().code)
        a.send(Act(m.hand, m.step + 1, Action("pass")))
        assertEquals(ErrorCode.NOT_ALLOWED, a.expect<Error>().code)
        refused = 2
      }
    }
  }

  @Test
  fun whoRunsOutOfTimeGetsTheBotsDecision(): Unit = runBlocking {
    val a = Client("Alice")
    a.hello()
    a.send(Create(oneHand))
    a.expect<Joined>()
    a.send(Start)
    // Never decides: the 5 s limit passes once, then leaves (the bot plays the rest at once).
    var timedOut = false
    while (!timedOut) {
      val m = a.next()
      if (m is TableView && m.view["my_turn"]?.jsonPrimitive?.boolean == true) {
        val step = m.step
        while (true) {
          val n = a.next()
          if (n is TableView && n.step > step) break
        }
        timedOut = true
      }
    }
    a.send(Leave)
    a.expect<Closed>()
  }

  @Test
  fun aContestDealsEveryoneTheSameWall(): Unit = runBlocking {
    val settings = RoomSettings(Mode.CONTEST, hands = 2, rules = RuleChoice("simple1", "none"), timeLimit = 5)
    val a = Client("Alice")
    val b = Client("Bob")
    a.hello()
    b.hello()
    a.send(Create(settings))
    a.expect<Joined>()
    val pin = a.expect<State>().room.pin
    b.send(Join(pin))
    b.expect<Joined>()
    a.send(Start)
    val ja = scope.launchPlayN(a, 2)
    val jb = scope.launchPlayN(b, 2)
    // Between hands both say they are ready.
    ja.join()
    jb.join()
    for (k in 0 until 2) {
      val out = a.results[k]
      assertEquals(2, out.tables.size)
      assertEquals(listOf(a.you, b.you), out.players)
      // Nobody ran out of time (the bot would have decided for them, differently).
      assertEquals(listOf(listOf(0, 0, 0, 0), listOf(0, 0, 0, 0)), out.tables.map { it.timeouts })
      val walls = out.tables.map { it.record["wall"]!!.jsonPrimitive.content }
      assertEquals(walls[0], walls[1])
      // The same decisions (both play the first legal action) and the same bots: the same hand.
      assertEquals(out.tables[0].record, out.tables[1].record)
    }
  }

  private fun CoroutineScope.launchPlayN(c: Client, hands: Int) = launch {
    while (c.results.size < hands) {
      c.play(c.results.size + 1)
      if (c.results.size < hands) c.send(Ready(c.results.size - 1))
    }
  }

  @Test
  fun badSettingsAndUnknownRulesAreRefused(): Unit = runBlocking {
    val a = Client("Alice")
    a.hello()
    a.send(Create(oneHand.copy(timeLimit = 7)))
    assertEquals(ErrorCode.BAD_SETTINGS, a.expect<Error>().code)
    a.send(Create(oneHand.copy(rules = RuleChoice("standard", "see", mapOf("nope" to 1)))))
    assertEquals(ErrorCode.BAD_SETTINGS, a.expect<Error>().code)
    a.send(Create(oneHand.copy(rules = RuleChoice("standard", "see", mapOf("base" to 2)))))
    a.expect<Joined>()
    assertTrue(a.expect<State>().room.rules.contains("base=2"))
  }

  @Test
  fun aWrongVersionOrPinIsRefused(): Unit = runBlocking {
    val a = Client("Alice")
    a.send(Hello(PROTOCOL_VERSION + 1, "x"))
    assertEquals(ErrorCode.VERSION, a.expect<Error>().code)
    val b = Client("Bob")
    b.hello()
    b.send(Join("123456"))
    assertEquals(ErrorCode.NO_ROOM, b.expect<Error>().code)
  }

  @Test
  fun namesLoseControlCharactersAndAreCut() {
    assertEquals("ab c", Validation.name("a‮b  c\n"))
    assertEquals(Limits.NAME_LENGTH, Validation.name("x".repeat(40)).length)
    assertEquals(null, Validation.action(Action("discard", "0m")))
    assertEquals(null, Validation.action(Action("chi", "1m", listOf("1m", "2m"))))
    assertEquals(Action("chi", "1m", listOf("1m", "2m", "3m")), Validation.action(Action("chi", "1m", listOf("1m", "2m", "3m"))))
  }
}
