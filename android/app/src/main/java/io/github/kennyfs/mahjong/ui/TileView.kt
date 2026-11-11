package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.kennyfs.mahjong.Tiles

/** Height over width: the face (3:4) and the edge of the back under it. */
const val TILE_RATIO = 1.45f

/** The faces' paths, parsed once (TileArt: the 42 faces on a 60 x 80 face). */
private object Faces {
  private val cache = HashMap<String, List<Triple<Path, Color, Boolean>>>()

  fun of(tile: String): List<Triple<Path, Color, Boolean>> = cache.getOrPut(tile) {
    (TileArt.faces[tile] ?: emptyList()).map { l ->
      val p = PathParser().parsePathString(l.path).toPath()
      if (l.evenOdd) p.fillType = PathFillType.EvenOdd
      Triple(p, Color(l.color), l.evenOdd)
    }
  }
}

/**
 * A tile, standing: its face (or its back for a null tile: another player's, a concealed kong), the edge of its back
 * under it. `w` wide, w * TILE_RATIO high. The web page's Tile.svelte, drawn the same way (a 60 x 87 box).
 * `danger` (1 to 3): a band along its foot, how likely a discard of it is to deal in (an analysis' figure).
 */
@Composable
fun TileView(
  tile: String?, w: Dp, modifier: Modifier = Modifier, selected: Boolean = false, dim: Boolean = false, glow: Boolean = false,
  mark: Boolean = false, danger: Int = 0,
) {
  val c = LocalTable.current
  val label = tile?.let(Tiles::name)
  Canvas(modifier.size(w, w * TILE_RATIO).let { m -> if (label != null) m.semantics { contentDescription = label } else m }) {
    val k = size.width / 60f
    val alpha = if (dim) 0.38f else 1f
    scale(k, k, pivot = Offset.Zero) {
      drawRoundRect(c.tileBack, Offset.Zero, Size(60f, 87f), CornerRadius(7f), alpha = alpha)
      if (tile == null) {
        drawRoundRect(c.tileBackLight, Offset(5f, 5f), Size(50f, 72f), CornerRadius(4f), alpha = alpha)
        return@scale
      }
      val face = when {
        selected -> c.tileSelected
        mark -> c.tileMark
        else -> c.tileFace
      }
      drawRoundRect(face, Offset(0.6f, 0.6f), Size(58.8f, 79.4f), CornerRadius(6.6f), alpha = alpha)
      val edge = if (selected || glow) c.highlight else c.tileEdge
      drawRoundRect(edge, Offset(0.6f, 0.6f), Size(58.8f, 79.4f), CornerRadius(6.6f), alpha = alpha,
        style = Stroke(if (glow) 4f else if (selected) 3f else 1.2f))
      // The art (60 x 80) in the face's 54 x 72 at (3, 3.2).
      translate(3f, 3.2f) {
        scale(0.9f, 0.9f, pivot = Offset.Zero) {
          for ((p, color, _) in Faces.of(tile)) drawPath(p, color, alpha = alpha)
        }
      }
      // The danger: a band along the foot (the edge of the back).
      if (danger > 0) clipRect(top = 76f) { drawRoundRect(Marks.danger(danger), Offset.Zero, Size(60f, 87f), CornerRadius(7f), alpha = alpha) }
    }
  }
}
