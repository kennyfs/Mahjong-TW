package io.github.kennyfs.mahjong.ui

import io.github.kennyfs.mahjong.Words
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kennyfs.mahjong.Action
import io.github.kennyfs.mahjong.CalloutClock
import io.github.kennyfs.mahjong.FxClock
import io.github.kennyfs.mahjong.HandResult
import io.github.kennyfs.mahjong.Discard
import io.github.kennyfs.mahjong.GameEvent
import io.github.kennyfs.mahjong.Meld
import io.github.kennyfs.mahjong.R
import io.github.kennyfs.mahjong.Side
import io.github.kennyfs.mahjong.Tiles
import io.github.kennyfs.mahjong.View
import io.github.kennyfs.mahjong.concealedCount
import io.github.kennyfs.mahjong.flowersOf
import io.github.kennyfs.mahjong.meldTiles
import io.github.kennyfs.mahjong.seatAt
import io.github.kennyfs.mahjong.sideOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import io.github.kennyfs.mahjong.TileMark
import io.github.kennyfs.mahjong.ChoiceMark
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalContext
import io.github.kennyfs.mahjong.Timing
import io.github.kennyfs.mahjong.Sounds
import kotlin.math.roundToLong
import androidx.compose.material3.LocalTextStyle
import io.github.kennyfs.mahjong.pts
import io.github.kennyfs.mahjong.pct
import io.github.kennyfs.mahjong.dangerLevel
import io.github.kennyfs.mahjong.dangerTiles
import io.github.kennyfs.mahjong.Opponent
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup

private val Float.d: Dp get() = this.dp

/** A few words on a name plate (a review): how likely the player is ready, or what they held (level 0 to 3). `opp`: the
 *  opponent's figures, shown when the words are tapped (SeatBadge, OppCard); `short`: the words where the plate has no
 *  room for them (Shorten). */
data class SeatNote(val text: String, val level: Int, val opp: Opponent? = null, val short: String? = null)

/** The room kept over each row of the hand for the tiles' tags, when there may be some. */
private const val TAG_H = 17f

/** A chosen tile's rise (of its height) where the tiles have tags. */
private const val MARKED_LIFT = 0.1f

/** What another seat shows: its melds' tile counts, its flowers, and its concealed tiles when they are shown. */
private class Shown(val counts: List<Int>, val flowers: Int, val concealed: Int)

/**
 * The table as one seat sees it (a seat's `view`): the player at the bottom with their hand, the others around, the
 * rivers in the middle. A wide table has the round, the wall and the dealer's streak in its top-left corner and the
 * screen's `corner` (its buttons) in its top-right one, nothing between the rivers; a tall one has them in a box
 * between the rivers. Sizes from TableLayout (the web page's Table.svelte, the same arithmetic).
 *
 * The player's turn to discard: the hand in a pulsing frame of the turn's color; with no tile drawn (the dealer's
 * first discard, after a call) no tile is set apart (the frame says whose turn it is). A discard the player may claim stands out in
 * its river (larger, pulsing), and the claim buttons show their tiles large, the claimed one lit. A wide table's own
 * flowers lie over the hand, above the player's name plate (in the hand's row they took its room: a drawn tile was
 * squeezed small).
 *
 * `revealed`: every seat's concealed tiles (a finished hand, a replay showing all). The others' then lie on a rack (a
 * darker tray) after their melds, their tiles as large as their room allows: the player opposite in one line along
 * the top of a wide table; on a tall one the others' strip grows for them (the rivers give the room).
 * `status`: beside the player's name plate (a battle's clock); `overlay`: over the table.
 * A review's: `marks` (tags and danger on the hand's tiles, Review.kt), `notes` (a few words on each name plate),
 * `strip` (a line over the hand: the options that are not discards, a tile's figures), `onPick` (the tile tapped).
 * `onNext` (practice at the manual pace): the others' next step waits for the player: a button for it, where the
 * player's hand leaves room when it is not their turn (a tall table: at the end of the hand's first row, the place of
 * no tile, so that a tap too many discards nothing; a wide one: over the hand's right end).
 * `stay`: a call's and a win's words stay until the next touch (the game waits for the player anyway).
 * `robbed`: the event (an index into the view's events) that was a claim taken from the player: its words are 搶碰,
 * 搶胡. The moments (ui/Moments.kt: a call's by the caller, a hand's end over the whole screen) play over the table, one
 * after another; the words wait under them. At a hand's end the hands are shown once who dealt in has been shown (a
 * draw's: after a pause), then a pause, then the result's moment, which stops for a tap. `review`: a review's table:
 * its moments of a hand's end are small, by the player, and shorter (Timing's review_end). The player's own discard
 * moves from the hand to the river while the hand closes its gap (HandMotion).
 *
 * The table has a density of its own (Theme's LocalTableDensity): the phone's own whatever the system's display size,
 * its words no larger than at TABLE_FONT_MAX of the system's font size. Its sizes are its own arithmetic
 * (TableLayout.kt), which knows nothing of the words' size: so it is laid out on every phone as tested, its tiles as
 * large as that gives, with nothing overlapping.
 */
@Composable
fun TableView(
  view: View, me: Int, names: List<String>, modifier: Modifier = Modifier, scores: List<Double>? = null, revealed: List<String>? = null,
  interactive: Boolean = true, onAct: ((Action) -> Unit)? = null, handLabel: String = "",
  /** The hand in a few characters (3, a contest's 3/8): the round's label as 東-3 where the full one has no room. */
  handShort: String = "", maxHand: Float = 64f,
  away: List<Boolean> = listOf(false, false, false, false),
  status: (@Composable () -> Unit)? = null, corner: (@Composable () -> Unit)? = null, overlay: (@Composable BoxScope.() -> Unit)? = null,
  marks: Map<String, TileMark>? = null, notes: List<SeatNote?>? = null, strip: (@Composable () -> Unit)? = null,
  onPick: ((String?) -> Unit)? = null, onNext: (() -> Unit)? = null, stay: Boolean = false, robbed: Int = -1, review: Boolean = false,
  readyHint: Boolean = false, deal: String? = null, choiceMarks: ((Action) -> ChoiceMark?)? = null,
  /** The tags' room kept over the hand whether it has tags now or not (the analysis shown as one plays: they come
   *  and go with the turns, and the table keeps its size). */
  tagRoom: Boolean = false,
  /** The player's expected result (a practice hand with its hints), by their name plate where there is room for it
   *  (ValueAt). */
  value: Double? = null,
  /** The analysis on its way (practice with it shown, its first one): grey shapes for the tags over the hand that may
   *  be discarded; `valuePending`, for the expected result while there is none yet. */
  pending: Boolean = false, valuePending: Boolean = false,
) {
  CompositionLocalProvider(LocalDensity provides LocalTableDensity.current) {
    TableViewIn(view = view, me = me, names = names, modifier = modifier, scores = scores, revealed = revealed, interactive = interactive, onAct = onAct, handLabel = handLabel, handShort = handShort, maxHand = maxHand, away = away, status = status, corner = corner, overlay = overlay, marks = marks, notes = notes, strip = strip, onPick = onPick, onNext = onNext, stay = stay, robbed = robbed, review = review, readyHint = readyHint, deal = deal, choiceMarks = choiceMarks, tagRoom = tagRoom,
      // (NaN: the expected result's grey shape, ValueChip.)
      value = value ?: if (valuePending) Double.NaN else null, pending = pending)
  }
}

