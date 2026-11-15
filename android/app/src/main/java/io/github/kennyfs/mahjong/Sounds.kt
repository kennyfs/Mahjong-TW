package io.github.kennyfs.mahjong

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ConcurrentHashMap
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
 */
object Sounds {
  /** Each sound's files (takes of the same, one picked at random) and its own loudness at the full level: at most 1
   *  (SoundPool's most; the files are each as loud as they can be). */
  enum class Name(val files: IntArray, val gain: Float) {
    SHUFFLE(intArrayOf(R.raw.shuffle), 0.94f),
    DEAL(intArrayOf(R.raw.deal_1, R.raw.deal_2), 0.56f),
    DISCARD(intArrayOf(R.raw.discard_1, R.raw.discard_2, R.raw.discard_3), 0.64f),
    CALL(intArrayOf(R.raw.call), 0.94f),
    KONG(intArrayOf(R.raw.kong), 1f),
    WIN(intArrayOf(R.raw.win), 0.99f),
    OTHER(intArrayOf(R.raw.other), 0.93f),
    LOSE(intArrayOf(R.raw.lose), 0.88f),
    DRAWN(intArrayOf(R.raw.drawn), 0.75f),
    FLOWER(intArrayOf(R.raw.flower), 0.43f),
    SUCCESS(intArrayOf(R.raw.success), 0.38f),
    FAILURE(intArrayOf(R.raw.failure), 0.38f),
    NEUTRAL(intArrayOf(R.raw.neutral), 0.28f),
    DECLARE(intArrayOf(R.raw.declare), 0.49f),
    CUE_CHI(intArrayOf(R.raw.cue_chi), 0.24f),
    CUE_PON(intArrayOf(R.raw.cue_pon), 0.33f),
    CUE_KAN(intArrayOf(R.raw.cue_kan), 0.36f),
    CUE_ANKAN(intArrayOf(R.raw.cue_ankan), 0.29f),
    CUE_KAKAN(intArrayOf(R.raw.cue_kakan), 0.28f),
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
  /** The sounds' timers: a thread of their own, so that the screen's work does not hold them up. */
  private val handler = Handler(HandlerThread("sounds").apply { start() }.looper)

  /** How much later a sound is heard here than on the web page once started, ms: SoundPool's way out (it mixes, and
   *  resamples a discard's pitch) is a slow one, a browser's Web Audio a quick one. A sound timed to the screen is
   *  started this much earlier, to be heard as on the web page. */
  private const val LATENCY = 60L

  /** The settings' level (AppModel sets it). */
  var level: () -> SoundLevel = { SoundLevel.full }

  /** The app is on the screen (MainActivity). */
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

  /** Plays a sound, `delay` ms from now (a discard's when it lands, a meld's tile as it lands in its gap, the web
   *  page's times): one timed so (`delay` > 0) is started LATENCY earlier, heard when the web page's is. Nothing when
   *  the settings have them off or the app is not on the screen. Gives what calls it off if it has not started yet. */
  fun play(name: Name, delay: Long = 0): () -> Unit {
    val d = if (delay > 0) delay - LATENCY else 0L
    if (d <= 0) {
      start(name)
      return {}
    }
    val r = Runnable { start(name) }
    handler.postDelayed(r, d)
    return { handler.removeCallbacks(r) }
  }

  private fun start(name: Name) {
    val p = pool ?: return
    val g = level().gain * name.gain
    if (g <= 0f || !visible) return
    val id = ids[name.files[Random.nextInt(name.files.size)]] ?: return
    if (id !in loaded) return
    // A little life in the repeated ones: the pitch a shade up or down.
    val rate = if (name == Name.DISCARD || name == Name.DEAL) 0.96f + Random.nextFloat() * 0.08f else 1f
    p.play(id, g, g, 1, 0, rate)
  }
}
