package io.github.kennyfs.mahjong.coach

import android.content.res.Resources
import io.github.kennyfs.mahjong.ActionFigures
import io.github.kennyfs.mahjong.Analysis
import io.github.kennyfs.mahjong.EQUAL
import io.github.kennyfs.mahjong.GRADE_NAME
import io.github.kennyfs.mahjong.HandRecord
import io.github.kennyfs.mahjong.LossKind
import io.github.kennyfs.mahjong.Meld
import io.github.kennyfs.mahjong.Notable
import io.github.kennyfs.mahjong.Position
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.RULE_HELP
import io.github.kennyfs.mahjong.Review
import io.github.kennyfs.mahjong.ReviewDecision
import io.github.kennyfs.mahjong.Rules
import io.github.kennyfs.mahjong.RulesInfo
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.Words
import io.github.kennyfs.mahjong.actionText
import io.github.kennyfs.mahjong.gradeOf
import io.github.kennyfs.mahjong.kindOf
import io.github.kennyfs.mahjong.meldTiles
import io.github.kennyfs.mahjong.notableOf
import io.github.kennyfs.mahjong.reasonOf
import io.github.kennyfs.mahjong.tilesShort
import io.github.kennyfs.mahjong.ui.actionLabel
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// The digest the coach reads (docs/coach.md): what the network made of the player's hands, as text in the app's
// language. The app computes every figure; the model is to find habits across hands and explain them, not to add up
// numbers or judge tiles. Of each hand only the key decisions are written out (a questionable one or worse, or a best
// action that is not the obvious one); the rest are counted.

/** A hand's part of the digest, kept (Coach's cache) for the network and the language it was made with. The hand's
 *  number in a digest is given when the digest is put together, so the lines here start after it. */
@Serializable
data class HandDigest(
  val id: String, val m: String, val lang: String,
  /** "東家莊｜結果 自摸胡，+12｜開局期望 −0.81" */
  val title: String,
  /** 0 won by a discard, 1 by self-draw, 2 dealt in, 3 another self-drew, 4 others won by a discard, 5 drawn hand. */
  val outcome: Int,
  val points: Double,
  /** Decisions with a choice; their total loss; per grade (questionable, mistake, blunder); the loss by LossKind. */
  val n: Int, val loss: Double, val grades: List<Int>, val kinds: List<Double>,
  /** Per Notable.Kind: how often, then how often found (2 x 4). */
  val notable: List<Int>,
  /** The discard that dealt in, in short. */
  val dealIn: String? = null,
  val keys: List<KeyLine> = emptyList(),
  /** Played with people at the table (else computer players only). */
  val people: Boolean = false,
)

@Serializable
data class KeyLine(val step: Int, val loss: Double, val text: String)

