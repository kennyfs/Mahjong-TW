package io.github.kennyfs.mahjong

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The words over the players (ui/TableView.kt: a call, a win, a flower): how long they stay, and waiting for them.
 * The computer players of a practice game wait for a call's words before they go on, so that it can be followed; a
 * touch anywhere (MainActivity) lets the words go at once, and whoever waited goes on. The web page's lib/callouts.ts.
 */
object CalloutClock {
  /** The pace of the settings (AppModel sets it): the words' times follow it. */
  var speedOf: () -> Speed = { Speed.NORMAL }

  /** A call's, a declaration's or a win's words, ms (the manual pace keeps them until the next touch; this is how long
   *  they stay where nothing waits for the player). */
  fun callMs(): Long = when (speedOf()) { Speed.SLOW -> 2000L; Speed.FAST -> 1000L; else -> 1500L }

  /** A flower's (there are many, and nothing is decided): brief, and nothing waits for them. */
  fun flowerMs(): Long = when (speedOf()) { Speed.SLOW -> 900L; Speed.FAST -> 500L; else -> 700L }

  /** A chow's, a pung's or a win's moment (ui/CallFx.kt): three and a half times the words' (the user found the
   *  words' time too short for it), and waited for as they are. */
  fun fxMs(): Long = callMs() * 7 / 2

  /** When the words that are waited for end (SystemClock.uptimeMillis), 0 when none are up. */
  private val until = MutableStateFlow(0L)

  /** The touches that let the words go: the table drops its words when this changes. */
  val skips = MutableStateFlow(0)

  /** Words went up for `ms`, to be waited for. */
  fun shown(ms: Long) {
    until.value = maxOf(until.value, SystemClock.uptimeMillis() + ms)
  }

  /** The words were let go (a touch anywhere). */
  fun skip() {
    until.value = 0L
    skips.value += 1
  }

  /** Returns when no words that are waited for are up: at once when there are none. */
  suspend fun done() {
    while (true) {
      val end = until.value
      val left = end - SystemClock.uptimeMillis()
      if (left <= 0) return
      // Until their time is up, or it changed: a touch (none are up then) or other words (later).
      withTimeoutOrNull(left) { until.first { it != end } }
    }
  }
}

/**
 * Whether a call's or a win's moment (ui/CallFx.kt, started by the table) is on the screen: a hand's result waits for
 * it to end before it shows, and the hold of the table as it ended (END_AFTER_FX) counts from its end. The web page's
 * lib/fx.svelte.ts.
 */
object FxClock {
  val on = MutableStateFlow(false)

  /** Whether the settings show the moments (AppModel sets it); otherwise the words alone. */
  var enabled: () -> Boolean = { true }

  /** How long a change may take to start its moment (the table's next composition: slow on a slow phone), ms. */
  const val START = 300L

  /** Returns when no moment is up: true when one was (it waited for its end), false when none was. A moment the change
   *  just made is waited for to start, for up to START (so false comes that late). */
  suspend fun done(): Boolean {
    if (withTimeoutOrNull(START) { on.first { it } } == null) return false
    on.first { !it }
    return true
  }
}
