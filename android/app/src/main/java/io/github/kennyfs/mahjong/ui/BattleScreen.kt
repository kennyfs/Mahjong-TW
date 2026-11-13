package io.github.kennyfs.mahjong.ui

import androidx.compose.ui.platform.testTag
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.kennyfs.mahjong.AppModel
import io.github.kennyfs.mahjong.FxClock
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Rules
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.battle.Battle
import io.github.kennyfs.mahjong.battle.isLocalNetwork
import io.github.kennyfs.mahjong.battle.serverUrl
import io.github.kennyfs.mahjong.battle.shownName
import io.github.kennyfs.mahjong.protocol.Limits
import io.github.kennyfs.mahjong.protocol.Mode
import io.github.kennyfs.mahjong.protocol.Phase
import io.github.kennyfs.mahjong.protocol.PlayerView
import io.github.kennyfs.mahjong.protocol.RoomSettings
import io.github.kennyfs.mahjong.protocol.RoomView
import kotlinx.coroutines.delay
import io.github.kennyfs.mahjong.protocol.Length as RoomLength
import io.github.kennyfs.mahjong.protocol.RuleChoice as RoomRules

/**
 * The permissions rooms nearby need. From Android 17 an app that targets it also needs local network access, or
 * Nearby Connections fails once it tries the Wi-Fi LAN (Thirteen's BattleScreen.kt).
 */