/** The digest's words in one language. Tiles and actions come from the app's own words (Words, the strings). */
class DigestWords(val zh: Boolean) {
  fun pct(p: Double): String {
    val x = p * 100
    return String.format(Locale.ROOT, if (x >= 9.5) "%.0f%%" else "%.1f%%", x)
  }
  fun f2(x: Double) = String.format(Locale.ROOT, "%.2f", x)
  fun s2(x: Double) = String.format(Locale.ROOT, "%+.2f", x)
  fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)

  val sep = if (zh) "｜" else " | "
  val list = if (zh) "、" else ", "
  val semi = if (zh) "；" else "; "
  fun turn(n: Int, wall: Int) = if (zh) "第${n}巡 牌牆剩$wall" else "turn $n, $wall left in the wall"
  fun hand(s: String) = if (zh) "手牌 $s" else "hand: $s"
  fun drew(t: String) = if (zh) "摸 $t" else "drew $t"
  fun discarded(who: String, t: String) = if (zh) "${who}打出 $t" else "$who discarded $t"
  val afterCall = if (zh) "吃碰後" else "after a call"
  fun melds(s: String) = if (zh) "副露 $s" else "melds: $s"
  val none = if (zh) "無" else "none"
  fun flowers(n: Int) = if (zh) "花 $n" else "$n flowers"
  fun oppReady(s: String) = if (zh) "對手聽牌機率 $s" else "opponents ready: $s"
  fun me(within: String, items: String) =
    (if (zh) "自己（照網路的打法）1／2／4／8 摸內聽牌 $within" else "own hand (as the network plays it): ready within 1/2/4/8 draws $within") +
      (if (items.isEmpty()) "" else if (zh) "；胡的話常有的台：$items" else "; a win would likely include: $items")
  fun graded(g: String, loss: Double) = if (zh) "$g，失分 ${f2(loss)}" else "$g, loss ${f2(loss)}"
  val found = if (zh) "（有做到）" else " (found)"
  val played = if (zh) "打了 " else "played "
  val best = if (zh) "最佳 " else "best "
  val usual = if (zh) "（常見選擇）" else "(the obvious choice) "
  val why = if (zh) "差在 " else "why: "
  fun fig(action: String, q: Double, win: Double, tai: Double, deal: Double) =
    if (zh) "$action：期望 ${s2(q)}、胡 ${pct(win)}（胡了平均 ${f1(tai)} 台）、放槍 ${pct(deal)}"
    else "$action: expected ${s2(q)}, win ${pct(win)} (a win ${f1(tai)} tai on average), deal in ${pct(deal)}"
  fun danger(p: Double) = if (zh) "、這張當下危險 ${pct(p)}" else ", this tile deals in now ${pct(p)}"
  fun dangerWho(who: String, p: Double, tai: Double) = if (zh) "（$who ${pct(p)}、${f1(tai)} 台）" else " ($who ${pct(p)}, ${f1(tai)} tai)"
  fun safety(s: String) = if (zh) "〔$s〕" else " [$s]"
  fun discardedBy(who: String) = if (zh) "${who}打過" else "$who discarded it"
  fun seen(n: Int) = if (zh) "場上已見${n}張" else "$n seen on the table"
  val live = if (zh) "生張" else "none seen on the table"
  val ready = if (zh) "聽牌" else "ready"
  val win = if (zh) "胡" else "a win"
  fun shanten(n: Int) = if (zh) "$n 向聽" else "$n-shanten"
  fun ukeire(n: Int) = if (zh) " 進張 $n" else ", $n useful tiles"
  fun waits(s: String) = if (zh) "（聽 $s）" else " (waits: $s)"
  fun wait(t: String, left: Int) = if (zh) "${t}剩$left" else "$t $left left"
  fun handTitle(wind: String, dealer: Boolean, result: String, pts: String, first: Double) =
    if (zh) "${wind}家${if (dealer) "莊" else ""}｜結果 $result，$pts｜開局期望 ${s2(first)}"
    else "$wind seat${if (dealer) ", dealer" else ""} | result: $result, $pts | expected at the start ${s2(first)}"
  fun handLine(h: Int, people: Boolean, title: String, n: Int, loss: Double, k: Int) =
    if (zh) "## H$h｜${if (people) "有真人" else "對電腦"}｜$title｜決策 $n 次、失分合計 ${f2(loss)}、列出 $k 次"
    else "## H$h | ${if (people) "with people" else "vs computer"} | $title | $n decisions, total loss ${f2(loss)}, $k listed"
  val outcomes = if (zh) listOf("胡", "自摸胡", "放槍", "被自摸", "別人互胡", "流局")
  else listOf("won on a discard", "won by self-draw", "dealt in", "another self-drew", "others won", "drawn hand")
  fun othersSelfDraw(who: String) = if (zh) "${who}自摸" else "$who self-drew"
  fun dealtIn(who: String) = if (zh) "放槍給$who" else "dealt in to $who"
  fun othersWon(w: String, l: String) = if (zh) "${w}胡${l}" else "$w won on $l's discard"
  fun dealInLine(danger: Double, ready: String, loss: Double) =
    if (zh) "這張危險 ${pct(danger)}、當時對手聽牌 $ready、失分 ${f2(loss)}" else "the tile's danger ${pct(danger)}, opponents ready $ready, loss ${f2(loss)}"
}

