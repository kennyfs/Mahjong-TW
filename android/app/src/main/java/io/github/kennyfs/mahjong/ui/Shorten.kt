package io.github.kennyfs.mahjong.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout

/**
 * Words that shorten before they are cut: `content` at level 0 (all its words in full) where it fits the room across,
 * else at level 1, 2… (the content says what each level puts in a few characters: a round's label first, as 東-3, then
 * the rules' chip), the last level whatever the room (its words cut or wrapped as their own style says). Only the
 * levels tried are composed. The web page's lib/shorten.ts.
 *
 * (Its parent must not ask it for its intrinsic sizes: a SubcomposeLayout has none.)
 */
@Composable
fun Shorten(levels: Int, modifier: Modifier = Modifier, content: @Composable (level: Int) -> Unit) {
  SubcomposeLayout(modifier) { cs ->
    var level = 0
    var ms = subcompose(0) { content(0) }
    while (level < levels - 1 && (ms.maxOfOrNull { it.maxIntrinsicWidth(cs.maxHeight) } ?: 0) > cs.maxWidth) {
      level++
      val at = level
      ms = subcompose(at) { content(at) }
    }
    val ps = ms.map { it.measure(cs) }
    val w = (ps.maxOfOrNull { it.width } ?: 0).coerceIn(cs.minWidth, cs.maxWidth)
    val h = (ps.maxOfOrNull { it.height } ?: 0).coerceIn(cs.minHeight, cs.maxHeight)
    layout(w, h) { ps.forEach { it.place(0, 0) } }
  }
}
