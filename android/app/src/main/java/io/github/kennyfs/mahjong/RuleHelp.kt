package io.github.kennyfs.mahjong

/**
 * What each rule parameter means, in a sentence or two: the strings' help_<parameter>, by parameter (the rules page
 * shows it behind an i button; a parameter without one has no button). Listed here rather than looked up by name, so
 * that a release build's resource shrinking sees them used. The web page looks them up by name (Rules.svelte): a new
 * help string needs its line here.
 */
val RULE_HELP: Map<String, Int> = mapOf(
  "base" to R.string.help_base, "draw_streak" to R.string.help_draw_streak, "multi_ron" to R.string.help_multi_ron, "keep" to R.string.help_keep,
  "min_tai" to R.string.help_min_tai, "duting_loose" to R.string.help_duting_loose,
  "flowers" to R.string.help_flowers, "flower_tai" to R.string.help_flower_tai, "flower_set" to R.string.help_flower_set,
  "eight_flowers" to R.string.help_eight_flowers, "seven_rob_one" to R.string.help_seven_rob_one,
  "dealer" to R.string.help_dealer, "streak" to R.string.help_streak, "menqing" to R.string.help_menqing, "zimo" to R.string.help_zimo,
  "menqing_zimo" to R.string.help_menqing_zimo, "duting" to R.string.help_duting, "pinghu" to R.string.help_pinghu,
  "quanqiu" to R.string.help_quanqiu, "quanqiu_duting" to R.string.help_quanqiu_duting, "banqiu" to R.string.help_banqiu,
  "round_wind" to R.string.help_round_wind, "seat_wind" to R.string.help_seat_wind, "dragon" to R.string.help_dragon,
  "xiaosanyuan" to R.string.help_xiaosanyuan, "dasanyuan" to R.string.help_dasanyuan, "xiaosixi" to R.string.help_xiaosixi,
  "dasixi" to R.string.help_dasixi, "dasixi_winds" to R.string.help_dasixi_winds, "pengpenghu" to R.string.help_pengpenghu,
  "sananke" to R.string.help_sananke, "sianke" to R.string.help_sianke, "wuanke" to R.string.help_wuanke, "hunyise" to R.string.help_hunyise,
  "qingyise" to R.string.help_qingyise, "ziyise" to R.string.help_ziyise, "qianggang" to R.string.help_qianggang,
  "gangshang" to R.string.help_gangshang, "haidi" to R.string.help_haidi, "hedi" to R.string.help_hedi, "tianhu" to R.string.help_tianhu,
  "dihu" to R.string.help_dihu, "renhu" to R.string.help_renhu, "any_wind" to R.string.help_any_wind,
  "no_honor_flower" to R.string.help_no_honor_flower, "kong_tai" to R.string.help_kong_tai, "ankong_tai" to R.string.help_ankong_tai,
  "tianting" to R.string.help_tianting, "diting" to R.string.help_diting,
)
