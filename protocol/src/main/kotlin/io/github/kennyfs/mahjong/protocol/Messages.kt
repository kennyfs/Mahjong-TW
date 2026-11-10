package io.github.kennyfs.mahjong.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The battle protocol: JSON messages, one per WebSocket frame or Nearby payload, with their type in "t". The room
 * (on the server, or on the hosting phone) is the only authority: it deals, holds every table's record, sends each
 * player only what their seat sees (the engine's `view` of that seat), takes their decisions, plays the bots and
 * those who run out of time, and scores. See docs/battle.md.
 *
 * Tiles are the engine's names ("1m" ... "9s", "1z" ... "7z", "1f" ... "8f"); a hand's record and a seat's view are
 * the engine's JSON (docs/gui-engine.md), passed on as they are.
 */
const val PROTOCOL_VERSION = 1

val BattleJson = Json {
  classDiscriminator = "t"
  ignoreUnknownKeys = true
  encodeDefaults = true
}

@Serializable
enum class Mode {
  /** One table: one to four people, bots in the empty seats; scored as usual, the most points wins. */
  TABLE,
  /**
   * 同牌賽: everyone plays the same hands (the same wall, seat and dealer) at a table of their own against bots that
   * decide the same way for the same play; a hand's score is the player's points in it.
   */
  CONTEST,
}

/** How long a table game is: one hand, one round (一圈), or a full game of four rounds (一將). */
@Serializable
enum class Length { HAND, ROUND, GAME }

/** The rules as the app's rules page has them: a tai preset, a flower preset and changed values (docs/rules.md 11). */
@Serializable
data class RuleChoice(val tai: String = "simple1", val flowers: String = "none", val overrides: Map<String, Int> = emptyMap())

@Serializable
data class RoomSettings(
  val mode: Mode,
  /** TABLE: how long the game is. */
  val length: Length = Length.HAND,
  /** CONTEST: how many hands. */
  val hands: Int = 4,
  val rules: RuleChoice = RuleChoice(),
  /** Seconds a player has for each decision, one of Limits.TIME_LIMITS; then the bot decides for them. */
  val timeLimit: Int = 15,
  /** The bots' spec (Validation.BOTS): the empty seats', and the choice for one who runs out of time or left. */
  val bot: String = "greedy",
)

@Serializable
enum class Phase {
  LOBBY,
  /** A hand is being played. */
  PLAYING,
  /** The hand is over and its result shown, until everyone is ready for the next one. */
  REVIEW,
  OVER,
}

@Serializable
data class PlayerView(
  val id: Int,
  val name: String,
  val bot: Boolean = false,
  val connected: Boolean = true,
  /** TABLE: the seat at the table (0 to 3) once started; CONTEST: -1. */
  val seat: Int = -1,
  val ready: Boolean = false,
  /** CONTEST: has finished the current hand. */
  val done: Boolean = false,
  /** Points over the finished hands. */
  val total: Double = 0.0,
  val left: Boolean = false,
)

@Serializable
data class RoomView(
  val pin: String,
  val settings: RoomSettings,
  /** The rules as the engine spells them out ("flowers=0,flower_tai=1,..."). */
  val rules: String,
  val phase: Phase,
  /** The current or last hand, from 0; -1 in the lobby. */
  val hand: Int,
  val host: Int,
  val players: List<PlayerView>,
  /** The time left of the review between hands. */
  val remainingMs: Long? = null,
)

/** A decision: as the engine's legal actions have it ({"type":"chi","tile":LOWEST,"tiles":[...]}). */
@Serializable
data class Action(val type: String, val tile: String? = null, val tiles: List<String>? = null)

/** A table of a closed hand: who sat where (player ids; -1 a contest's bot), its record, and each seat's points. */
@Serializable
data class TablePlay(
  val seats: List<Int>,
  /** The hand's record (rules, setup, wall, every decision): all of it, now that the hand is over. */
  val record: JsonObject,
  val points: List<Double>,
  /** Per seat: how many of its decisions the bot made (the player ran out of time, or had left). */
  val timeouts: List<Int> = listOf(0, 0, 0, 0),
)

/** A closed hand: TABLE one table; CONTEST each player's own table (the same wall), in the order of `players`. */
@Serializable
data class HandOutcome(
  val index: Int,
  val tables: List<TablePlay>,
  val players: List<Int> = emptyList(),
)

@Serializable
sealed interface ClientMsg

@Serializable @SerialName("hello")
data class Hello(val version: Int, val name: String) : ClientMsg

@Serializable @SerialName("create")
data class Create(val settings: RoomSettings) : ClientMsg

@Serializable @SerialName("join")
data class Join(val pin: String) : ClientMsg

/** Back into a room after a dropped connection, with the token of Joined. */
@Serializable @SerialName("resume")
data class Resume(val room: String, val token: String) : ClientMsg

/** The host changes the settings in the lobby. */
@Serializable @SerialName("settings")
data class ChangeSettings(val settings: RoomSettings) : ClientMsg

@Serializable @SerialName("start")
data object Start : ClientMsg

/** A decision at the player's table: `step` is the TableView's (the record's length it was made on). */
@Serializable @SerialName("act")
data class Act(val hand: Int, val step: Int, val action: Action) : ClientMsg

/** Done looking at the result of a hand. */
@Serializable @SerialName("ready")
data class Ready(val hand: Int) : ClientMsg

@Serializable @SerialName("leave")
data object Leave : ClientMsg

@Serializable
sealed interface ServerMsg

@Serializable @SerialName("welcome")
data class Welcome(val version: Int) : ServerMsg

/** In a room as player `you`; `token` (secret) resumes the seat after a dropped connection. */
@Serializable @SerialName("joined")
data class Joined(val room: String, val you: Int, val token: String) : ServerMsg

@Serializable @SerialName("state")
data class State(val room: RoomView) : ServerMsg

/**
 * What the player's seat sees now: the engine's view of `seat` (the position, whose turn, the legal actions when the
 * player must decide, the events since the last TableView: all of them when `full`). `step` goes with an Act;
 * `remainingMs` is the time left to decide.
 */
@Serializable @SerialName("table")
data class TableView(
  val hand: Int,
  val seat: Int,
  val step: Int,
  val view: JsonObject,
  val full: Boolean = false,
  val remainingMs: Long? = null,
  /** The players at the seats (ids; -1 a contest's bot). */
  val seats: List<Int> = emptyList(),
) : ServerMsg

@Serializable @SerialName("result")
data class HandResult(val outcome: HandOutcome) : ServerMsg

/** Every closed hand so far, after Resume. */
@Serializable @SerialName("history")
data class History(val outcomes: List<HandOutcome>) : ServerMsg

@Serializable @SerialName("error")
data class Error(val code: ErrorCode, val message: String = "") : ServerMsg

/** The room is gone (closed, or the player left); the connection may stay for another room. */
@Serializable @SerialName("closed")
data class Closed(val reason: String = "") : ServerMsg

@Serializable
enum class ErrorCode {
  VERSION, BAD_MESSAGE, NOT_ALLOWED, NO_ROOM, ROOM_FULL, STARTED, TOO_MANY, BUSY, BAD_SETTINGS, BAD_ACTION, RATE,
}
