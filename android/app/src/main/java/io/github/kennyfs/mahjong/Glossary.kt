package io.github.kennyfs.mahjong

import androidx.annotation.StringRes

/**
 * Beginner glossary (術語表; the web page's lib/glossary.ts). Entries are strictly topologically ordered so no earlier
 * definition depends on a later entry. Also splits Explain text into longest-match clickable term spans.
 */
data class GlossaryEntry(
  val id: String,
  @param:StringRes val title: Int,
  @param:StringRes val desc: Int,
  val zh: List<String>,
  val en: List<String>,
)

data class TermPart(val text: String, val termId: String? = null)

object Glossary {
  val ENTRIES: List<GlossaryEntry> = listOf(
    GlossaryEntry("wall", R.string.glossary_t_wall, R.string.glossary_d_wall, listOf("牌牆"), listOf("the wall")),
    GlossaryEntry("hand_tiles", R.string.glossary_t_hand_tiles, R.string.glossary_d_hand_tiles, listOf("手牌"), emptyList()),
    GlossaryEntry("river", R.string.glossary_t_river, R.string.glossary_d_river, listOf("牌河"), listOf("river")),
    GlossaryEntry("turn", R.string.glossary_t_turn, R.string.glossary_d_turn, listOf("一、二巡", "早巡"), listOf("early turns")),
    GlossaryEntry("suit_tile", R.string.glossary_t_suit_tile, R.string.glossary_d_suit_tile, listOf("數牌"), listOf("number tiles", "number tile")),
    GlossaryEntry("honor", R.string.glossary_t_honor, R.string.glossary_d_honor, listOf("字牌"), listOf("honors", "honor")),
    GlossaryEntry("terminal_honor", R.string.glossary_t_terminal_honor, R.string.glossary_d_terminal_honor, listOf("么九"), listOf("terminals", "terminal")),
    GlossaryEntry("middle", R.string.glossary_t_middle, R.string.glossary_d_middle, listOf("中張"), listOf("middle tiles", "middle tile")),
    GlossaryEntry("chow", R.string.glossary_t_chow, R.string.glossary_d_chow, listOf("順子"), listOf("chow", "run")),
    GlossaryEntry("pung", R.string.glossary_t_pung, R.string.glossary_d_pung, listOf("刻子"), listOf("pung")),
    GlossaryEntry("kong", R.string.glossary_t_kong, R.string.glossary_d_kong, listOf("槓子", "暗槓", "明槓"), listOf("kong")),
    GlossaryEntry("set", R.string.glossary_t_set, R.string.glossary_d_set, listOf("面子"), emptyList()),
    GlossaryEntry("pair", R.string.glossary_t_pair, R.string.glossary_d_pair, listOf("對子"), listOf("pairs", "pair")),
    GlossaryEntry("eye", R.string.glossary_t_eye, R.string.glossary_d_eye, listOf("沒了眼", "當眼", "缺眼"), listOf("the eye", "eye")),
    GlossaryEntry("win", R.string.glossary_t_win, R.string.glossary_d_win, listOf("胡牌"), emptyList()),
    GlossaryEntry("zimo", R.string.glossary_t_zimo, R.string.glossary_d_zimo, listOf("自摸"), listOf("self-draw")),
    GlossaryEntry("dealin", R.string.glossary_t_dealin, R.string.glossary_d_dealin, listOf("放槍"), listOf("deals in", "deal-in")),
    GlossaryEntry("draw", R.string.glossary_t_draw, R.string.glossary_d_draw, listOf("流局"), listOf("exhaustive draw")),
    GlossaryEntry("hand_deal", R.string.glossary_t_hand_deal, R.string.glossary_d_hand_deal, emptyList(), emptyList()),
    GlossaryEntry("tenpai", R.string.glossary_t_tenpai, R.string.glossary_d_tenpai, listOf("聽牌", "已聽", "報聽"), listOf("ready")),
    GlossaryEntry("tai", R.string.glossary_t_tai, R.string.glossary_d_tai, listOf("台數", "一台"), listOf("tai")),
    GlossaryEntry("dealer", R.string.glossary_t_dealer, R.string.glossary_d_dealer, listOf("莊家"), listOf("dealer")),
    GlossaryEntry("streak", R.string.glossary_t_streak, R.string.glossary_d_streak, listOf("連莊"), listOf("dealer streak")),
    GlossaryEntry("round", R.string.glossary_t_round, R.string.glossary_d_round, listOf("一將"), emptyList()),
    GlossaryEntry("flower", R.string.glossary_t_flower, R.string.glossary_d_flower, listOf("花牌"), listOf("flowers", "flower")),
    GlossaryEntry("meld", R.string.glossary_t_meld, R.string.glossary_d_meld, listOf("副露", "吃碰"), listOf("melds", "meld")),
    GlossaryEntry("move", R.string.glossary_t_move, R.string.glossary_d_move, emptyList(), emptyList()),
    GlossaryEntry("menqing", R.string.glossary_t_menqing, R.string.glossary_d_menqing, listOf("門清"), listOf("concealed hand", "closed hand")),
    GlossaryEntry("pass_lock", R.string.glossary_t_pass_lock, R.string.glossary_d_pass_lock, listOf("過水"), listOf("missed win")),
    GlossaryEntry("shape", R.string.glossary_t_shape, R.string.glossary_d_shape, listOf("搭子"), listOf("partial sets", "partial set", "partial run")),
    GlossaryEntry("lone", R.string.glossary_t_lone, R.string.glossary_d_lone, listOf("孤張"), listOf("lone number tile", "lone honors", "lone honor", "lone tile")),
    GlossaryEntry("ryanmen", R.string.glossary_t_ryanmen, R.string.glossary_d_ryanmen, listOf("兩面"), listOf("two-sided wait", "open wait")),
    GlossaryEntry("kanchan", R.string.glossary_t_kanchan, R.string.glossary_d_kanchan, listOf("嵌張", "中洞"), listOf("closed and edge waits", "closed wait", "inside wait")),
    GlossaryEntry("penchan", R.string.glossary_t_penchan, R.string.glossary_d_penchan, listOf("邊張"), listOf("edge waits", "edge wait")),
    GlossaryEntry("tanki", R.string.glossary_t_tanki, R.string.glossary_d_tanki, listOf("單吊"), listOf("single wait", "pair wait")),
    GlossaryEntry("shanpon", R.string.glossary_t_shanpon, R.string.glossary_d_shanpon, listOf("雙碰"), listOf("dual-pair wait")),
    GlossaryEntry("dead_pair", R.string.glossary_t_dead_pair, R.string.glossary_d_dead_pair, listOf("死對子"), listOf("dead pair")),
    GlossaryEntry("shanten", R.string.glossary_t_shanten, R.string.glossary_d_shanten, listOf("向聽數", "一向聽", "兩向聽", "三向聽", "四向聽", "五向聽", "六向聽", "七向聽", "向聽"), listOf("shanten")),
    GlossaryEntry("ukeire", R.string.glossary_t_ukeire, R.string.glossary_d_ukeire, listOf("進張"), listOf("improving tiles", "useful tiles")),
    GlossaryEntry("efficiency", R.string.glossary_t_efficiency, R.string.glossary_d_efficiency, listOf("牌效率"), listOf("tile efficiency", "tile-efficient")),
    GlossaryEntry("tsumogiri", R.string.glossary_t_tsumogiri, R.string.glossary_d_tsumogiri, listOf("摸切"), listOf("straight from the draw", "draw-discard")),
    GlossaryEntry("tedashi", R.string.glossary_t_tedashi, R.string.glossary_d_tedashi, listOf("手切"), listOf("hand-discard")),
    GlossaryEntry("genbutsu", R.string.glossary_t_genbutsu, R.string.glossary_d_genbutsu, listOf("現物"), listOf("their own discards", "own discards", "genbutsu")),
    GlossaryEntry("suji", R.string.glossary_t_suji, R.string.glossary_d_suji, listOf("筋牌"), listOf("suji")),
    GlossaryEntry("non_suji", R.string.glossary_t_non_suji, R.string.glossary_d_non_suji, listOf("無筋"), listOf("non-suji")),
    GlossaryEntry("half_suji", R.string.glossary_t_half_suji, R.string.glossary_d_half_suji, listOf("半筋"), listOf("half-suji")),
    GlossaryEntry("live_tile", R.string.glossary_t_live_tile, R.string.glossary_d_live_tile, listOf("生張"), listOf("unseen middle tile", "live tile")),
    GlossaryEntry("seen_tile", R.string.glossary_t_seen_tile, R.string.glossary_d_seen_tile, listOf("熟張"), listOf("seen tile")),
  )

