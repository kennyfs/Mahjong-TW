package io.github.kennyfs.mahjong

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kennyfs.mahjong.battle.Battle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The top-level screens, in the navigation bar or rail. */
enum class Screen { PLAY, BATTLE, ANALYZE, RECORDS, SETTINGS }

/** A page opened from a screen: Back returns to what was under it. */
enum class Page { RULES, REVIEW }

/**
 * What the screens share, for as long as the app runs: the settings, the records, practice, battles, the pages opened
 * on each screen, the hand under review, the analysis screen's position, and an error to show.
 */
class AppModel(private val app: Application) : AndroidViewModel(app) {
  init {
    Engine.setup(app)
  }

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
  /** The review's step, kept while another screen is shown over it (a step taken to the analysis screen). */
  var reviewStep by mutableIntStateOf(0)

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

  /** The records being reviewed one after another for the statistics: how many are done, and of how many. */
  var reviewing by mutableStateOf<Pair<Int, Int>?>(null)
    private set
  private var reviews: Job? = null

  /** Reviews these records in turn, keeping each one's summary (Review.kt keepSummary), until done or stopped. */
  fun reviewAll(list: List<HandRecord>) {
    if (reviews?.isActive == true || list.isEmpty()) return
    reviewing = 0 to list.size
    reviews = viewModelScope.launch {
      try {
        for ((i, h) in list.withIndex()) {
          keepSummary(records, h)
          reviewing = i + 1 to list.size
        }
      } finally {
        reviewing = null
      }
    }
  }

  fun stopReviews() {
    reviews?.cancel()
  }
}