private fun nearbyPermissions(): Array<String> = when {
  Build.VERSION.SDK_INT >= 37 -> arrayOf(
    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK,
  )
  Build.VERSION.SDK_INT >= 33 -> arrayOf(
    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.NEARBY_WIFI_DEVICES,
  )
  Build.VERSION.SDK_INT >= 31 -> arrayOf(
    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
  )
  else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

/**
 * Battles (battle/Battle.kt): the entry (online or nearby; a new room or joining one), the room's lobby (its PIN,
 * the players, the host's settings), the table while a hand is played (the player's own seat and the time left to
 * decide; no hint or analysis), each hand's result with the standings, and the final standings with the player's hands
 * (their review and analysis come from the records). The web page's Battle.svelte, with phones nearby added.
 */
@Composable
fun BattleScreen(vm: AppModel) {
  val battle = vm.battle
  val room = battle.room
  val table = battle.table
  val handOver = table?.view?.result != null
  var confirmLeave by remember { mutableStateOf(false) }
  val page = when {
    room == null -> 0
    room.phase == Phase.LOBBY && !battle.finished -> 1
    battle.over && (battle.lastSeen || !handOver) -> 3
    else -> 2
  }
  BackHandler(page == 1) { battle.leave() }
  BackHandler(page == 2 && battle.inRoom) { confirmLeave = true }
  BackHandler(page == 2 && !battle.inRoom || page == 3) { battle.dismiss() }
  when (page) {
    0 -> EntryPage(vm)
    1 -> LobbyPage(vm, room!!)
    2 -> TablePage(vm, room!!) { confirmLeave = true }
    else -> OverPage(vm, room!!)
  }
  if (confirmLeave) ConfirmDialog(stringResource(R.string.battle_leave_confirm), onConfirm = { battle.leave() }, onDismiss = { confirmLeave = false },
    confirm = stringResource(R.string.battle_leave))
}

/** The entry's steps: its first page, a new room (which kind), joining one. */
private enum class EntryStep { HOME, CREATE, JOIN }

/**
 * The entry: first only online or nearby, and a new room or joining one; then, for a new room, the name (and the
 * server, online) and which kind, a table or a same-tiles contest; to join, the name and the room's PIN (online: the
 * server too; nearby: the rooms found). Back from a step: the first page.
 */
@Composable
private fun EntryPage(vm: AppModel) {
  val battle = vm.battle
  val settings = vm.settings
  val context = LocalContext.current
  var editServer by remember { mutableStateOf(settings.server.isBlank()) }
  // What to do once the permissions asked for are granted, and what to say if they are not.
  var then by remember { mutableStateOf<(() -> Unit)?>(null) }
  var denied by remember { mutableIntStateOf(R.string.battle_permissions) }
  val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted.values.all { it }) then?.invoke() else vm.error = context.getString(denied)
    then = null
  }
  fun ask(needed: List<String>, deniedText: Int, action: () -> Unit) {
    val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isEmpty()) action() else {
      then = action
      denied = deniedText
      permissions.launch(missing.toTypedArray())
    }
  }
  fun withNearby(action: () -> Unit) = ask(nearbyPermissions().toList(), R.string.battle_permissions, action)
  // A server on the local network (a computer at home, the emulator's host): Android 17 asks first.
  fun withOnline(action: () -> Unit) {
    val u = serverUrl(settings.server)
    if (Build.VERSION.SDK_INT >= 37 && u != null && isLocalNetwork(u)) {
      ask(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK), R.string.battle_local_network, action)
    } else action()
  }
  var step by rememberSaveable { mutableStateOf(EntryStep.HOME) }
  // Nearby first until a server is set: the phones nearby need none.
  var nearby by rememberSaveable { mutableStateOf(settings.server.isBlank()) }
  var finding by remember { mutableStateOf(false) }
  fun home() {
    if (finding) {
      finding = false
      battle.stopFinding()
    }
    step = EntryStep.HOME
  }
  BackHandler(step != EntryStep.HOME) { home() }
  val cs = MaterialTheme.colorScheme
  val nameField = @Composable {
    OutlinedTextField(
      settings.name, { settings.name = it.take(Limits.NAME_LENGTH) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.battle_name)) },
      placeholder = { Text(battle.playerName) }, singleLine = true,
    )
  }
  val serverField = @Composable {
    if (editServer) OutlinedTextField(
      settings.server, { settings.server = it.trim() }, Modifier.fillMaxWidth().testTag("battle_server"), label = { Text(stringResource(R.string.battle_server)) },
      placeholder = { Text(stringResource(R.string.battle_server_app_hint)) }, singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    ) else Row(verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.battle_server_is, settings.server), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurfaceVariant)
      TextButton(onClick = { editServer = true }) { Text(stringResource(R.string.change)) }
    }
  }
  val pinField = @Composable { mod: Modifier ->
    OutlinedTextField(
      battle.pin, { battle.pin = it.filter(Char::isDigit).take(6) }, mod.testTag("battle_pin"), label = { Text(stringResource(R.string.battle_pin)) },
      singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), textStyle = MaterialTheme.typography.headlineSmall,
    )
  }
  Column(Modifier.fillMaxSize()) {
    TitleBar(
      stringResource(when (step) { EntryStep.HOME -> R.string.battle_title; EntryStep.CREATE -> R.string.battle_create; EntryStep.JOIN -> R.string.battle_join_room }),
      if (step == EntryStep.HOME) null else ({ home() }),
    )
    Box(Modifier.fillMaxWidth().height(4.dp)) { if (battle.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (step) {
          EntryStep.HOME -> {
            ToggleGroup(
              listOf(false to stringResource(R.string.battle_where_online), true to stringResource(R.string.battle_where_nearby)), nearby, { nearby = it },
              Modifier.fillMaxWidth().testTag("battle_where"),
            )
            Text(stringResource(if (nearby) R.string.battle_nearby_info else R.string.battle_online_info), style = MaterialTheme.typography.bodyMedium,
              color = cs.onSurfaceVariant)
            ChoiceCard(stringResource(R.string.battle_create), stringResource(if (nearby) R.string.battle_create_nearby_info else R.string.battle_create_info),
              !battle.busy, "battle_create") { step = EntryStep.CREATE }
            ChoiceCard(stringResource(R.string.battle_join_room), stringResource(if (nearby) R.string.battle_join_nearby_info else R.string.battle_join_room_info),
              !battle.busy, "battle_join", tonal = true) { step = EntryStep.JOIN }
            Text(stringResource(R.string.battle_no_help), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
          }
          EntryStep.CREATE -> {
            nameField()
            if (!nearby) serverField()
            Text(stringResource(R.string.battle_mode_title), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium)
            if (nearby) {
              ChoiceCard(stringResource(R.string.battle_new_table), stringResource(R.string.battle_table_info), !battle.busy, "battle_host_table") {
                withNearby { battle.host(Mode.TABLE) }
              }
              ChoiceCard(stringResource(R.string.battle_new_contest), stringResource(R.string.battle_contest_info), !battle.busy, "battle_host_contest") {
                withNearby { battle.host(Mode.CONTEST) }
              }
            } else {
              val ready = !battle.busy && settings.server.isNotBlank()
              ChoiceCard(stringResource(R.string.battle_new_table), stringResource(R.string.battle_table_info), ready, "battle_create_table") {
                withOnline { battle.create(Mode.TABLE) }
              }
              ChoiceCard(stringResource(R.string.battle_new_contest), stringResource(R.string.battle_contest_info), ready, "battle_create_contest") {
                withOnline { battle.create(Mode.CONTEST) }
              }
            }
          }
          EntryStep.JOIN -> {
            nameField()
            if (!nearby) {
              serverField()
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pinField(Modifier.weight(1f))
                Button(onClick = { withOnline { battle.join() } }, enabled = !battle.busy && settings.server.isNotBlank() && battle.pin.length == 6,
                  modifier = Modifier.heightIn(min = BIG_BUTTON).testTag("battle_join_go")) {
                  Text(stringResource(R.string.battle_join))
                }
              }
            } else if (!finding) {
              Button(onClick = { withNearby { finding = true; battle.findNearby() } }, enabled = !battle.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = BIG_BUTTON).testTag("battle_find")) {
                Text(stringResource(R.string.battle_find))
              }
            } else {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.battle_finding), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { finding = false; battle.stopFinding() }) { Text(stringResource(R.string.battle_stop_finding)) }
              }
              if (battle.nearbyRooms.isNotEmpty()) pinField(Modifier.fillMaxWidth())
              for ((endpoint, name) in battle.nearbyRooms) {
                Button(onClick = { finding = false; battle.joinNearby(endpoint) }, enabled = !battle.busy && battle.pin.length == 6,
                  modifier = Modifier.fillMaxWidth().heightIn(min = BIG_BUTTON)) {
                  Text(stringResource(R.string.battle_join_nearby, shownName(name)))
                }
              }
            }
          }
        }
      }
    }
  }
}