/** TableView in the table's density. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TableViewIn(
  view: View, me: Int, names: List<String>, modifier: Modifier = Modifier, scores: List<Double>? = null, revealed: List<String>? = null,
  interactive: Boolean = true, onAct: ((Action) -> Unit)? = null, handLabel: String = "",
  /** The hand in a few characters (3, a contest's 3/8): the round's label as 東-3 where the full one has no room. */
  handShort: String = "", maxHand: Float = 64f,
  away: List<Boolean> = listOf(false, false, false, false),
  status: (@Composable () -> Unit)? = null, corner: (@Composable () -> Unit)? = null, overlay: (@Composable BoxScope.() -> Unit)? = null,
  marks: Map<String, TileMark>? = null, notes: List<SeatNote?>? = null, strip: (@Composable () -> Unit)? = null,
  onPick: ((String?) -> Unit)? = null, onNext: (() -> Unit)? = null, stay: Boolean = false, robbed: Int = -1, review: Boolean = false,
  readyHint: Boolean = false, deal: String? = null, choiceMarks: ((Action) -> ChoiceMark?)? = null,
  /** The tags' room kept over the hand whether it has tags now or not (the analysis shown as one plays: they come
   *  and go with the turns, and the table keeps its size). */
  tagRoom: Boolean = false,
  /** The player's expected result (a practice hand with its hints), by their name plate where there is room for it
   *  (ValueAt). */
  value: Double? = null,
  pending: Boolean = false,
) {
  val c = LocalTable.current
  val density = LocalDensity.current
  val host = remember { FxHost() }
  DisposableEffect(host) {
    FxStage.host = host
    onDispose {
      if (FxStage.host === host) FxStage.host = null
      FxClock.on.value = false
      FxClock.resultOn.value = false
    }
  }
  host.review = review
  // The words over the players and the moments, from the events (TableEvents); the moments' times (MomentsClock).
  val words = remember { mutableStateListOf<Callout>() }
  TableEvents(host, view, me, names, robbed, review, stay, words)
  MomentsClock(host)
  // The hands shown (`revealed`) once a hand's end comes to them: after who dealt in, a draw's pause (a gap that hides
  // them), and before the pause that leads to the result's moment; not before the events are seen (their moments start
  // a frame later).
  val unseen = host.seen >= 0 && view.eventCount - host.seen in 1..8 && FxClock.enabled() &&
    view.events.takeLast(view.eventCount - host.seen).any { it.type == "ron" || it.type == "exhausted" || it.type == "tsumo" || it.type == "flower_win" }
  val shownAll = if (unseen || host.moments.indexOfFirst { it.kind == MomentKind.GAP && !it.hide } > 0) null else revealed
  // A hand's end: one sound as the hands are shown, by what it is to the player: their own win; another's they pay for
  // (on their discard, or self-drawn); another's they neither gain nor lose by; a draw. Without the moments (words
  // only), the result's jingle a moment after. (A table that starts with them shown, a replay showing all: none.)
  val wasShown = remember { arrayOfNulls<Boolean>(1) }
  val shownNow = shownAll != null
  LaunchedEffect(shownNow) {
    if (shownNow && wasShown[0] == false) {
      val r = view.result
      val pays = r != null && (r.loser == me || (r.selfDraw && r.winners.none { it.seat == me }))
      Sounds.play(when {
        r == null -> Sounds.Name.OTHER
        r.exhausted -> Sounds.Name.DRAWN
        r.winners.any { it.seat == me } -> Sounds.Name.WIN
        pays -> Sounds.Name.LOSE
        else -> Sounds.Name.OTHER
      })
      if (!FxClock.enabled()) Sounds.play(jingleOf(r, me), 400)
    }
    wasShown[0] = shownNow
  }
  // The melds that calls just made, which their moments will bring: out of sight already in this first frame (Melds),
  // as on the page, not shown for the frame before TableEvents starts the moments.
  val coming = if (host.seen >= 0 && view.eventCount - host.seen in 1..8 && FxClock.enabled())
    comingMelds(view, view.events.takeLast(view.eventCount - host.seen)) else emptyMap()
  // A hand just begun (practice: its id, `deal`): its tiles are shuffled and dealt first (DealFx), once (not again when
  // the table comes back to it). Meanwhile the player's hand is not there, the others' backs neither, nobody's flowers;
  // then the hand turns face up (the flowers with it). The computer players wait for it (Practice: CalloutClock); a
  // touch lets it go.
  var dealing by remember { mutableStateOf<Dealing?>(null) }
  /** The hand's tile width as laid out (dp): where the player's tiles go. */
  val dealHand = remember { floatArrayOf(40f) }
  val dealSoon = deal != null && deal != DealShown.last && FxClock.enabled()
  val dealHide = dealSoon || dealing?.flipping == false
  val dealOn = dealHide || dealing != null
  LaunchedEffect(deal) {
    val d = deal ?: return@LaunchedEffect
    if (DealShown.last == d) return@LaunchedEffect
    if (!FxClock.enabled()) {
      DealShown.last = d
      return@LaunchedEffect
    }
    // (The table laid out first: where the tiles go.)
    withFrameMillis { }
    withFrameMillis { }
    DealShown.last = d
    val table = host.areas["table"]
    val mid = host.areas["center"]
    val hand = host.areas["seat-$me"]
    if (table == null || mid == null || hand == null) return@LaunchedEffect
    val o = table.topLeft
    val px = density.density
    val handW = dealHand[0]
    val targets = (0 until 4).map { seat ->
      if (seat == me) Offset(hand.left - o.x + minOf(hand.width / 2, (16 + 8 * handW) * px), hand.bottom - o.y - handW * TILE_RATIO * 0.7f * px)
      else host.areas["seat-$seat"]?.let { it.center - o } ?: Offset.Zero
    }
    dealing = Dealing(mid.translate(-o.x, -o.y), targets)
    CalloutClock.shown(dealMs())
  }
  val dl = dealing
  LaunchedEffect(dl, dl?.flipping) {
    if (dl == null || !dl.flipping) return@LaunchedEffect
    val d = Timing.ms("deal_flip")
    val start = withFrameMillis { it }
    while (dl.flipT < d) withFrameMillis { dl.flipT = it - start }
    if (dealing === dl) dealing = null
  }
  // A touch lets the deal go.
  val dealSkips by CalloutClock.skips.collectAsState()
  val dealSkips0 = remember { intArrayOf(dealSkips) }
  LaunchedEffect(dealSkips) {
    if (dealSkips != dealSkips0[0]) dealing = null
    dealSkips0[0] = dealSkips
  }
  val motion = remember { HandMotion() }
  LaunchedEffect(motion.ticks) {
    if (motion.ticks == 0) return@LaunchedEffect
    while (motion.anyMoving) withFrameMillis { motion.frame = it }
    motion.frame = -1
    motion.flying = false
  }
  BoxWithConstraints(modifier.fillMaxSize().background(c.felt)) {
    val roomW = maxWidth.value
    val roomH = maxHeight.value
    val pos = view.position
    val setup = pos.setup
    val turnSeat = if (view.phase == "over") -1 else view.current
    val winds = view.seatWinds
    // The name plates as they are laid out, in dp (a long note takes a second line); BOTTOM: the player's corner.
    var plates by remember { mutableStateOf(mapOf<Side, Pair<Float, Float>>()) }
    fun measured(side: Side) = Modifier.onSizeChanged { size ->
      val v = size.width / density.density to size.height / density.density
      if (plates[side] != v) plates = plates + (side to v)
    }
    fun shownOf(seat: Int): Shown {
      val p = pos.players[seat]
      return Shown(p.melds.map { meldTiles(it).size }, flowersOf(p).size, if (shownAll != null) Tiles.parse(shownAll[seat]).size else 0)
    }
    // Room over the hand's tiles for their tags, where they have any (the settings may show none).
    val tagH = if (tagRoom || (marks != null && marks.values.any { it.tag.isNotEmpty() })) TAG_H else 0f

    // The sizes. The tags' room comes off the table's height: what the hand's own lift does not already leave them.
    // A tall table's others' strip with everyone's tiles shown: as high as the three need at the largest tiles (20 dp
    // and up) that leave the rivers tiles of SHOWN_RIVER.
    var s0 = tableSizes(roomW, roomH, maxHand)
    var extra = 0f
    if (tagH > 0) {
      extra = (if (s0.wide) 1 else 2) * maxOf(0f, tagH - s0.hand * TILE_RATIO * LIFT)
      if (extra > 0) s0 = tableSizes(roomW, roomH - extra, maxHand)
    }
    // A tall table's line over the hand holds the player's name plate too: one higher than the line (its words in
    // two lines on a narrow phone) takes its room from the rest, or the hand went under the screen's bottom; so do
    // melds and flowers in two lines (too many for one at 22 dp), or they went over the player's river.
    if (!s0.wide) {
      val counts = pos.players[me].melds.map { meldTiles(it).size }
      val flowers = if (dealHide) 0 else flowersOf(pos.players[me]).size
      val melds = myMeldsHeight(counts, flowers, minOf(roomW, s0.tableW) - 16 - (plates[Side.BOTTOM]?.first ?: 90f), s0.meld, 22f)
      val over = maxOf(0f, maxOf(plates[Side.BOTTOM]?.second ?: 0f, melds) - s0.meldLine)
      if (over > 0) {
        extra += over
        s0 = tableSizes(roomW, roomH - extra, maxHand)
      }
    }
    if (shownAll != null && !s0.wide) {
      fun need(room: Float, maxW: Float, minW: Float): Float {
        var n = 0f
        for (side in listOf(Side.RIGHT, Side.TOP, Side.LEFT)) {
          val o = shownOf(seatAt(side, me))
          val plateH = plates[side]?.second ?: 40f
          val w = fitShown(o.counts, o.flowers, o.concealed, s0.sideW - 9, room - plateH - 12, maxW, minW)
          n = maxOf(n, plateH + 12 + shownHeight(o.counts, o.flowers, o.concealed, s0.sideW - 9, w))
        }
        return n
      }
      val room = tallTopRoom(s0, roomH - extra, SHOWN_RIVER)
      val n = need(room, s0.opp, 20f)
      // No room even at 20 dp: the shown tiles down to 16 dp (no larger than 20), the rivers down to SHOWN_RIVER_MIN.
      val top = if (n <= room) n else tallTopRoom(s0, roomH - extra, SHOWN_RIVER_MIN).let { minOf(it, need(it, 20f, 16f)) }
      if (top > s0.topH) s0 = tableSizes(roomW, roomH - extra, maxHand, top)
    } else if (!s0.wide) {
      // Another's melds and flowers in more than OPP_LINES lines even at 16 dp (many, on a narrow phone): the strip
      // higher, from the rivers' room down to SHOWN_RIVER, or their last line was cut off.
      var need = 0f
      for (side in listOf(Side.RIGHT, Side.TOP, Side.LEFT)) {
        val o = shownOf(seatAt(side, me))
        val mw = fitMelds(o.counts, o.flowers, s0.sideW - 8, OPP_LINES, s0.opp)
        if (meldLines(o.counts, o.flowers, s0.sideW - 8, mw) <= OPP_LINES) continue
        need = maxOf(need, (plates[side]?.second ?: 40f) + 12 + shownHeight(o.counts, o.flowers, 0, s0.sideW - 8, mw))
      }
      val top = minOf(need, tallTopRoom(s0, roomH - extra, SHOWN_RIVER))
      if (top > s0.topH) s0 = tableSizes(roomW, roomH - extra, maxHand, top)
    }
    val s = s0
    dealHand[0] = s.hand
    val meH = s.meH + extra
    fun plateH(side: Side) = plates[side]?.second ?: if (s.wide) 30f else 40f
    // A wide table's corners: the round, the wall and the streak at the top-left; the screen's buttons at the
    // top-right. The player opposite keeps between them; the side players start below them where they reach under
    // the top row.
    var infoSize by remember { mutableStateOf(0f to 0f) }
    var cornerSize by remember { mutableStateOf(0f to 0f) }
    val padL = if (s.wide) infoSize.first + 10 else 0f
    val padR = if (s.wide && corner != null) cornerSize.first + 10 else 0f
    val belowL = if (s.wide) maxOf(0f, infoSize.second + 8 - s.topH) else 0f
    val belowR = if (s.wide && corner != null) maxOf(0f, cornerSize.second + 8 - s.topH) else 0f
    /** The player's own name plate (with their flowers over it, and a battle's clock) lies over the foot of the left
     *  column of a wide table: the column ends above it. The right one ends as high as the plate alone would be. */
    val cornerR = if (s.wide) s.font * density.fontScale * 1.7f + 12 else 0f
    val cornerBase = if (s.wide) maxOf(cornerR, (plates[Side.BOTTOM]?.second ?: 0f) + 8) else 0f
    /** Whole pixels, so that a line of tiles is as wide as counted. */
    fun snap(w: Float) = floor(w * density.density) / density.density

    // The others' tiles. Tall: each one's melds and flowers in OPP_LINES lines of their column, smaller when needed.
    // With everyone's tiles shown: as large as fits each one's room with the concealed tiles too: the player opposite
    // in one line along a wide table's top (the corner kept clear and the name plate off its width), the others in
    // their columns under their name plates.
    fun oppWith(side: Side, seat: Int, cornerH: Float): Float {
      val o = shownOf(seat)
      if (!s.wide) {
        // Smaller also where the lines would not fit under the name plate: a FlowRow leaves out what does not fit
        // (a second meld went missing under a plate of two lines).
        if (shownAll == null) return minOf(fitMelds(o.counts, o.flowers, s.sideW - 8, OPP_LINES, s.opp),
          fitShown(o.counts, o.flowers, 0, s.sideW - 8, s.topH - plateH(side) - 12, s.opp))
        return fitShown(o.counts, o.flowers, o.concealed, s.sideW - 9, s.topH - plateH(side) - 12, s.opp)
      }
      // Wide: the concealed tiles' backs, when they are not shown, take their room first.
      val backs = if (shownAll != null || s.back == 0f) 0 else concealedCount(view, seat)
      if (side == Side.TOP) {
        val room = minOf(roomW, s.tableW) - 12 - padL - padR - (plates[Side.TOP]?.first ?: 150f) - 14 - (if (backs > 0) backs * s.back + 10 else 0f)
        return fitShownRow(o.counts, o.flowers, o.concealed, room, if (shownAll != null) s.opp - 1.5f else s.opp)
      }
      val width = s.sideW - 13
      val backsH = if (backs > 0) runLines(backs, width, s.back) * s.back * TILE_RATIO + 4 else 0f
      val taken = if (side == Side.LEFT) cornerH + belowL else cornerR + belowR
      return fitShown(o.counts, o.flowers, o.concealed, width, roomH - s.topH - meH - taken - plateH(side) - 12 - backsH, s.opp)
    }
    // The expected result by the player's plate, where it leaves everything else its size: a wide table's in the left
    // column's foot, beside the plate where the column is wide enough, else over the plate's corner where the left
    // player's tiles keep their size in what is left to them (the rivers come down beside the column); a tall one's in
    // the melds' line before the plate, where the melds keep their size. Measured unseen where it has no room.
    var valueSize by remember { mutableStateOf(0f to 0f) }
    var myPlate by remember { mutableStateOf(0f to 0f) }
    val wideValueAt: ValueAt? = run {
      val (vw, vh) = valueSize
      val bw = myPlate.first
      if (value == null || !s.wide || vw <= 0f || bw <= 0f) return@run null
      if (bw + 6 + vw <= s.sideW - 12) return@run ValueAt.SIDE
      if (vw > s.sideW - 12) return@run null
      val left = seatAt(Side.LEFT, me)
      val free = roomH - s.topH - meH - cornerBase - belowL - plateH(Side.LEFT) - 12 - vh - 4
      if (free >= 0 && oppWith(Side.LEFT, left, cornerBase + vh + 4) >= oppWith(Side.LEFT, left, cornerBase)) ValueAt.OVER else null
    }
    /** The left column ends above the corner (and the expected result over it). */
    val cornerH = cornerBase + if (wideValueAt == ValueAt.OVER) valueSize.second + 4 else 0f
    fun oppW(side: Side, seat: Int) = oppWith(side, seat, cornerH)

    val lastDiscard = remember(view.events) {
      var seat = -1
      for (e in view.events.asReversed()) {
        if (e.type == "discard") { seat = e.seat; break }
        if (e.type in setOf("chi", "pon", "kan")) break
      }
      seat
    }
    // The player's tiles: the drawn one apart. None drawn (the dealer's first discard, after a call): none apart.
    val all = if (shownAll != null) Tiles.parse(shownAll[me]) else Tiles.parse(pos.hand)
    val reallyDrawn = if (shownAll == null && pos.drawn != null && view.phase == "self" && view.current == me) pos.drawn else null
    val sorted = Tiles.sort(if (reallyDrawn != null) Tiles.without(all, listOf(reallyDrawn)) else all)
    val mine = sorted
    val drawn = reallyDrawn
    // The player's discard: its moves armed as it is composed, before it is laid out (HandMotion).
    val handNow = if (drawn != null) mine + drawn else mine
    SideEffect {
      val count = view.eventCount
      val n = count - motion.seen
      if (motion.seen >= 0 && n in 1..8 && shownAll == null && FxClock.enabled()) {
        val fresh = view.events.takeLast(n)
        val d = fresh.lastOrNull { it.type == "discard" && it.seat == me }
        if (d?.tile != null) motion.arm(d.tile, pos.players[me].discards.size - 1, Timing.ms("hand_move"), Timing.ms("discard_move"))
        // Another's discard, the last that came, the next player's draw (and flowers) after it or nothing: a discard
        // nobody may claim comes with that draw (a review's step over several: the others' before it are in place).
        val k = fresh.indexOfLast { it.type == "discard" }
        val o = if (k >= 0 && fresh.drop(k + 1).all { it.type == "draw" || it.type == "flower" }) fresh[k] else null
        val flies = o != null && o.seat != me && o.seat in pos.players.indices
        if (flies) motion.armIn(o!!.seat, sideOf(o.seat, me), pos.players[o.seat].discards.size - 1, Timing.ms("discard_move"))
        // The player's draw: the tile comes into the hand, once that discard has landed.
        if (drawn != null && fresh.any { it.type == "draw" && it.seat == me })
          motion.armDrawn(HandMotion.tileKeys(handNow).last(), if (flies) Timing.ms("discard_move") else 0L)
      }
      motion.seen = count
      motion.hand = handNow
      motion.drawn = drawn != null
    }
    val discardable = if (interactive && !dealOn && view.myTurn) view.legal.filter { it.type == "discard" }.mapNotNull { it.tile }.toSet() else emptySet()
    val claims = if (interactive && !dealOn && view.myTurn) view.legal.filter { it.type != "discard" } else emptyList()
    /** The player's turn to discard: the hand's frame pulses. */
    val myTurn = interactive && !dealOn && view.myTurn && discardable.isNotEmpty()
    /** The tile the player may claim (a discard, or a kong's added tile), while they may: it stands out. */
    val claimTile = if (claims.any { it.type != "pass" } && view.phase != "self") pos.claim?.tile else null
    val claimSeat = if (claimTile != null && view.phase == "react") pos.claim?.seat ?: -1 else -1
    val myFlowers = if (dealHide) emptyList() else flowersOf(pos.players[me])
    /** The player's melds and flowers, in the line over the hand (a wide table's as wide as the table; a tall one's
     *  beside the name plate): smaller rather than in a second line. */
    val myMeldW = run {
      val counts = pos.players[me].melds.map { meldTiles(it).size }
      if (counts.isEmpty() && myFlowers.isEmpty()) s.meld
      else if (s.wide) fitMelds(counts, myFlowers.size, minOf(roomW, s.tableW) - 28, 1, s.meld, 16f)
      else fitMelds(counts, myFlowers.size, minOf(roomW, s.tableW) - 16 - (plates[Side.BOTTOM]?.first ?: 90f), 1, s.meld, 22f)
    }
    val valueAt: ValueAt? = if (s.wide) wideValueAt else run {
      val vw = valueSize.first
      val bw = plates[Side.BOTTOM]?.first ?: 0f
      if (value == null || vw <= 0f || bw <= 0f) return@run null
      val counts = pos.players[me].melds.map { meldTiles(it).size }
      val room = minOf(roomW, s.tableW) - 16 - bw - 8 - vw
      if (counts.isEmpty() && myFlowers.isEmpty()) { if (room >= 0) ValueAt.SIDE else null }
      else if (room > 0 && fitMelds(counts, myFlowers.size, room, 1, s.meld, 22f) >= s.meld) ValueAt.SIDE else null
    }
    // A call to decide on with no tile of the hand to play (吃, 碰, 胡, 過): a tap on the hand lifts nothing, and what was
    // lifted goes down. Else a lifted tile stays up while the others play (the player may have chosen it already: one
    // tap plays it on their turn), till the hand itself changes (a tile drawn is apart: not a change). A review: each
    // step its own.
    val mustChoose = claims.isNotEmpty() && view.legal.none { it.type == "discard" }
    var selected by remember(if (marks != null) view.eventCount else -1, mine, mustChoose) { mutableStateOf<Int?>(null) }
    if (onPick != null) LaunchedEffect(selected, view.eventCount) { onPick(selected?.let { i -> mine.getOrNull(i) ?: drawn }) }

    /** On the player's plate while playing: how far the hand is from ready, from 3 向聽 (聽牌 at 0). */
    val myStanding = view.shanten?.takeIf { it in 0..3 && !review && !dealOn && shownAll == null && view.phase != "over" }?.let {
      SeatNote(if (it == 0) stringResource(R.string.analysis_ready) else stringResource(R.string.analysis_shanten, it), 0)
    }
    /** 聽牌提示: the tiles a ready hand waits with, blue (on the player's turn, after the tile chosen is discarded, else
     *  the drawn one). */
    val waitAt: Set<Int>? = if (!readyHint || review || shownAll != null) null else if (view.phase == "self" && view.current == me) {
      val i = selected ?: if (drawn != null) mine.size else null
      val x = i?.let { mine.getOrNull(it) ?: drawn }
      x?.let { view.readyTiles[it] }?.let { Tiles.groupIndices(mine, drawn, Tiles.parse(it), i) }
    } else view.waitTiles.takeIf { it.isNotEmpty() }?.let { Tiles.groupIndices(mine, drawn, Tiles.parse(it), null) }
    // A lifted tile goes down at a tap anywhere but the hand (before what was tapped acts); not in a review, whose
    // tiles are tapped to see their figures.
    val drop = rememberUpdatedState { if (selected != null) selected = null }

    @Composable
    fun badge(seat: Int, count: Int?) = SeatBadge(
      Tiles.windMark(winds[seat]), names[seat], scores?.get(seat), setup.dealer == seat, setup.streak, turnSeat == seat, count,
      compact = !s.wide, away = away[seat], font = s.font,
      // A review's words; in play, a declared ready hand (天聽, 地聽, 報聽); the player's own standing.
      note = (if (seat == me) null else notes?.getOrNull(seat))
        ?: pos.players.getOrNull(seat)?.declared?.let { SeatNote(stringResource(when (it) { "diting" -> R.string.call_diting; "baoting" -> R.string.call_baoting; else -> R.string.call_tianting }), 0) }
        ?: if (seat == me) myStanding else null,
      // (The player's plate keeps the room of its longest standing, shown or not: it does not change size.)
      room = if (seat == me && !review) stringResource(R.string.analysis_shanten, 3) else null,
    )

    @Composable
    fun seat(side: Side, mod: Modifier) {
      val seat = seatAt(side, me)
      val p = pos.players[seat]
      val hidden = if (dealHide) 0 else concealedCount(view, seat)
      val theirFlowers = if (dealOn) emptyList() else flowersOf(p)
      val ow = snap(oppW(side, seat))
      val shown = shownAll?.get(seat)?.let { Tiles.sort(Tiles.parse(it)) }
      val count = if (s.back == 0f && shownAll == null) hidden else null
      if (s.wide && side == Side.TOP) {
        Row(mod.testTag("seat_$seat").fxArea { host.area("seat-$seat", it) }.padding(start = (6 + padL).d, end = (6 + padR).d, top = 4.dp, bottom = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
          Box(measured(side)) { badge(seat, count) }
          Row(horizontalArrangement = Arrangement.spacedBy(RACK_GAP_ROW.d), verticalAlignment = Alignment.Bottom) {
            Melds(p.melds, theirFlowers, ow, wrap = false, seat = seat, host = host, coming = coming[seat] ?: -1)
            if (shown != null) Rack(shown, ow, wrap = false)
            else if (s.back > 0) Row { repeat(hidden) { TileView(null, s.back.d) } }
          }
        }
        return
      }
      val end = s.wide && side == Side.RIGHT
      // A test tag: what a seat shows (its name plate's count, its melds) is checked by scripts on a device.
      Column(
        if (s.wide) mod.testTag("seat_$seat").fxArea { host.area("seat-$seat", it) }
          .padding(start = 6.dp, end = 6.dp, top = (4 + if (end) belowR else belowL).d, bottom = (4 + if (end) cornerR else cornerH).d)
        else mod.testTag("seat_$seat").fxArea { host.area("seat-$seat", it) }.padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = if (!s.wide) Alignment.CenterHorizontally else if (end) Alignment.End else Alignment.Start,
        verticalArrangement = if (s.wide) Arrangement.spacedBy(4.dp, Alignment.CenterVertically) else Arrangement.spacedBy(4.dp),
      ) {
        Box(measured(side)) { badge(seat, count) }
        Melds(p.melds, theirFlowers, ow, wrap = true,
          arrangement = if (!s.wide) Arrangement.Center else if (end) Arrangement.End else Arrangement.Start, seat = seat, host = host,
          coming = coming[seat] ?: -1)
        if (shown != null) Rack(shown, ow, wrap = true, end = end)
        else if (s.back > 0) FlowRow(horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) { repeat(hidden) { TileView(null, s.back.d) } }
      }
    }

    // The rivers' places on the table, px (the words over them are set by these).
    var rivers by remember { mutableStateOf(mapOf<Side, Rect>()) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    /** The player's hand, in the root (a press outside it puts a lifted tile down). */
    var handRect by remember { mutableStateOf(Rect.Zero) }
    // The foot of the table and of the player's melds' line (px in the root), the melds' width and the choices' (dp).
    var tableFoot by remember { mutableStateOf(0f) }
    var meldFoot by remember { mutableStateOf(0f) }
    var meldsW by remember { mutableStateOf(0f) }
    var claimW by remember { mutableStateOf(0f) }
    @Composable
    fun river(side: Side, rows: Int, cols: Int) {
      val seat = seatAt(side, me)
      Box(Modifier.onGloballyPositioned {
        // (Not boundsInRoot: an empty river, of no height, has Rect.Zero there, and its words went to the corner.)
        val o = it.positionInRoot()
        val r = Rect(o, Size(it.size.width.toFloat(), it.size.height.toFloat()))
        if (rivers[side] != r) rivers = rivers + (side to r)
        host.area("river-$seat", r)
      }) {
        River(pos.players[seat].discards, s.river, cols, rows, lastDiscard == seat, claim = claimSeat == seat, onLast = { host.river(seat, it) },
          fly = motion, seat = seat, mine = seat == me)
      }
    }

    @Composable
    fun center(mod: Modifier) {
      // (Over the hand while the player's discard flies from it: overWhileFlying, here and on the way to the river.)
      Box(Modifier.overWhileFlying(motion).then(mod).fxArea { host.area("center", it) }, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Box(Modifier.width((s.sideCols * s.river).d), contentAlignment = Alignment.CenterEnd) { river(Side.LEFT, s.sideRows, s.sideCols) }
          // A wide table: nothing between the two rivers across but a gap. A tall one: the box.
          if (s.wide) Column(Modifier.overWhileFlying(motion), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(s.mid.d)) {
            river(Side.TOP, s.acrossRows, s.acrossCols)
            river(Side.BOTTOM, s.acrossRows, s.acrossCols)
          } else Column(Modifier.overWhileFlying(motion), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            river(Side.TOP, s.acrossRows, s.acrossCols)
            Box(
              Modifier.width(s.boxW.d).heightIn(min = s.mid.d).background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
              contentAlignment = Alignment.Center,
            ) {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.round_wind, Tiles.wind(setup.round)) + if (handLabel.isNotEmpty()) " $handLabel" else "",
                  color = c.onFelt, fontWeight = FontWeight.Bold, fontSize = (s.font * 1.08f).sp)
                Text(stringResource(R.string.wall_left, pos.wallLeft), color = c.onFeltDim, fontSize = s.font.sp)
                if (setup.streak > 0) Text("${names[setup.dealer]} " + stringResource(R.string.streak_n, setup.streak), color = c.highlight,
                  fontWeight = FontWeight.Bold, fontSize = (s.font * 0.92f).sp)
                corner?.invoke()
              }
            }
            river(Side.BOTTOM, s.acrossRows, s.acrossCols)
          }
          Box(Modifier.width((s.sideCols * s.river).d), contentAlignment = Alignment.CenterStart) { river(Side.RIGHT, s.sideRows, s.sideCols) }
        }
      }
    }

    val discard = { t: String -> onAct?.invoke(Action("discard", t)); Unit }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Box(Modifier.fillMaxHeight().width(minOf(roomW, s.tableW).d).pointerInput(review) {
        // A press anywhere but on the hand puts a lifted tile down (seen first, taken by nothing: what was pressed acts).
        if (review) return@pointerInput
        awaitPointerEventScope {
          while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val at = e.changes.firstOrNull()?.position ?: continue
            if (e.type == PointerEventType.Press && !handRect.contains(at + origin)) drop.value()
          }
        }
      }.onGloballyPositioned {
        val o = it.positionInRoot()
        if (origin != o) origin = o
        val foot = o.y + it.size.height
        if (tableFoot != foot) tableFoot = foot
        host.area("table", Rect(o, Size(it.size.width.toFloat(), it.size.height.toFloat())))
        motion.table = Rect(o, Size(it.size.width.toFloat(), it.size.height.toFloat()))
      }) {
        if (s.wide) {
          // The player opposite along the whole top (the screen's corner button, if any, before them), the other two
          // beside the rivers.
          Column(Modifier.fillMaxSize()) {
            seat(Side.TOP, Modifier.height(s.topH.d).fillMaxWidth())
            Row(Modifier.overWhileFlying(motion).weight(1f).fillMaxWidth()) {
              seat(Side.LEFT, Modifier.width(s.sideW.d).fillMaxHeight())
              center(Modifier.weight(1f).fillMaxHeight())
              seat(Side.RIGHT, Modifier.width(s.sideW.d).fillMaxHeight())
            }
            // The hand to the left (a discard leaves the rest in place); over it, at its left, the melds, the first at
            // the far left and each after it to its right, so that none moves when another comes, and the flowers.
            Column(Modifier.height(meH.d).fillMaxWidth().tourAnchor("hand").fxArea { host.area("seat-$me", it) }
              .padding(start = 16.dp, end = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
              Box(Modifier.height(s.meldLine.d).fillMaxWidth().onGloballyPositioned { meldFoot = it.positionInRoot().y + it.size.height },
                contentAlignment = Alignment.BottomStart) {
                Box(Modifier.onSizeChanged { meldsW = it.width / density.density }) {
                  Melds(pos.players[me].melds, myFlowers, snap(myMeldW), wrap = false, seat = me, host = host, coming = coming[me] ?: -1)
                }
              }
              if (!dealHide) MyHand(mine, drawn, s.hand, 0, discardable, discard, selected, { selected = it }, marks, tagH, turn = myTurn, motion = motion, pending = pending,
                pick = !mustChoose, ready = if (readyHint && myTurn) view.ready else null, flip = dealing,
                waitAt = waitAt, modifier = Modifier.onGloballyPositioned { handRect = it.boundsInRoot() })
            }
          }
          Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = (meH + 2).d).widthIn(max = (s.sideW - 12).d).then(measured(Side.BOTTOM)),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            status?.invoke()
            Box(Modifier.onSizeChanged { val v = it.width / density.density to it.height / density.density; if (myPlate != v) myPlate = v }) { badge(me, null) }
          }
          if (value != null) {
            val vh = valueSize.second
            val at = if (valueAt == ValueAt.SIDE) Modifier.padding(start = (8 + myPlate.first + 6).d, bottom = (meH + 2 + (myPlate.second - vh) / 2).coerceAtLeast(0f).d)
              else Modifier.padding(start = 8.dp, bottom = (meH + 2 + (plates[Side.BOTTOM]?.second ?: 0f) + 4).d)
            Box(Modifier.align(Alignment.BottomStart).then(at).alpha(if (valueAt != null) 1f else 0f)
              .onSizeChanged { val v = it.width / density.density to it.height / density.density; if (valueSize != v) valueSize = v }) {
              ValueChip(value, s.font, compact = false, if (valueAt != null) Modifier.testTag("my_value") else Modifier)
            }
          }
          // The corners: the round, the wall and the streak; the screen's buttons.
          Column(
            // As wide as its first line needs (a round's label on one line, as the page's): the opposite seat moves aside.
            Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp).widthIn(max = maxOf(s.sideW - 12, s.font * 12).d)
              .onSizeChanged { val v = it.width / density.density to it.height / density.density; if (infoSize != v) infoSize = v }
              .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
          ) {
            val wind = Tiles.wind(setup.round)
            val mark = Tiles.windMark(setup.round)
            Shorten(2) { level ->
              Text(if (level == 0 || handShort.isEmpty()) stringResource(R.string.round_wind, wind) + if (handLabel.isNotEmpty()) " $handLabel" else ""
                else stringResource(R.string.round_short, mark, handShort),
                color = c.onFelt, fontWeight = FontWeight.Bold, fontSize = s.font.sp, lineHeight = (s.font * 1.25f).sp)
            }
            Text(stringResource(R.string.wall_left, pos.wallLeft), color = c.onFeltDim, fontSize = (s.font * 0.92f).sp, lineHeight = (s.font * 1.2f).sp)
            if (setup.streak > 0) Text("${names[setup.dealer]} " + stringResource(R.string.streak_n, setup.streak), color = c.highlight,
              fontWeight = FontWeight.Bold, fontSize = (s.font * 0.85f).sp, lineHeight = (s.font * 1.15f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          if (corner != null) Column(
            Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 4.dp).widthIn(max = (s.sideW - 12).d)
              .onSizeChanged { val v = it.width / density.density to it.height / density.density; if (cornerSize != v) cornerSize = v },
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp),
          ) { corner() }
        } else {
          Column(Modifier.fillMaxSize()) {
            Row(Modifier.height(s.topH.d).fillMaxWidth()) {
              seat(Side.LEFT, Modifier.weight(1f).fillMaxHeight())
              seat(Side.TOP, Modifier.weight(1f).fillMaxHeight())
              seat(Side.RIGHT, Modifier.weight(1f).fillMaxHeight())
            }
            center(Modifier.weight(1f).fillMaxWidth())
            Column(Modifier.height(meH.d).fillMaxWidth().tourAnchor("hand").fxArea { host.area("seat-$me", it) }
              .padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
              horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
              // The melds at the left, the first at the far left (none moves when another comes), the flowers after them;
              // the name plate at the right end.
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                  Melds(pos.players[me].melds, myFlowers, snap(myMeldW), wrap = true, seat = me, host = host, coming = coming[me] ?: -1)
                }
                if (value != null && valueAt == ValueAt.SIDE) Box(Modifier.align(Alignment.CenterVertically)) { ValueChip(value, s.font, compact = true, Modifier.testTag("my_value")) }
                Box(Modifier.align(Alignment.CenterVertically).then(measured(Side.BOTTOM))) { badge(me, null) }
              }
              if (!dealHide) MyHand(mine, drawn, s.hand, s.handRow, discardable, discard, selected, { selected = it }, marks, tagH, turn = myTurn, pending = pending,
                motion = motion, pick = !mustChoose, ready = if (readyHint && myTurn) view.ready else null, flip = dealing,
                waitAt = waitAt, modifier = Modifier.onGloballyPositioned { handRect = it.boundsInRoot() })
            }
          }
          if (status != null) Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = (meH + 4).d)) { status() }
          // (The expected result measured, unseen: whether the melds' line has room for it.)
          if (value != null) Box(Modifier.align(Alignment.TopStart).alpha(0f)
            .onSizeChanged { val v = it.width / density.density to it.height / density.density; if (valueSize != v) valueSize = v }) {
            ValueChip(value, s.font, compact = true)
          }
        }
        if (claims.isNotEmpty() && onAct != null) {
          // Sideways, the choices low, off the next player's melds: level with the foot of the player's melds and
          // flowers, at their right; only when those reach under where the choices would be, over them (as upright).
          // (Not by the hand: its length changes often, the choices' place hardly.)
          val low = s.wide && claimW > 0 && meldFoot > 0 && tableFoot > meldFoot && minOf(roomW, s.tableW) - 8 - claimW >= 16 + meldsW + 8
          Box(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, start = 8.dp,
            bottom = if (low) ((tableFoot - meldFoot) / density.density).d else (meH + 4).d)) {
            Box(Modifier.onSizeChanged { claimW = it.width / density.density }) { ClaimBar(claims, s.hand, onAct, claimTile, choiceMarks) }
          }
        }
        if (onNext != null) NextStep(onNext, s, meH, minOf(roomW, s.tableW), if (s.handRow > 0 && mine.size + (if (drawn != null) 1 else 0) > s.handRow) 2 else 1)
        // A wide table: between the side players, clear of the player's own name plate.
        val stripPad = if (s.wide) (s.sideW + 4).d else 8.dp
        if (strip != null) Column(Modifier.align(Alignment.BottomCenter).padding(start = stripPad, end = stripPad, bottom = (meH + 4).d),
          horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { strip() }
        Callouts(words, s, rivers.mapValues { it.value.translate(-origin.x, -origin.y) }, minOf(roomW, s.tableW), roomH, stay,
          hidden = host.moments.isNotEmpty())
        val dealt = dealing
        if (dealt != null && !dealt.flipping) DealFx(dealt.center, dealt.targets, s.river, setup.dealer) { dealt.flipping = true }
      }
      // The moments themselves: over the whole window (FxOverlay), with what they show from here: a win's hand, the
      // tiles' widths, the rivers' middle (a draw's in a review).
      val fxW = maxOf(40f, s.hand * 1.15f)
      val smallW = maxOf(24f, minOf(50f, s.hand * 0.9f))
      val winHand = host.cur?.let { winHand(it, view, shownAll) } ?: emptyList()
      val rt = rivers[Side.TOP]
      val rb = rivers[Side.BOTTOM]
      SideEffect {
        host.me = me
        host.w = fxW
        host.smallW = smallW
        host.hand = winHand
        if (rt != null && rb != null) host.area("middle", Rect(Offset(rt.center.x, (rt.bottom + rb.top) / 2), Size.Zero))
      }
      overlay?.invoke(this)
    }
  }
}

