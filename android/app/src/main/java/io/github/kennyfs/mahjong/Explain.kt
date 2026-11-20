package io.github.kennyfs.mahjong

import android.content.res.Resources
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// A decision of a review explained by rules, without a language model (解說; the web page's lib/explain.ts): which
// lesson it is, the signs on the table that call for it, and a rule of thumb, with figures from self-play
// (docs/explain.md). What it compares: the action played against the best, when it lost; when the best was played and
// tile efficiency would have lost, tile efficiency's choice against the best ("well played").

/** A decision explained: `key` the lesson (e.g. "defend-step"); `found`: the best was played, and what is explained is
 *  tile efficiency's choice; `choice`: "You: … · Better: …"; `you`/`alt`: structured labels for chip display;
 *  `signs`: the opponent's, when it is about defense. */
data class Explanation(
  val key: String, val found: Boolean, val title: String, val choice: String, val signs: String?, val text: List<String>, val rule: String?,
  val you: String = "", val alt: String = "",
)

object Explain {
  // From 200,000 hands of the shipped network playing itself (simple2, see; docs/explain.md section 3). THREAT: the
  // share of opponents truly ready by "discards bucket,melds,two or more 摸切 at the end" (discards: up to 2, 3-5, 6-8,
  // 9-11, 12 and more; melds up to 4). SAFETY: a tile's chance of dealing in to a ready opponent by "what it is to it,
  // rank, copies seen on the table besides the seat's own (up to 3)".
  private val THREAT = mapOf(
    "0,0,0" to 0.0005, "0,1,0" to 0.0034, "1,0,0" to 0.0128, "1,1,0" to 0.0376, "2,1,0" to 0.1398, "2,0,0" to 0.0734,
    "2,2,0" to 0.2623, "1,1,1" to 0.1163, "1,2,0" to 0.108, "2,3,0" to 0.4781, "1,3,0" to 0.2893, "2,1,1" to 0.3282,
    "1,2,1" to 0.2392, "0,2,0" to 0.0177, "2,2,1" to 0.4655, "3,2,0" to 0.3898, "3,1,0" to 0.2485, "3,0,0" to 0.1568,
    "3,3,0" to 0.5912, "3,1,1" to 0.5641, "2,0,1" to 0.2429, "3,2,1" to 0.6731, "3,3,1" to 0.7967, "1,0,1" to 0.0614,
    "4,2,0" to 0.3665, "4,2,1" to 0.84, "4,1,0" to 0.2462, "2,4,0" to 0.8232, "3,4,0" to 0.8774, "4,3,1" to 0.9003,
    "4,4,0" to 0.7736, "4,3,0" to 0.5427, "4,1,1" to 0.7823, "4,0,0" to 0.1694, "3,0,1" to 0.4842, "4,0,1" to 0.7323,
    "2,3,1" to 0.6618, "1,3,1" to 0.455, "3,4,1" to 0.9411, "4,4,1" to 0.9678, "0,0,1" to 0.002, "1,4,0" to 0.6906,
    "2,4,1" to 0.8854,
  )
  private val SAFETY = mapOf(
    "x,37,0" to 0.1221, "x,456,0" to 0.1326, "h,456,1" to 0.0587, "x,456,1" to 0.0897, "x,28,0" to 0.1137,
    "s,19,0" to 0.0445, "s,37,0" to 0.0775, "x,19,2" to 0.0289, "x,19,0" to 0.0912, "x,z,0" to 0.0344,
    "x,z,1" to 0.0053, "g,28,1" to 0.0083, "x,37,1" to 0.0744, "g,456,1" to 0.0101, "g,z,2" to 0.0,
    "x,19,1" to 0.0525, "g,28,2" to 0.0055, "x,28,1" to 0.0676, "g,z,1" to 0.0028, "g,19,1" to 0.0087,
    "s,456,0" to 0.0573, "h,456,0" to 0.1003, "s,19,3" to 0.0017, "s,37,2" to 0.0127, "g,456,2" to 0.006,
    "s,19,1" to 0.0085, "x,z,2" to 0.0001, "x,37,2" to 0.0395, "h,456,2" to 0.0277, "g,37,1" to 0.0055,
    "x,28,2" to 0.0342, "g,37,2" to 0.0041, "x,z,3" to 0.0, "s,37,1" to 0.0319, "x,456,2" to 0.0527,
    "g,28,3" to 0.0035, "s,28,0" to 0.0604, "g,z,3" to 0.0, "x,37,3" to 0.0268, "g,37,3" to 0.0023,
    "s,28,1" to 0.022, "g,19,2" to 0.0062, "s,28,2" to 0.0058, "g,19,3" to 0.0041, "x,19,3" to 0.0339,
    "x,28,3" to 0.0312, "s,456,1" to 0.024, "s,37,3" to 0.0045, "h,456,3" to 0.0256, "g,456,3" to 0.0044,
    "s,456,3" to 0.0041, "x,456,3" to 0.0482, "s,456,2" to 0.0105, "s,19,2" to 0.0034, "s,28,3" to 0.0022,
  )

