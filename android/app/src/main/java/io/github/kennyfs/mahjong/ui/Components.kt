package io.github.kennyfs.mahjong.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kennyfs.mahjong.R

// The pieces the pages share (Thirteen's ui/Components.kt, the same look): the title row, a section's heading, a row
// of a settings list, a group of toggles, a chip, a stepper, a small label, a confirmation.

/** The height of the primary actions (Next hand, Analyze). */
val BIG_BUTTON = 56.dp

/** A page's title row: an optional back button, the title, and actions at the end. */
@Composable
fun TitleBar(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
  Row(
    modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (onBack == null) 16.dp else 4.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    actions()
  }
}

/** A group's heading in a list. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
  Text(
    text, modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
  )
}

/** A row of a settings list: a title, its current value under it, and an arrow when it opens a page. */
@Composable
fun SettingRow(
  title: String, value: String? = null, modifier: Modifier = Modifier, arrow: Boolean = true,
  trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
  Row(
    modifier.fillMaxWidth().heightIn(min = 64.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    trailing?.invoke()
    if (onClick != null && arrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

data class ToggleColors(val onContainer: Color, val onContent: Color, val offContainer: Color, val offContent: Color) {
  companion object {
    @Composable
    fun page() = MaterialTheme.colorScheme.let { ToggleColors(it.primary, it.onPrimary, it.surfaceContainerHighest, it.onSurface) }
  }
}

/** Two to four choices side by side, one of them on: a connected group of 48 dp buttons. */
@Composable
fun <T> ToggleGroup(
  options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier,
  colors: ToggleColors = ToggleColors.page(), style: TextStyle = MaterialTheme.typography.labelLarge,
) {
  Row(modifier.height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    for ((i, o) in options.withIndex()) {
      val on = o.first == selected
      val shape = RoundedCornerShape(
        topStart = if (i == 0) 24.dp else 6.dp, bottomStart = if (i == 0) 24.dp else 6.dp,
        topEnd = if (i == options.lastIndex) 24.dp else 6.dp, bottomEnd = if (i == options.lastIndex) 24.dp else 6.dp,
      )
      Box(
        Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).clip(shape).background(if (on) colors.onContainer else colors.offContainer)
          .selectable(on, role = Role.RadioButton) { onSelect(o.first) }.padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(o.second, color = if (on) colors.onContent else colors.offContent, style = style, textAlign = TextAlign.Center)
      }
    }
  }
}

/** An outlined chip, 40 dp tall within a 48 dp touch target; `on` fills it. */
@Composable
fun Chip(
  label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, menu: Boolean = false,
  leading: (@Composable () -> Unit)? = null, content: Color? = null,
) {
  val cs = MaterialTheme.colorScheme
  Surface(
    onClick = onClick, modifier = modifier.heightIn(min = 48.dp).padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp),
    color = if (on) cs.secondaryContainer else Color.Transparent, contentColor = content ?: if (on) cs.onSecondaryContainer else cs.onSurface,
    border = if (on) null else BorderStroke(1.dp, content?.copy(alpha = 0.6f) ?: cs.outline),
  ) {
    Row(Modifier.padding(start = if (leading != null) 8.dp else 12.dp, end = if (menu) 6.dp else 12.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      leading?.invoke()
      if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 240.dp))
      if (menu) Icon(Icons.Filled.ArrowDropDown, null)
    }
  }
}

/** A number with − and + buttons; `name`: what it counts, for a screen reader ("底 減一"). */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int = 0, max: Int = 99, name: String = "") {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    FilledTonalIconButton(onClick = { onChange(value - 1) }, enabled = value > min, modifier = Modifier.size(48.dp)) {
      Icon(Icons.Filled.Remove, if (name.isEmpty()) "−" else stringResource(R.string.stepper_less, name))
    }
    Text("$value", Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    FilledTonalIconButton(onClick = { onChange(value + 1) }, enabled = value < max, modifier = Modifier.size(48.dp)) {
      Icon(Icons.Filled.Add, if (name.isEmpty()) "+" else stringResource(R.string.stepper_more, name))
    }
  }
}

/** A small rounded label: the best action, the one chosen, a changed rule. */
@Composable
fun Badge(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
  Text(
    text, modifier.background(container, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
    color = content, style = MaterialTheme.typography.labelMedium, maxLines = 1,
  )
}

/** A question with Cancel and OK (or `confirm`). */
@Composable
fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit, confirm: String = stringResource(R.string.ok)) {
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodyLarge) } },
  )
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Keeps a column of text and controls to a readable width on large screens. */
fun Modifier.readable(max: Dp = 720.dp) = this.widthIn(max = max)

/** A points figure's color: green won, red lost. */
@Composable
fun pointsColor(x: Double): Color = when {
  x > 0 -> LocalOutcome.current.good
  x < 0 -> LocalOutcome.current.bad
  else -> MaterialTheme.colorScheme.onSurface
}