/** A hand shown: on a rack (a darker tray with a light edge), to tell it from the melds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Rack(tiles: List<String>, w: Float, wrap: Boolean, end: Boolean = false) {
  if (tiles.isEmpty()) return
  val mod = Modifier.background(Color.Black.copy(alpha = 0.24f), RoundedCornerShape(6.dp))
    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(6.dp)).padding(RACK_PAD.d)
  if (wrap) FlowRow(mod, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) { for (t in tiles) TileView(t, w.d) }
  else Row(mod) { for (t in tiles) TileView(t, w.d) }
}

/**
 * A player's name plate: their seat wind (lit for the dealer, with the streak), name, tiles held, score. `note` (a
 * review): a few words in place of the count; it goes to a second line where the plate has no room for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeatBadge(
  wind: String, name: String, score: Double?, dealer: Boolean, streak: Int, turn: Boolean, count: Int?, compact: Boolean = false,
  away: Boolean = false, font: Float = 16f, note: SeatNote? = null, room: String? = null,
) {
  val c = LocalTable.current
  val f = (if (compact) font * 0.86f else font).sp
  Row(
    Modifier.alpha(if (away) 0.6f else 1f)
      .background(Color.Black.copy(alpha = if (turn) 0.28f else 0.16f), RoundedCornerShape(16.dp))
      .border(2.dp, if (turn) c.highlight else Color.Transparent, RoundedCornerShape(16.dp))
      .padding(start = 2.dp, end = if (compact) 6.dp else 8.dp, top = 2.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
  ) {
    val density = LocalDensity.current
    val d = with(density) { (f.toDp() * 1.7f) }
    // The wind centered in its disc by its ink: a CJK glyph sits low in its line (the line keeps the fallback font's
    // tall ascent, and the style's line height), so the line's middle was below the disc's.
    val ink = remember(wind, f, density) {
      android.graphics.Rect().also { r ->
        android.graphics.Paint().apply { textSize = with(density) { f.toPx() }; typeface = android.graphics.Typeface.DEFAULT_BOLD }
          .getTextBounds(wind, 0, wind.length, r)
      }
    }
    Box(Modifier.size(d).background(if (dealer) c.highlight else c.onFelt, CircleShape), contentAlignment = Alignment.Center) {
      Text(wind, Modifier.layout { m, cs ->
        val p = m.measure(cs)
        val base = p[FirstBaseline]
        val dy = if (base == AlignmentLine.Unspecified || ink.isEmpty) 0 else (p.height / 2f - (base + ink.exactCenterY())).roundToInt()
        layout(p.width, p.height) { p.place(0, dy) }
      }, color = if (dealer) Color(0xFF3A2A00) else c.felt, fontWeight = FontWeight.Bold, fontSize = f)
    }
    val scoreText: @Composable () -> Unit = {
      if (score != null) Text(signed(score), color = if (score > 0) c.good else if (score < 0) c.bad else c.onFelt, fontWeight = FontWeight.Bold,
        fontSize = f, maxLines = 1)
    }
    // The player's own plate on a wide table: the score by the name, the standing (the room of its longest) on a line of
    // its own (beside each other they did not fit the side column: the score was cut off).
    val scoreByName = room != null && !compact
    // The name whole (cut only where it alone is too long), the dealer's 莊連N after it or under it.
    val nameText = @Composable {
      Text(name, color = c.onFelt, fontWeight = FontWeight.Medium, fontSize = f, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val dealText: (@Composable () -> Unit)? = if (!dealer) null else ({
      Text(stringResource(R.string.dealer_mark) + if (streak > 0) stringResource(R.string.streak_n, streak) else "",
        color = c.highlight, fontWeight = FontWeight.Bold, fontSize = f, maxLines = 1)
    })
    val figures: @Composable RowScope.() -> Unit = {
      if (room != null) Box(
        Modifier.background(if (note == null) Color.Transparent else if (note.level > 0) Marks.danger(note.level) else Color.Black.copy(alpha = 0.3f),
          RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
      ) {
        // The room's words, unseen, keep the width; the note over them.
        Text(room, Modifier.alpha(0f), fontWeight = FontWeight.Bold, fontSize = f, maxLines = 1)
        if (note != null) Text(note.text, color = if (note.level > 0) Marks.on(note.level) else c.onFelt, fontWeight = FontWeight.Bold, fontSize = f,
          maxLines = 1)
      } else if (note != null) Box(Modifier.weight(1f, fill = false)) {
        var shown by remember(note.opp) { mutableStateOf(false) }
        Shorten(if (note.short != null) 2 else 1) { level ->
          Text(
            if (level == 0) note.text else note.short!!, color = if (note.level > 0) Marks.on(note.level) else c.onFelt, fontWeight = FontWeight.Bold,
            fontSize = f, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (note.level > 0) Marks.danger(note.level) else Color.Black.copy(alpha = 0.3f))
              .let { if (note.opp != null) it.clickable(role = Role.Button) { shown = true } else it }
              .padding(horizontal = 6.dp),
          )
        }
        // An opponent's figures, by the note (a tap outside closes them).
        if (shown && note.opp != null) OppCard(name, note.opp) { shown = false }
      } else if (count != null) Box(Modifier.weight(1f, fill = false)) {
        // The count, the number alone where the words don't fit (an English "16 tiles" in a side column): the score
        // after it stays whole.
        Shorten(2) { level ->
          Text(if (level == 0) stringResource(R.string.hand_cards, count) else "$count", color = c.onFeltDim, fontSize = f, maxLines = 1)
        }
      }
      if (!scoreByName) scoreText()
    }
    val space = if (compact) 4.dp else 6.dp
    val gap = Arrangement.spacedBy(space)
    // A third of an upright phone's width: the name over the figures (a name of a battle's player has room then).
    if (compact && (count != null || note != null || room != null)) NameOverFigures(space, nameText, dealText, Modifier.weight(1f, fill = false)) {
      Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { figures() }
    } else {
      // One line, or what does not fit after the name under it (the side column too narrow for all: a dealer's 莊連N, a
      // large font): the name stays whole.
      FlowRow(Modifier.weight(1f, fill = false), horizontalArrangement = gap, itemVerticalAlignment = Alignment.CenterVertically) {
        nameText()
        dealText?.invoke()
        if (scoreByName) scoreText()
        if (note != null || room != null || count != null || (score != null && !scoreByName)) Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { figures() }
      }
    }
  }
}

/**
 * A narrow plate's words (a third of an upright phone): the name on the first line, the figures on the second; the
 * dealer's 莊連N after the name where it fits there, else first on the second line (the name had been cut short for
 * it), and the figures after it there where they fit, else on a third line. The web page's SeatBadge flows the same way.
 */
