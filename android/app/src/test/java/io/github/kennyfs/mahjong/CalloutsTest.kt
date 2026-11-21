package io.github.kennyfs.mahjong

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CalloutClock and DealClock: seen resets across hands, and skip() does not prematurely release DealClock. */
class CalloutsTest {
  @Test fun releaseResetsSeenSoNextHandSettleWaits() = runBlocking {
    CalloutClock.saw(50)
    CalloutClock.release()

    var settled = false
    val job = async {
      CalloutClock.settle(1)
      settled = true
    }
    delay(40)
    assertFalse("settle(1) must wait after release() even though previous hand saw 50", settled)
    CalloutClock.saw(1)
    job.await()
    assertTrue(settled)
  }

  @Test fun skipDoesNotReleaseDealClockButReleaseDoes() = runBlocking {
    DealClock.arm("hand/0")
    val ready = async { DealClock.awaitReady() }
    delay(30)
    assertFalse(ready.isCompleted)

    // Off-table touches call skip(): DealClock stays armed until the table completes or releases it.
    CalloutClock.skip()
    delay(30)
    assertFalse("skip() must not release DealClock before the table deals", ready.isCompleted)

    // Completing another deal id does not release this one.
    DealClock.complete("other/0")
    delay(20)
    assertFalse(ready.isCompleted)

    // Completing the matching id releases it.
    DealClock.complete("hand/0")
    ready.await()
    assertTrue(ready.isCompleted)

    // release() (new game) does release an armed DealClock.
    DealClock.arm("hand/1")
    val ready2 = async { DealClock.awaitReady() }
    delay(20)
    assertFalse(ready2.isCompleted)
    CalloutClock.release()
    ready2.await()
    assertTrue(ready2.isCompleted)
  }
}
