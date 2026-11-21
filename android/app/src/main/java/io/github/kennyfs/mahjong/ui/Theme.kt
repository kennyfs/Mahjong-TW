package io.github.kennyfs.mahjong.ui

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kennyfs.mahjong.ColorStyle
import io.github.kennyfs.mahjong.DarkMode
import io.github.kennyfs.mahjong.TextSize

// The colors of Thirteen's app (its ui/theme/Theme.kt), the web page's app.css the same: the brand's green, the felt,
// and their dark forms; and the tiles: an ivory face, a blue back that shows on the felt.

val FELT = Color(0xFF1F6F54)

private val BrandLight = lightColorScheme(
  primary = FELT, onPrimary = Color.White, primaryContainer = Color(0xFFAEEFD0), onPrimaryContainer = Color(0xFF002115),
  secondary = Color(0xFF4D6357), onSecondary = Color.White, secondaryContainer = Color(0xFFCFE9D9), onSecondaryContainer = Color(0xFF0A1F15),
  tertiary = Color(0xFF3D6373), onTertiary = Color.White, tertiaryContainer = Color(0xFFC1E8FB), onTertiaryContainer = Color(0xFF001F29),
  error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
  background = Color(0xFFF5FBF6), onBackground = Color(0xFF171D1A), surface = Color(0xFFF5FBF6), onSurface = Color(0xFF171D1A),
  surfaceVariant = Color(0xFFDBE5DD), onSurfaceVariant = Color(0xFF404943), outline = Color(0xFF707973), outlineVariant = Color(0xFFBFC9C2),
  surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF5F0), surfaceContainer = Color(0xFFE9EFEA),
  surfaceContainerHigh = Color(0xFFE4EAE5), surfaceContainerHighest = Color(0xFFDEE4DF), surfaceDim = Color(0xFFD6DBD7),
  surfaceBright = Color(0xFFF5FBF6), inverseSurface = Color(0xFF2C322E), inverseOnSurface = Color(0xFFECF2ED), inversePrimary = Color(0xFF8CD5B4),
)

private val BrandDark = darkColorScheme(
  primary = Color(0xFF8CD5B4), onPrimary = Color(0xFF003827), primaryContainer = Color(0xFF00513A), onPrimaryContainer = Color(0xFFAEEFD0),
  secondary = Color(0xFFB4CCBE), onSecondary = Color(0xFF1F352A), secondaryContainer = Color(0xFF354B40), onSecondaryContainer = Color(0xFFCFE9D9),
  tertiary = Color(0xFFA5CCDF), onTertiary = Color(0xFF073543), tertiaryContainer = Color(0xFF244C5B), onTertiaryContainer = Color(0xFFC1E8FB),
  error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
  background = Color(0xFF0F1512), onBackground = Color(0xFFDEE4DF), surface = Color(0xFF0F1512), onSurface = Color(0xFFDEE4DF),
  surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFBFC9C2), outline = Color(0xFF8A938C), outlineVariant = Color(0xFF404943),
  surfaceContainerLowest = Color(0xFF0A0F0D), surfaceContainerLow = Color(0xFF171D1A), surfaceContainer = Color(0xFF1B211E),
  surfaceContainerHigh = Color(0xFF252B28), surfaceContainerHighest = Color(0xFF303633), surfaceDim = Color(0xFF0F1512),
  surfaceBright = Color(0xFF353B37), inverseSurface = Color(0xFFDEE4DF), inverseOnSurface = Color(0xFF2C322E), inversePrimary = FELT,
)