@Composable
private fun NameOverFigures(gap: Dp, name: @Composable () -> Unit, deal: (@Composable () -> Unit)?, modifier: Modifier = Modifier,
  figures: @Composable () -> Unit) {
  Layout(content = { name(); deal?.invoke(); figures() }, modifier = modifier) { ms, cs ->
    val g = gap.roundToPx()
    val loose = cs.copy(minWidth = 0, minHeight = 0)
    val max = cs.maxWidth
    val d = if (deal != null) ms[1].measure(loose) else null
    val dealUp = d != null && ms[0].maxIntrinsicWidth(Constraints.Infinity) + g + d.width <= max
    val n = ms[0].measure(if (dealUp) loose.copy(maxWidth = (max - g - d!!.width).coerceAtLeast(0)) else loose)
    val f = ms.last().measure(loose)
    // The lines, each its pieces from the left.
    val lines = buildList {
      add(if (dealUp) listOf(n, d!!) else listOf(n))
      if (d != null && !dealUp) {
        if (d.width + g + f.width <= max) add(listOf(d, f)) else { add(listOf(d)); add(listOf(f)) }
      } else add(listOf(f))
    }
    val widths = lines.map { l -> l.sumOf { it.width } + g * (l.size - 1) }
    val heights = lines.map { l -> l.maxOf { it.height } }
    layout((widths.maxOrNull() ?: 0).coerceIn(cs.minWidth, max), heights.sum().coerceAtLeast(cs.minHeight)) {
      var y = 0
      lines.forEachIndexed { i, l ->
        var x = 0
        for (p in l) { p.place(x, y + (heights[i] - p.height) / 2); x += p.width + g }
        y += heights[i]
      }
    }
  }
}

