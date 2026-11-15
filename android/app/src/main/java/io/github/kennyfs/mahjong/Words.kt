package io.github.kennyfs.mahjong

import android.content.res.Resources

/**
 * Words the code puts together, in the app's language (strings.xml): a list's separators, a colon, words in brackets,
 * the tiles' names (Tiles.name, tilesShort) and the winds' marks. Loaded when the activity starts (MainActivity), so
 * again when the language changes (Android starts the activity anew then). Until then, Taiwan's (the tests' too).
 */
object Words {
  /** 「・」: between a line's parts. */
  var sep = "・"; private set
  /** 「、」: between a list's items. */
  var list = "、"; private set
  /** 「：」 */
  var colon = "："; private set
  private var paren = "（%1\$s）"
  var numerals = "一二三四五六七八九".map { it.toString() }; private set
  var suits = listOf("萬", "筒", "條"); private set
  var honors = "東南西北白發中".map { it.toString() }; private set
  var flowers = "春夏秋冬梅蘭菊竹".map { it.toString() }; private set
  var windMarks = "東南西北".map { it.toString() }; private set
  private var tileName = "%1\$s%2\$s"
  var numSep = ""; private set
  var tilesSep = ""; private set
  /** 搶 of 搶碰 (rob_mark): in another color on a plaque. */
  var robMark = "搶"; private set
  /** The tai items' names by their parameters (the engine names them in Chinese). */
  private var taiNames = emptyMap<String, String>()

  fun load(res: Resources) {
    sep = res.getString(R.string.sep)
    list = res.getString(R.string.sep_list)
    colon = res.getString(R.string.colon)
    paren = res.getString(R.string.paren)
    numerals = res.getStringArray(R.array.tile_numerals).toList()
    suits = res.getStringArray(R.array.tile_suits).toList()
    honors = res.getStringArray(R.array.tile_honors).toList()
    flowers = res.getStringArray(R.array.tile_flowers).toList()
    windMarks = res.getStringArray(R.array.wind_marks).toList()
    tileName = res.getString(R.string.tile_name)
    numSep = res.getString(R.string.tiles_num_sep)
    tilesSep = res.getString(R.string.tiles_sep)
    robMark = res.getString(R.string.rob_mark)
    taiNames = TAI.mapValues { res.getString(it.value) }
  }

  /** A tai item's name, by its parameter; null before the resources are loaded (then the engine's). */
  fun tai(param: String): String? = taiNames[param]

  private val TAI = mapOf(
    "dealer" to R.string.tai_dealer,
    "streak" to R.string.tai_streak,
    "menqing" to R.string.tai_menqing,
    "zimo" to R.string.tai_zimo,
    "menqing_zimo" to R.string.tai_menqing_zimo,
    "duting" to R.string.tai_duting,
    "pinghu" to R.string.tai_pinghu,
    "quanqiu" to R.string.tai_quanqiu,
    "banqiu" to R.string.tai_banqiu,
    "round_wind" to R.string.tai_round_wind,
    "seat_wind" to R.string.tai_seat_wind,
    "dragon" to R.string.tai_dragon,
    "xiaosanyuan" to R.string.tai_xiaosanyuan,
    "dasanyuan" to R.string.tai_dasanyuan,
    "xiaosixi" to R.string.tai_xiaosixi,
    "dasixi" to R.string.tai_dasixi,
    "pengpenghu" to R.string.tai_pengpenghu,
    "sananke" to R.string.tai_sananke,
    "sianke" to R.string.tai_sianke,
    "wuanke" to R.string.tai_wuanke,
    "hunyise" to R.string.tai_hunyise,
    "qingyise" to R.string.tai_qingyise,
    "ziyise" to R.string.tai_ziyise,
    "qianggang" to R.string.tai_qianggang,
    "gangshang" to R.string.tai_gangshang,
    "haidi" to R.string.tai_haidi,
    "hedi" to R.string.tai_hedi,
    "tianhu" to R.string.tai_tianhu,
    "dihu" to R.string.tai_dihu,
    "renhu" to R.string.tai_renhu,
    "any_wind" to R.string.tai_any_wind,
    "no_honor_flower" to R.string.tai_no_honor_flower,
    "kong_tai" to R.string.tai_kong_tai,
    "ankong_tai" to R.string.tai_ankong_tai,
    "flower_tai" to R.string.tai_flower_tai,
    "flower_set" to R.string.tai_flower_set,
    "eight_flowers" to R.string.tai_eight_flowers,
    "seven_rob_one" to R.string.tai_seven_rob_one,
    "tianting" to R.string.tai_tianting,
    "diting" to R.string.tai_diting,
    "baoting" to R.string.tai_baoting
  )

  /** Words in brackets: 「（x）」, " (x)". */
  fun paren(x: String) = paren.format(x)
  /** The brackets' halves, for words put between them piece by piece. */
  val open: String get() = paren.substringBefore("%1\$s")
  val close: String get() = paren.substringAfter("%1\$s")
  /** A suited tile's name from its numeral (or numerals) and suit: 「五萬」, "5 Characters". */
  fun suited(numerals: String, suit: Int) = tileName.format(numerals, suits[suit])
}
