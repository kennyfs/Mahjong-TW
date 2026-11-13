package io.github.kennyfs.mahjong

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kennyfs.mahjong.ui.AnalyzeScreen
import io.github.kennyfs.mahjong.ui.BattleScreen
import io.github.kennyfs.mahjong.ui.GuideScreen
import io.github.kennyfs.mahjong.ui.LocalTable
import io.github.kennyfs.mahjong.ui.LocalTour
import io.github.kennyfs.mahjong.ui.MahjongTheme
import io.github.kennyfs.mahjong.ui.PlayScreen
import io.github.kennyfs.mahjong.ui.RecordsScreen
import io.github.kennyfs.mahjong.ui.ReplayScreen
import io.github.kennyfs.mahjong.ui.AdvancedScreen
import io.github.kennyfs.mahjong.ui.RulesScreen
import io.github.kennyfs.mahjong.ui.SettingsScreen
import io.github.kennyfs.mahjong.ui.TOUR_STEPS
import io.github.kennyfs.mahjong.ui.TourOverlay
import io.github.kennyfs.mahjong.ui.FxOverlay
import io.github.kennyfs.mahjong.ui.WelcomeDialog
import io.github.kennyfs.mahjong.ui.isDark
import io.github.kennyfs.mahjong.ui.tourAnchor

private class Tab(val screen: Screen, val label: Int, val icon: ImageVector, val selected: ImageVector)

/** Practice's icon, the web page's (Icon.svelte: tileOutlined, tile): a tile, drawn here; outlined, and filled when chosen. */
private fun tileIcon(name: String, path: String): ImageVector =
  ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

private val TileOutlined = tileIcon(
  "TileOutlined",
  "M8 2H16A3 3 0 0 1 19 5V19A3 3 0 0 1 16 22H8A3 3 0 0 1 5 19V5A3 3 0 0 1 8 2ZM8 4A1 1 0 0 0 7 5V19A1 1 0 0 0 8 20H16A1 1 0 0 0 17 19V5A1 1 0 0 0 16 4Z" +
    "M12 9A3 3 0 1 1 12 15A3 3 0 1 1 12 9Z",
)
private val TileFilled = tileIcon(
  "TileFilled",
  "M8 2H16A3 3 0 0 1 19 5V19A3 3 0 0 1 16 22H8A3 3 0 0 1 5 19V5A3 3 0 0 1 8 2ZM12 9A3 3 0 1 0 12 15A3 3 0 1 0 12 9Z",
)

private val TABS = listOf(
  Tab(Screen.PLAY, R.string.nav_play, TileOutlined, TileFilled),
  Tab(Screen.BATTLE, R.string.nav_battle, Icons.Outlined.Groups, Icons.Filled.Groups),
  Tab(Screen.ANALYZE, R.string.nav_analyze, Icons.Outlined.Assessment, Icons.Filled.Assessment),
  Tab(Screen.RECORDS, R.string.nav_records, Icons.Outlined.History, Icons.Filled.History),
  Tab(Screen.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
  /** A touch anywhere lets the words over the players and the moments go (CalloutClock; a result's moment stopped for the
   *  touch goes on); it still does what it was for, but where it began over a moment (FxClock.downOverFx: the table's
   *  tap to go on at a hand's end). */
  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
      FxClock.downOverFx = FxClock.on.value
      CalloutClock.skip()
    }
    return super.dispatchTouchEvent(ev)
  }

  // (No sounds while the app is not on the screen.)
  override fun onStart() {
    super.onStart()
    Sounds.visible = true
  }

  override fun onStop() {
    Sounds.visible = false
    super.onStop()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val vm: AppModel = viewModel()
      val dark = isDark(vm.settings.dark, isSystemInDarkTheme())
      // System bar icons follow the app's own light or dark, which can differ from the system's.
      DisposableEffect(dark) {
        val style = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose {}
      }
      MahjongTheme(vm.settings.colors, vm.settings.dark, vm.settings.text) {
        // Test tags as resource ids: android/scripts/shots.py finds the controls by them (uiautomator).
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.surface) {
          App(vm)
          vm.error?.let { message ->
            AlertDialog(
              onDismissRequest = { vm.error = null },
              confirmButton = { TextButton(onClick = { vm.error = null }) { Text(stringResource(R.string.ok)) } },
              text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
            )
          }
        }
      }
    }
  }
}