/** Below its anchor (above it where there is more room there), inside the window by 8 dp. */
private class UnderAnchor(private val gap: Int, private val margin: Int) : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin, (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin))
    val below = anchorBounds.bottom + gap
    val above = anchorBounds.top - gap - popupContentSize.height
    val y = if (below + popupContentSize.height <= windowSize.height - margin || anchorBounds.top < windowSize.height - anchorBounds.bottom) below else above
    // (Neither fits whole: kept inside the window, over the plate.)
    return IntOffset(x, y.coerceAtMost(windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin))
  }
}

/**
 * An opponent's figures, a tap on its plate's 聽 N% (the hints): how likely it is ready and one away, and the discards
 * likeliest to deal in to it. The web page's SeatBadge has the same card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OppCard(name: String, o: Opponent, onClose: () -> Unit) {
  val d = LocalDensity.current
  Popup(UnderAnchor(with(d) { 6.dp.roundToPx() }, with(d) { 8.dp.roundToPx() }), onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
    val cs = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    Surface(Modifier.widthIn(max = 320.dp).testTag("opp_card"), shape = RoundedCornerShape(12.dp), color = cs.surfaceContainerHigh, shadowElevation = 6.dp) {
      Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.opp_ready, pct(o.ready.getOrElse(0) { 0.0 })) + Words.sep + stringResource(R.string.opp_one_away, pct(o.ready.getOrElse(1) { 0.0 })),
          style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.opp_detail_danger), style = small, color = cs.onSurfaceVariant)
        val dangers = dangerTiles(o)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
          for (k in dangers) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TileView(Tiles.KINDS[k], 30.dp, danger = dangerLevel(o.danger[k]))
            Text(pct(o.danger[k]), style = small.copy(fontFeatureSettings = "tnum"))
          }
          if (dangers.isEmpty()) Text(stringResource(R.string.opp_no_danger), style = small, color = cs.onSurfaceVariant)
        }
        Text(stringResource(R.string.opp_detail_source), style = small, color = cs.onSurfaceVariant)
      }
    }
  }
}

/** Where the player's expected result goes by their plate (TableView's `value`). */
private enum class ValueAt { SIDE, OVER }

/** The player's expected result (期望 +0.31): a pill of its own beside their plate, as tall as it, in its tone. */
@Composable
private fun ValueChip(value: Double, font: Float, compact: Boolean, modifier: Modifier = Modifier) {
  val c = LocalTable.current
  val f = (if (compact) font * 0.86f else font).sp
  val h = with(LocalDensity.current) { f.toDp() * 1.7f } + 8.dp
  Row(
    modifier.heightIn(min = h).background(Color.Black.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
      .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(stringResource(R.string.fig_value), color = c.onFeltDim, fontSize = f, maxLines = 1)
    // (NaN: the figure on its way, its grey shape.)
    if (value.isNaN()) Box(Modifier.size(with(LocalDensity.current) { f.toDp() } * 2.8f, with(LocalDensity.current) { f.toDp() } * 0.9f)
      .alpha(0.5f + 0.5f * pulse(1600)).background(SKELETON, RoundedCornerShape(4.dp)))
    else Text(pts(value), color = if (value > 0.005) c.good else if (value < -0.005) c.bad else c.onFelt, fontWeight = FontWeight.Bold, fontSize = f,
      maxLines = 1, style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
  }
}

/** "+3", "−1.5", "±0". */
fun signed(x: Double): String {
  val a = kotlin.math.abs(x)
  val n = if (a == a.roundToInt().toDouble()) a.roundToInt().toString() else "%.1f".format(a)
  return (if (x > 0) "+" else if (x < 0) "−" else "±") + n
}

/**
 * A player's melds, each a group of tiles, and their flowers after them (each on its own, so a line breaks between).
 * A chow's taken tile lies in the middle (meldTiles), tinted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Melds(
  melds: List<Meld>, flowers: List<String>, w: Float, wrap: Boolean, arrangement: Arrangement.Horizontal = Arrangement.Start,
  seat: Int = -1, host: FxHost? = null, coming: Int = -1,
) {
  if (melds.isEmpty() && flowers.isEmpty()) return
  val g = meldGaps(w)
  // `host`: where each meld and flower is, for a moment to land at or start from; the meld a call's moment is bringing
  // is out of sight till then, and from the frame before it starts (`coming`, comingMelds).
  val hide = (host?.hideMeld(seat) ?: -1).let { if (it >= 0) it else coming }
  val content: @Composable () -> Unit = {
    @Composable
    fun meld(i: Int, m: Meld) = Row(
      if (host == null) Modifier else Modifier.fxAnchor { host.meld(seat, i, it) }.alpha(if (i == hide) 0f else 1f),
    ) { for ((k, t) in meldTiles(m).withIndex()) TileView(t, w.d, mark = m.type == "chi" && m.called != null && k == 1) }
    @Composable
    fun flower(f: String) = TileView(f, (w * FLOWER_SCALE).d, modifier = if (host == null) Modifier else Modifier.fxAnchor { host.flower(f, it) })
    for ((i, m) in melds.withIndex()) meld(i, m)
    for (f in flowers) flower(f)
  }
  if (wrap) FlowRow(horizontalArrangement = Arrangement.spacedBy(g.col.d, if (arrangement == Arrangement.End) Alignment.End else if (arrangement == Arrangement.Center) Alignment.CenterHorizontally else Alignment.Start),
    verticalArrangement = Arrangement.spacedBy(g.row.d), itemVerticalAlignment = Alignment.Bottom) { content() }
  else Row(horizontalArrangement = Arrangement.spacedBy(g.col.d), verticalAlignment = Alignment.Bottom) { content() }
}

/**
 * One player's discards in order, `cols` a row; the tiles taken by a meld faded, the last discard lit. `claim`: the
 * player may claim the last discard: it stands out, larger and pulsing. `fly`: the table's moves; a new discard comes
 * from the hand (`mine`: discardFly) or in from the table's edge (`seat`'s: discardIn).
 */
@Composable
fun River(
  discards: List<Discard>, w: Float, cols: Int, rows: Int, last: Boolean, claim: Boolean = false, onLast: ((FxAnchor) -> Unit)? = null,
  fly: HandMotion? = null, seat: Int = -1, mine: Boolean = false,
) {
  val fit = if (rows > 0 && discards.size > cols * rows) ceil(discards.size.toFloat() / rows).toInt() else cols
  val tw = w * cols / fit
  val pulse = if (claim) pulse(900) else 0f
  Column(Modifier.width((cols * w).d)) {
    for (r in discards.indices step fit) {
      val end = r + fit >= discards.size
      Row(if (claim && end) Modifier.zIndex(1f) else Modifier) {
        for (i in r until minOf(r + fit, discards.size)) {
          val d = discards[i]
          val out = claim && i == discards.size - 1 && !d.called
          // The last tile's place: a call's or a win's moment lifts it off from there.
          val anchor = if (onLast != null && i == discards.size - 1) Modifier.fxAnchor(onLast) else Modifier
          Box(anchor.then(if (out) Modifier.zIndex(1f).scale(1.25f + 0.15f * pulse) else Modifier)
            .then(if (fly != null && i == discards.size - 1) Modifier.zIndex(2f).then(if (mine) Modifier.discardFly(fly, i) else Modifier.discardIn(fly, seat, i))
              else Modifier)) {
            TileView(d.tile, tw.d, dim = d.called, glow = (last || out) && i == discards.size - 1 && !d.called)
          }
        }
      }
    }
  }
}

/**
 * A hand's end: whether the table as it ended still waits for the player before the result. The result's moment stops
 * for the player's tap itself (FxHost): when it was tapped on, the result comes at its end; without one (the words
 * alone) the table waits for a tap (TapOn). Not for a result come back to. The second value lets go (TapOn's tap). The
 * web page's Play.svelte (held).
 */
@Composable
fun endHold(over: Boolean): Pair<Boolean, () -> Unit> {
  var held by remember { mutableStateOf(false) }
  val inPlay = remember { booleanArrayOf(false) }
  LaunchedEffect(over) {
    if (!over) {
      inPlay[0] = true
      held = false
    } else if (inPlay[0]) {
      held = true
      inPlay[0] = false
      FxClock.done()
      if (FxClock.tapped) held = false
    }
  }
  return (over && (held || inPlay[0])) to { held = false }
}

/**
 * Over the table at a hand's end: it waits for the player's tap (anywhere over it), then `onNext` (the result). A touch
 * that began over a moment only let the moment go (FxHost: a result's moment stopped for it goes on to its end, and the
 * result comes after it). Its words, ≫ 按一下以繼續, show over the moments (FxOverlay): from the start of a result's
 * moment, or at once without one. The web page's TapOn.svelte.
 */
@Composable
fun BoxScope.TapOn(onNext: () -> Unit) {
  val go by rememberUpdatedState(onNext)
  DisposableEffect(Unit) {
    FxStage.tapOn = true
    onDispose { FxStage.tapOn = false }
  }
  Box(Modifier.matchParentSize().testTag("result_skip").pointerInput(Unit) { detectTapGestures { if (!FxClock.downOverFx) go() } })
}

/** A value going between 0 and 1 and back, `ms` each way: what pulses (the turn's frame, a tile to claim). */
@Composable
fun pulse(ms: Int): Float {
  val t = rememberInfiniteTransition(label = "pulse")
  val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
  return v
}

/**
 * The player's concealed tiles, the drawn one apart at the end. On the player's turn a tap lifts a tile and a second
 * tap discards it; a tile dragged upwards is discarded when let go. `perRow`: tiles a row (an upright phone), 0 one row.
 * `turn`: the player's turn to discard: a frame of the turn's color pulses around the tiles, and a tile that may not
 * be discarded then (after declaring a ready hand) is darker. `motion`: after the player's discard the tiles move to
 * their places (HandMotion).
 */
@Composable
fun MyHand(
  tiles: List<String>, drawn: String?, w: Float, perRow: Int, discardable: Set<String>, onDiscard: (String) -> Unit,
  selected: Int?, onSelect: (Int?) -> Unit, marks: Map<String, TileMark>? = null, tagH: Float = 0f, turn: Boolean = false,
  motion: HandMotion? = null, pick: Boolean = true, ready: Map<String, List<String>>? = null, flip: Dealing? = null,
  waitAt: Set<Int>? = null, modifier: Modifier = Modifier,
  /** The tags on their way (the analysis not there yet): a grey shape over each tile that may be discarded. */
  pending: Boolean = false,
) {
  val highlight = LocalTable.current.highlight
  val glow = if (turn) pulse(1100) else 0f
  val all = if (drawn != null) tiles + drawn else tiles
  // Each tile by its key (the n-th of a kind), so that it is the same tile wherever it moves.
  val keys = HandMotion.tileKeys(all)
  val h = w * TILE_RATIO
  val size = if (perRow > 0) perRow else maxOf(1, tiles.size)
  val rows = ArrayList<MutableList<Int>>()
  for (i in tiles.indices step size) rows += (i until minOf(i + size, tiles.size)).toMutableList()
  if (rows.isEmpty()) rows += mutableListOf<Int>()
  // A seventeenth tile not drawn (the dealer's at the deal) ends the second row, as a drawn tile would: two rows.
  if (perRow > 0 && drawn == null && rows.size > 1 && rows.last().size == 1) rows[rows.size - 2] += rows.removeAt(rows.size - 1)
  if (drawn != null) rows.last() += tiles.size
  var dragging by remember { mutableIntStateOf(-1) }
  var dy by remember { mutableStateOf(0f) }
  val density = LocalDensity.current
  // How far a chosen tile rises. With tags (a review, the analysis shown) a little (MARKED_LIFT), its tag with it as far
  // as it may go over the row above's foot; its face and frame say it is chosen too.
  val up = if (marks != null) h * MARKED_LIFT else h * LIFT
  val flipMs = remember { Timing.ms("deal_flip") }
  fun tap(i: Int) {
    val t = all[i]
    if (t !in discardable) return onSelect(if (selected == i || !pick) null else i)
    if (selected == i) {
      onSelect(null)
      onDiscard(t)
    } else onSelect(i)
  }
  Column(
    modifier.padding(top = maxOf(h * LIFT, tagH).d).let { if (perRow > 0) it.width(((perRow + DRAWN_GAP + 1) * w).d) else it }
      // The turn's frame, around the tiles and the tags over them (not the room over them for a lifted tile): a line and
      // its glow, under the tiles and their tags (a lifted one is not crossed by it).
      .then(if (turn) Modifier.drawBehind {
        val out = 5.dp.toPx()
        val tags = tagH.dp.toPx()
        val corner = CornerRadius(10.dp.toPx())
        val at = Offset(-out, -out - tags)
        val area = this.size
        val box = Size(area.width + 2 * out, area.height + 2 * out + tags)
        for (k in 3 downTo 1) drawRoundRect(highlight.copy(alpha = 0.10f * glow), at - Offset(k * 2.dp.toPx(), k * 2.dp.toPx()),
          Size(box.width + k * 4.dp.toPx(), box.height + k * 4.dp.toPx()), CornerRadius(10.dp.toPx() + k * 2.dp.toPx()), Stroke(4.dp.toPx()))
        drawRoundRect(highlight.copy(alpha = 0.45f + 0.55f * glow), at, box, corner, Stroke(3.dp.toPx()))
      } else Modifier),
    verticalArrangement = Arrangement.spacedBy(maxOf(h * ROW_GAP, tagH).d),
  ) {
    for (row in rows) {
      Row(Modifier.height(h.d), verticalAlignment = Alignment.Bottom) {
        for (i in row) androidx.compose.runtime.key(keys[i]) {
          val isDrawn = drawn != null && i == tiles.size
          val lift = when {
            dragging == i -> dy
            selected == i -> -up * density.density
            else -> 0f
          }
          val mark = marks?.get(all[i])
          // The touch target is the tile's own place, whether the tile is lifted or not, and while it is lifted the
          // room over it too: the second tap lands where the first did (a thumb at the tile's foot) or on the tile
          // where it now is. (A target that rose with the tile left its foot dead: "sometimes it will not discard".)
          val reach = if (selected == i) up else 0f
          Box(
            Modifier
              .testTag(if (isDrawn) "hand_drawn" else "hand_$i")
              .semantics(mergeDescendants = true) {}
              .padding(start = if (isDrawn && row.size > 1) (w * DRAWN_GAP).d else 0.dp)
              .handSlide(motion, keys[i])
              // After the deal: face up one after another from the left (DealFx).
              .then(if (flip?.flipping == true) Modifier.graphicsLayer {
                rotationY = flipTurn(i, all.size, flip.flipT, flipMs)
                cameraDistance = 12f * density.density
              } else Modifier)
              .then(if (isDrawn) Modifier.drawnIn(motion, keys[i], h * density.density) else Modifier)
              .layout { m, c ->
                val up = reach.d.roundToPx()
                val p = m.measure(if (c.hasBoundedHeight) c.copy(maxHeight = c.maxHeight + up) else c)
                layout(p.width, p.height - up) { p.place(0, -up) }
              }
              .pointerInput(all[i], i, selected, discardable) { detectTapGestures { tap(i) } }
              // Only a tile that may be discarded follows the finger up (a review's tiles are only tapped).
              .pointerInput(all[i], i, discardable) {
                detectVerticalDragGestures(
                  onDragStart = { dragging = i; dy = 0f },
                  onDragEnd = {
                    val t = all.getOrNull(i)
                    if (dy < -h * 0.45f * density.density && t != null && t in discardable) {
                      onSelect(null)
                      onDiscard(t)
                    }
                    dragging = -1
                    dy = 0f
                  },
                  onDragCancel = { dragging = -1; dy = 0f },
                ) { _, d -> if (all.getOrNull(i) in discardable) dy = minOf(0f, dy + d) }
              }
              .padding(top = reach.d),
          ) {
            // On the player's turn, a tile that may not be discarded (a declared ready hand keeps its tiles): darker.
            val held = turn && all[i] !in discardable
            val dim = if (!held) Modifier else Modifier
              .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
              .drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = 0.42f), blendMode = BlendMode.SrcAtop)
              }
            // 聽牌提示: a small dot over a discard that leaves the hand ready, above the turn's frame.
            val dotDp = maxOf(5f, w * 0.15f)
            val dot = if (ready?.containsKey(all[i]) != true) Modifier else Modifier.drawBehind {
              val d = dotDp.dp.toPx()
              val at = Offset(center.x, -10.dp.toPx() - d / 2)
              drawCircle(Color.Black.copy(alpha = 0.45f), d / 2 + 1.5.dp.toPx(), at)
              drawCircle(Color(0xFF8FE3FF), d / 2, at)
            }
            Box(Modifier.offset { IntOffset(0, lift.roundToInt()) }.then(dot).then(dim)) {
              TileView(all[i], w.d, selected = selected == i, glow = mark?.played == true, danger = mark?.danger ?: 0, wait = waitAt?.contains(i) == true)
              if (mark != null && mark.tag.isNotEmpty()) TileTag(mark, w, if (tagH > 0) tagH else TAG_H, Modifier.align(Alignment.TopCenter))
              else if (pending && tagH > 0 && all[i] in discardable) TagSkeleton(w, tagH, Modifier.align(Alignment.TopCenter))
            }
          }
        }
      }
    }
  }
}