  /** The tips' tables (TipsContent): unconditional share ready by discards (3-5, 6-8, 9-11, 12+) and melds (0-4),
   *  matching docs/explain.md section 3 (monotonically increasing across discards and melds). */
  val TIPS_THREAT: List<List<Double?>> = listOf(
    listOf(0.01, 0.04, 0.11, 0.29, null),
    listOf(0.09, 0.16, 0.29, 0.50, 0.82),
    listOf(0.22, 0.33, 0.47, 0.66, 0.90),
    listOf(0.29, 0.42, 0.57, 0.74, 0.91),
  )
  /** How often the network turns to safety with an opponent who looks 60-80% ready: by its own hand (ready, 1, 2, 3+
   *  away) and the tiles left (30+, 20-29, 10-19, under 10). */
  val TIPS_FOLD = listOf(listOf(0.03, 0.04, 0.06, 0.19), listOf(0.13, 0.19, 0.30, 0.63), listOf(0.19, 0.34, 0.55, 0.54), listOf(0.31, 0.44, 0.40, 0.37))
  /** A tile's chance of dealing in to a ready opponent, ordered monotonically from safest to riskiest:
   *  by rank (honor, 1/9, 2/8, 3/7, 4-6) × [genbutsu, suji, non-suji 2 seen, non-suji 1 seen, non-suji unseen]. */
  val TIPS_SAFETY: List<List<Double?>> = listOf(
    listOf(0.001, null, 0.0, 0.005, 0.034),
    listOf(0.007, 0.025, 0.029, 0.053, 0.091),
    listOf(0.007, 0.040, 0.034, 0.068, 0.114),
    listOf(0.005, 0.056, 0.040, 0.074, 0.122),
    listOf(0.008, 0.042, 0.053, 0.090, 0.133),
  )
  const val TIPS_HALF = 0.082

  /** A rank's class: z (an honor), 19, 28, 37, 456. */
  fun rankClass(k: Int): String {
    if (k >= 27) return "z"
    val r = k % 9
    return if (r == 0 || r == 8) "19" else if (r == 1 || r == 7) "28" else if (r == 2 || r == 6) "37" else "456"
  }

  /** A tile to an opponent that discarded `disc` (kinds): g its own discard (現物), s suji (筋), h half suji, x other. */
  fun tileCat(k: Int, disc: Set<Int>): Char {
    if (k in disc) return 'g'
    if (k >= 27) return 'x'
    val r = k % 9
    val lo = r >= 3 && (k - 3) in disc
    val hi = r <= 5 && (k + 3) in disc
    return when {
      r <= 2 -> if (hi) 's' else 'x'
      r >= 6 -> if (lo) 's' else 'x'
      lo && hi -> 's'
      lo || hi -> 'h'
      else -> 'x'
    }
  }

