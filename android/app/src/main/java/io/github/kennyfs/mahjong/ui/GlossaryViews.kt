package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.kennyfs.mahjong.Glossary
import io.github.kennyfs.mahjong.R
import kotlinx.coroutines.launch

/** Builds an AnnotatedString with underlined clickable glossary terms (styled like CoachScreen move links). */
@Composable
fun linkedGlossaryText(
  text: String,
  onTerm: (String) -> Unit,
  beforeId: String? = null,
  linkColor: Color = MaterialTheme.colorScheme.primary,
): AnnotatedString {
  val locales = LocalConfiguration.current.locales
  val english = locales.size() > 0 && locales[0].language.equals("en", ignoreCase = true)
  val parts = remember(text, english, beforeId) { Glossary.link(text, english, beforeId) }
  val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
  return remember(parts, linkColor) {
    buildAnnotatedString {
      for (p in parts) {
        val id = p.termId
        if (id != null) {
          withLink(LinkAnnotation.Clickable(tag = id, styles = linkStyle) { onTerm(id) }) { append(p.text) }
        } else {
          append(p.text)
        }
      }
    }
  }
}

/** Beginner glossary (術語表) on a page of its own, from Settings or Guide. */
@Composable
fun GlossaryScreen(onBack: () -> Unit, focusId: String? = null) {
  Column(Modifier.fillMaxSize()) {
    TitleBar(stringResource(R.string.glossary_title), onBack)
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      GlossaryList(
        focusId = focusId,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
      )
    }
  }
}

/** Beginner glossary modal dialog, opened when tapping an inline term inside ExplainDialog. */
@Composable
fun GlossaryDialog(focusId: String?, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
    modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 560.dp).testTag("glossary_dialog"),
    title = { Text(stringResource(R.string.glossary_title)) },
    text = {
      GlossaryList(
        focusId = focusId,
        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
      )
    },
    confirmButton = { FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
  )
}

@Composable
private fun GlossaryList(focusId: String?, modifier: Modifier = Modifier, contentPadding: PaddingValues) {
  val cs = MaterialTheme.colorScheme
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  var activeId by remember(focusId) { mutableStateOf(focusId) }

  fun jumpTo(id: String) {
    activeId = id
    val idx = Glossary.INDEX_BY_ID[id] ?: return
    // +1 accounts for the intro header item at index 0.
    scope.launch { listState.animateScrollToItem(idx.coerceAtLeast(0)) }
  }

  LaunchedEffect(focusId) {
    if (focusId != null) {
      val idx = Glossary.INDEX_BY_ID[focusId] ?: return@LaunchedEffect
      listState.scrollToItem(idx.coerceAtLeast(0))
    }
  }

  LazyColumn(
    state = listState,
    modifier = modifier,
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    itemsIndexed(Glossary.ENTRIES, key = { _, e -> e.id }) { idx, entry ->
      val focused = activeId == entry.id
      val bg = if (focused) cs.primaryContainer else cs.surfaceContainerLow
      val fg = if (focused) cs.onPrimaryContainer else cs.onSurface
      val linkColor = if (focused) cs.onPrimaryContainer else cs.primary
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = if (focused) BorderStroke(1.dp, cs.primary) else null,
        modifier = Modifier.fillMaxWidth().testTag("glossary_${entry.id}"),
      ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
              "${idx + 1}",
              style = MaterialTheme.typography.labelMedium,
              color = if (focused) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
            )
            Text(stringResource(entry.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
          }
          val desc = linkedGlossaryText(stringResource(entry.desc), onTerm = ::jumpTo, beforeId = entry.id, linkColor = linkColor)
          Text(desc, style = MaterialTheme.typography.bodyMedium, color = fg)
        }
      }
    }
  }
}