/**
 * The screens with the navigation bar (a tall window) or rail (a wide one). Each screen keeps its own pages, so
 * switching screens and back returns to the page left. Back: the screen's own pages first, then to Practice, then out
 * of the app. At a battle's table the other screens are out of reach (no bar or rail) until the player leaves; nor is
 * there a bar or rail over a hand's review: its Back leads out, and the table takes the room. Over it all: the welcome
 * card of the first start, and the tour (ui/Tour.kt), which shows each of its steps' screens at their top.
 */
@Composable
private fun App(vm: AppModel) {
  val battle = vm.battle
  val window = LocalWindowInfo.current.containerSize
  val landscape = window.width > window.height
  val type = if (landscape) NavigationSuiteType.NavigationRail
  else NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
  // Landscape is full screen: the status and navigation bars come back with a swipe from the edge.
  val view = LocalView.current
  SideEffect {
    val w = (view.context as? Activity)?.window ?: return@SideEffect
    val controller = WindowCompat.getInsetsController(w, view)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (landscape) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
  }
  LaunchedEffect(battle.inRoom) { if (battle.inRoom) vm.tab = Screen.BATTLE }
  val tour = vm.tour
  LaunchedEffect(tour.step) { TOUR_STEPS.getOrNull(tour.step)?.let { vm.show(it.screen) } }
  val review = vm.pages.lastOrNull() == Page.REVIEW
  val immersive = battle.playing && vm.tab == Screen.BATTLE && vm.pages.isEmpty() || review
  BackHandler(vm.pages.isNotEmpty()) { vm.back() }
  BackHandler(vm.pages.isEmpty() && vm.tab != Screen.PLAY && !battle.inRoom && battle.room == null) { vm.tab = Screen.PLAY }
  CompositionLocalProvider(LocalTour provides tour) {
  NavigationSuiteScaffold(
    navigationSuiteItems = {
      for (t in TABS) {
        val selected = t.screen == vm.tab
        item(
          selected = selected,
          onClick = { vm.go(t.screen) },
          icon = { Icon(if (selected) t.selected else t.icon, null) },
          label = { Text(stringResource(t.label), maxLines = 1) },
          modifier = Modifier.testTag("nav_${t.screen.name}").tourAnchor("nav_${t.screen.name}"),
          alwaysShowLabel = true,
        )
      }
    },
    layoutType = if (immersive) NavigationSuiteType.None else type,
  ) {
    // The bar or rail looks after its own side; the content keeps clear of the rest.
    // (A review sideways keeps clear of the sides itself: its table's felt and its controls reach the edges.)
    val sides = if (review && landscape) WindowInsetsSides.Top + WindowInsetsSides.Bottom
    else if (immersive) WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    else if (type == NavigationSuiteType.NavigationBar) WindowInsetsSides.Top + WindowInsetsSides.Horizontal
    else WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom
    // Sideways, a table's felt reaches the edges, under the camera's cutout too; its tiles keep clear of it.
    val felt = landscape && vm.pages.isEmpty() && (vm.tab == Screen.PLAY || immersive && !battle.over)
    Box(
      Modifier.fillMaxSize().then(if (felt) Modifier.background(LocalTable.current.felt) else Modifier)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(sides)),
    ) {
      when (vm.pages.lastOrNull()) {
        Page.RULES -> RulesScreen(vm, vm::back)
        Page.ADVANCED -> AdvancedScreen(vm, vm::back)
        Page.REVIEW -> ReplayScreen(vm, vm::back)
        Page.GUIDE -> GuideScreen(
          vm, vm::back,
          onPlay = { vm.close(Page.GUIDE); vm.show(Screen.PLAY) },
          onTour = { vm.close(Page.GUIDE); tour.start() },
        )
        null -> when (vm.tab) {
          Screen.PLAY -> PlayScreen(vm)
          Screen.BATTLE -> BattleScreen(vm)
          Screen.ANALYZE -> AnalyzeScreen(vm)
          Screen.RECORDS -> RecordsScreen(vm)
          Screen.SETTINGS -> SettingsScreen(vm)
        }
      }
    }
  }
  // The first start: the welcome card, once. Not over a battle's room.
  if (vm.welcome && !battle.inRoom) WelcomeDialog(
    onLearn = { vm.welcomed(); vm.open(Page.GUIDE) },
    onTour = { vm.welcomed(); tour.start() },
    onClose = { vm.welcomed() },
  )
  if (tour.on && !battle.inRoom) TourOverlay(tour) { vm.open(Page.GUIDE) }
  // A call's or a win's moment, over everything.
  FxOverlay()
  }
}
