package io.github.kennyfs.mahjong

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The table's sounds (the web page's lib/sound.ts; the files are its public/sounds, copied in by the build: a name's
 * '-' as '_'): the shuffle, the tiles dealt, a tile discarded (a few takes, one at random each time), each call its
 * own (a chow, a pung, an open, concealed or added kong), a flower; at a hand's end, as the hands are shown: the player's own win, another's (neither gaining nor
 * losing by it), another's they pay for, a draw; with the result's moment, a jingle: the player's success, failure, or
 * neither; a declared ready hand (天聽, 地聽, 報聽): surprise. Each loaded once (SoundPool) at the start; one asked for
 * before it is in is not played late. The level: the settings' (off, soft, full). Nothing while the app is not on the
 * screen (`visible`, MainActivity).
 */
object Sounds {
  /** Each sound's files (takes of the same, one picked at random) and its own loudness at the full level. */
  enum class Name(val files: IntArray, val gain: Float) {
    SHUFFLE(intArrayOf(R.raw.shuffle), 0.8f),
    DEAL(intArrayOf(R.raw.deal_1, R.raw.deal_2), 0.55f),
    DISCARD(intArrayOf(R.raw.discard_1, R.raw.discard_2, R.raw.discard_3), 0.45f),
    CALL(intArrayOf(R.raw.call), 0.75f),
    KONG(intArrayOf(R.raw.kong), 0.75f),
    WIN(intArrayOf(R.raw.win), 0.7f),
    OTHER(intArrayOf(R.raw.other), 0.7f),
    LOSE(intArrayOf(R.raw.lose), 0.7f),
    DRAWN(intArrayOf(R.raw.drawn), 0.6f),
    FLOWER(intArrayOf(R.raw.flower), 0.5f),
    SUCCESS(intArrayOf(R.raw.success), 0.3f),
    FAILURE(intArrayOf(R.raw.failure), 0.3f),
    NEUTRAL(intArrayOf(R.raw.neutral), 0.22f),
    DECLARE(intArrayOf(R.raw.declare), 0.45f),
    CHI(intArrayOf(R.raw.chi), 0.7f),
    PON(intArrayOf(R.raw.pon), 0.75f),
    KAN(intArrayOf(R.raw.kan), 0.8f),
    ANKAN(intArrayOf(R.raw.ankan), 0.7f),
    KAKAN(intArrayOf(R.raw.kakan), 0.7f),
  }

  /** A call's own sound (a chow, a pung, an open, concealed or added kong); CALL for any other. */
  fun callSound(type: String): Name = when (type) {
    "chi" -> Name.CHI
    "pon" -> Name.PON
    "kan" -> Name.KAN
    "ankan" -> Name.ANKAN
    "kakan" -> Name.KAKAN
    else -> Name.CALL
  }

  private var pool: SoundPool? = null
  /** A file (its resource) → its sound in the pool. */
  private val ids = HashMap<Int, Int>()
  private val loaded = ConcurrentHashMap.newKeySet<Int>()
  private val handler = Handler(Looper.getMainLooper())

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

  /** Plays a sound, `delay` ms from now (a discard's when it lands, a meld's tile as it lands in its gap). Nothing when
   *  the settings have them off or the app is not on the screen. Gives what calls it off if it has not started yet. */
  fun play(name: Name, delay: Long = 0): () -> Unit {
    if (delay <= 0) {
      start(name)
      return {}
    }
    val r = Runnable { start(name) }
    handler.postDelayed(r, delay)
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
