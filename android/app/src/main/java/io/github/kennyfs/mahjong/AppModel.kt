package io.github.kennyfs.mahjong

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kennyfs.mahjong.battle.Battle
import io.github.kennyfs.mahjong.ui.BranchRun
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The top-level screens, in the navigation bar or rail. */
enum class Screen { PLAY, BATTLE, ANALYZE, RECORDS, SETTINGS }

/** A page opened from a screen: Back returns to what was under it. GUIDE: the rules explained (ui/GuideScreen.kt);
 *  COACH: the AI coach (ui/CoachScreen.kt); TIPS: the table tips (ui/ExplainViews.kt). */
enum class Page { RULES, REVIEW, GUIDE, COACH, TIPS }

/**
 * What the screens share, for as long as the app runs: the settings, the records, practice, battles, the pages opened
 * on each screen, the hand under review, the analysis screen's position, the tour, and an error to show.
 */
class AppModel(private val app: Application) : AndroidViewModel(app) {
  init {
    Engine.setup(app)
    Timing.load(app)
    Sounds.load(app)
  }

  val settings = Settings(app).also { s ->
    Timing.speedOf = { s.speed }
    Sounds.level = { s.sound }
    // (Not with the system's animations off: the words alone, as the web page's reduced motion.)
    FxClock.enabled = { android.animation.ValueAnimator.areAnimatorsEnabled() }
    StoreMode.rules?.let { s.rules = it; s.persist() }
  }
  val records = Records(app)
  /** The AI coach: the player's hands read by a language model with the player's key (coach/Coach.kt). */
  val coach = io.github.kennyfs.mahjong.coach.Coach(app, viewModelScope, records)
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
  /** The review's branch (ui/BranchScreen.kt): the record and the step it was taken from; the analysis' position played
   *  on (從這裡打: its record, the player's seat). Here, not in the screens: they outlive the screens' turning sideways. */
  var branchFrom by mutableStateOf<Pair<String, Int>?>(null)
  var playFrom by mutableStateOf<Pair<GameRecord, Int>?>(null)
  /** The branch in play (its computer players run in this model's scope): the same one for the same start. */
  private var branch: Pair<Pair<GameRecord, Int>, BranchRun>? = null

  fun branchRun(source: GameRecord, me: Int): BranchRun {
    branch?.let { (key, run) -> if (key.first == source && key.second == me) return run }
    return BranchRun(viewModelScope, source, me) { settings.bot }.also { branch = (source to me) to it }
  }

  /** The branch is left: its computer players stop. */
  fun endBranch() {
    branch?.second?.close()
    branch = null
  }

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

  /** A screen at its top, whatever was open on it (the tour's steps, the guide's way to practice). */
  fun show(s: Screen) {
    tab = s
    stacks = stacks + (s to emptyList())
  }

  /** The page closed on the screen shown, wherever it is in its stack (the guide, left for practice or the tour). */
  fun close(p: Page) {
    stacks = stacks + (tab to pages.filter { it != p })
  }

  /** The tour (ui/Tour.kt): its step, and where the elements it lights are. */
  val tour = io.github.kennyfs.mahjong.ui.TourState()

  /** The welcome card of the first start (the rules explained, the tour, or play). */
  var welcome by mutableStateOf(!settings.welcomeSeen)
    private set

  fun welcomed() {
    welcome = false
    settings.welcomeSeen = true
  }

  /** A record's review, at a step (the coach's links open a decision's moment). */
  fun review(id: String, step: Int = 0) {
    reviewId = id
    reviewStep = step
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
