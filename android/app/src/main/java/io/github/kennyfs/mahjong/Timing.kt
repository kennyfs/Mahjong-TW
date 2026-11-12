package io.github.kennyfs.mahjong

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * The pace of the game: how long the computer players wait before they play, the words over a player stay, and the
 * moments of calls, wins and the rest last. One file holds it for the app and the web page alike (res/raw/timing.json;
 * the page's lib/timing.ts):
 *
 * - `ms`: each time at the normal pace, in ms;
 * - `speed`: the slow and the fast pace as factors of the normal one (the manual pace is the normal one), so that a time
 *   changed once changes at every pace; `fixed`: the times that do not follow the pace (a notice's: reading);
 * - `review_end`: a review's moments of a hand's end (small, by the player) as a factor of their time;
 * - `style`: the looks picked with the moments' tuning tool (the web page's branch fx-dev), the only ones here.
 */
object Timing {
  @Serializable
  private class Pace(val slow: Double = 1.0, val fast: Double = 1.0)

  @Serializable
  private class File(
    val speed: Pace = Pace(), val fixed: List<String> = emptyList(), val ms: Map<String, Long> = emptyMap(),
    @SerialName("review_end") val reviewEnd: Double = 1.0,
  )

  private var file = File()

  /** The pace of the settings (AppModel sets it). */
  var speedOf: () -> Speed = { Speed.NORMAL }

  /** Reads the file (AppModel, at the start). */
  fun load(context: Context) {
    file = EngineJson.decodeFromString(File.serializer(), context.resources.openRawResource(R.raw.timing).bufferedReader().use { it.readText() })
  }

  /** The pace's factor (the manual pace: the normal one). */
  fun factor(speed: Speed = speedOf()): Double = when (speed) {
    Speed.SLOW -> file.speed.slow
    Speed.FAST -> file.speed.fast
    else -> 1.0
  }

  /** A time at the settings' pace, ms (a name the file lacks: 0). */
  fun ms(key: String): Long {
    val v = file.ms[key] ?: 0L
    return if (key in file.fixed) v else (v * factor()).roundToLong()
  }

  /** A review's moments of a hand's end: this factor of their time. */
  val reviewEnd: Double get() = file.reviewEnd
}
