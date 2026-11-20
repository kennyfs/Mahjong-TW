package io.github.kennyfs.mahjong

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The table's sounds (the web page's lib/sound.ts; the files are its public/sounds, copied in by the build: a name's
 * '-' as '_'): the shuffle, the tiles dealt, a tile discarded (a few takes, one at random each time), a call's, a
 * kong's (the tiles knocking into each other as the tile taken lands), and each call its cue as its moment starts
 * (steel pan notes: a chow, a pung, an open, concealed or added kong: something other than a discard happens), a
 * flower; at a hand's end, as the hands are shown: the player's own win, another's (neither gaining nor
 * losing by it), another's they pay for, a draw; with the result's moment, a jingle: the player's success, failure, or
 * neither; a declared ready hand (天聽, 地聽, 報聽): surprise. Each loaded once (SoundPool) at the start; one asked for
 * before it is in is not played late. The level: the settings' (off, soft, full). Nothing while the app is not on the
 * screen (`visible`, MainActivity).
 *
 * Quick to be heard: every sound is started on a thread of its own (never behind the screen's work), at its own pitch
 * (rate 1) and from 48 kHz files, as a phone's output is: SoundPool may then play it on the phone's fast path, which a
 * resampled sound never gets. In step with the screen: what an animation waits for (`awaitSynced`), see `play`.
 */
object Sounds {
  /** Each sound's files (takes of the same, one picked at random), its own loudness at the full level (at most 1:
   *  SoundPool's most; the files are each as loud as they can be), and how long it is, ms. */
  enum class Name(val files: IntArray, val gain: Float, val durationMs: Long) {
    SHUFFLE(intArrayOf(R.raw.shuffle), 0.94f, 2901L),
    DEAL(intArrayOf(R.raw.deal_1, R.raw.deal_2), 0.56f, 250L),
    DISCARD(intArrayOf(R.raw.discard_1, R.raw.discard_2, R.raw.discard_3), 0.64f, 212L),
    CALL(intArrayOf(R.raw.call), 0.94f, 162L),
    KONG(intArrayOf(R.raw.kong), 1f, 170L),
    WIN(intArrayOf(R.raw.win), 0.99f, 390L),
    OTHER(intArrayOf(R.raw.other), 0.93f, 411L),
    LOSE(intArrayOf(R.raw.lose), 0.88f, 443L),
    DRAWN(intArrayOf(R.raw.drawn), 0.75f, 424L),
    FLOWER(intArrayOf(R.raw.flower), 0.43f, 500L),
    SUCCESS(intArrayOf(R.raw.success), 0.38f, 1387L),
    FAILURE(intArrayOf(R.raw.failure), 0.38f, 1369L),
    NEUTRAL(intArrayOf(R.raw.neutral), 0.28f, 1052L),
    DECLARE(intArrayOf(R.raw.declare), 0.49f, 900L),
    CUE_CHI(intArrayOf(R.raw.cue_chi), 0.24f, 600L),
    CUE_PON(intArrayOf(R.raw.cue_pon), 0.33f, 650L),
    CUE_KAN(intArrayOf(R.raw.cue_kan), 0.36f, 850L),
    CUE_ANKAN(intArrayOf(R.raw.cue_ankan), 0.29f, 850L),
    CUE_KAKAN(intArrayOf(R.raw.cue_kakan), 0.28f, 700L),
  }

  /** A call's cue (a chow, a pung, an open, concealed or added kong); none for another. */
  fun cueOf(type: String): Name? = when (type) {
    "chi" -> Name.CUE_CHI
    "pon" -> Name.CUE_PON
    "kan" -> Name.CUE_KAN
    "ankan" -> Name.CUE_ANKAN
    "kakan" -> Name.CUE_KAKAN
    else -> null
  }

  private var pool: SoundPool? = null
  /** A file (its resource) → its sound in the pool. */
  private val ids = HashMap<Int, Int>()
  private val loaded = ConcurrentHashMap.newKeySet<Int>()
  /** The sounds' thread: started there, they are not held up by the screen's work. (Made when first used: the tests
   *  call `heardFor` without Android.) */
  private val handler by lazy { Handler(HandlerThread("sounds", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }.looper) }

  /** How much later a sound is heard than it is started, ms (a guess: SoundPool does not say). A sound timed to the
   *  screen (`delay` > 0) is started this much earlier, to be heard when the screen shows its moment. */
  private const val LATENCY = 60L
  /** At most this much longer than planned is waited for a sound (`awaitSynced`): one held up badly is let go. */
  private const val SLACK = 500L

  /** The settings' level (AppModel sets it). */
  var level: () -> SoundLevel = { SoundLevel.full }

  /** The app is on the screen (MainActivity: started, not stopped). */
  @Volatile var visible = true

  /** Loads the files (AppModel, at the start). */
  fun load(context: Context) {
    if (pool != null) return
    val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    val p = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
    p.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
    for (n in Name.entries) for (res in n.files) if (res !in ids) ids[res] = p.load(context, res, 1)
    pool = p
  }

  /**
   * How much of a sound (`length` ms) must have been heard before what comes after its animation starts: a sound
   * started `delay` ms into an animation that ends `end` ms from now is heard to its end if it ends before the
   * animation does, else only up to where it would be at the animation's end; one that comes as the animation ends
   * (a tile landing) need only have started.
   */
  internal fun heardFor(length: Long, delay: Long, end: Long): Long = minOf(length, maxOf(0L, end - maxOf(0L, delay)))

  /** The sounds started or to start that the screen waits for: a token → (planned, due), uptime ms. `due`: when
   *  enough of it will have been heard (`heardFor`); `planned`: that time as first planned. */
  private class Wait(val planned: Long, @Volatile var due: Long)
  private val waits = ConcurrentHashMap<Int, Wait>()
  private val tokens = AtomicInteger()
  /** Changes as `waits` do: wakes `awaitSynced`. */
  private val changed = MutableStateFlow(0)

  private fun touch() { changed.value = tokens.incrementAndGet() }

  /**
   * Plays a sound `delay` ms from now (a discard's when it lands, a meld's tile as it lands in its gap, the web page's
   * times): one timed so (`delay` > 0) is started LATENCY earlier, heard when the web page's is. `end`: when the
   * animation it belongs to ends, ms from now (by default as it starts: a sound that only has to have been heard by
   * then); what comes after the animation waits for it (`awaitSynced`, `heardFor`). Nothing when the settings have
   * them off or the app is not on the screen. Gives what calls it off if it has not started yet.
   */
  fun play(name: Name, delay: Long = 0, end: Long = delay): () -> Unit {
    if (level().gain * name.gain <= 0f || !visible || pool == null) return {}
    val heard = heardFor(name.durationMs, delay, end)
    val token = tokens.incrementAndGet()
    val now = SystemClock.uptimeMillis()
    // Heard `delay` from now, or LATENCY from now when that is later (it cannot be started earlier than now).
    val planned = now + maxOf(delay, LATENCY) + heard
    waits[token] = Wait(planned, planned)
    touch()
    val r = Runnable {
      val at = SystemClock.uptimeMillis()
      if (start(name)) {
        // Started now: heard LATENCY later.
        waits[token]?.due = at + LATENCY + heard
      } else waits.remove(token)
      touch()
    }
    val d = if (delay > 0) maxOf(0L, delay - LATENCY) else 0L
    handler.postDelayed(r, d)
    return {
      handler.removeCallbacks(r)
      if (waits.remove(token) != null) touch()
    }
  }

  /** Lets go of what the screen waits for: a touch let the animations go, or another game began. */
  fun skipSync() {
    waits.clear()
    touch()
  }

  /**
   * Returns once the sounds started or to start have been heard as far as their animations need (`play`): at once
   * when the sounds are off or the app is not on the screen; one held up is waited for at most SLACK past its plan.
   */
  suspend fun awaitSynced() {
    while (true) {
      if (level().gain <= 0f || !visible) return
      val seen = changed.value
      val now = SystemClock.uptimeMillis()
      var until = 0L
      for ((k, w) in waits) {
        val t = minOf(w.due, w.planned + SLACK)
        if (t <= now) waits.remove(k, w) else until = maxOf(until, t)
      }
      if (until == 0L) return
      withTimeoutOrNull(until - now) { changed.first { it != seen } }
    }
  }

  /** Starts a sound now (on the sounds' thread): whether it started. */
  private fun start(name: Name): Boolean {
    val p = pool ?: return false
    val g = level().gain * name.gain
    if (g <= 0f || !visible) return false
    val id = ids[name.files[Random.nextInt(name.files.size)]] ?: return false
    if (id !in loaded) return false
    // At its own pitch (a resampled sound is not played on the fast path): the takes are what varies.
    return p.play(id, g, g, 1, 0, 1f) != 0
  }
}