object Digest {
  /** At most this many key decisions in a digest: the costliest (a hundred hands have about 300). */
  const val MAX_KEYS = 150

  private fun rel(res: Resources, me: Int, seat: Int) = res.getString(
    when ((seat - me + 4) % 4) { 1 -> R.string.rel_next; 2 -> R.string.rel_opposite; 3 -> R.string.rel_prev; else -> R.string.you },
  )

  private fun handText(s: String) = tilesShort(Tiles.sort(Tiles.parse(s).filter { !Tiles.isFlower(it) }))

  /** "碰 白", "吃 三四五萬". */
  private fun meldText(res: Resources, m: Meld): String {
    val word = res.getString(actionLabel(m.type))
    val ts = Tiles.sort(meldTiles(m).filterNotNull())
    if (ts.isEmpty()) return word
    return "$word " + if (m.type == "chi") tilesShort(ts) else Tiles.name(ts.first())
  }

  /** Copies of a kind on the table: the discards not called, and the melds' tiles. */
  private fun seenOnTable(pos: Position, t: String): Int =
    pos.players.sumOf { p -> p.discards.count { it.tile == t && !it.called } + p.melds.sumOf { m -> meldTiles(m).count { it == t } } }

  /** A decision's chance to win under the network's play (the policy's mean). */
  private fun policyWin(a: Analysis): Double {
    val withPolicy = a.actions.filter { it.policy != null }
    if (withPolicy.isEmpty()) return a.actions.getOrNull(a.best ?: 0)?.win ?: 0.0
    return withPolicy.sumOf { (it.policy ?: 0.0) * (it.win ?: 0.0) }
  }

  private fun fig(res: Resources, w: DigestWords, a: Analysis, i: Int, pos: Position): String {
    val f: ActionFigures = a.actions[i]
    val sb = StringBuilder(w.fig(actionText(res, f.action), f.q ?: 0.0, f.win ?: 0.0, f.tai ?: 0.0, f.dealIn ?: 0.0))
    val tile = f.action.tile
    if (f.action.type == "discard" && tile != null && f.danger != null) {
      sb.append(w.danger(f.danger))
      val each = f.dangerEach
      if (f.danger >= 0.01 && each != null && each.size == 3) {
        val o = (0..2).maxBy { each[it] }
        sb.append(w.dangerWho(rel(res, pos.me, (pos.me + o + 1) % 4), each[o], f.dangerTai?.getOrNull(o) ?: 0.0))
      }
      val why = ArrayList<String>()
      for (r in 1..3) {
        val seat = (pos.me + r) % 4
        if (pos.players.getOrNull(seat)?.discards?.any { it.tile == tile } == true) why += w.discardedBy(rel(res, pos.me, seat))
      }
      val seen = seenOnTable(pos, tile)
      why += if (seen > 0) w.seen(seen) else w.live
      sb.append(w.safety(why.joinToString(w.list)))
    }
    sb.append(w.list)
    sb.append(if (f.shanten < 0) w.win else if (f.shanten == 0) w.ready else w.shanten(f.shanten))
    if (f.shanten >= 0) sb.append(w.ukeire(f.ukeire))
    if (f.shanten == 0 && f.waits.isNotEmpty()) sb.append(w.waits(f.waits.joinToString(w.list) { w.wait(Tiles.name(it.tile), it.left) }))
    return sb.toString()
  }

