package io.github.kennyfs.mahjong

import org.junit.Assert.assertEquals
import org.junit.Test

/** How much of a sound the next animation waits to have been heard (Sounds.heardFor). */
class SoundsTest {
  @Test fun heardFor() {
    // A call's cue (650 ms) as its moment (1200 ms) starts: all of it.
    assertEquals(650L, Sounds.heardFor(650, 0, 1200))
    // The shuffle (2901 ms) over the shuffle and its pause (2900 ms): to the animation's end.
    assertEquals(1100L, Sounds.heardFor(2901, 0, 1100))
    // A meld's tile set down 900 ms into a 1000 ms moment: the 100 ms left.
    assertEquals(100L, Sounds.heardFor(212, 900, 1000))
    // A tile landing as its flight ends (by default the animation ends as the sound starts): only started.
    assertEquals(0L, Sounds.heardFor(212, 500, 500))
    assertEquals(0L, Sounds.heardFor(212, 0, 0))
  }
}
