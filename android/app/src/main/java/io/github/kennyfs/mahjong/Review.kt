package io.github.kennyfs.mahjong

import android.content.res.Resources
import io.github.kennyfs.mahjong.ui.actionLabel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// A reviewed hand: how its decisions are graded, what is worth stopping at, and the words and figures the screens
// show for it (docs/analysis.md). The web page's lib/review.ts: change both.
//
// A decision loses the best action's expected result less the played one's, in points (台). Losses under EQUAL are
// the network's own noise and count as nothing; the grades start at 0.2, 0.5 and 1 point for a typical payment of 4
// (the review's `unit` scales them to the hand's rules). The level of the settings says from which grade on a
// decision counts as a mistake.

const val EQUAL = 0.05
private val GRADE_STEPS = doubleArrayOf(0.2, 0.5, 1.0)
private fun scale(unit: Double?) = if (unit != null && unit > 0) unit / 4 else 1.0

/** 0: fine; 1: questionable; 2: a mistake; 3: a blunder. */
fun gradeOf(loss: Double, unit: Double?): Int {
  val x = loss / scale(unit)
  return if (x >= GRADE_STEPS[2]) 3 else if (x >= GRADE_STEPS[1]) 2 else if (x >= GRADE_STEPS[0]) 1 else 0
}

/** The smallest loss of a grade under these rules. */
fun gradeFrom(grade: Int, unit: Double?) = if (grade > 0) GRADE_STEPS[grade - 1] * scale(unit) else 0.0

val GRADE_NAME = listOf(R.string.grade_fine, R.string.grade_questionable, R.string.grade_mistake, R.string.grade_blunder)
fun levelWord(res: Resources, level: Level): String = res.getString(GRADE_NAME[level.grade])

private fun fixed(x: Double, digits: Int) = "%.${digits}f".format(Locale.ROOT, x)

/** "+0.31", "−1.20": points with two decimals (one from 10 up). */
fun pts(x: Double, signed: Boolean = true): String {
  val a = abs(x)
  val s = fixed(a, if (a >= 9.95) 1 else 2)
  if (!signed) return (if (x < 0) "−" else "") + s
  return (if (x < -0.005) "−" else if (x > 0.005) "+" else "±") + s
}

/** A loss on a tile's tag: "−0.3", "−12". */
fun tagCost(x: Double) = "−" + fixed(x, if (x >= 9.95) 0 else 1)

/** "3.1%", "18%". */
fun pct(p: Double): String {
  val x = p * 100
  return fixed(x, if (x < 9.95) 1 else 0) + "%"
}

/** How dangerous a discard is now: 0 under 1%, then up to 5%, up to 15%, and beyond. */
fun dangerLevel(p: Double?): Int = if (p == null || p < 0.01) 0 else if (p < 0.05) 1 else if (p < 0.15) 2 else 3

/** How likely an opponent is ready, for its color: 0 under 10%, then up to 30%, up to 60%, and beyond. */
fun readyLevel(p: Double): Int = if (p < 0.1) 0 else if (p < 0.3) 1 else if (p < 0.6) 2 else 3

/** Tiles in few words: 3m 6m 2p 5z -> "三六萬二筒白". */
fun tilesShort(tiles: List<String>): String = buildString {
  for (i in tiles.indices) {
    val x = tiles[i]
    val name = Tiles.name(x)
    val sameSuitNext = i + 1 < tiles.size && tiles[i + 1][1] == x[1] && x[1] in "mps"
    append(if (sameSuitNext) name.substring(0, 1) else name)
  }
}

/** An action in words: "打出 五萬", "吃 三四五萬". */
fun actionText(res: Resources, a: Action): String {
  val word = res.getString(actionLabel(a.type))
  val ts = actionTiles(a)
  return if (ts.isEmpty()) word else "$word ${ts.joinToString("") { Tiles.name(it) }}"
}

/** What action `i` costs against the best. */
fun costOf(a: Analysis, i: Int): Double = (a.actions[a.best ?: 0].q ?: 0.0) - (a.actions[i].q ?: 0.0)

