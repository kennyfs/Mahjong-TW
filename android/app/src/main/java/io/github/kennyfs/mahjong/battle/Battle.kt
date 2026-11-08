package io.github.kennyfs.mahjong.battle

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kennyfs.mahjong.Action
import io.github.kennyfs.mahjong.BattleGroup
import io.github.kennyfs.mahjong.Engine
import io.github.kennyfs.mahjong.EngineJson
import io.github.kennyfs.mahjong.GameRecord
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.RecordKind
import io.github.kennyfs.mahjong.Records
import io.github.kennyfs.mahjong.Settings
import io.github.kennyfs.mahjong.View
import io.github.kennyfs.mahjong.protocol.Act
import io.github.kennyfs.mahjong.protocol.ChangeSettings
import io.github.kennyfs.mahjong.protocol.ClientMsg
import io.github.kennyfs.mahjong.protocol.Closed
import io.github.kennyfs.mahjong.protocol.Create
import io.github.kennyfs.mahjong.protocol.EngineClient
import io.github.kennyfs.mahjong.protocol.ErrorCode
import io.github.kennyfs.mahjong.protocol.HandOutcome
import io.github.kennyfs.mahjong.protocol.Hello
import io.github.kennyfs.mahjong.protocol.History
import io.github.kennyfs.mahjong.protocol.Hub
import io.github.kennyfs.mahjong.protocol.HubLimits
import io.github.kennyfs.mahjong.protocol.Join
import io.github.kennyfs.mahjong.protocol.Joined
import io.github.kennyfs.mahjong.protocol.Leave
import io.github.kennyfs.mahjong.protocol.Mode
import io.github.kennyfs.mahjong.protocol.PROTOCOL_VERSION
import io.github.kennyfs.mahjong.protocol.Phase
import io.github.kennyfs.mahjong.protocol.PlayerView
import io.github.kennyfs.mahjong.protocol.Ready
import io.github.kennyfs.mahjong.protocol.Resume
import io.github.kennyfs.mahjong.protocol.RoomSettings
import io.github.kennyfs.mahjong.protocol.RoomView
import io.github.kennyfs.mahjong.protocol.ServerMsg
import io.github.kennyfs.mahjong.protocol.Start
import io.github.kennyfs.mahjong.protocol.State
import io.github.kennyfs.mahjong.protocol.TablePlay
import io.github.kennyfs.mahjong.protocol.TableView
import io.github.kennyfs.mahjong.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import io.github.kennyfs.mahjong.protocol.Action as WireAction
import io.github.kennyfs.mahjong.protocol.Error as ErrorMsg
import io.github.kennyfs.mahjong.protocol.HandResult as ResultMsg
import io.github.kennyfs.mahjong.protocol.Length as RoomLength
import io.github.kennyfs.mahjong.protocol.RuleChoice as RoomRules

/** The player's table now: their seat, the view of it (every event from the deal), and the time left to decide. */
data class MyTable(val hand: Int, val seat: Int, val step: Int, val seats: List<Int>, val view: View, val deadline: Long?)

/**
 * Battles: a room on a battle server (docs/battle.md), on a phone nearby, or hosted on this phone for phones nearby.
 * The room is the only authority: it deals, keeps every table's record, sends this player only their own seat's
 * view, plays the bots and whoever runs out of time, and scores. This keeps what the room said and sends the
 * player's decisions; nothing helps the player while a hand is played. The web page's lib/battle.svelte.ts.
 */
