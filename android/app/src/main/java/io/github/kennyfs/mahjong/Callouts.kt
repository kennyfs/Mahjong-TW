package io.github.kennyfs.mahjong

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The words over the players (ui/TableView.kt: a call, a win, a flower) and the moments (ui/Moments.kt): how long they
 * stay, and waiting for them. The computer players of a practice game wait for a call's words or moment before they go
 * on, so that it can be followed; a touch anywhere (MainActivity) lets them go at once, and whoever waited goes on. The
 * web page's lib/callouts.ts.
 */
object CalloutClock {
  /** A call's, a declaration's or a win's words, ms (the manual pace keeps them until the next touch; this is how long
   *  they stay where nothing waits for the player). */
  fun callMs(): Long = Timing.ms("words_call")

  /** A flower's (there are many, and nothing is decided): nothing waits for them. */
  fun flowerMs(): Long = Timing.ms("words_flower")

  /** When the words or moments that are waited for end (SystemClock.uptimeMillis), 0 when none are up. */
  private val until = MutableStateFlow(0L)

  /** The touches that let the words go: the table drops its words (and its moments) when this changes. */
  val skips = MutableStateFlow(0)

  /** The events the table has looked at (ui/TableView.kt TableEvents: their words and moments are up by then). */
  private val seen = MutableStateFlow(-1)

  /** Words or moments went up for `ms`, to be waited for. */
  fun shown(ms: Long) {
    until.value = maxOf(until.value, SystemClock.uptimeMillis() + ms)
  }

  /** The table looked at the view of `count` events. */
  fun saw(count: Int) {
    seen.value = count
  }

  /** Returns once the table has looked at the view of `count` events, so that the words and moments that view brings
   *  are up and `done` waits for them: the table looks a composition after the view changed, and without this a
   *  computer player's discard after its chow or pung came with the call's moment, not after it. At most
   *  FxClock.START: a table not on the screen never looks. */
  suspend fun settle(count: Int) {
    withTimeoutOrNull(FxClock.START) { seen.first { it >= count } }
  }

  /** The words were let go (a touch anywhere). */
  fun skip() {
    until.value = 0L
    skips.value += 1
  }

  /** Whoever waits goes on now (another game: the last one's loop sees it is gone), the words left as they are. */
  fun release() {
    until.value = 0L
  }

  /** Returns when no words that are waited for are up: at once (false) when there are none, else (true) at their end. */
  suspend fun done(): Boolean {
    var waited = false
    while (true) {
      val end = until.value
      val left = end - SystemClock.uptimeMillis()
      if (left <= 0) return waited
      waited = true
      // Until their time is up, or it changed: a touch (none are up then) or other words (later).
      withTimeoutOrNull(left) { until.first { it != end } }
    }
  }
}

/**
 * Whether the table's moments (ui/Moments.kt) are on the screen: a hand's result waits for them to end before it shows,
 * so that the two never cover each other; the table as it ended then waits for a tap (ui/TapOn). The web page's
 * lib/fx.svelte.ts.
 */
object FxClock {
  /** A moment is up (from when the first of a run comes till the last goes: not between two). */
  val on = MutableStateFlow(false)

  /** A hand's result over the whole screen (a win, a draw) is up now: it stops for the player's tap. */
  val resultOn = MutableStateFlow(false)

  /** The result's moment was tapped on (or let go): the result comes after it at once; else (words alone) the table
   *  waits for a tap. Set again for each hand's end. */
  @Volatile var tapped = false

  /** The touch going on began over a moment (MainActivity, before the touch lets it go): it only let the moment go. */
  @Volatile var downOverFx = false

  /** Whether the moments show (AppModel sets it: the settings, and the system's animations); otherwise the words. */
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