  /** The share ready of opponents with these signs. */
  fun threatOf(discards: Int, melds: Int, tsumogiriTail: Int): Double? {
    val nd = if (discards <= 2) 0 else if (discards <= 5) 1 else if (discards <= 8) 2 else if (discards <= 11) 3 else 4
    return THREAT["$nd,${min(melds, 4)},${if (tsumogiriTail >= 2) 1 else 0}"]
  }

  fun kindOf(t: String): Int = "mpsz".indexOf(t[1]) * 9 + (t[0] - '0') - 1
  private fun counts(tiles: List<String>): IntArray {
    val c = IntArray(34)
    for (x in tiles) if (x[1] != 'f') c[kindOf(x)]++
    return c
  }

  // The most complete sets, then partial ones (pairs, two of a chow), of one suit's counts (study.cpp's).
  private fun bestBlocks(c: IntArray, start: Int, sets: Int, parts: Int, best: IntArray) {
    var i = start
    while (i < 9 && c[i] == 0) i++
    if (i >= 9) {
      if (sets > best[0] || (sets == best[0] && parts > best[1])) { best[0] = sets; best[1] = parts }
      return
    }
    if (c[i] >= 3) { c[i] -= 3; bestBlocks(c, i, sets + 1, parts, best); c[i] += 3 }
    if (i <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
      c[i]--; c[i + 1]--; c[i + 2]--; bestBlocks(c, i, sets + 1, parts, best); c[i]++; c[i + 1]++; c[i + 2]++
    }
    if (c[i] >= 2) { c[i] -= 2; bestBlocks(c, i, sets, parts + 1, best); c[i] += 2 }
    if (i <= 7 && c[i + 1] > 0) { c[i]--; c[i + 1]--; bestBlocks(c, i, sets, parts + 1, best); c[i]++; c[i + 1]++ }
    if (i <= 6 && c[i + 2] > 0) { c[i]--; c[i + 2]--; bestBlocks(c, i, sets, parts + 1, best); c[i]++; c[i + 2]++ }
    c[i]--; bestBlocks(c, i, sets, parts, best); c[i]++
  }
  private fun blocks(h: IntArray): Pair<Int, Int> {
    var sets = 0
    var parts = 0
    for (s in 0 until 3) {
      val best = intArrayOf(-1, -1)
      bestBlocks(h.copyOfRange(s * 9, s * 9 + 9), 0, 0, 0, best)
      sets += best[0]; parts += best[1]
    }
    for (k in 27 until 34) if (h[k] >= 3) sets++ else if (h[k] == 2) parts++
    return sets to parts
  }

  /** What a discard of kind k takes from the hand: zpair, zlone, zset (an honor's), set (breaks a set), pair, penchan,
   *  kanchan, ryanmen (breaks a partial set), lone (an isolated number tile), extra. */
  fun roleOf(c: IntArray, k: Int): String {
    if (k >= 27) return if (c[k] == 1) "zlone" else if (c[k] == 2) "zpair" else "zset"
    val b0 = blocks(c)
    c[k]--
    val b1 = blocks(c)
    c[k]++
    if (b1.first < b0.first) return "set"
    if (c[k] >= 2) return "pair"
    val r = k % 9
    val l1 = r >= 1 && c[k - 1] > 0
    val r1 = r <= 7 && c[k + 1] > 0
    val l2 = r >= 2 && c[k - 2] > 0
    val r2 = r <= 6 && c[k + 2] > 0
    if (b1.second < b0.second) {
      if (l1 || r1) {
        val edge = (r == 0 && r1) || (r == 8 && l1) || (r == 1 && l1 && !r1) || (r == 7 && r1 && !l1)
        return if (edge) "penchan" else "ryanmen"
      }
      return "kanchan"
    }
    return if (l1 || r1 || l2 || r2) "extra" else "lone"
  }

  private fun isWin(a: Action) = a.type == "ron" || a.type == "tsumo"
  private fun isCall(a: Action) = a.type == "chi" || a.type == "pon" || a.type == "kan"
  private val WEAK = setOf("shape", "wait-other", "other", "call-other")

