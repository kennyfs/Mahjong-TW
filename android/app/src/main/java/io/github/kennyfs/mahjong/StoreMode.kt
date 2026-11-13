package io.github.kennyfs.mahjong

import android.content.Intent

/**
 * The store's screenshots (android/scripts/shots.py STORE=1): a debug build started with the extra `store_seed` plays
 * practice from that seed, the same game on every run: the seed is the game's id (which seeds every computer player's
 * decision), each hand's deal comes from it, and the player deals first. The extra `store_rules` ("tai,flowers", e.g.
 * simple2,see) sets the rules. android/scripts/store_plan.py plays the same game with the engine and writes the
 * player's moves for shots.py to make. A release build never reads the extras.
 */
object StoreMode {
  /** The game's id, or null: an ordinary game. */
  var seed: String? = null
    private set
  var rules: RuleChoice? = null
    private set

  fun read(intent: Intent?) {
    if (!BuildConfig.DEBUG || intent == null) return
    seed = intent.getStringExtra("store_seed")?.ifBlank { null } ?: return
    rules = intent.getStringExtra("store_rules")?.split(',')?.takeIf { it.size == 2 }?.let { RuleChoice(it[0], it[1]) }
  }

  /** The deal of a game's first hand, and of the hand after `hands` hands. */
  fun firstDeal(): String? = seed?.let { "$it/0" }
  fun nextDeal(hands: Int): String? = seed?.let { "$it/next/$hands" }
}