/** A large choice: a title and a line under it, the whole card the button; `tonal`: the second choice's colors. */
@Composable
private fun ChoiceCard(title: String, info: String, enabled: Boolean, tag: String, tonal: Boolean = false, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  val container = if (tonal) cs.secondaryContainer else cs.primaryContainer
  val content = if (tonal) cs.onSecondaryContainer else cs.onPrimaryContainer
  Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = container, contentColor = content,
    modifier = Modifier.fillMaxWidth().testTag(tag)) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = if (enabled) content else content.copy(alpha = 0.5f))
        Text(info, style = MaterialTheme.typography.bodyMedium)
      }
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
  }
}

@Composable
private fun lengthLabel(l: RoomLength) = stringResource(when (l) { RoomLength.HAND -> R.string.length_hand; RoomLength.ROUND -> R.string.length_round
  RoomLength.GAME -> R.string.length_game })

@Composable
private fun roomRules(s: RoomSettings) = rulesChip(s.rules.tai, s.rules.flowers, s.rules.overrides.isNotEmpty())

@Composable
private fun notes(battle: Battle, room: RoomView, p: PlayerView): String = listOfNotNull(
  if (p.id == room.host) stringResource(R.string.battle_host) else null,
  if (p.left) stringResource(R.string.battle_left) else if (!p.connected) stringResource(R.string.battle_away) else null,
).joinToString("・").also { battle.hashCode() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LobbyPage(vm: AppModel, room: RoomView) {
  val battle = vm.battle
  val s = room.settings
  val people = room.players.filter { !it.bot }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val short = maxHeight < 560.dp
    val wide = maxWidth >= 760.dp
    val start = @Composable {
      if (battle.isHost) Button(onClick = { battle.startGame() }, modifier = Modifier.heightIn(min = if (short) 40.dp else BIG_BUTTON).testTag("battle_start")) {
        Text(stringResource(R.string.battle_start))
      } else Text(stringResource(R.string.battle_waiting_host), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.fillMaxSize()) {
      TitleBar(stringResource(if (s.mode == Mode.CONTEST) R.string.battle_new_contest else R.string.battle_new_table)) {
        OutlinedButton(onClick = { battle.leave() }, Modifier.testTag("battle_leave")) { Text(stringResource(R.string.battle_leave)) }
        if (short) start()
      }
      val left = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(stringResource(R.string.battle_room_number), style = MaterialTheme.typography.bodyLarge)
              Text(room.pin.take(3) + " " + room.pin.drop(3), fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
              Text(stringResource(R.string.battle_room_number_info), style = MaterialTheme.typography.bodyMedium)
              if (battle.hosting) Text(stringResource(R.string.battle_hosting), style = MaterialTheme.typography.bodySmall)
            }
          }
          SectionHeader(stringResource(R.string.battle_players, people.size), Modifier.padding(start = 0.dp))
          for (p in people) Row(
            Modifier.fillMaxWidth().background(if (p.id == battle.me) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
              .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
              Text(p.name.take(1).ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(p.name + if (p.id == battle.me) "（${stringResource(R.string.you)}）" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
              maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(notes(battle, room, p), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      val right = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          SectionHeader(stringResource(R.string.battle_settings), Modifier.padding(start = 0.dp))
          fun change(x: RoomSettings) = battle.changeSettings(x)
          if (battle.isHost) {
            if (s.mode == Mode.TABLE) Setting(stringResource(R.string.battle_length)) {
              ToggleGroup(RoomLength.entries.map { it to lengthLabel(it) }, s.length, { change(s.copy(length = it)) }, Modifier.fillMaxWidth())
            } else Setting(stringResource(R.string.battle_hands)) {
              ToggleGroup(Battle.HAND_CHOICES.map { it to "$it" }, s.hands, { change(s.copy(hands = it)) }, Modifier.fillMaxWidth())
            }
            Setting(stringResource(R.string.battle_time_seconds)) {
              ToggleGroup(Battle.TIME_LIMITS.map { it to "$it" }, s.timeLimit, { change(s.copy(timeLimit = it)) }, Modifier.fillMaxWidth())
              Text(stringResource(R.string.battle_time_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Setting(stringResource(R.string.battle_rules)) {
              ToggleGroup(Rules.TAI_PRESETS.map { it to stringResource(Rules.taiLabel(it)) }, s.rules.tai,
                { change(s.copy(rules = s.rules.copy(tai = it))) }, Modifier.fillMaxWidth())
              ToggleGroup(Rules.FLOWER_PRESETS.map { it to stringResource(Rules.flowerLabel(it)) }, s.rules.flowers,
                { change(s.copy(rules = s.rules.copy(flowers = it))) }, Modifier.fillMaxWidth())
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(roomRules(s), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                  val r = vm.settings.rules
                  change(s.copy(rules = RoomRules(r.tai, r.flowers, r.overrides)))
                }) { Text(stringResource(R.string.battle_rules_page)) }
              }
            }
          } else {
            InfoRow(stringResource(if (s.mode == Mode.TABLE) R.string.battle_length else R.string.battle_hands),
              if (s.mode == Mode.TABLE) lengthLabel(s.length) else stringResource(R.string.battle_hands_n, s.hands))
            InfoRow(stringResource(R.string.battle_time), stringResource(R.string.battle_time_n, s.timeLimit))
            InfoRow(stringResource(R.string.battle_rules), roomRules(s))
          }
        }
      }
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = if (wide) 1100.dp else 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
          if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f)) { left() }
            Box(Modifier.weight(1f)) { right() }
          } else {
            left()
            right()
          }
          Spacer(Modifier.height(16.dp))
        }
      }
      if (!short) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center) { start() }
    }
  }
}