  /** A hand's part: `positions` holds the player's position at each key decision's step (and at the discard that
   *  dealt in). */
  fun hand(res: Resources, w: DigestWords, rec: HandRecord, r: Review, positions: Map<Int, Position>, model: String): HandDigest {
    val me = rec.me
    val unit = r.unit
    val grades = IntArray(3)
    val kinds = DoubleArray(LossKind.entries.size)
    val notable = IntArray(8)
    val keys = ArrayList<KeyLine>()
    for (d in r.decisions) {
      val a = d.analysis
      if (!a.net) continue
      val loss = max(0.0, d.loss ?: 0.0)
      val g = gradeOf(loss, unit)
      if (g > 0) grades[g - 1]++
      if (loss >= EQUAL) kinds[kindOf(d).ordinal] += loss
      val note = notableOf(a, unit)
      if (note != null) {
        notable[note.kind.ordinal]++
        if (loss < EQUAL) notable[4 + note.kind.ordinal]++
      }
      if (g == 0 && note == null) continue
      val pos = positions[d.step] ?: continue
      keys += KeyLine(d.step, loss, keyText(res, w, d, pos, g, loss, note))
    }
    val outcome = when {
      rec.exhausted -> 5
      me in rec.winners -> if (rec.selfDraw) 1 else 0
      rec.selfDraw -> 3
      rec.loser == me -> 2
      else -> 4
    }
    val result = when (outcome) {
      2 -> w.dealtIn(rec.winners.joinToString(w.list) { rel(res, me, it) })
      3 -> w.othersSelfDraw(rec.winners.firstOrNull()?.let { rel(res, me, it) } ?: "?")
      4 -> w.othersWon(rec.winners.joinToString(w.list) { rel(res, me, it) }, rel(res, me, rec.loser))
      else -> w.outcomes[outcome]
    }
    val points = rec.points.getOrElse(me) { 0.0 }
    val setup = rec.game.setup
    val wind = Tiles.wind(((me - setup.east) % 4 + 4) % 4)
    val title = w.handTitle(wind, setup.dealer == me, result, String.format(Locale.ROOT, "%+.0f", points), r.firstValue ?: 0.0)
    // The discard that dealt in: the hand's last decision.
    val dealIn = if (outcome == 2) r.decisions.lastOrNull()?.let { d ->
      val f = d.analysis.actions.getOrNull(d.played)
      val opp = d.analysis.opponents?.joinToString("/") { w.pct(it.ready.firstOrNull() ?: 0.0) } ?: "?"
      if (f == null) null else w.dealInLine(f.danger ?: 0.0, opp, max(0.0, d.loss ?: 0.0))
    } else null
    val people = rec.bots.withIndex().any { (i, b) -> i != me && b == null }
    return HandDigest(
      rec.id, model, if (w.zh) "zh" else "en", title, outcome, points, r.decisions.count { it.analysis.net }, r.decisions.sumOf { max(0.0, it.loss ?: 0.0) },
      grades.toList(), kinds.toList(), notable.toList(), dealIn, keys, people,
    )
  }

  /** The steps whose positions hand() needs: the key decisions'. */
  fun keySteps(r: Review): List<Int> = r.decisions.filter { d ->
    d.analysis.net && (gradeOf(max(0.0, d.loss ?: 0.0), r.unit) > 0 || notableOf(d.analysis, r.unit) != null)
  }.map { it.step }