/**
 * A tile's tag at a decision (a review): what discarding it costs against the best, over the tile (outside its own
 * room: the hand keeps `tagH` over each row). The tile played has a lit edge.
 */
@Composable
fun TileTag(mark: TileMark, w: Float, tagH: Float, modifier: Modifier = Modifier) {
  val c = LocalTable.current
  val bg = toneColor(mark.tone)
  val fg = if (mark.tone == TileMark.Tone.G1 || mark.tone == TileMark.Tone.G2) Marks.on(1) else Color.White
  // In dp, whatever the font scale: the tag has a fixed room.
  val size = with(LocalDensity.current) { (w * 0.27f).coerceIn(10f, 13f).dp.toSp() }
  Box(
    modifier
      .layout { m, _ ->
        val p = m.measure(Constraints())
        layout(0, 0) { p.place(-p.width / 2, -p.height - 1.dp.roundToPx()) }
      }
      .height((tagH - 2).d).background(bg, RoundedCornerShape(5.dp))
      .let { if (mark.played) it.border(2.dp, c.highlight, RoundedCornerShape(5.dp)) else it }.padding(horizontal = 3.dp),
    contentAlignment = Alignment.Center,
  ) {
    // The words' own line may be higher than the tag (Chinese characters' is): it is centered over the tag's edges
    // rather than held to the tag's height, where a line that does not fit is not drawn at all.
    Text(mark.tag, Modifier.wrapContentHeight(Alignment.CenterVertically, unbounded = true), color = fg, fontSize = size, lineHeight = size,
      fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
  }
}

/** A tag on its way (the analysis not there yet): its grey shape, pulsing. */
@Composable
private fun TagSkeleton(w: Float, tagH: Float, modifier: Modifier = Modifier) {
  val a = 0.5f + 0.5f * pulse(1600)
  Box(
    modifier
      .layout { m, _ ->
        val p = m.measure(Constraints())
        layout(0, 0) { p.place(-p.width / 2, -p.height - 1.dp.roundToPx()) }
      }
      .size((w * 0.7f).d, (tagH - 2).d).alpha(a).background(SKELETON, RoundedCornerShape(5.dp)),
  )
}

/** A skeleton's grey on the felt or a page. */
private val SKELETON = Color(0x738C9691)

/** A tag's color by its tone: the best, the grades of a loss, or plain. */
fun toneColor(tone: TileMark.Tone): Color = when (tone) {
  TileMark.Tone.BEST -> Marks.found
  TileMark.Tone.G1 -> Marks.danger1
  TileMark.Tone.G2 -> Marks.danger2
  TileMark.Tone.G3 -> Marks.danger3
  TileMark.Tone.PLAIN -> Color(0xC214201A)
}

/**
 * The others' next step (practice at the manual pace): the turn's color, large enough for a thumb, always in the same
 * place. A tall table: at the end of the first of the hand's `rows` (no tile's place, so that a tap too many discards
 * nothing); a wide one: over the hand's right end. `w`: the table's width, dp.
 */
@Composable
private fun BoxScope.NextStep(onNext: () -> Unit, s: TableSizes, meH: Float, w: Float, rows: Int) {
  val c = LocalTable.current
  val ink = Color(0xFF3A2A00)
  if (s.wide) Surface(
    onClick = onNext, shape = RoundedCornerShape(26.dp), color = c.highlight, contentColor = ink, shadowElevation = 4.dp,
    // In the corner, under the right player's column: further in, the rivers of a long hand come down to this height.
    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = (meH + 4).d).testTag("next_step"),
  ) {
    Row(Modifier.heightIn(min = 52.dp).padding(start = 12.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Icon(Icons.Filled.PlayArrow, null, Modifier.size(28.dp))
      Text(stringResource(R.string.manual_next), fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
    }
  } else {
    val block = (HAND_ROW + DRAWN_GAP + 1) * s.hand
    Surface(
      onClick = onNext, shape = RoundedCornerShape(12.dp), color = c.highlight, contentColor = ink, shadowElevation = 4.dp,
      modifier = Modifier.align(Alignment.BottomEnd)
        .padding(end = (maxOf(0f, (w - block) / 2) + 1).d, bottom = (6 + (rows - 1) * s.hand * TILE_RATIO * (1 + ROW_GAP)).d)
        .size((s.hand * (1 + DRAWN_GAP) - 4).d, (s.hand * TILE_RATIO).d).testTag("next_step"),
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(26.dp))
        val small = with(LocalDensity.current) { 13.dp.toSp() }
        Text(stringResource(R.string.manual_next), fontWeight = FontWeight.Bold, fontSize = small, lineHeight = small, maxLines = 1, softWrap = false)
      }
    }
  }
}

private val ORDER = listOf("ron", "tsumo", "declare", "kan", "ankan", "kakan", "pon", "chi", "pass")

fun actionLabel(type: String) = when (type) {
  "ron" -> R.string.act_ron
  "tsumo" -> R.string.act_tsumo
  "kan" -> R.string.act_kan
  "ankan" -> R.string.act_ankan
  "kakan" -> R.string.act_kakan
  "pon" -> R.string.act_pon
  "chi" -> R.string.act_chi
  "discard" -> R.string.act_discard
  "declare" -> R.string.act_declare
  else -> R.string.act_pass
}

/**
 * The player's choices other than discarding: win, kongs, pung, each way of a chow (its three tiles), and pass. The
 * tiles are large (`w` is the hand's tile), and `claimed`, the discard (or added tile) being claimed, is lit in each.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClaimBar(actions: List<Action>, w: Float, onAct: (Action) -> Unit, claimed: String? = null, marks: ((Action) -> ChoiceMark?)? = null) {
  val shown = actions.filter { it.type != "discard" }.sortedBy { ORDER.indexOf(it.type) }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val c = LocalTable.current
    for (a in shown) {
      val win = a.type == "ron" || a.type == "tsumo"
      val pass = a.type == "pass"
      val m = marks?.invoke(a)
      Surface(
        onClick = { onAct(a) }, shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp,
        // The one made there (a step gone back to): its edge lit.
        modifier = Modifier.testTag("claim_${a.type}").let { if (m?.played == true) it.border(3.dp, c.highlight, RoundedCornerShape(24.dp)) else it },
        color = when { win -> Color(0xFFD84315); pass -> MaterialTheme.colorScheme.surfaceContainerHighest; else -> c.action },
        contentColor = when { win -> Color.White; pass -> MaterialTheme.colorScheme.onSurface; else -> c.onAction },
      ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(actionLabel(a.type)), fontWeight = if (pass) FontWeight.Medium else FontWeight.Bold, fontSize = if (win) 20.sp else 18.sp)
          val ts = if (a.type == "chi") a.tiles ?: emptyList() else if (a.tile != null && a.type != "ron") listOf(a.tile) else emptyList()
          val at = if (claimed != null) ts.indexOf(claimed) else -1
          if (ts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            for ((k, t) in ts.withIndex()) TileView(t, (w * 0.8f).d, glow = k == at)
          }
          // What the choice costs against the best (the analysis shown), in its grade's tone.
          if (m != null) Text(m.tag, Modifier.background(toneColor(m.tone), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
            color = if (m.tone == TileMark.Tone.G1 || m.tone == TileMark.Tone.G2) Marks.on(1) else Color.White, fontWeight = FontWeight.Bold,
            fontSize = 15.sp, maxLines = 1)
        }
      }
    }
  }
}

/** The plaques the words are set on: the face's color, the word's, the lip's under it, the fine line's inside the edge. */
internal enum class CalloutKind(val face: Color, val ink: Color, val back: Color, val line: Color) {
  /** A call, a declaration: gold. */
  CALL(Color(0xFFF6B93B), Color(0xFF3B2200), Color(0xFFB9780F), Color(0x6B3B2200)),
  /** A win: vermilion, and larger. */
  WIN(Color(0xFFD5382D), Color(0xFFFFF6DC), Color(0xFF8C1C15), Color(0xE6FFD678)),
  /** The one who dealt in: dark. */
  DEALIN(Color(0xFF2E383E), Color(0xFFFFB3A7), Color(0xFF12171A), Color(0x73FFB3A7)),
  /** A flower: small, jade. */
  FLOWER(Color(0xFF2B5B4C), Color(0xFFD8F3E5), Color(0xFF17382E), Color(0x73D8F3E5)),
  /** A drawn hand: slate. */
  DRAWN(Color(0xFF5B7684), Color.White, Color(0xFF384B55), Color(0x73FFFFFF)),
}