@Composable
private fun Setting(title: String, content: @Composable ColumnScope.() -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    content()
  }
}

@Composable
private fun InfoRow(title: String, value: String) {
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** The standings: rank, name (and notes), this hand's points (when asked), the total. */
@Composable
private fun Standings(battle: Battle, room: RoomView, withHand: Boolean) {
  val last = battle.outcomes.firstOrNull { it.index == room.hand }
  val cs = MaterialTheme.colorScheme
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
      Text(stringResource(R.string.battle_standings), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      if (withHand) Text(stringResource(R.string.battle_this_hand), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      Text(stringResource(R.string.battle_total), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    for (p in battle.standings()) {
      val x = if (withHand) battle.pointsOf(last, p.id) else null
      Row(
        Modifier.fillMaxWidth().background(if (p.id == battle.me) cs.secondaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
          .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("${battle.rankOf(p)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
          Text(battle.nameOf(p), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
          val n = notes(battle, room, p)
          if (n.isNotEmpty()) Text(n, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (withHand) Text(x?.let(::signed) ?: "", Modifier.width(64.dp), color = pointsColor(x ?: 0.0), style = MaterialTheme.typography.bodyLarge)
        Text(signed(p.total), Modifier.width(64.dp), color = pointsColor(p.total), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
      }
    }
  }
}

@Composable
private fun TablePage(vm: AppModel, room: RoomView, onLeave: () -> Unit) {
  val battle = vm.battle
  val table = battle.table
  val s = room.settings
  val handOver = table?.view?.result != null
  var hideResult by remember { mutableStateOf(false) }
  LaunchedEffect(handOver) { if (!handOver) hideResult = false }
  // A hand's end: the result's moment waits for the player's tap (TableView), then the result; without one (the words
  // alone) the table as it ended waits for it (TapOn). The time to be ready runs from the hand's end all the same.
  val (held, letGo) = endHold(handOver)
  val fxOn by FxClock.on.collectAsState()
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(250)
    }
  }
  val left = table?.deadline?.let { maxOf(0L, it - now) }
  val nextIn = if (room.phase == Phase.REVIEW) room.remainingMs?.let { maxOf(0L, it - (now - battle.roomAt)) } else null
  val people = room.players.filter { !it.bot }
  val handLabel = if (s.mode == Mode.CONTEST) stringResource(R.string.battle_hand_of, room.hand + 1, s.hands) else stringResource(R.string.hand_n, room.hand + 1)
  val clock = @Composable {
    if (left != null) {
      val bad = left < 5000
      Row(
        Modifier.background(if (bad) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
          .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        val color = if (bad) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
        Icon(Icons.Filled.Timer, null, Modifier.size(18.dp), tint = color)
        Text(stringResource(R.string.battle_your_time, ((left + 999) / 1000).toInt()), color = color, fontWeight = FontWeight.Bold)
      }
    }
  }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth > maxHeight
    val short = maxHeight < 560.dp
    val w = maxWidth.value
    Column(Modifier.fillMaxSize()) {
      if (!wide) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((table?.let { stringResource(R.string.round_wind, Tiles.wind(it.view.position.setup.round)) } ?: "") + " " + handLabel,
          style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Spacer(Modifier.weight(1f))
        clock()
        TextButton(onClick = onLeave) { Text(stringResource(R.string.battle_leave)) }
      }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        if (table == null) Loading()
        else {
          val names = battle.namesAt(table.seats)
          TableView(
            table.view, table.seat, names, scores = battle.totalsAt(table.seats), away = battle.awayAt(table.seats),
            revealed = if (handOver) table.view.hands else null, interactive = table.view.myTurn && !handOver,
            onAct = { battle.act(it) }, handLabel = if (wide) handLabel else "", readyHint = vm.settings.readyHint,
            status = if (wide) clock else null,
            // The table's top-right corner: the rules over the way out.
            corner = if (wide) ({
              Text(roomRules(s), color = LocalTable.current.onFeltDim, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
              MiniButton(stringResource(R.string.battle_leave), onLeave)
            }) else null,
            overlay = {
              if (handOver && (held || fxOn)) TapOn(letGo)
              else if (handOver && !hideResult) {
                val side = short && room.phase == Phase.REVIEW && people.size > 1
                val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                  TextButton(onClick = { hideResult = true }) { Text(stringResource(R.string.result_hands)) }
                  val wait = MaterialTheme.typography.bodyMedium
                  when {
                    room.phase == Phase.PLAYING -> Text(stringResource(R.string.battle_waiting_others, people.count { it.done || it.left }, people.size), style = wait)
                    battle.over -> Button(onClick = { battle.lastSeen = true }) { Text(stringResource(R.string.see_totals)) }
                    battle.mine?.ready == true -> Text(stringResource(R.string.battle_ready_waiting,
                      people.count { it.ready || it.left || !it.connected }, people.size), style = wait)
                    else -> Button(onClick = { battle.ready() }) { Text(stringResource(R.string.battle_ready)) }
                  }
                }
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)).padding(12.dp), contentAlignment = Alignment.Center) {
                  val standings = @Composable {
                    if (room.phase == Phase.REVIEW && people.size > 1) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                      Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Standings(battle, room, withHand = true)
                        if (nextIn != null) Text(stringResource(R.string.battle_next_in, ((nextIn + 999) / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
                      }
                    }
                  }
                  if (side) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    HandResultCard(table.view, names, table.seat, ((w - 330) / 18.5f).coerceIn(22f, 30f), Modifier.weight(1f), actions = actions)
                    Box(Modifier.width(300.dp)) { standings() }
                  } else Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HandResultCard(table.view, names, table.seat, resultTileW(wide, w), Modifier.weight(1f, fill = false), actions = actions)
                    standings()
                  }
                }
              } else if (handOver) Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Button(onClick = { hideResult = false }) { Text(stringResource(R.string.back)) }
              }
            },
          )
        }
        if (battle.reconnecting) Row(
          Modifier.align(Alignment.TopCenter).padding(8.dp).background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
          Text(stringResource(R.string.battle_reconnecting), color = MaterialTheme.colorScheme.inverseOnSurface)
        }
      }
    }
  }
}

@Composable
private fun OverPage(vm: AppModel, room: RoomView) {
  val battle = vm.battle
  val myHands = battle.battleId?.let { vm.records.ofGroup(it) } ?: emptyList()
  Column(Modifier.fillMaxSize()) {
    TitleBar(stringResource(R.string.battle_over)) {
      Button(onClick = { battle.dismiss() }) { Text(stringResource(R.string.battle_done)) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.readable(640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        battle.mine?.let { Text(stringResource(R.string.battle_place, battle.rankOf(it)), style = MaterialTheme.typography.headlineSmall) }
        Standings(battle, room, withHand = false)
        if (myHands.isNotEmpty()) {
          SectionHeader(stringResource(R.string.battle_your_hands), Modifier.padding(start = 0.dp))
          for (h in myHands) {
            val x = h.points[h.me]
            Row(Modifier.fillMaxWidth().clickable { vm.review(h.id) }.heightIn(min = 56.dp).padding(horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.record_hand_of, h.index ?: 0), style = MaterialTheme.typography.bodyLarge)
                Text(localTime(h.time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Text(signed(x), color = pointsColor(x), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
              Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          }
        }
      }
    }
  }
}