  private fun keyText(res: Resources, w: DigestWords, d: ReviewDecision, pos: Position, g: Int, loss: Double, note: Notable?): String {
    val a = d.analysis
    val me = pos.me
    val mine = pos.players.getOrNull(me)
    val ctx = when {
      pos.claim != null && pos.claim.tile != null -> w.discarded(rel(res, me, pos.claim.seat), Tiles.name(pos.claim.tile))
      pos.drawn != null -> w.drew(Tiles.name(pos.drawn))
      else -> w.afterCall
    }
    val melds = mine?.melds?.joinToString(w.list) { meldText(res, it) }?.ifEmpty { null } ?: w.none
    val flowers = Tiles.parse(mine?.flowers).size
    val opp = a.opponents?.joinToString(w.list) { o -> rel(res, me, o.seat) + " " + w.pct(o.ready.firstOrNull() ?: 0.0) } ?: "?"
    val head = listOfNotNull(
      w.turn((mine?.discards?.size ?: 0) + 1, max(0, pos.wallLeft)), w.hand(handText(pos.hand)), ctx, w.melds(melds),
      if (flowers > 0) w.flowers(flowers) else null, w.oppReady(opp),
    ).joinToString(w.sep)
    val lines = arrayListOf(head)
    a.me?.let { m ->
      // How soon the hand gets ready (left out once it is), and the tai a win would likely bring: each item's chance
      // given a win, the likelier ones.
      // Four separate outputs, which can dip a little (4.1%/3.9%): shown as never going down, as the chances do.
      val within = if (m.readyWithin.size == 4 && m.readyWithin[0] < 0.995) m.readyWithin.runningReduce(::maxOf).joinToString("/") { w.pct(it) } else null
      val pw = policyWin(a)
      val items = if (pw >= 0.02) m.taiItems.mapNotNull { (k, p) -> Words.tai(k)?.let { it to min(1.0, p / pw) } }
        .filter { it.second >= 0.15 }.sortedByDescending { it.second }.take(5).joinToString(w.list) { "${it.first} ${w.pct(it.second)}" } else ""
      if (within != null) lines += "  " + w.me(within, items)
      else if (items.isNotEmpty()) lines += "  " + w.me("—", items)
    }
    val tag = ArrayList<String>()
    if (g > 0) tag += w.graded(res.getString(GRADE_NAME[g]), loss)
    if (note != null) tag += res.getString(note.kind.label) + if (loss < EQUAL) w.found else ""
    lines += "  " + tag.joinToString(w.semi)
    val bi = a.best ?: 0
    if (d.played in a.actions.indices) lines += "  " + w.played + fig(res, w, a, d.played, pos)
    if (bi != d.played) {
      lines += "  " + w.best + fig(res, w, a, bi, pos)
      val why = reasonOf(res, a, d.played)
      if (why.isNotEmpty()) lines += "  " + w.why + why
    }
    if (note != null && note.alt != bi && note.alt != d.played && note.alt in a.actions.indices) lines += "  " + w.usual + fig(res, w, a, note.alt, pos)
    return lines.joinToString("\n")
  }

  /** The rules in words: each parameter by the app's name and value, the ones that score with the rules page's help. */
  fun rulesText(res: Resources, w: DigestWords, info: RulesInfo, name: String): String {
    val v = info.values
    fun label(p: String) = Words.tai(p) ?: paramName(res, p)
    val out = ArrayList<String>()
    out += (if (w.zh) "規則：" else "Rules: ") + name
    fun help(p: String) = RULE_HELP[p]?.let { res.getString(it).replace("\n", " ") }
    for (p in Rules.PLAY_PARAMS + Rules.FLOWER_PARAMS) {
      val x = v[p] ?: continue
      out += "- ${label(p)} = $x" + (help(p)?.let { (if (w.zh) "：" else ": ") + it } ?: "")
    }
    val tai = info.items.map { it.param }.filter { it !in Rules.FLOWER_PARAMS }
    val counted = tai.filter { (v[it] ?: 0) != 0 }
    val zero = tai.filter { (v[it] ?: 0) == 0 }
    out += if (w.zh) "台（只有這些算台；值是台數）：" else "Tai (only these score; the value is the tai):"
    for (p in counted) out += "- ${label(p)} ${v[p]}" + (help(p)?.let { (if (w.zh) "：" else ": ") + it } ?: "")
    for (p in v.keys.filter { it in setOf("quanqiu_duting", "dasixi_winds") }) out += "- ${label(p)} = ${v[p]}" + (help(p)?.let { ": $it" } ?: "")
    if (zero.isNotEmpty()) out += (if (w.zh) "0 台（這套規則不算）：" else "0 tai (not scored in these rules): ") + zero.joinToString(w.list) { label(it) }
    return out.joinToString("\n")
  }