  /** Tile efficiency's choice (python greedy, study.cpp): a win; a kong whenever it may; a call only when it lowers the
   *  shanten; else the discard with the fewest steps from ready and the most improving tiles, honors then terminals
   *  first on a tie. */
  fun greedyOf(a: Analysis): Int {
    val xs = a.actions
    val win = xs.indexOfFirst { isWin(it.action) }
    if (win >= 0) return win
    val pass = xs.indexOfFirst { it.action.type == "pass" }
    if (pass >= 0) {
      var at = pass
      xs.forEachIndexed { i, f -> if (isCall(f.action) && f.shanten < xs[at].shanten) at = i }
      return at
    }
    val kong = xs.indexOfFirst { it.action.type == "ankan" || it.action.type == "kakan" }
    if (kong >= 0) return kong
    fun order(f: ActionFigures): Int {
      val k = f.action.tile?.let(::kindOf) ?: 0
      return if (k >= 27) 0 else if (k % 9 == 0 || k % 9 == 8) 1 else 2
    }
    var at = -1
    xs.forEachIndexed { i, f ->
      if (f.action.type != "discard") return@forEachIndexed
      if (at < 0) { at = i; return@forEachIndexed }
      val b = xs[at]
      if (f.shanten < b.shanten || (f.shanten == b.shanten && (f.ukeire > b.ukeire || (f.ukeire == b.ukeire && order(f) < order(b))))) at = i
    }
    return at
  }

  /** 已經聽牌, 一向聽, 兩向聽, …; ready, 1 away, …. */
  fun steps(res: Resources, sh: Int): String = res.getStringArray(R.array.explain_steps)[sh.coerceIn(0, 7)]

  /** "約 4 成", "about 40%"; "約 3%"; "不到 1%". */
  fun chance(res: Resources, p: Double): String = when {
    p >= 0.195 -> res.getString(R.string.explain_tenths, (p * 10).roundToInt().toString())
    p >= 0.01 -> res.getString(R.string.explain_about, (p * 100).roundToInt().toString())
    else -> res.getString(R.string.explain_under_one)
  }

  /** Exact percent for deal-in rates: "約 12%", "約 9%", "不到 1%" (avoids jumping to "約 1 成" at 9.5%). */
  fun percent(res: Resources, p: Double): String =
    if (p >= 0.01) res.getString(R.string.explain_about, (p * 100).roundToInt().toString())
    else res.getString(R.string.explain_under_one)

  /**
   * The explanation of a reviewed decision of `view`'s seat, or null when there is nothing to say: the played action
   * lost less than a questionable decision and tile efficiency would not have lost more either. `view`: the seat's
   * view at the decision, its events from the start.
   */
  fun explain(res: Resources, a: Analysis, played: Int, view: View, unit: Double?): Explanation? {
    val best = a.best
    if (a.source != "network" || best == null || played < 0 || played >= a.actions.size) return null
    fun q(i: Int) = a.actions[i].q ?: 0.0
    val least = gradeFrom(1, unit)
    if (q(best) - q(played) >= least) return Lesson(res, a, view, false, q(best) - q(played) >= gradeFrom(2, unit)).of(played, best)
    // Well played: what was played against tile efficiency's choice, when that would have lost as much; said only
    // where there is a lesson in it.
    val g = greedyOf(a)
    if (g < 0 || g == played || q(played) - q(g) < least) return null
    val e = Lesson(res, a, view, true, false).of(g, played)
    return if (e.key in WEAK) null else e
  }

  private class Opp(val rel: Int, val ready: Double, val p: PlayerPos, val disc: Set<Int>, val passed: Set<Int>, val tail: Int)

  private class Lesson(val res: Resources, val an: Analysis, val view: View, val found: Boolean, val big: Boolean) {
    val pos = view.position
    val me = pos.me
    val c: IntArray
    val vis: IntArray
    val values = Rules.values(view.rules)
    val w = pos.wallLeft