/** The table's colors: the felt, words on it, the highlight (the dealer, whose turn), and the tiles. */
@Immutable
data class TableColors(
  val felt: Color, val onFelt: Color, val onFeltDim: Color, val highlight: Color, val good: Color, val bad: Color,
  val action: Color, val onAction: Color,
  val tileFace: Color, val tileEdge: Color, val tileBack: Color, val tileBackLight: Color, val tileSelected: Color, val tileMark: Color,
  /** The tiles a ready hand waits with (聽牌提示): a blue face, its edge. */
  val tileWait: Color, val tileWaitEdge: Color,
  /** A discard straight from the draw (摸切): an orange face, its edge; one taken by a meld deeper (TileView). The bar
   *  beside a player's first discard after its call. */
  val tileDrawn: Color = Color(0xFFF9C08A), val tileDrawnEdge: Color = Color(0xFFD99A5C),
  val tileDrawnTaken: Color = Color(0xFFE8792A), val tileDrawnTakenEdge: Color = Color(0xFFB85A18), val callMark: Color = Color(0xFF1A73E8),
)

@Immutable
/** A result's green and red; `figGood`, `figBad`: the lists' figures' (darker in the light theme, to read on the
 *  lists' tinted rows). */
data class Outcome(val good: Color, val bad: Color, val figGood: Color = good, val figBad: Color = bad)

private val BRASS = Color(0xFFF0C058)

private fun tableFor(style: ColorStyle, scheme: ColorScheme, dark: Boolean): TableColors {
  val tiles = if (!dark) listOf(0xFFFDFBF3, 0xFFCFC8B4, 0xFF28559B, 0xFF3B6DB5, 0xFFFFF3C4, 0xFFE3F2FD, 0xFFB9D8FB, 0xFF7FA9D8)
  else listOf(0xFFF3EFE3, 0xFFB9B29C, 0xFF234B88, 0xFF315EA3, 0xFFFFE9A8, 0xFFD4E7FA, 0xFFA9CBF2, 0xFF6F98C8)
  val t = tiles.map { Color(it) }
  val table = when (style) {
    ColorStyle.TABLE ->
      if (!dark) TableColors(FELT, Color(0xFFEEF7F2), Color(0xFFB9D9C8), BRASS, Color(0xFFA8E6B0), Color(0xFFFFB4AB), Color(0xFFEEF7F2), FELT,
        t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7])
      else TableColors(Color(0xFF164F3D), Color(0xFFE3F0E9), Color(0xFFA3C4B3), Color(0xFFE8B954), Color(0xFFA8E6B0), Color(0xFFFFB4AB),
        Color(0xFFE3F0E9), Color(0xFF164F3D), t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7])
    ColorStyle.MINIMAL -> TableColors(
      scheme.surface, scheme.onSurface, scheme.onSurfaceVariant, scheme.primary,
      if (dark) Color(0xFF81C784) else Color(0xFF2E7D32), if (dark) Color(0xFFE57373) else Color(0xFFC62828), scheme.primary, scheme.onPrimary,
      t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7],
    )
    // Felt in the wallpaper's colors (Thirteen's): its primary, or in the dark its primary container.
    ColorStyle.WALLPAPER -> {
      val felt = if (dark) scheme.primaryContainer else scheme.primary
      val on = if (dark) scheme.onPrimaryContainer else scheme.onPrimary
      TableColors(felt, on, on.copy(alpha = 0.75f).compositeOver(felt), BRASS, Color(0xFFA8E6B0), Color(0xFFFFB4AB), on, felt,
        t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7])
    }
  }
  return if (dark) table.copy(tileDrawn = Color(0xFFECB47F), tileDrawnEdge = Color(0xFFC48A52), callMark = Color(0xFF6AB0FF)) else table
}

/** An analysis' levels: danger and grades (1 mild, 3 severe), and a good find. The web page's --danger-n and --found. */
object Marks {
  val danger1 = Color(0xFFF2C94C)
  val danger2 = Color(0xFFF2884A)
  val danger3 = Color(0xFFE5484D)
  val found = Color(0xFF2F9E6B)
  private val onMild = Color(0xFF2B1700)

  fun danger(level: Int) = when (level) { 1 -> danger1; 2 -> danger2; else -> danger3 }
  /** Words on a level's color. */
  fun on(level: Int) = if (level >= 3) Color.White else onMild
}

val LocalTable = staticCompositionLocalOf { tableFor(ColorStyle.TABLE, BrandLight, false) }
val LocalOutcome = staticCompositionLocalOf { Outcome(Color(0xFF2E7D32), Color(0xFFC62828)) }