  private fun paramName(res: Resources, p: String): String = when (p) {
    "flowers" -> res.getString(R.string.param_flowers)
    "base" -> res.getString(R.string.param_base)
    "draw_streak" -> res.getString(R.string.param_draw_streak)
    "multi_ron" -> res.getString(R.string.param_multi_ron)
    "keep" -> res.getString(R.string.param_keep)
    "min_tai" -> res.getString(R.string.param_min_tai)
    "duting_loose" -> res.getString(R.string.param_duting_loose)
    "quanqiu_duting" -> res.getString(R.string.param_quanqiu_duting)
    "dasixi_winds" -> res.getString(R.string.param_dasixi_winds)
    else -> p
  }

  /** The digest of these hands, oldest first (H1 the oldest). */
  fun assemble(res: Resources, w: DigestWords, rules: String, hands: List<HandDigest>): String {
    val n = hands.size
    val out = ArrayList<String>()
    out += if (w.zh) "# 牌局摘要（由 app 的神經網路分析產生；數字都是網路的估計）" else "# Digest of the hands (from the app's neural network; every figure is its estimate)"
    out += rules
    out += legend(res, w)
    val c = IntArray(6)
    hands.forEach { c[it.outcome]++ }
    val points = hands.sumOf { it.points }
    val people = hands.count { it.people }
    out += if (w.zh) "共 $n 局（對電腦 ${n - people}、有真人 $people）：胡 ${c[0] + c[1]}（自摸 ${c[1]}）、放槍 ${c[2]}、被自摸 ${c[3]}、別人互胡 ${c[4]}、流局 ${c[5]}；總分 ${String.format(Locale.ROOT, "%+.0f", points)}。"
    else "$n hands (${n - people} vs computer, $people with people): won ${c[0] + c[1]} (self-draw ${c[1]}), dealt in ${c[2]}, another self-drew ${c[3]}, others won ${c[4]}, drawn ${c[5]}; points ${String.format(Locale.ROOT, "%+.0f", points)}."
    val decisions = hands.sumOf { it.n }
    val loss = hands.sumOf { it.loss }
    val g = (0..2).map { i -> hands.sumOf { it.grades.getOrElse(i) { 0 } } }
    val gn = (1..3).map { res.getString(GRADE_NAME[it]) }
    out += if (w.zh) "決策 $decisions 次；失分合計 ${w.f2(loss)}（每局 ${w.f2(loss / max(1, n))}）；${gn[0]} ${g[0]}、${gn[1]} ${g[1]}、${gn[2]} ${g[2]}。"
    else "$decisions decisions; total loss ${w.f2(loss)} (${w.f2(loss / max(1, n))} a hand); ${gn[0]} ${g[0]}, ${gn[1]} ${g[1]}, ${gn[2]} ${g[2]}."
    val kinds = LossKind.entries.map { k -> res.getString(k.label) to hands.sumOf { it.kinds.getOrElse(k.ordinal) { 0.0 } } }.sortedByDescending { it.second }
    out += (if (w.zh) "失分依種類：" else "Loss by kind of decision: ") + kinds.joinToString(w.list) { "${it.first} ${w.f2(it.second)}" }
    val notes = Notable.Kind.entries.mapNotNull { k ->
      val x = hands.sumOf { it.notable.getOrElse(k.ordinal) { 0 } }
      val y = hands.sumOf { it.notable.getOrElse(4 + k.ordinal) { 0 } }
      if (x > 0) "${res.getString(k.label)} $x/$y" else null
    }
    out += (if (w.zh) "網路的最佳不是直覺選擇的時機（出現次數／有做到）：" else "Times the network's best was not the obvious choice (seen/found): ") + (notes.joinToString(w.list).ifEmpty { w.none })
    val deals = hands.withIndex().filter { it.value.dealIn != null }.take(20)
    if (deals.isNotEmpty()) out += (if (w.zh) "放槍的那一手：" else "The discards that dealt in: ") + deals.joinToString(w.semi) { "H${it.index + 1} ${it.value.dealIn}" }
    // The costliest key decisions, when there are too many.
    val all = hands.flatMapIndexed { i, h -> h.keys.map { i to it } }
    val kept = if (all.size <= MAX_KEYS) all.toSet() else all.sortedByDescending { it.second.loss }.take(MAX_KEYS).toSet()
    out += ""
    out += if (all.size > kept.size) {
      if (w.zh) "下面每局只列出有失分或值得一提的決策（只列失分最大的 ${kept.size} 次，其餘 ${all.size - kept.size} 次只算進上面的統計）。代號 [H局-步] 在 app 裡可以點開。"
      else "Below, each hand lists only its decisions with a loss or worth a note (the ${kept.size} costliest; the other ${all.size - kept.size} count in the figures above only). An id [H<hand>-<step>] opens that moment in the app."
    } else {
      if (w.zh) "下面每局只列出有失分或值得一提的決策。代號 [H局-步] 在 app 裡可以點開。"
      else "Below, each hand lists only its decisions with a loss or worth a note. An id [H<hand>-<step>] opens that moment in the app."
    }
    for ((i, h) in hands.withIndex()) {
      val ks = h.keys.filter { (i to it) in kept }
      out += ""
      out += w.handLine(i + 1, h.people, h.title, h.n, h.loss, ks.size)
      for (k in ks) out += "[H${i + 1}-${k.step}] " + k.text
    }
    return out.joinToString("\n") + "\n"
  }