    init {
      val hand = Tiles.parse(pos.hand).toMutableList()
      val drawn = pos.drawn
      if (view.phase == "self" && drawn != null && hand.size % 3 != 2) hand.add(drawn)
      c = counts(hand)
      // What the seat sees: its hand, every discard not taken, the melds' tiles (another's concealed kong hidden).
      vis = c.copyOf()
      for (pl in pos.players) {
        for (d in pl.discards) if (!d.called) vis[kindOf(d.tile)]++
        for (m in pl.melds) for (x in m.tiles.orEmpty()) vis[kindOf(x)]++
      }
    }

    fun s(id: Int, vararg args: Any): String = res.getString(id, *args)
    fun unseen(k: Int) = max(0, 4 - vis[k])
    val roundWind = pos.setup.round
    val seatWind = (me - pos.setup.dealer + 4) % 4
    fun honorTai(k: Int) = when {
      k >= 31 -> (values["dragon"] ?: 0) > 0
      k in 27..30 -> (values["any_wind"] ?: 0) > 0 ||
        (k - 27 == roundWind && (values["round_wind"] ?: 0) > 0) ||
        (k - 27 == seatWind && (values["seat_wind"] ?: 0) > 0)
      else -> false
    }
    fun name(f: ActionFigures) = f.action.tile?.let(Tiles::name) ?: ""
    fun kOf(f: ActionFigures) = f.action.tile?.let(::kindOf) ?: -1
    fun steps(sh: Int) = Explain.steps(res, sh)
    fun waitsLeft(f: ActionFigures) = f.waits.sumOf { it.left }
    fun waitTai(f: ActionFigures) = f.waits.maxOfOrNull { max(it.ronTai, it.tsumoTai) } ?: 0
    fun act(a: Action) = actionText(res, a)