/** Where a plaque stands: over the player's river, over their own tiles (who dealt in, 補花), or in the middle. */
private enum class CalloutAt { RIVER, OWN, CENTER }

private class Callout(val id: Int, val side: Side, val text: String, val kind: CalloutKind, val ms: Long, val who: String = "") {
  val at get() = when (kind) {
    CalloutKind.DRAWN -> CalloutAt.CENTER
    CalloutKind.DEALIN, CalloutKind.FLOWER -> CalloutAt.OWN
    else -> CalloutAt.RIVER
  }
}

/** The words' coming in and their fading, ms. */
private const val CALLOUT_IN = 320
private const val CALLOUT_OUT = 160

private val WORDS = mapOf(
  "chi" to R.string.act_chi, "pon" to R.string.act_pon, "kan" to R.string.act_kan, "ankan" to R.string.act_ankan, "kakan" to R.string.act_kakan,
  "tsumo" to R.string.act_tsumo, "ron" to R.string.act_ron, "flower" to R.string.call_flower, "flower_win" to R.string.call_flower_win,
  "exhausted" to R.string.call_exhausted, "declare:tianting" to R.string.call_tianting, "declare:diting" to R.string.call_diting, "declare:baoting" to R.string.call_baoting,
)
private val ROB_WORDS = mapOf("chi" to R.string.rob_chi, "pon" to R.string.rob_pon, "kan" to R.string.rob_kan, "ron" to R.string.rob_ron)
private val CALLS = setOf("chi", "pon", "kan", "ron")
private val RESULTS = setOf("ron", "tsumo", "flower_win", "exhausted")

/** Seat → the meld that a call among `fresh` made, as TableEvents gives its moment (the meld it brings). */
private fun comingMelds(view: View, fresh: List<GameEvent>): Map<Int, Int> {
  val out = HashMap<Int, Int>()
  for (e in fresh) {
    val melds = view.position.players.getOrNull(e.seat)?.melds ?: continue
    when (e.type) {
      "chi", "pon", "kan", "ankan" -> out[e.seat] = melds.size - 1
      "kakan" -> out[e.seat] = melds.indexOfFirst { it.type == "kakan" && it.tile == e.tile }
    }
  }
  return out
}

/**
 * What the events that came since the last view bring (the web page's Table.svelte, its effect over the events): the
 * moments (ui/Moments.kt), one after another, unless the settings (or the system's animations off) keep to the words;
 * and words over the players where no moment shows the same (a flower's, all of them without moments). A call by the
 * caller (small); who dealt in small by them, the player's own over the whole screen; then the hands shown, a pause, and
 * each winner in turn over the whole screen; a draw: a pause before its hands are shown too. In a review (`review`) a
 * hand's end is small, by the player, and shorter. `robbed`: the event that was a claim taken from the player (搶碰, 搶胡).
 * A jump over many events (a review going to another decision) says none of them.
 */
@Composable
private fun TableEvents(host: FxHost, view: View, me: Int, names: List<String>, robbed: Int, review: Boolean, stay: Boolean,
  words: SnapshotStateList<Callout>) {
  val context = LocalContext.current
  val next = remember { intArrayOf(0) }
  LaunchedEffect(view.eventCount) {
    val count = view.eventCount
    val seen = host.seen
    // The first view shows what is there, without words for what came before it; another hand's words go.
    if (seen < 0 || count < seen) {
      host.seen = count
      words.clear()
      host.clear()
      FxClock.tapped = false
      CalloutClock.saw(count)
      return@LaunchedEffect
    }
    val evs = view.events
    val fresh = evs.takeLast((count - seen).coerceIn(0, evs.size))
    host.seen = count
    if (fresh.size > 8) {
      CalloutClock.saw(count)
      return@LaunchedEffect
    }
    val anim = FxClock.enabled()
    val pos = view.position
    fun str(id: Int) = context.getString(id)
    fun name(seat: Int) = names.getOrElse(seat) { "" }
    val dealIn = str(R.string.call_deal_in)
    val list = ArrayList<Moment>()
    val flowersShown = HashSet<Int>()
    var dealtIn = false
    // One at a time over a player: later words take the place of earlier ones. What is not a flower is waited for.
    fun say(side: Side, text: String, kind: CalloutKind, ms: Long, who: String = "") {
      val co = Callout(next[0]++, side, text, kind, ms, who)
      words.removeAll { it.side == side && it.at == co.at }
      words += co
      if (kind != CalloutKind.FLOWER) CalloutClock.shown(ms)
    }
    val small = review
    fun fullMs(key: String) = (Timing.ms(key) * (if (review) Timing.reviewEnd else 1.0)).roundToLong()
    val base = evs.size - fresh.size
    fun lastBefore(at: Int, types: Set<String>) = (at - 1 downTo 0).firstOrNull { evs[it].type in types }?.let { evs[it] }
    for ((i, e) in fresh.withIndex()) {
      // A player's discard lands in their river: their words over it go.
      if (e.type == "discard") {
        val side = sideOf(e.seat, me)
        words.removeAll { it.side == side && it.at == CalloutAt.RIVER }
        // Its sound when it lands (the last of a review's step over several).
        if (i == fresh.indexOfLast { it.type == "discard" }) Sounds.play(Sounds.Name.DISCARD, if (anim) Timing.ms("discard_move") else 0L)
        continue
      }
      val at = base + i
      val taken = at == robbed && e.type in CALLS
      val wordId = (if (e.type == "declare") WORDS["declare:${e.declared ?: "tianting"}"] else if (taken) ROB_WORDS[e.type] else null) ?: WORDS[e.type] ?: continue
      val word = str(wordId)
      // One 補花 for a run of flowers.
      if (e.type == "flower" && !flowersShown.add(e.seat)) continue
      val side = sideOf(e.seat, me)
      fun moment(kind: MomentKind, ms: Long) = Moment(host.nextId(), kind, e.type, e.seat, side, word, name(e.seat), mine = e.seat == me, ms = ms)
      fun callParts() = Parts(Timing.ms("call_in"), Timing.ms("call_drop"), Timing.ms("call_hold"), Timing.ms("call_out"))
      /** A win's or a declaration's over the whole screen, or (a review's) its words by the player, small. */
      fun full(m: Moment, key: String): Moment {
        if (!small) return m.copy(ms = fullMs(key))
        val total = fullMs(key)
        val come = Timing.ms("call_in")
        val out = Timing.ms("dealin_out")
        val hold = maxOf(200L, total - come - out)
        val sad = m.kind == MomentKind.SAD
        return m.copy(
          kind = if (sad) MomentKind.DEALIN else MomentKind.SMALL, ms = come + hold + out, parts = Parts(come, 0, hold, out),
          tiles = if (sad && m.tile != null) listOf(m.tile) else emptyList(), gap = -1, tile = null, src = null,
          at = if (m.kind == MomentKind.DRAWN) FxAt.Middle else if (sad) FxAt.Seat(m.seat) else FxAt.River(m.seat),
        )
      }
      var has = false
      if (anim) when (e.type) {
        "chi", "pon", "kan" -> {
          // The discard it was called on: the last one before it. The tile called lies in the middle, as in the meld.
          val from = lastBefore(at, setOf("discard"))?.seat ?: -1
          val tiles: List<String?> = if (e.type == "chi") Tiles.without(Tiles.sort(e.tiles ?: emptyList()), listOfNotNull(e.tile))
          else List(if (e.type == "pon") 2 else 3) { e.tile }
          val p = callParts()
          list += moment(MomentKind.CALL, p.total).copy(
            parts = p, tiles = tiles, gap = 1, tile = e.tile, src = if (from >= 0 && from != e.seat) FxFrom.River(from) else null,
            from = if (from >= 0) sideOf(from, me) else null, meld = pos.players[e.seat].melds.size - 1, at = FxAt.River(e.seat),
          )
          has = true
        }
        "ankan", "kakan" -> {
          // A concealed kong (another's: its backs) or a pung made a kong: the fourth tile comes in at the end.
          val melds = pos.players[e.seat].melds
          val meld = if (e.type == "kakan") melds.indexOfFirst { it.type == "kakan" && it.tile == e.tile } else melds.size - 1
          val p = callParts()
          list += moment(MomentKind.CALL, p.total).copy(parts = p, tiles = List(3) { e.tile }, gap = 3, tile = e.tile, meld = meld, at = FxAt.River(e.seat))
          has = true
        }
        "tsumo" -> {
          list += full(moment(MomentKind.WIN, 0).copy(tile = e.tile, gap = 0), "tsumo")
          has = true
        }
        "flower_win" -> {
          // The winner's flowers; 七搶一: the other player's one comes from where it lies.
          val own = flowersOf(pos.players[e.seat])
          val loser = view.result?.loser ?: -1
          val other = if (loser >= 0 && loser != e.seat) flowersOf(pos.players[loser]).firstOrNull() else null
          val item = view.result?.winners?.firstOrNull { it.seat == e.seat }?.items?.firstOrNull { it.item == "eight_flowers" || it.item == "seven_rob_one" }
          list += full(moment(MomentKind.WIN, 0).copy(
            tiles = if (other != null) own else own.dropLast(1), gap = if (other != null) own.size else maxOf(0, own.size - 1),
            tile = other ?: own.lastOrNull(), src = other?.let { FxFrom.Flower(it) }, from = if (other != null) sideOf(loser, me) else null,
            note = item?.label ?: "",
          ), "flower_win")
          has = true
        }
        "declare" -> {
          // The declared hand: the player's own; another's backs.
          val tiles: List<String?> = if (e.seat == me) Tiles.sort(Tiles.parse(pos.hand)) else List(concealedCount(view, e.seat)) { null }
          list += full(moment(MomentKind.DECLARE, 0).copy(tiles = tiles), "declare")
          has = true
        }
        "exhausted" -> {
          list += full(moment(MomentKind.DRAWN, 0).copy(who = "", neutral = true), "drawn")
          has = true
        }
        "ron" -> {
          if (!dealtIn) {
            // Who dealt in first (the player's own: over the whole screen), then each winner in turn.
            dealtIn = true
            val last = evs.lastOrNull { it.type == "discard" || it.type == "kakan" }
            val loser = view.result?.loser?.takeIf { it >= 0 } ?: last?.seat ?: -1
            val rons = fresh.filter { it.type == "ron" }
            val winners = rons.joinToString(Words.list) { name(it.seat) }
            if (loser >= 0 && loser != e.seat) {
              if (loser == me) list += full(Moment(host.nextId(), MomentKind.SAD, e.type, loser, Side.BOTTOM, dealIn, name(loser),
                note = "$winners ${str(R.string.act_ron)}", mine = true, tile = e.tile, ms = 0), "sad")
              else {
                val p = Parts(Timing.ms("dealin_in"), 0, Timing.ms("dealin_hold"), Timing.ms("dealin_out"))
                list += Moment(host.nextId(), MomentKind.DEALIN, e.type, loser, sideOf(loser, me), dealIn, name(loser),
                  tiles = listOfNotNull(e.tile), ms = p.total, parts = p, at = FxAt.Seat(loser))
              }
            }
            for (r in rons) {
              val rTaken = base + fresh.indexOf(r) == robbed
              val by = loser >= 0 && loser != r.seat
              val m = full(Moment(host.nextId(), MomentKind.WIN, r.type, r.seat, sideOf(r.seat, me), str(if (rTaken) R.string.rob_ron else R.string.act_ron),
                name(r.seat), note = if (by) "${name(loser)} $dealIn" else "", mine = r.seat == me, gap = 0, tile = r.tile,
                src = if (last?.type != "kakan" && by) FxFrom.River(loser) else null, from = if (by) sideOf(loser, me) else null,
                neutral = r.seat != me && loser != me, ms = 0), "win")
              list += m
            }
          }
          has = true
        }
      }
      // (Its moment showed it, and sounds it: no words after it, even those that stay.)
      if (has) continue
      when (e.type) {
        "flower" -> Sounds.play(Sounds.Name.FLOWER)
        // (Its sound as the hands are shown.)
        "exhausted", "ron", "tsumo", "flower_win" -> {}
        "kan", "ankan", "kakan" -> Sounds.play(Sounds.Name.KONG)
        "declare" -> Sounds.play(Sounds.Name.DECLARE)
        else -> Sounds.play(Sounds.Name.CALL)
      }
      Sounds.cueOf(e.type)?.let { Sounds.play(it) }
      val kind = when (e.type) {
        "tsumo", "ron", "flower_win" -> CalloutKind.WIN
        "flower" -> CalloutKind.FLOWER
        "exhausted" -> CalloutKind.DRAWN
        else -> CalloutKind.CALL
      }
      say(if (e.type == "exhausted") Side.TOP else side, word, kind, if (e.type == "flower") CalloutClock.flowerMs() else CalloutClock.callMs(),
        if (kind == CalloutKind.WIN) name(e.seat) else "")
      // Who dealt in: the discard (or the added kong's tile) the win was on. Once, however many won on it.
      if (e.type == "ron" && words.none { it.kind == CalloutKind.DEALIN }) {
        val from = view.result?.loser?.takeIf { it >= 0 } ?: evs.lastOrNull { it.type == "discard" || it.type == "kakan" }?.seat ?: -1
        if (from >= 0 && from != e.seat) say(sideOf(from, me), dealIn, CalloutKind.DEALIN, CalloutClock.callMs(), name(from))
      }
    }
    // A hand's end: (who dealt in;) the hands shown, a pause, the result's moment (who won, the draw). A draw: a pause
    // before the hands are shown too. (Not before 天聽, 地聽.)
    if (anim) {
      val at = list.indexOfFirst { (it.kind == MomentKind.WIN || it.kind == MomentKind.DRAWN || it.kind == MomentKind.SMALL) && it.type in RESULTS }
      if (at >= 0) {
        val drawn = list[at].type == "exhausted"
        fun gap(key: String, hide: Boolean) = Moment(host.nextId(), MomentKind.GAP, if (drawn) "exhausted" else "ron", me, Side.BOTTOM, "",
          hide = hide, ms = fullMs(key))
        list.addAll(at, if (drawn) listOf(gap("show_wait", true), gap("reveal", false)) else listOf(gap("reveal", false)))
        // Another's win (not one the player made): a pause first, the hands still hidden, as a player draws and looks
        // (or sees the discard land) before showing their hand; a discard flying in this step lands first (win_wait).
        val won = fresh.filter { it.type == "tsumo" || it.type == "ron" || it.type == "flower_win" }.map { it.seat }
        if (!drawn && !review && won.isNotEmpty() && me !in won) {
          val w = gap("win_wait", true)
          list.add(0, if (fresh.any { it.type == "discard" }) w.copy(ms = w.ms + Timing.ms("discard_move")) else w)
        }
      }
    }
    // The result's jingle: with its first moment over the whole screen.
    host.jingleAt = list.firstOrNull { it.kind == MomentKind.SAD || it.kind == MomentKind.WIN || it.kind == MomentKind.DRAWN }?.id ?: -1
    host.jingle = jingleOf(view.result, me)
    host.start(list)
    // (The computer players of a practice game wait for what is up now: CalloutClock.settle.)
    CalloutClock.saw(count)
  }
}