  private fun legend(res: Resources, w: DigestWords): String {
    val gn = (1..3).map { res.getString(GRADE_NAME[it]) }
    val notes = Notable.Kind.entries.joinToString(w.list) { res.getString(it.label) }
    return if (w.zh) """
      單位：期望與失分都以「台」計（一台＝一個底）。失分＝最佳的期望減去打了的期望。等級：失分 ≥0.10 ${gn[0]}、≥0.25 ${gn[1]}、≥0.50 ${gn[2]}；<0.05 是網路本身的誤差，等於沒差。
      期望：這個選擇之後這一局的平均結果（算進胡牌、放槍、被自摸）。胡／放槍：這個選擇之後到這局結束會胡、會放槍的機率；胡了平均 N 台：胡的話平均幾台。
      這張當下危險：打這張馬上有人胡的機率，括號裡是最可能胡它的對手。〔…〕：誰打過這種牌、場上看得到幾張；生張＝場上一張都沒出現過。這套規則沒有振聽，別人打過的牌只是比較安全，不是絕對安全。
      差在：失分拆成胡得少、放槍多、被自摸多。對手聽牌機率是網路的估計。自己 1／2／4／8 摸內聽牌：照網路的打法，接下來幾次摸牌內聽牌的機率；胡的話常有的台：胡的話各台出現的機率。
      $notes：網路的最佳不是直覺的選擇；「有做到」是玩家選對了。
    """.trimIndent() else """
      Units: expected results and losses are in tai (one tai = one base). Loss = the best action's expected result minus the played one's. Grades: loss ≥0.10 ${gn[0]}, ≥0.25 ${gn[1]}, ≥0.50 ${gn[2]}; under 0.05 is the network's own error, as good as no loss.
      Expected: the hand's average result after this choice (wins, deal-ins and others' self-draws). Win / deal in: the chances of winning / dealing in by the end of the hand after this choice; a win N tai on average: the tai of a win.
      This tile deals in now: the chance that someone wins on this discard at once, with the opponent most likely to. [...]: who has discarded the kind, and how many copies are on the table; none seen = not one copy on the table yet. These rules have no furiten: a tile an opponent discarded is only safer, never safe.
      Why: the loss split into winning less, dealing in more and others' self-draws. Opponents ready: the network's chances. Ready within 1/2/4/8 draws: as the network would play the hand on; a win would likely include: each tai item's chance in a win.
      $notes: the network's best was not the obvious choice; "found" means the player chose it.
    """.trimIndent()
  }
}
