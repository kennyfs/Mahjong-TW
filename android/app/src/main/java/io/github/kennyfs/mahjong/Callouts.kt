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
  /** A call's, a declaration's or a win's words, ms. */
  const val CALL_MS = 2000L

  /** A flower's (there are many, and nothing is decided): brief, and nothing waits for them. */
  const val FLOWER_MS = 900L

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
