package io.github.kennyfs.mahjong.ui

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val SHADE = CubicBezierEasing(0.3f, 0.1f, 0.3f, 1f)
private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)

private fun seg(x: Float, a: Float, b: Float, e: Easing = LinearEasing) = e.transform(((x - a) / (b - a)).coerceIn(0f, 1f))
private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

/** The table without its color (where the system can blend so: Android 10 on), darker. */
private val GREY = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/**
 * The player's own deal-in, over the whole screen (ui/Moments.kt; TableView starts it, and the winners' moments come
 * after it): the table loses its color and darkens, dark and opaque at the top and clearer downwards, and 放槍 sinks in
 * with the tile dealt and whom it went to. It lasts `m.ms`; a touch anywhere lets it go. The web page's SadFx.svelte.
 */
@Composable
fun SadFx(m: Moment, host: FxHost) {
  val time = rememberFxTime(m, host)
  val density = LocalDensity.current.density
  BoxWithConstraints(Modifier.fillMaxSize().testTag("sad_fx"), contentAlignment = Alignment.Center) {
    val vmin = minOf(maxWidth.value, maxHeight.value)
    Canvas(Modifier.fillMaxSize()) {
      val p = time.p
      val dim = if (p < 0.16f) p / 0.16f else 1 - seg(p, 0.86f, 1f)
      if (GREY) drawRect(Color(0xFF808080), alpha = 0.9f * dim, blendMode = BlendMode.Saturation)
      drawRect(Color(0xFF080B10), alpha = 0.42f * dim)
      // Dark from the top down.
      val s = seg(p, 0f, 0.3f, SHADE)
      val a = minOf(1f, p / 0.3f) * (1 - seg(p, 0.86f, 1f))
      val h = size.height * mix(0.2f, 1f, s)
      drawRect(
        Brush.verticalGradient(0f to Color(0xF50A0D13), 0.22f to Color(0xD10A0D13), 0.48f to Color(0x730A0D13), 0.78f to Color(0x000A0D13), startY = 0f, endY = h),
        size = Size(size.width, h), alpha = a,
      )
    }
    // 放槍, sinking in; the tile dealt under it.
    val fs = (0.12f * vmin).coerceIn(41.6f, 96f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((0.03f * maxHeight.value).coerceIn(14f, 28f).dp)) {
      Column(
        Modifier.graphicsLayer {
          val p = time.p
          val em = fs * density
          val y = when {
            p < 0.08f -> -0.5f * em
            p < 0.3f -> mix(-0.5f * em, 0f, seg(p, 0.08f, 0.3f, EASE))
            p < 0.84f -> mix(0f, 0.08f * em, seg(p, 0.3f, 0.84f, EASE))
            else -> mix(0.08f * em, 0.2f * em, seg(p, 0.84f, 1f, EASE))
          }
          translationY = y
          alpha = when {
            p < 0.08f -> 0f
            p < 0.3f -> seg(p, 0.08f, 0.3f, EASE)
            p < 0.84f -> 1f
            else -> 1 - seg(p, 0.84f, 1f, EASE)
          }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Plaque(m.word, CalloutKind.DEALIN, fs, if (isCjk(m.word)) 2 else 3, "", tilt = 0f,
          face = Color(0xFF2B343A), ink = Color(0xFFFFB3A7), back = Color(0xFF0B0F12), line = null)
        if (m.note.isNotEmpty()) {
          val f = with(LocalDensity.current) { (fs * 0.2f).coerceAtLeast(13f).dp.toSp() }
          Text(m.note, Modifier.padding(top = (fs * 0.35f).dp).background(Color(0xEB101418), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 2.dp),
            style = TextStyle(color = Color(0xFFD9E2EA), fontWeight = FontWeight.Bold, fontSize = f), maxLines = 1, softWrap = false)
        }
      }
      if (m.tile != null) {
        val tw = minOf(host.w * 1.1f, 64f)
        TileView(m.tile, tw.dp, modifier = Modifier.graphicsLayer {
          val p = time.p
          translationY = if (p < 0.18f) -14 * density else mix(-14 * density, 0f, seg(p, 0.18f, 0.36f, EASE_OUT))
          alpha = if (p < 0.18f) 0f else if (p < 0.86f) seg(p, 0.18f, 0.36f) else 1 - seg(p, 0.86f, 1f)
          compositingStrategy = CompositingStrategy.Offscreen
        }.drawWithContent {
          // Greyer: towards a grey over the tile alone.
          drawContent()
          drawRect(Color(0xFF8A8F94), alpha = 0.35f, blendMode = BlendMode.SrcAtop)
        })
      }
    }
  }
}