private fun isWin(x: Action) = x.type == "ron" || x.type == "tsumo"
private fun isCall(x: Action) = x.type in setOf("pon", "chi", "kan", "ankan", "kakan")

/**
 * A best action that is not the obvious one, when it is clearly better than the obvious one: a win passed up, a
 * discard that gives up a step towards ready (to fold, or for a slower hand worth more), a call left alone. `alt`: the
 * obvious action's index, `margin`: how much the best is worth over it.
 */
data class Notable(val kind: Kind, val alt: Int, val margin: Double) {
  enum class Kind(val label: Int) {
    DECLINE_WIN(R.string.note_decline_win), FOLD(R.string.note_fold), SLOW(R.string.note_slow), NO_CALL(R.string.note_no_call),
  }
}

fun notableOf(a: Analysis, unit: Double?): Notable? {
  val bi = a.best ?: return null
  val best = a.actions[bi]
  val least = gradeFrom(1, unit)
  fun top(pick: (ActionFigures) -> Boolean): Int {
    var at = -1
    a.actions.forEachIndexed { i, f -> if (pick(f) && (at < 0 || (f.q ?: 0.0) > (a.actions[at].q ?: 0.0))) at = i }
    return at
  }
  fun out(kind: Notable.Kind, alt: Int): Notable? = if (alt >= 0 && costOf(a, alt) >= least) Notable(kind, alt, costOf(a, alt)) else null
  if (!isWin(best.action)) {
    val win = top { isWin(it.action) }
    if (win >= 0) return out(Notable.Kind.DECLINE_WIN, win)
  }
  if (best.action.type == "discard") {
    val fewest = a.actions.filter { it.action.type == "discard" }.minOf { it.shanten }
    if (best.shanten > fewest) {
      // To fold when most of the gain is in what the others win; else a slower hand that is worth more.
      val alt = top { it.action.type == "discard" && it.shanten == fewest }
      val b = best.parts
      val p = if (alt >= 0) a.actions[alt].parts else null
      val defence = if (b != null && p != null) b[1] - p[1] + (b[2] - p[2]) else 0.0
      return out(if (defence >= 0.5 * (if (alt >= 0) costOf(a, alt) else 0.0)) Notable.Kind.FOLD else Notable.Kind.SLOW, alt)
    }
  }
  if (best.action.type == "pass") return out(Notable.Kind.NO_CALL, top { isCall(it.action) && it.shanten < best.shanten })
  return null
}

fun notableText(res: Resources, a: Analysis, n: Notable): String {
  val alt = a.actions[n.alt].action
  val what = when (n.kind) {
    Notable.Kind.DECLINE_WIN -> res.getString(R.string.note_alt_win)
    Notable.Kind.NO_CALL -> res.getString(R.string.note_alt_call, actionText(res, alt))
    else -> res.getString(R.string.note_alt_efficient, alt.tile?.let(Tiles::name) ?: "")
  }
  return "${res.getString(n.kind.label)}（${res.getString(R.string.note_margin, what, pts(n.margin, false))}）"
}

/** Why `i` is worse than the best, from the parts of the two values: what it wins less, deals in more, is drawn on more. */
fun reasonOf(res: Resources, a: Analysis, i: Int): String {
  val bi = a.best ?: return ""
  if (i == bi || i !in a.actions.indices) return ""
  val b = a.actions[bi].parts ?: return ""
  val p = a.actions[i].parts ?: return ""
  if (b.size < 3 || p.size < 3) return ""
  val least = max(EQUAL, costOf(a, i) * 0.2)
  val names = listOf(
    R.string.reason_win_less to R.string.reason_win_more, R.string.reason_deal_more to R.string.reason_deal_less,
    R.string.reason_tsumo_more to R.string.reason_tsumo_less,
  )
  val worse = ArrayList<String>()
  val better = ArrayList<String>()
  for (k in 0..2) {
    val d = b[k] - p[k]
    if (d >= least) worse += res.getString(names[k].first, pts(d, false))
    else if (-d >= least) better += res.getString(names[k].second, pts(-d, false))
  }
  if (worse.isEmpty()) return ""
  return worse.joinToString("、") + if (better.isNotEmpty()) res.getString(R.string.reason_but) + better.joinToString("、") else ""
}