/** Material's sizes, a little firmer: body text 16sp, nothing under 12sp. */
private val AppTypography = Typography().let { t ->
  fun TextStyle.at(size: Int, line: Int, weight: FontWeight? = null) = copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight ?: fontWeight)
  t.copy(
    headlineSmall = t.headlineSmall.at(22, 30, FontWeight.Bold),
    titleLarge = t.titleLarge.at(20, 26, FontWeight.Bold),
    titleMedium = t.titleMedium.at(16, 22, FontWeight.Medium),
    titleSmall = t.titleSmall.at(14, 20, FontWeight.Medium),
    bodyLarge = t.bodyLarge.at(16, 23),
    bodyMedium = t.bodyMedium.at(14, 20),
    bodySmall = t.bodySmall.at(13, 18),
    labelLarge = t.labelLarge.at(14, 20, FontWeight.Medium),
    labelMedium = t.labelMedium.at(13, 18, FontWeight.Medium),
    labelSmall = t.labelSmall.at(12, 16, FontWeight.Medium),
  )
}

private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(10.dp),
  large = RoundedCornerShape(14.dp), extraLarge = RoundedCornerShape(20.dp),
)

fun isDark(mode: DarkMode, system: Boolean) = when (mode) {
  DarkMode.SYSTEM -> system
  DarkMode.LIGHT -> false
  DarkMode.DARK -> true
}

/** The table's words at most this much larger than the system's standard size (the user's own phone's). */
const val TABLE_FONT_MAX = 1.15f

/** The screen's shorter side, at least this many dp at the table's density: a phone's narrowest (Android's 360 dp). */
const val TABLE_MIN_DP = 360f

/** The whole screen's shorter side, px (not a split screen's window: the table's density is the screen's). */
private fun screenShortSide(context: Context): Int {
  val wm = context.getSystemService(WindowManager::class.java)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
    val b = wm.maximumWindowMetrics.bounds
    return minOf(b.width(), b.height())
  }
  val m = context.resources.displayMetrics
  return minOf(m.widthPixels, m.heightPixels)
}

/**
 * The table's density (TableView, and the moments over it: FxOverlay): the phone's own whatever the system's display
 * size (DENSITY_DEVICE_STABLE), the font size no larger than TABLE_FONT_MAX, times the app's text size. A larger
 * display size or font size took the table's room for the words alone: its tiles got smaller, the hand went under the
 * navigation bar, the name plates over the box in the middle (2026-11-15). The rest of the app follows the system.
 * And no denser than TABLE_MIN_DP across the screen's shorter side: a Galaxy A53 drew its table at about 600 dpi
 * (its stable density as large as the one in use, a display size made larger), 288 dp high sideways, where the rail of
 * screens lost its last item and the plates their second line (2026-11-21).
 */
val LocalTableDensity = staticCompositionLocalOf { Density(1f) }

@Composable
fun MahjongTheme(style: ColorStyle, darkMode: DarkMode, textSize: TextSize, content: @Composable () -> Unit) {
  val dark = isDark(darkMode, isSystemInDarkTheme())
  val context = LocalContext.current
  val scheme = if (style == ColorStyle.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
  } else if (dark) BrandDark else BrandLight
  val outcome = if (dark) Outcome(Color(0xFF81C784), Color(0xFFEF9A9A)) else Outcome(Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF1B6E23), Color(0xFFA3191A))
  val density = LocalDensity.current
  val config = LocalConfiguration.current
  val tableDp = remember(config) { minOf(DisplayMetrics.DENSITY_DEVICE_STABLE / 160f, screenShortSide(context) / TABLE_MIN_DP) }
  CompositionLocalProvider(
    LocalTable provides tableFor(style, scheme, dark),
    LocalOutcome provides outcome,
    LocalDensity provides Density(density.density, density.fontScale * textSize.scale),
    LocalTableDensity provides Density(tableDp, minOf(density.fontScale, TABLE_FONT_MAX) * textSize.scale),
  ) {
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
  }
}
