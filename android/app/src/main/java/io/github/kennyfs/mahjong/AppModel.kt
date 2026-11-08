package io.github.kennyfs.mahjong

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kennyfs.mahjong.battle.Battle

/** The top-level screens, in the navigation bar or rail. */
enum class Screen { PLAY, BATTLE, ANALYZE, RECORDS, SETTINGS }

/** A page opened from a screen: Back returns to what was under it. */
enum class Page { RULES, REVIEW, ANALYSIS }

/** A decision of a recorded hand to analyze: the record cut there, and the seat deciding; the action it took. */
data class AnalysisTarget(val game: GameRecord, val seat: Int, val chosen: Action?, val names: List<String>)

/**
 * What the screens share, for as long as the app runs: the settings, the records, practice, battles, the pages opened
 * on each screen, the hand under review and the decision under analysis, and an error to show.
 */
class AppModel(private val app: Application) : AndroidViewModel(app) {
  val settings = Settings(app)
  val records = Records(app)
  var error by mutableStateOf<String?>(null)

  private fun names() = listOf(app.getString(R.string.you)) + (1..3).map { app.getString(R.string.cpu_n, it) }
  val practice = Practice(app, viewModelScope, settings, records, ::names) { error = app.getString(R.string.error_engine, it) }
  val battle = Battle(app, viewModelScope, settings, records) { error = it }
  val editor = PositionEditor()

  var tab by mutableStateOf(Screen.PLAY)
  private var stacks by mutableStateOf(Screen.entries.associateWith { emptyList<Page>() })
  val pages: List<Page> get() = stacks.getValue(tab)

  var reviewId by mutableStateOf<String?>(null)
  /** The review's step, kept while a decision's analysis is open over it. */
  var reviewStep by mutableIntStateOf(0)
  var analysis by mutableStateOf<AnalysisTarget?>(null)

  fun open(p: Page) {
    if (pages.lastOrNull() != p) stacks = stacks + (tab to pages + p)
  }

  fun back() {
    if (pages.isNotEmpty()) stacks = stacks + (tab to pages.dropLast(1))
  }

  /** Another screen; the one shown again goes back to its top. */
  fun go(s: Screen) {
    if (s == tab) stacks = stacks + (s to emptyList()) else tab = s
  }

  fun review(id: String) {
    reviewId = id
    reviewStep = 0
    open(Page.REVIEW)
  }

  fun analyze(t: AnalysisTarget) {
    analysis = t
    open(Page.ANALYSIS)
  }
}