/** What a decision was about, for the statistics: a tile, a discard that deals in, a call, a win to take or not. */
enum class LossKind(val label: Int) {
  TILE(R.string.stats_kind_tile), DANGER(R.string.stats_kind_danger), CALL(R.string.stats_kind_call), WIN(R.string.stats_kind_win),
}

fun kindOf(d: ReviewDecision): LossKind {
  val a = d.analysis
  val acts = a.actions.map { it.action }
  if (acts.any(::isWin)) return LossKind.WIN
  val played = acts.getOrNull(d.played)
  val best = a.best?.let { acts.getOrNull(it) }
  if (acts.any { it.type == "pass" } || (played != null && isCall(played)) || (best != null && isCall(best))) return LossKind.CALL
  if (a.best != null && d.played >= 0) {
    val b = a.actions[a.best].parts
    val p = a.actions[d.played].parts
    if (b != null && p != null && b[1] - p[1] >= 0.5 * (d.loss ?: 0.0)) return LossKind.DANGER
  }
  return LossKind.TILE
}

/** A review's decision as the graph and the steps see it. `found`: a notable best action that was played. */
data class ReviewKey(val step: Int, val loss: Double, val grade: Int, val notable: Notable?, val found: Boolean)

fun keysOf(r: Review): List<ReviewKey> = r.decisions.map { d ->
  val loss = max(0.0, d.loss ?: 0.0)
  val notable = notableOf(d.analysis, r.unit)
  ReviewKey(d.step, loss, gradeOf(loss, r.unit), notable, notable != null && loss < EQUAL)
}

/** Whether the review stops here at this level: a mistake of the level, or a best action that is not the obvious one. */
fun isKey(k: ReviewKey, level: Level) = k.grade >= level.grade || k.notable != null

fun summarize(r: Review): ReviewSummary {
  val grades = IntArray(3)
  val kinds = DoubleArray(LossKind.entries.size)
  for (d in r.decisions) {
    val loss = max(0.0, d.loss ?: 0.0)
    val g = gradeOf(loss, r.unit)
    if (g > 0) grades[g - 1]++
    if (loss >= EQUAL) kinds[kindOf(d).ordinal] += loss
  }
  fun r2(x: Double) = Math.round(x * 100) / 100.0
  return ReviewSummary(
    Engine.MODEL_ID, r2(r.firstValue ?: 0.0), r2(r.loss ?: 0.0), r2(r.luck ?: 0.0), r2(r.unit ?: 4.0), r.decisions.size, grades.toList(),
    kinds.map(::r2),
  )
}

/** Mistakes of a summary at a level: the decisions of its grade and worse. */
fun mistakesOf(s: ReviewSummary, level: Level): Int = s.grades.drop(level.grade - 1).sum()

/** The level's grade and worse, for a count of them all: "失誤以上" (a beginner's is the worst alone). */
fun levelOrWorse(res: Resources, level: Level): String =
  if (level.grade < 3) res.getString(R.string.grade_or_worse, levelWord(res, level)) else levelWord(res, level)

/** Mistakes at a level in words, each grade by its own name from the worst down: "大失誤 1・疑問手 2" (`grades`: how
 *  many were questionable, mistakes, blunders); "沒有失誤" when there are none. */
fun mistakesText(res: Resources, level: Level, grades: List<Int>): String {
  val out = (3 downTo level.grade).filter { grades.getOrElse(it - 1) { 0 } > 0 }
    .map { res.getString(R.string.review_mistakes, res.getString(GRADE_NAME[it]), grades[it - 1].toString()) }
  return if (out.isNotEmpty()) out.joinToString("・") else res.getString(R.string.review_count_none, levelWord(res, level))
}

private val reviews = LinkedHashMap<String, Review>()