class Battle(private val context: Context, private val scope: CoroutineScope, private val settings: Settings,
  private val records: Records, private val onError: (String) -> Unit) : LinkListener {

  var pin by mutableStateOf("")
  var busy by mutableStateOf(false)
    private set
  var reconnecting by mutableStateOf(false)
    private set
  var room by mutableStateOf<RoomView?>(null)
    private set
  /** When `room` arrived, for its countdown. */
  var roomAt by mutableStateOf(0L)
    private set
  var me by mutableStateOf(-1)
    private set
  var table by mutableStateOf<MyTable?>(null)
    private set
  var outcomes by mutableStateOf<List<HandOutcome>>(emptyList())
    private set
  /** The room is gone but its results stay to look at. */
  var finished by mutableStateOf(false)
    private set
  /** The last hand's result has been seen: the final standings now. */
  var lastSeen by mutableStateOf(false)
  var battleId by mutableStateOf<String?>(null)
    private set
  /** Rooms advertised nearby: endpoint id to the host's name. */
  var nearbyRooms by mutableStateOf<Map<String, String>>(emptyMap())
    private set
  /** This phone hosts the room (for phones nearby). */
  var hosting by mutableStateOf(false)
    private set

  private var link: Link? = null
  private var via = "online"
  private var joined: Joined? = null
  /** Sent once the room says welcome: create, join or resume. */
  private var pending: ClientMsg? = null
  private var url: String? = null
  private var startedAt = 0L
  private var beforeAct: MyTable? = null
  private var hub: Hub? = null
  private var host: NearbyHost? = null
  private var finder: NearbyFinder? = null
  private var retry: Job? = null

  private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
  val playerName: String get() = settings.name.trim().ifEmpty { str(R.string.battle_default_name, 1000 + (android.os.Process.myUid() % 9000)) }

  val inRoom: Boolean get() = room != null && !finished && room?.phase != Phase.OVER
  val playing: Boolean get() = inRoom && room?.phase != Phase.LOBBY
  val over: Boolean get() = room != null && (finished || room?.phase == Phase.OVER)
  val mine: PlayerView? get() = room?.players?.firstOrNull { it.id == me }
  val isHost: Boolean get() = room != null && room?.host == me
  val contest: Boolean get() = room?.settings?.mode == Mode.CONTEST

  /** A player's name as shown: a table game's bots are 電腦 1 to 電腦 3. */
  fun nameOf(p: PlayerView) = if (p.bot) str(R.string.cpu_n, p.name.toIntOrNull() ?: 1) else p.name

  /** The names at a table's seats: the player's own as "you", others' by their names, a contest's bots by seat. */
  fun namesAt(seats: List<Int>, you: Boolean = true): List<String> {
    var bot = 0
    return seats.map { id ->
      val p = room?.players?.firstOrNull { it.id == id }
      when {
        p == null -> str(R.string.cpu_n, ++bot)
        you && id == me -> str(R.string.you)
        else -> nameOf(p)
      }
    }
  }

  fun awayAt(seats: List<Int>) = seats.map { id -> room?.players?.firstOrNull { it.id == id }?.let { !it.bot && (!it.connected || it.left) } ?: false }

  fun totalsAt(seats: List<Int>): List<Double>? = if (contest) null else seats.map { id -> room?.players?.firstOrNull { it.id == id }?.total ?: 0.0 }

  /** Who is ranked, the most points first: everyone at a table game's table, bots too; a contest's people. */
  fun standings(): List<PlayerView> {
    val r = room ?: return emptyList()
    val ranked = if (r.settings.mode == Mode.TABLE) r.players.filter { !it.bot || it.seat >= 0 } else r.players.filter { !it.bot }
    return ranked.sortedByDescending { it.total }
  }

  fun rankOf(p: PlayerView) = 1 + standings().count { it.total > p.total + 1e-9 }

  fun myTable(o: HandOutcome): TablePlay? {
    if (o.tables.size == 1 && !contest) return o.tables[0]
    val i = o.players.indexOf(me)
    return if (i >= 0) o.tables[i] else null
  }

  /** A closed hand's points for a player: their seat's at their table. */
  fun pointsOf(o: HandOutcome?, id: Int): Double? {
    o ?: return null
    val tables = if (o.players.isNotEmpty()) o.tables.filterIndexed { i, _ -> o.players[i] == id } else o.tables
    for (tp in tables) {
      val seat = tp.seats.indexOf(id)
      if (seat >= 0) return tp.points[seat]
    }
    return null
  }

  /** A new room's settings: the rules page's rules, the practice length, the last time limit and hands chosen. */
  fun defaults(mode: Mode): RoomSettings {
    val prefs = context.getSharedPreferences("battle", Context.MODE_PRIVATE)
    return RoomSettings(
      mode, RoomLength.valueOf(settings.length.name), prefs.getInt("hands", 4), RoomRules(settings.rules.tai, settings.rules.flowers, settings.rules.overrides),
      prefs.getInt("time", 15), settings.bot,
    )
  }

  // ---- Into a room ----

  fun create(mode: Mode) {
    if (busy || inRoom) return
    val u = serverUrl(settings.server) ?: return onError(str(R.string.battle_server_bad))
    start(Create(defaults(mode)))
    url = u
    open { WsLink(u, this) }
  }

  fun join() {
    if (busy || inRoom) return
    if (!Regex("\\d{6}").matches(pin)) return onError(str(R.string.battle_pin_bad))
    val u = serverUrl(settings.server) ?: return onError(str(R.string.battle_server_bad))
    start(Join(pin))
    url = u
    open { WsLink(u, this) }
  }

  /** A room on this phone for phones nearby: its own hub, advertised, the player's seat a link without a network. */
  fun host(mode: Mode) {
    if (busy || inRoom) return
    start(Create(defaults(mode)))
    via = "nearby"
    val h = Hub(EngineClient { Engine.raw(it) }, scope, HubLimits.NEARBY)
    hub = h
    host = NearbyHost(context, h, scope, playerName, onError)
    hosting = true
    open { LocalLink(h, scope, this) }
  }

  /** Looks for rooms nearby (until a room is joined or the search stops). */
  fun findNearby() {
    finder?.stop()
    finder = NearbyFinder(context, { nearbyRooms = it }, onError)
  }

  fun stopFinding() {
    finder?.stop()
    finder = null
    nearbyRooms = emptyMap()
  }

  fun joinNearby(endpoint: String) {
    if (busy || inRoom) return
    if (!Regex("\\d{6}").matches(pin)) return onError(str(R.string.battle_pin_bad))
    start(Join(pin))
    via = "nearby"
    stopFinding()
    open { NearbyLink(context, endpoint, playerName, this) }
  }

  private fun start(msg: ClientMsg) {
    settings.persist()
    clear()
    pending = msg
  }

  private fun open(make: () -> Link) {
    busy = true
    val l = runCatching(make).getOrElse {
      busy = false
      return onError(str(R.string.battle_no_connection, it.message ?: ""))
    }
    link = l
    l.send(Hello(PROTOCOL_VERSION, playerName))
  }

  // ---- In the room ----

  fun changeSettings(s: RoomSettings) {
    context.getSharedPreferences("battle", Context.MODE_PRIVATE).edit().putInt("hands", s.hands).putInt("time", s.timeLimit).apply()
    link?.send(ChangeSettings(s))
  }

  fun startGame() {
    link?.send(Start)
    host?.stopAdvertising()
  }

  fun act(a: Action) {
    val tb = table ?: return
    if (!tb.view.myTurn) return
    link?.send(Act(tb.hand, tb.step, WireAction(a.type, a.tile, a.tiles)))
    beforeAct = tb
    table = tb.copy(view = tb.view.copy(myTurn = false, legal = emptyList()), deadline = null)
  }

  fun ready() {
    room?.let { link?.send(Ready(it.hand)) }
  }

  fun leave() {
    saveGroup(left = true)
    link?.send(Leave)
    val l = link
    link = null
    scope.launch {
      delay(300)
      l?.close()
    }
    clear()
  }

  fun dismiss() = clear()

  private fun clear() {
    retry?.cancel()
    reconnecting = false
    link?.close()
    link = null
    joined = null
    pending = null
    room = null
    me = -1
    table = null
    outcomes = emptyList()
    battleId = null
    startedAt = 0
    finished = false
    lastSeen = false
    busy = false
    beforeAct = null
    via = "online"
    host?.stop()
    host = null
    hub = null
    hosting = false
  }

  // ---- What the room says ----

  override fun onMessage(link: Link, msg: ServerMsg) {
    if (link !== this.link) return
    when (msg) {
      is Welcome -> pending?.let(link::send)
      is Joined -> {
        joined = msg
        me = msg.you
        busy = false
        reconnecting = false
        // A resume tells everything again.
        pending = Resume(msg.room, msg.token)
      }
      is State -> {
        room = msg.room
        roomAt = System.currentTimeMillis()
        if (msg.room.phase != Phase.LOBBY && battleId == null) {
          battleId = Records.newId()
          startedAt = System.currentTimeMillis()
        }
      }
      is TableView -> {
        val v = runCatching { EngineJson.decodeFromJsonElement<View>(msg.view) }.getOrNull() ?: return
        val cur = table
        val view = if (msg.full || cur == null || cur.hand != msg.hand) v else v.copy(events = cur.view.events + v.events)
        table = MyTable(msg.hand, msg.seat, msg.step, msg.seats, view,
          msg.remainingMs?.takeIf { view.myTurn }?.let { System.currentTimeMillis() + it })
        beforeAct = null
      }
      is ResultMsg -> addOutcomes(listOf(msg.outcome))
      is History -> addOutcomes(msg.outcomes)
      is ErrorMsg -> {
        busy = false
        if (joined != null && msg.code == ErrorCode.NO_ROOM && pending is Resume && (reconnecting || room == null)) {
          reconnecting = false
          if (room == null) {
            clear()
            return onError(str(R.string.battle_closed))
          }
          return roomGone(str(R.string.battle_closed))
        }
        // A decision refused: the view it was made on, again; or it came too late (the bot made it): nothing to say.
        if ((msg.code == ErrorCode.BAD_ACTION || msg.code == ErrorCode.NOT_ALLOWED) && room?.phase == Phase.PLAYING) {
          val b = beforeAct
          if (b != null && table?.step == b.step) table = b
          beforeAct = null
          return
        }
        onError(errorText(msg.code))
        if (joined == null && msg.code in setOf(ErrorCode.VERSION, ErrorCode.NO_ROOM, ErrorCode.ROOM_FULL, ErrorCode.STARTED,
            ErrorCode.TOO_MANY, ErrorCode.BUSY, ErrorCode.RATE)) {
          this.link = null
          link.close()
          if (hosting) clear()
        }
      }
      is Closed -> {
        if (msg.reason == "resumed elsewhere" || msg.reason == "left") return
        roomGone(if (room?.phase == Phase.OVER) null else str(R.string.battle_closed))
      }
    }
  }

  override fun onDown(link: Link, reason: String) {
    if (link !== this.link) return
    this.link = null
    val j = joined
    if (j == null || room?.phase == Phase.OVER) {
      if (j == null) {
        busy = false
        onError(str(R.string.battle_no_connection, reason))
      }
      return roomGone(null)
    }
    // A server's room keeps the seat for a while: it is taken back. A phone nearby that is gone takes its room along.
    val u = url
    if (via != "online" || u == null) return roomGone(str(R.string.battle_lost))
    if (reconnecting) return
    reconnecting = true
    busy = true
    retry = scope.launch {
      for (attempt in 0 until 6) {
        delay(1000L shl minOf(attempt, 3))
        if (!reconnecting) return@launch
        pending = Resume(j.room, j.token)
        open { WsLink(u, this@Battle) }
        val mine = this@Battle.link
        delay(8000)
        if (!reconnecting) return@launch
        if (this@Battle.link === mine) {
          this@Battle.link = null
          mine?.close()
        }
      }
      reconnecting = false
      busy = false
      roomGone(str(R.string.battle_lost))
    }
  }

  private fun roomGone(message: String?) {
    message?.let(onError)
    link?.close()
    link = null
    host?.stop()
    if (outcomes.isEmpty()) clear() else finished = true
  }

  private fun addOutcomes(list: List<HandOutcome>) {
    val known = outcomes.associateBy { it.index }.toMutableMap()
    for (o in list) known[o.index] = o
    outcomes = known.values.sortedBy { it.index }
    scope.launch { recordHands(list) }
  }

  /** The player's table of each closed hand goes to the records (as a table battle's or a contest's hand). */
  private suspend fun recordHands(list: List<HandOutcome>) {
    val r = room ?: return
    val group = battleId ?: return
    for (o in list) {
      val index = o.index + 1
      if (records.hands.any { it.group == group && it.index == index }) continue
      val tp = myTable(o) ?: continue
      val seat = tp.seats.indexOf(me)
      if (seat < 0) continue
      try {
        val game = EngineJson.decodeFromJsonElement<GameRecord>(tp.record)
        val v = Engine.view(game, seat, 0, reveal = true)
        val result = v.result ?: continue
        val bots = tp.seats.map { id -> if (r.players.any { it.id == id && !it.bot }) null else r.settings.bot }
        records.add(Records.handRecord(if (r.settings.mode == Mode.TABLE) RecordKind.table else RecordKind.contest, game, result, seat,
          namesAt(tp.seats, you = false), bots, group, index))
      } catch (e: Exception) {
        // Not kept: the engine could not replay it.
      }
    }
    saveGroup()
  }

  private fun saveGroup(left: Boolean = false) {
    val r = room ?: return
    val id = battleId ?: return
    if (outcomes.isEmpty()) return
    val people = if (r.settings.mode == Mode.TABLE) r.players.filter { it.seat >= 0 }.sortedBy { it.seat } else r.players.filter { !it.bot }
    records.addBattle(BattleGroup(
      id, Instant.ofEpochMilli(if (startedAt > 0) startedAt else System.currentTimeMillis()).toString(),
      if (r.settings.mode == Mode.TABLE) RecordKind.table else RecordKind.contest, via, r.rules, people.map(::nameOf),
      people.indexOfFirst { it.id == me }, people.map { it.total }, outcomes.size, left,
    ))
  }

  private fun errorText(code: ErrorCode) = str(when (code) {
    ErrorCode.VERSION -> R.string.battle_err_version
    ErrorCode.NO_ROOM -> R.string.battle_err_no_room
    ErrorCode.ROOM_FULL -> R.string.battle_err_full
    ErrorCode.STARTED -> R.string.battle_err_started
    ErrorCode.TOO_MANY, ErrorCode.RATE -> R.string.battle_err_rate
    ErrorCode.BUSY -> R.string.battle_err_busy
    ErrorCode.BAD_SETTINGS -> R.string.battle_err_settings
    ErrorCode.BAD_ACTION -> R.string.battle_err_action
    else -> R.string.battle_err_not_allowed
  })

  companion object {
    val TIME_LIMITS = listOf(5, 10, 15, 20, 30, 60)
    val HAND_CHOICES = listOf(1, 2, 4, 8, 12, 16)
  }
}