  val INDEX_BY_ID: Map<String, Int> = ENTRIES.mapIndexed { i, e -> e.id to i }.toMap()

  private class Matcher(val regex: Regex, val map: Map<String, String>)

  private fun build(english: Boolean): Matcher {
    val pairs = ENTRIES.flatMap { e -> (if (english) e.en else e.zh).map { it to e.id } }
      .sortedByDescending { it.first.length }
    val map = HashMap<String, String>(pairs.size)
    for ((phrase, id) in pairs) map[if (english) phrase.lowercase() else phrase] = id
    val body = pairs.joinToString("|") { Regex.escape(it.first) }
    val regex = if (english) Regex("\\b(?:$body)\\b", RegexOption.IGNORE_CASE) else Regex("(?:$body)")
    return Matcher(regex, map)
  }

  private val ZH by lazy { build(english = false) }
  private val EN by lazy { build(english = true) }

  /** Splits `text` into plain runs and clickable glossary links. When `beforeId` is set, only strictly earlier entries match. */
  fun link(text: String, english: Boolean, beforeId: String? = null): List<TermPart> {
    if (text.isEmpty()) return listOf(TermPart(text))
    val m = if (english) EN else ZH
    val limit = if (beforeId != null) (INDEX_BY_ID[beforeId] ?: -1) else Int.MAX_VALUE
    val out = ArrayList<TermPart>()
    var at = 0
    for (hit in m.regex.findAll(text)) {
      val key = if (english) hit.value.lowercase() else hit.value
      val id = m.map[key] ?: continue
      if ((INDEX_BY_ID[id] ?: Int.MAX_VALUE) >= limit) continue
      if (hit.range.first > at) out.add(TermPart(text.substring(at, hit.range.first)))
      out.add(TermPart(hit.value, id))
      at = hit.range.last + 1
    }
    if (at < text.length) out.add(TermPart(text.substring(at)))
    return if (out.isEmpty()) listOf(TermPart(text)) else out
  }
}