/** A record's review by the network, kept while the app runs (the last 40). From the main thread. */
suspend fun reviewOf(rec: HandRecord): Review {
  reviews[rec.id]?.let { return it }
  val r = Engine.review(rec.game, rec.me)
  reviews[rec.id] = r
  if (reviews.size > 40) reviews.remove(reviews.keys.first())
  return r
}

/** Reviews a record and keeps the summary with it, unless this network's is there. Failures are left for a later try. */
suspend fun keepSummary(records: Records, rec: HandRecord) {
  if (rec.rev?.m == Engine.MODEL_ID) return
  try {
    val r = reviewOf(rec)
    if (r.firstValue != null) records.setReview(rec.id, summarize(r))
  } catch (e: EngineException) {
    // No network: no summary.
  }
}

/** What a tile of the hand shows at a decision: a tag over it, its tone, whether it is the tile played, its danger. */
data class TileMark(val tag: String, val tone: Tone, val played: Boolean, val danger: Int) {
  enum class Tone { BEST, PLAIN, G1, G2, G3 }
}

/** The marks of a decision's discards by tile: what each costs against the best, and its danger. */
fun tileMarks(res: Resources, a: Analysis, played: Int, unit: Double?): Map<String, TileMark> {
  val out = HashMap<String, TileMark>()
  val bi = a.best ?: return out
  a.actions.forEachIndexed { i, f ->
    val tile = f.action.tile
    if (f.action.type != "discard" || tile == null) return@forEachIndexed
    val c = costOf(a, i)
    val mine = i == played
    val g = gradeOf(c, unit)
    out[tile] = TileMark(
      if (i == bi) res.getString(R.string.grade_best) else if (c < EQUAL) "≈" else tagCost(c),
      if (i == bi) TileMark.Tone.BEST else if (mine && g > 0) TileMark.Tone.entries[1 + g] else TileMark.Tone.PLAIN,
      mine, dangerLevel(f.danger),
    )
  }
  return out
}

/** An action's figures in words, for a line: label and value. `names`: the three opponents', for the danger. */
fun figureItems(res: Resources, f: ActionFigures, c: Double?, names: List<String>?): List<Pair<String, String>> {
  val out = ArrayList<Pair<String, String>>()
  val q = f.q ?: return out
  out += res.getString(R.string.fig_value) to pts(q)
  if (c != null && c >= EQUAL) out += res.getString(R.string.fig_cost) to pts(-c)
  val win = f.win ?: 0.0
  out += res.getString(R.string.fig_win) to pct(win) +
    if (win >= 0.005) "（${res.getString(R.string.fig_tsumo)} ${pct(f.tsumo ?: 0.0)}・${res.getString(R.string.fig_tai, fixed(f.tai ?: 0.0, 1))}）" else ""
  out += res.getString(R.string.fig_deal_in) to pct(f.dealIn ?: 0.0)
  out += res.getString(R.string.fig_others_tsumo) to pct(f.othersTsumo ?: 0.0)
  val danger = f.danger
  if (danger != null) {
    var who = ""
    val each = f.dangerEach
    if (names != null && danger >= 0.01 && !each.isNullOrEmpty()) {
      val top = each.indices.maxBy { each[it] }
      who = "（${names.getOrElse(top) { "" }} ${pct(each[top])}・${res.getString(R.string.fig_tai, fixed(f.dangerTai?.getOrNull(top) ?: 0.0, 1))}）"
    }
    out += res.getString(R.string.fig_danger) to pct(danger) + who
  }
  return out
}

/** Tile efficiency in words: "胡牌", "聽牌・進張 6", "1 向聽・進張 12". */
fun efficiencyText(res: Resources, f: ActionFigures): String {
  if (f.shanten < 0) return res.getString(R.string.analysis_win) + if (f.winTai >= 0) "・" + res.getString(R.string.analysis_win_tai, f.winTai) else ""
  return (if (f.shanten == 0) res.getString(R.string.analysis_ready) else res.getString(R.string.analysis_shanten, f.shanten)) + "・" +
    res.getString(R.string.analysis_ukeire, f.ukeire)
}
