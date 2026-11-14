package io.github.kennyfs.mahjong.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * How a room paces its tables (docs/battle.md): the times of the screens' timing.json
 * (android/app/src/main/res/raw/timing.json, at the normal pace), so that what a room sends comes as practice shows
 * it (the app's Claims.kt BotPace, the web page's lib/claims.ts):
 *
 * - a bot's own turn takes `botTurnMs` (after its own chow or pung `botAfterCallMs`) from when its turn came, and its
 *   discard is sent `discardMs` before that turn's end, as the screens fly it in to its river that long: a turn comes
 *   when the discard before it has landed (a bot's) or was made (a person's), or when a call's moment is over;
 * - a bot's call on a tile (a chow, a pung, a kong, a win) is sent `botClaimMs` after the tile came, never before it
 *   has landed; a tile no bot calls goes on at once;
 * - a call's moment (`callMs`) and a declaration's (`declareMs`, 天聽, 地聽, 報聽) are let finish before the bot that
 *   made it goes on; the deal's (`dealMs`) before anyone decides;
 * - at a table of two or more people, what follows a discard (or an added kong) waits `claimWindowMs` from it, whoever
 *   may claim it, so that a pause tells nobody who could. At a table of one person there is nobody to tell.
 *
 * A screen whose moments last longer (a slower pace in its settings) shows what the room sent once they are over.
 */
data class Pace(
  val claimWindowMs: Long,
  val botTurnMs: Long,
  val botClaimMs: Long,
  val botAfterCallMs: Long,
  val discardMs: Long,
  val callMs: Long,
  val declareMs: Long,
  val dealMs: Long,
) {
  companion object {
    /** A test's: no waiting at all. */
    val NONE = Pace(0, 0, 0, 0, 0, 0, 0, 0)

    /** The deal's tiles come in one after another over this much beyond their own flight (the screens' DealFx). */
    private const val DEAL_STAGGER_MS = 90L

    /** The times of timing.json's text (its `ms`, the normal pace). A time it lacks is an error. */
    fun fromTiming(text: String): Pace {
      val ms = Json.parseToJsonElement(text).jsonObject["ms"]?.jsonObject ?: throw IllegalArgumentException("timing.json has no ms")
      fun v(key: String): Long = ms[key]?.jsonPrimitive?.long ?: throw IllegalArgumentException("timing.json has no $key")
      return Pace(
        claimWindowMs = v("claim_window"),
        botTurnMs = v("bot_turn"),
        botClaimMs = v("bot_claim"),
        botAfterCallMs = v("bot_after_call"),
        discardMs = v("discard_move"),
        callMs = v("call_in") + v("call_drop") + v("call_hold") + v("call_out"),
        declareMs = v("declare"),
        dealMs = v("deal_shuffle") + v("deal_pause") + v("deal_fly") + DEAL_STAGGER_MS + v("deal_flip"),
      )
    }

    /** The protocol's copy of timing.json, put among its resources by its build (the server's). */
    fun standard(): Pace {
      val text = Pace::class.java.getResourceAsStream("timing.json")?.use { it.reader().readText() }
        ?: throw IllegalStateException("The protocol was built without timing.json")
      return fromTiming(text)
    }
  }
}