    fun of(p: Int, b: Int): Explanation {
      val xs = an.actions
      val ap = xs[p]
      val ab = xs[b]
      val youAct = act(if (found) ab.action else ap.action)
      val altAct = act(if (found) ap.action else ab.action)
      fun out(key: String, title: Int, text: List<String>, rule: Int? = null, signs: String? = null) = Explanation(
        key, found, s(title),
        if (found) s(R.string.explain_choice_found, youAct, altAct) else s(R.string.explain_choice, youAct, altAct),
        signs, text, rule?.let { s(it) }, you = youAct, alt = altAct,
      )
      val t = ap.action.type
      val bt = ab.action.type

      // Wins.
      if (t == "ron" && bt == "pass") return out("win-wait", R.string.explain_t_win_wait, listOf(s(R.string.explain_win_wait, w, waitsLeft(ab))), R.string.explain_rule_win_wait)
      if (isWin(ab.action) && !isWin(ap.action)) return out("win-take", R.string.explain_t_win_take, listOf(s(R.string.explain_win_take)))
      // Kongs.
      if ((t == "ankan" || t == "kakan") && bt == "discard" && ap.shanten > ab.shanten)
        return out("kong-breaks", R.string.explain_t_kong, listOf(s(R.string.explain_kong_breaks, name(ap), steps(ab.shanten), steps(ap.shanten))), R.string.explain_rule_kong)
      // Calls.
      if (view.phase != "self") {
        val closed = pos.players[me].melds.all { it.type == "ankan" }
        val pass = xs.firstOrNull { it.action.type == "pass" }
        val sh = pass?.shanten ?: ab.shanten
        val word = s(if (t == "chi") R.string.act_chi else if (t == "pon") R.string.act_pon else R.string.act_kan).lowercase()
        if (isCall(ap.action) && bt == "pass") {
          // Late: a call raises the chance of dealing in, for little chance of winning (docs/explain.md section 7, 10).
          if (w <= 25) return out("call-late", R.string.explain_t_call_late,
            listOf(s(if (ap.shanten < sh) R.string.explain_call_late else R.string.explain_call_late_same, w, word)), R.string.explain_rule_call_late)
          // Closed and three or more away (two away the network takes nearly every call that brings it closer: section 7, 13).
          if (closed && sh >= 3) {
            val mq = values["menqing"] ?: 0
            val lose = if (mq > 0) s(R.string.explain_lose_menqing, mq, mq + (values["zimo"] ?: 0) + (values["menqing_zimo"] ?: 0)) else s(R.string.explain_lose_tiles)
            // (A call that leaves the hand as far: no faster at all.)
            val closedText = if (ap.shanten >= sh) R.string.explain_call_closed_same else R.string.explain_call_closed
            return out("call-closed", R.string.explain_t_call_closed, listOf(s(closedText, sh, word, lose)), R.string.explain_rule_call_closed)
          }
          // No faster: the network's chance of winning drops (about 5 points in its self-play); one step faster but not worth
          // it: its chance of dealing in rises (about 3) for no more wins (docs/explain.md section 4).
          return out("call-no", R.string.explain_t_call_no, listOf(
            if (ap.shanten >= ab.shanten) s(R.string.explain_call_no_same, word, steps(ap.shanten))
            else s(R.string.explain_call_no, word, steps(ap.shanten), steps(ab.shanten))))
        }
        val claimed = pos.claim?.tile ?: ab.action.tile
        if (t == "pass" && bt == "pon" && claimed != null) {
          val k = kindOf(claimed)
          if (k >= 27) return out("pon-honor", R.string.explain_t_pon_honor,
            listOf(s(R.string.explain_pon_honor, Tiles.name(claimed), if (honorTai(k)) s(R.string.explain_pon_tai) else "")), R.string.explain_rule_pon_honor)
          if (ab.shanten < sh) return out("call-yes", R.string.explain_t_call_yes, listOf(s(R.string.explain_call_yes, act(ab.action), steps(ab.shanten))))
          if (pass != null && ab.ukeire > pass.ukeire)
            return out("pon-same", R.string.explain_t_pon_same, listOf(s(R.string.explain_pon_same, Tiles.name(claimed), pass.ukeire, ab.ukeire)))
          return out("pon-same", R.string.explain_t_pon_same, listOf(s(R.string.explain_pon_same2, Tiles.name(claimed))))
        }
        if (t == "pass" && isCall(ab.action) && ab.shanten < sh)
          return out("call-yes", R.string.explain_t_call_yes, listOf(s(R.string.explain_call_yes, act(ab.action), steps(ab.shanten))))
        return out("call-other", R.string.explain_t_other, listOf(s(R.string.explain_other, act(ab.action), act(ap.action))))
      }
      if (t != "discard" || bt != "discard") return out("other", R.string.explain_t_other, listOf(s(R.string.explain_other, act(ab.action), act(ap.action))))

      // Discards: the opponent most likely ready, by the network, and what the seat can see of it.
      val opps = (1..3).map { r ->
        val seat = (me + r) % 4
        val pl = pos.players[seat]
        val o = an.opponents?.firstOrNull { it.seat == seat } ?: an.opponents?.getOrNull(r - 1)
        var tail = 0
        for (i in pl.discards.indices.reversed()) {
          // A call resets any prior draw-discard streak (kong replacement draws set both drawn and afterCall).
          if (pl.discards[i].afterCall || !pl.discards[i].drawn) break
          tail++
        }
        // The kinds discarded since its last discard: it let them go (過水), so it cannot win on them now.
        val passed = HashSet<Int>()
        for (e in view.events.asReversed()) {
          if (e.type != "discard") continue
          if (e.seat == seat) break
          e.tile?.let { passed.add(kindOf(it)) }
        }
        Opp(r - 1, o?.ready?.firstOrNull() ?: 0.0, pl, pl.discards.map { kindOf(it.tile) }.toSet(), passed, tail)
      }
      val top = opps.maxBy { it.ready }
      fun signsOf(o: Opp): String {
        val nd = o.p.discards.size
        val nm = o.p.melds.size
        val ss = mutableListOf(if (nd == 1) s(R.string.explain_sign_discards_one) else s(R.string.explain_sign_discards, nd))
        if (nm > 0) ss.add(if (nm == 1) s(R.string.explain_sign_melds_one) else s(R.string.explain_sign_melds, nm))
        if (o.tail >= 2) ss.add(s(R.string.explain_sign_tsumogiri, o.tail))
        // (Not a fresh call: at the same discards and melds it hardly changes the share ready, section 7, 2.)
        if (o.p.declared != null) ss.add(s(R.string.explain_sign_declared))
        val list = ss.joinToString(s(R.string.explain_list_sep))
        val seatName = res.getStringArray(R.array.explain_seats)[o.rel]
        val rate = threatOf(o.p.discards.size, o.p.melds.size, o.tail)
        if (rate == null || o.p.declared != null) return s(R.string.explain_signs, seatName, list)
        // The network reads more than these signs (which tiles, in what order): said when it differs much.
        val more = if (o.ready >= rate + 0.25) s(R.string.explain_signs_more) else if (o.ready <= rate - 0.25) s(R.string.explain_signs_less) else ""
        return s(R.string.explain_signs_ready, seatName, list, chance(res, rate)) + more
      }
      fun safety(o: Opp, f: ActionFigures): Pair<String, Double?> {
        val k = kOf(f)
        val nm = name(f)
        if (k in o.passed && k !in o.disc) return s(R.string.explain_tile_passed, nm) to 0.0
        val cat = tileCat(k, o.disc)
        val rc = rankClass(k)
        val seen = min(3, vis[k] - c[k])
        val rate = SAFETY["$cat,$rc,$seen"]
        return when (cat) {
          'g' -> s(R.string.explain_tile_genbutsu, nm) to rate
          's' -> s(R.string.explain_tile_suji, nm) to rate
          'h' -> s(R.string.explain_tile_half, nm) to rate
          else -> {
            val seenText = if (seen == 0) s(R.string.explain_seen_none) else s(R.string.explain_seen, seen)
            (if (k >= 27) s(R.string.explain_tile_honor, nm, seenText) else s(R.string.explain_tile_number, nm, s(rankName(rc)), seenText)) to rate
          }
        }
      }
      val dangerP = ap.danger ?: 0.0
      val dangerB = ab.danger ?: 0.0
      if (dangerP - dangerB >= 0.01 && top.ready >= 0.3) {
        val (lp, rp) = safety(top, ap)
        val (lb, rb) = safety(top, ab)
        val deal = if (rp != null && rb != null) s(R.string.explain_deal, lp, percent(res, rp), lb, percent(res, rb)) else s(R.string.explain_deal_short, lp, lb)
        if (ab.shanten > ap.shanten) return out("defend-step", R.string.explain_t_defend,
          listOf(deal, s(R.string.explain_defend_step, steps(ap.shanten), w, name(ab))), R.string.explain_rule_defend, signsOf(top))
        if (ab.shanten == 0 && ap.shanten == 0) return out("wait-safer", R.string.explain_t_wait,
          listOf(deal, s(R.string.explain_wait_safer, name(ab))), R.string.explain_rule_safer, signsOf(top))
        return out("defend-same", R.string.explain_t_safer,
          listOf(deal, if (ab.shanten < ap.shanten) s(R.string.explain_safer_faster, name(ab)) else s(R.string.explain_safer_same)),
          R.string.explain_rule_safer, signsOf(top))
      }
      if (dangerB - dangerP >= 0.01 && ap.shanten > ab.shanten)
        return out("push", R.string.explain_t_push, listOf(s(R.string.explain_push, steps(ab.shanten), w, name(ap))), R.string.explain_rule_push, signsOf(top))

      val kp = kOf(ap)
      val kb = kOf(ab)
      val hp = roleOf(c, kp)
      val hb = roleOf(c, kb)
      fun isPair(h: String) = h == "zpair" || h == "pair"
      fun tai(k: Int) = if (k >= 27 && honorTai(k)) s(R.string.explain_honor_tai) else ""
      fun joinedToRun(k: Int) = k < 27 && listOf(-2, -1, 1, 2).any { d -> val r = k % 9 + d; r in 0..8 && c[k + d] > 0 }
      // A pair that stands alone (no tile of a chow beside it) and can still be punged.
      fun livePair(k: Int) = c[k] == 2 && unseen(k) >= 1 && !joinedToRun(k)
      if (isPair(hp) && unseen(kp) >= 1 && !isPair(hb) && hb != "set" && hb != "zset") {
        val shape = s(shapeName(hb))
        val why = when (hb) {
          "kanchan", "penchan" -> s(R.string.explain_pair_wait, name(ap), unseen(kp), shape)
          "ryanmen" -> s(R.string.explain_pair_even)
          else -> s(R.string.explain_pair_lone, name(ab), name(ap), unseen(kp))
        }
        return out("pair-keep", R.string.explain_t_pair, listOf(s(R.string.explain_pair_keep, name(ap), name(ab), shape), why + tai(kp)), R.string.explain_rule_pair)
      }
      if (isPair(hp) && isPair(hb) && livePair(kp)) {
        val why = when {
          unseen(kb) == 0 -> s(R.string.explain_pair_dead, name(ab))
          joinedToRun(kb) -> s(R.string.explain_pair_joined, name(ab))
          else -> null
        }
        if (why != null) return out("pair-which", R.string.explain_t_pair_which, listOf(s(R.string.explain_pair_which, name(ab), name(ap), why)), R.string.explain_rule_pair_which)
      }
      if (hp == "zlone" && hb == "lone" && pos.players[me].discards.size <= 6 && top.ready < 0.2)
        return out("honor-later", R.string.explain_t_honor,
          listOf(s(R.string.explain_honor_later, name(ab), name(ap), if (honorTai(kp)) s(R.string.explain_honor_tai_short) else "")), R.string.explain_rule_honor)
      if (ab.shanten == 0 && ap.shanten == 0) {
        if (waitsLeft(ab) > waitsLeft(ap)) return out("wait-more", R.string.explain_t_wait, listOf(s(R.string.explain_wait_more, name(ab), waitsLeft(ab), name(ap), waitsLeft(ap))))
        if (waitTai(ab) > waitTai(ap)) return out("wait-tai", R.string.explain_t_wait, listOf(s(R.string.explain_wait_tai, name(ab), waitTai(ab), waitTai(ap))))
        if (dangerP - dangerB >= 0.01) return out("wait-safer", R.string.explain_t_wait, listOf(s(R.string.explain_wait_safer, name(ab))))
        if ((ab.win ?: 0.0) >= (ap.win ?: 0.0) + 0.03) return out("wait-easier", R.string.explain_t_wait, listOf(s(R.string.explain_wait_easier, name(ab))))
        return out("wait-other", R.string.explain_t_wait, listOf(s(R.string.explain_wait_other, name(ab))))
      }
      if (ap.shanten > ab.shanten)
        return out("eff-steps", R.string.explain_t_eff, listOf(s(R.string.explain_eff_steps, name(ap), steps(ab.shanten), steps(ap.shanten), name(ab))), R.string.explain_rule_eff)
      if (ab.shanten > ap.shanten) return out("slow", R.string.explain_t_slow, listOf(s(R.string.explain_slow, name(ap), steps(ap.shanten), name(ab), steps(ab.shanten))))
      if (ab.ukeire >= ap.ukeire + 4) return out("eff-more", R.string.explain_t_eff, listOf(s(R.string.explain_eff_more, name(ab), ab.ukeire, name(ap), ap.ukeire)), R.string.explain_rule_eff)
      return out("shape", R.string.explain_t_shape, listOf(s(if (big) R.string.explain_shape_big else R.string.explain_shape, name(ab), name(ap), ab.ukeire, ap.ukeire)))
    }
  }

  fun rankName(rc: String) = when (rc) {
    "19" -> R.string.explain_rank_19; "28" -> R.string.explain_rank_28; "37" -> R.string.explain_rank_37; else -> R.string.explain_rank_456
  }
  private fun shapeName(h: String) = when (h) {
    "kanchan" -> R.string.explain_shape_kanchan; "penchan" -> R.string.explain_shape_penchan; "ryanmen" -> R.string.explain_shape_ryanmen
    "lone", "zlone" -> R.string.explain_shape_lone; else -> R.string.explain_shape_extra
  }
}