/** A hand's result to the player, its jingle: their own win; a hand they pay for (their deal-in, another's self-draw);
 *  neither (another's win on another's discard, a draw). */
private fun jingleOf(r: HandResult?, me: Int): Sounds.Name = when {
  r == null || r.exhausted -> Sounds.Name.NEUTRAL
  r.winners.any { it.seat == me } -> Sounds.Name.SUCCESS
  r.loser == me || r.selfDraw -> Sounds.Name.FAILURE
  else -> Sounds.Name.NEUTRAL
}

/** The moments' times (FxHost): each in turn; a result's stops at HOLD_AT till the tap that lets it go on. Whether one
 *  is up (FxClock), and the touches that let them go. */
@Composable
private fun MomentsClock(host: FxHost) {
  val cur = host.cur
  LaunchedEffect(cur?.id) {
    val c = cur ?: return@LaunchedEffect
    host.soundsOf(c)
    if (host.holds(c)) {
      delay((c.ms * HOLD_AT).toLong())
      host.hold(c.id)
    } else {
      delay(c.ms)
      host.done(c.id)
    }
  }
  val released = host.released
  LaunchedEffect(released) {
    val c = host.cur?.takeIf { it.id == released } ?: return@LaunchedEffect
    delay((c.ms * (1 - HOLD_AT)).toLong())
    host.done(c.id)
  }
  val any = host.moments.isNotEmpty()
  val result = cur != null && host.holds(cur)
  SideEffect {
    FxClock.on.value = any
    FxClock.resultOn.value = result
  }
  val skips by CalloutClock.skips.collectAsState()
  val skips0 = remember { intArrayOf(skips) }
  LaunchedEffect(skips) {
    if (skips != skips0[0]) host.tap()
    skips0[0] = skips
  }
}

/**
 * Words over a player (TableEvents says them): each word on a plaque (about twice a tile of the hand): a call's and a
 * win's over the river of the player who made them; 放槍 and 補花 over the tiles of whose they are, clear of the rivers;
 * 流局 in the middle. They stay their time (CalloutClock: a practice game's computer players wait for them; `stay`: until
 * the next touch, but a flower's), and a touch anywhere lets them go at once; a player's own go when they discard. Under
 * the moments they are out of sight (`hidden`). `rivers`: the rivers' places on the table, px; `w`, `h`: the table's
 * size, dp.
 */
@Composable
private fun BoxScope.Callouts(
  list: SnapshotStateList<Callout>, s: TableSizes, rivers: Map<Side, Rect>, w: Float, h: Float, stay: Boolean, hidden: Boolean,
) {
  val skips by CalloutClock.skips.collectAsState()
  val skips0 = remember { intArrayOf(skips) }
  LaunchedEffect(skips) {
    if (skips != skips0[0]) list.clear()
    skips0[0] = skips
  }
  val density = LocalDensity.current.density
  for (co in list.toList()) {
    key(co.id) {
      // 0 to 1: the words come in (larger, then their size); they stay their time; 1 to 2: they fade.
      val a = remember { Animatable(0f) }
      LaunchedEffect(Unit) {
        a.animateTo(1f, tween(CALLOUT_IN, easing = LinearEasing))
        if (stay && co.kind != CalloutKind.FLOWER) return@LaunchedEffect
        delay(co.ms - CALLOUT_IN)
        a.animateTo(2f, tween(CALLOUT_OUT, easing = LinearEasing))
        list.remove(co)
      }
      val t = a.value
      val fade = when { t < 0.5f -> t / 0.5f; t > 1f -> 2f - t; else -> 1f }
      val scale = when { t < 0.5f -> 0.5f + t / 0.5f * 0.65f; t < 1f -> 1.15f - (t - 0.5f) / 0.5f * 0.15f; else -> 1f }
      // The point the plaque is set at (px), and which point of the plaque lies there (0 to 1 across and down).
      val wPx = w * density
      val hPx = h * density
      val rt = rivers[Side.TOP]
      val rb = rivers[Side.BOTTOM]
      val middle = if (rt != null && rb != null) Offset(rt.center.x, (rt.bottom + rb.top) / 2) else Offset(wPx / 2, hPx / 2)
      val (at, pivot) = when (co.at) {
        CalloutAt.CENTER -> middle to Offset(0.5f, 0.5f)
        CalloutAt.RIVER -> (rivers[co.side]?.center ?: middle) to Offset(0.5f, 0.5f)
        // Over the player's own tiles. A wide table: beside the rivers and along the top; a tall one: at the top of
        // the others' columns. The player's own: over the foot of their hand.
        CalloutAt.OWN -> when (co.side) {
          Side.BOTTOM -> Offset(wPx / 2, hPx - 8 * density) to Offset(0.5f, 1f)
          Side.TOP -> Offset(wPx / 2, 2 * density) to Offset(0.5f, 0f)
          Side.LEFT -> if (s.wide) Offset(s.sideW * density / 2, hPx * 0.45f) to Offset(0.5f, 0.5f) else Offset(wPx / 6, 2 * density) to Offset(0.5f, 0f)
          Side.RIGHT -> if (s.wide) Offset(wPx - s.sideW * density / 2, hPx * 0.45f) to Offset(0.5f, 0.5f) else Offset(wPx * 5 / 6, 2 * density) to Offset(0.5f, 0f)
        }
      }
      val cjk = co.text.length in 1..4 && co.text.all { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }
      val len = if (!cjk) 3 else if (co.text.length > 1) 2 else 1
      // The word's size, dp: about twice a tile of the hand for the plaque of one character.
      val fs = when (co.kind) {
        CalloutKind.WIN -> when (len) { 1 -> (s.hand * 1.25f).coerceIn(38f, 91f); 2 -> (s.hand * 0.98f).coerceIn(30f, 72f); else -> (s.hand * 0.66f).coerceIn(21f, 48f) }
        CalloutKind.DEALIN -> if (len < 3) (s.hand * 0.8f).coerceIn(26f, 59f) else (s.hand * 0.55f).coerceIn(18f, 40f)
        CalloutKind.FLOWER -> (s.hand * 0.44f).coerceIn(17f, 32f)
        else -> when (len) { 1 -> (s.hand * 1.1f).coerceIn(34f, 80f); 2 -> (s.hand * 0.88f).coerceIn(27f, 64f); else -> (s.hand * 0.6f).coerceIn(19f, 43f) }
      }
      Box(
        Modifier.align(Alignment.TopStart).layout { m, _ ->
          val p = m.measure(Constraints())
          layout(0, 0) { p.place((at.x - p.width * pivot.x).roundToInt(), (at.y - p.height * pivot.y).roundToInt()) }
          // Under a call's or a win's moment: out of sight (they keep their time).
          // (Room in its layer for the plaque's tilt and shade: Moments.kt roomyLayer.)
        }.roomyLayer((fs * 0.2f).dp) {
          scaleX = scale
          scaleY = scale
          alpha = if (hidden) 0f else fade
        }.testTag("callout"),
      ) { Plaque(co.text, co.kind, fs, len, co.who) }
    }
  }
}

/**
 * A word on its plaque: one plaque a word, its corners cut (no tile has them), a lip under it and a fine line inside
 * its edge, laid down a little askew (`tilt`, degrees). `fs`: the word's size, dp; `len`: 1 one Chinese character, 2
 * more, 3 other words. Under it `who`, on a tab over the plaque's lower edge. The colors are `kind`'s unless given (a
 * moment's: ui/CallFx.kt); `line` null: none.
 */
@Composable
internal fun Plaque(
  text: String, kind: CalloutKind, fs: Float, len: Int, who: String, robInk: Color? = null, tilt: Float = -2f, face: Color = kind.face,
  ink: Color = kind.ink, back: Color = kind.back, line: Color? = kind.line,
) {
  val flower = kind == CalloutKind.FLOWER
  val lip = fs * 0.09f
  val cut = fs * (if (flower) 0.3f else 0.24f)
  val pad = fs * when (len) { 1 -> 0.42f; 2 -> 0.36f; else -> 0.6f }
  // In dp, whatever the font scale: the plaque is sized by it.
  val font = with(LocalDensity.current) { fs.dp.toSp() }
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier.rotate(tilt).drawBehind {
        fun plate(l: Float, t: Float, r: Float, b: Float, c: Float) = Path().apply {
          moveTo(l + c, t); lineTo(r - c, t); lineTo(r, t + c); lineTo(r, b - c); lineTo(r - c, b); lineTo(l + c, b); lineTo(l, b - c); lineTo(l, t + c)
          close()
        }
        val lipPx = lip.dp.toPx()
        val c = cut.dp.toPx()
        val bottom = size.height - lipPx
        // The shade under the plaque, its lip, its face, the line inside the face.
        drawPath(plate(0f, lipPx * 1.9f, size.width, size.height + lipPx * 0.9f, c), Color(0x59000000))
        drawPath(plate(0f, lipPx, size.width, size.height, c), back)
        drawPath(plate(0f, 0f, size.width, bottom, c), face)
        val inset = (fs * 0.1f).dp.toPx()
        if (line != null) drawPath(plate(inset, inset, size.width - inset, bottom - inset, c - inset * 0.59f), line, style = Stroke((fs * 0.035f).dp.toPx()))
      }.height((fs * (if (flower) 1.6f else 1.5f) + lip).d).padding(start = pad.d, end = (if (len == 2) pad - fs * 0.06f else pad).d, bottom = lip.d),
      contentAlignment = Alignment.Center,
    ) {
      // 搶碰, 搶胡 (a claim taken from the player, in a moment): the 搶 in `robInk`.
      val rob = Words.robMark
      val shown = if (robInk != null && rob.isNotEmpty() && text.startsWith(rob)) buildAnnotatedString {
        withStyle(SpanStyle(color = robInk)) { append(rob) }
        append(text.substring(rob.length))
      } else AnnotatedString(text)
      Text(shown, Modifier.wrapContentHeight(Alignment.CenterVertically, unbounded = true), maxLines = 1, softWrap = false,
        style = TextStyle(color = ink, fontWeight = if (flower) FontWeight.ExtraBold else FontWeight.Black, fontFamily = FontFamily.Serif,
          fontSize = font, lineHeight = font, letterSpacing = if (len == 2) (font.value * 0.06f).sp else 0.sp))
    }
    if (who.isNotEmpty()) {
      val pill = RoundedCornerShape(50)
      val small = with(LocalDensity.current) { (fs * 0.3f).coerceIn(13f, 16f).dp.toSp() }
      Text(who, Modifier.offset(y = -(fs * 0.2f).d).background(Color(0xFF18221F), pill).border(1.5.dp, line ?: kind.line, pill).padding(horizontal = 13.dp, vertical = 3.dp),
        style = TextStyle(color = Color(0xFFFFF6DC), fontWeight = FontWeight.Bold, fontSize = small), maxLines = 1, softWrap = false)
    }
  }
}

@Composable
private inline fun key(id: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(id) { content() }

