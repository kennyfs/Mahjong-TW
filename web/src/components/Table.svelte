<!--
  The table as one seat sees it (a seat's `view`): the player at the bottom with their hand, the others around, every
  river in the middle. A wide table has the round, the wall and the dealer's streak in its top-left corner and the
  screen's `corner` (its buttons) in its top-right one, nothing between the rivers; a tall one has them in a box
  between the rivers (its screen's buttons are in its own bar). Its sizes come from its own room (lib/layout.ts).
  Calls, wins and flowers show in large words on a plaque (one plaque a word, about twice a tile of the hand): a call's
  and a win's over the river of the player who made them, where nothing of that moment lies (the tiles called are in
  the melds and in another's river, the winner's hand at their seat); 放槍 over the tiles of the one who dealt in, clear
  of the rivers (the tile dealt is in theirs); 流局 in the middle. Under a win's and under 放槍, the player's name.
  A call's and a win's stay two seconds (lib/callouts.ts; a practice game's computer players wait for them), a
  flower's briefly; a tap anywhere, or a key, lets them go at once, and a player's own go when they discard. New
  discards, melds and the drawn tile come in moving.

  The player's turn to discard: the hand in a pulsing frame of the turn's color; with no tile drawn (the dealer's
  first discard, after a call) no tile is set apart (the frame says whose turn it is). A discard the player may claim stands out
  in its river (larger, pulsing), and the claim buttons show their tiles large, the claimed one lit.
  A wide table's own flowers lie over the hand, above the player's name plate (in the hand's row they took its room).

  `revealed`: every seat's concealed tiles, to show (a finished hand, a replay showing all). The others' then lie on
  a rack (a darker tray) after their melds, their tiles as large as their room allows: the player opposite in one
  line along the top of a wide table; on a tall one the others' strip grows for them (the rivers give the room).
  `keys`: a computer's: a click discards, and the keyboard plays (← → choose a tile, Enter discards it, Esc lets go;
  H wins, G kongs, P pungs, C chows, 1 2 3 a chow's way, Space passes).
  `aside`: a panel at the right (`asideW` px wide), shown when the window has room for it beside the table (a computer's;
  `asideShown` says whether it is);
  the `corner` is not shown then (the panel has its buttons).
  A review's: `marks` (tags and danger on the hand's tiles, lib/review.ts), `notes` (a few words on each name plate),
  `strip` (a line over the hand, over the choices when there are some: the options that are not discards, a tile's
  figures), `onpick` (the tile tapped). With the analysis shown while playing (a branch, a practice hand with its
  hints) also `choiceMarks` (on each choice's button what it costs) and `tagRoom`: the tags' room kept whether the
  step has tags or not, so that the table keeps its size from a turn of the player's to another's.
  `onnext` (practice at the manual pace): the others' next step waits for the player: a button for it, where the
  player's hand leaves room when it is not their turn (a tall table: at the end of the hand's first row, the place
  of no tile, so that a tap too many discards nothing; a wide one: over the hand's right end), and Space or Enter.
  `stay`: a call's and a win's words stay until the next tap or key (the game waits for the player anyway); not those
  of a moment (it showed them).
-->
<script lang="ts">
  import { tick, untrack, type Snippet } from 'svelte';
  import { fade } from 'svelte/transition';
  import { callMs, flowerMs, calloutShown, calloutsSkipped } from '../lib/callouts';
  import { t } from '../lib/i18n.svelte';
  import {
    DRAWN_GAP, ROW_GAP, fitMelds, fitShown, fitShownRow, OPP_LINES, runLines, SHOWN_RIVER, shownHeight, tableSizes, tallTopRoom, type TableSizes,
  } from '../lib/layout';
  import { concealedCount, meldTiles, seatAt, sideOf, flowersOf, type Side } from '../lib/seats';
  import type { ChoiceMark, TileMark } from '../lib/review';
  import { groupIndices, parseTiles, sortTiles, windName, without, type Tile as T } from '../lib/tiles';
  import type { Action, GameEvent, View } from '../lib/types';
  import { dealsShown, movesShown, resultTap, setFx } from '../lib/fx.svelte';
  import { playSound } from '../lib/sound';
  import { later } from '../lib/clock';
  import CallFx from './CallFx.svelte';
  import SadFx from './SadFx.svelte';
  import SmallFx from './SmallFx.svelte';
  import DealFx from './DealFx.svelte';
  import { FULL, total, type Moment, type Parts } from '../lib/moments';
  import { ms, REVIEW_END, type TimingKey } from '../lib/timing';
  import ClaimBar from './ClaimBar.svelte';
  import { settings } from '../lib/settings.svelte';
  import Melds from './Melds.svelte';
  import MyHand from './MyHand.svelte';
  import River from './River.svelte';
  import SeatBadge from './SeatBadge.svelte';
  import Tile, { TILE_RATIO } from './Tile.svelte';

  let {
    view, me, names, scores = null, revealed: revealedIn = null, interactive = true, onact, handLabel = '', maxHand = 64, keys = false,
    away = [false, false, false, false], status, overlay, corner, aside, selected = $bindable(null), asideW = 300,
    marks = null, notes = null, strip, onpick, onnext, stay = false, robbed = null, review = false, deal = null,
    choiceMarks = null, tagRoom = false, asideShown = $bindable(false), waitShown = false,
  }: {
    view: View; me: number; names: string[]; scores?: number[] | null; revealed?: string[] | null; interactive?: boolean;
    onact?: (a: Action) => void; handLabel?: string; maxHand?: number; keys?: boolean; away?: boolean[];
    status?: Snippet; overlay?: Snippet; corner?: Snippet; aside?: Snippet; selected?: number | null; asideW?: number;
    marks?: Map<T, TileMark> | null; notes?: ({ text: string; level: number } | null)[] | null; strip?: Snippet;
    onpick?: (tile: T | null) => void; onnext?: (() => void) | null; stay?: boolean;
    /** The event (an index into view.events) that was a claim taken from the player (lib/claims.ts): its words are
     *  搶碰, 搶胡 instead of 碰, 胡. */
    robbed?: number | null;
    /** A review's table: its moments of a hand's end are small, by the player, and shorter (lib/timing.ts). */
    review?: boolean;
    /** A hand just begun (practice: its id): its tiles are shuffled and dealt first (DealFx.svelte),
     *  once (not again when the table comes back to it). */
    deal?: string | null;
    choiceMarks?: ((a: Action) => ChoiceMark | null) | null;
    tagRoom?: boolean;
    asideShown?: boolean;
    /** With the hints: the tiles a ready hand waits with (聽的相關牌組) have blue faces; on the player's turn, those it
     *  would wait with after the tile chosen (or under the mouse) is discarded, else after the drawn one. */
    waitShown?: boolean;
  } = $props();
  let tableEl = $state<HTMLElement>();
  /** The moments (below): one after another. */
  let moments = $state<Moment[]>([]);
  /** A hand's deal going on (below): the tiles each seat has had dealt so far; `flip`: dealt, the hand turning up. */
  let dealing = $state<{ id: string; n: number[]; flip: boolean } | null>(null);
  /** The hands shown (`revealed`) once a hand's end comes to them: after who dealt in, a draw's pause (lib/moments.ts
   *  gap), and before the pause that leads to the result's moment. */
  const revealed = $derived(moments.findIndex((m) => m.kind === 'gap' && !m.hide) > 0 ? null : revealedIn);

  const others: Side[] = ['right', 'top', 'left'];
  const pos = $derived(view.position);
  const setup = $derived(pos.setup);
  /** What another seat shows: its melds' tile counts, its flowers, and its concealed tiles when they are shown. */
  function shownOf(seat: number) {
    const p = pos.players[seat];
    return { counts: p.melds.map((m) => meldTiles(m).length), flowers: flowersOf(p).length, concealed: revealed ? parseTiles(revealed[seat]).length : 0 };
  }
  /** The others' name plates as they are laid out (a long note takes a second line). */
  let plate = $state<Record<Side, { w: number; h: number }>>({ bottom: { w: 0, h: 0 }, right: { w: 0, h: 0 }, top: { w: 0, h: 0 }, left: { w: 0, h: 0 } });
  const plateH = (side: Side) => plate[side].h || 28;

  /** The room kept over each row of the hand for the tiles' tags, when there may be some. */
  const TAG_H = 17;
  // Room over the hand's tiles for their tags, where they have any (the settings may show none).
  const tagH = $derived(tagRoom || (marks && [...marks.values()].some((m) => m.tag)) ? TAG_H : 0);
  let W = $state(0);
  let H = $state(0);
  /** A tall table's others' strip with everyone's tiles shown: as high as the three need at the largest tiles (20 px
   *  and up) that leave the rivers tiles of SHOWN_RIVER px. */
  function shownTop(s0: TableSizes, h: number): number {
    const room = tallTopRoom(s0, h, SHOWN_RIVER);
    let need = 0;
    for (const side of others) {
      const o = shownOf(seatAt(side, me));
      const mine = room - plateH(side) - 12;
      const w = fitShown(o.counts, o.flowers, o.concealed, s0.sideW - 8, mine, s0.opp, 20);
      need = Math.max(need, plateH(side) + 12 + shownHeight(o.counts, o.flowers, o.concealed, s0.sideW - 8, w));
    }
    return Math.min(room, need);
  }
  // The tags' room comes off the table's height: what the hand's own lift does not already leave them.
  function sizes(w: number, h: number) {
    let s0 = tableSizes(w, h, maxHand);
    let extra = 0;
    if (tagH) {
      extra = (s0.wide ? 1 : 2) * Math.max(0, tagH - s0.hand * TILE_RATIO * 0.2);
      if (extra > 0) s0 = tableSizes(w, h - extra, maxHand);
    }
    if (revealed && !s0.wide) {
      const top = shownTop(s0, h - extra);
      if (top > s0.topH) s0 = tableSizes(w, h - extra, maxHand, top);
    }
    return { s: s0, extra };
  }
  const full = $derived(sizes(W, H));
  const withAside = $derived(!!aside && full.s.wide && W - full.s.tableW >= asideW + 24);
  const fit = $derived(withAside ? sizes(W - asideW - 16, H) : full);
  $effect(() => { asideShown = withAside; });
  const s = $derived(fit.s);
  const meH = $derived(s.meH + fit.extra);

  // A wide table's corners: the round, the wall and the streak at the top-left; the screen's buttons at the top-right.
  // The player opposite keeps between them; the side players start below them where they reach under the top row.
  const showCorner = $derived(!!corner && !withAside);
  let info = $state({ w: 0, h: 0 });
  let buttons = $state({ w: 0, h: 0 });
  const padL = $derived(s.wide ? info.w + 10 : 0);
  const padR = $derived(s.wide && showCorner ? buttons.w + 10 : 0);
  const belowL = $derived(s.wide ? Math.max(0, info.h + 8 - s.topH) : 0);
  const belowR = $derived(s.wide && showCorner ? Math.max(0, buttons.h + 8 - s.topH) : 0);

  // The player's tiles: the drawn one apart. None drawn (the dealer's first discard, after a call): none apart.
  const mine = $derived.by(() => {
    const all = revealed ? parseTiles(revealed[me]) : parseTiles(pos.hand);
    const drawn = !revealed && pos.drawn && view.phase === 'self' && view.current === me ? pos.drawn : null;
    const tiles = sortTiles(drawn ? without(all, [drawn]) : all);
    return { tiles, drawn };
  });
  // The tile the player draws after another's discard nobody could claim (it comes in the same view): it comes into
  // the hand once that discard has landed in its river (flyIn), not while it flies. (`seen`: the events shown before
  // this view; the event effect moves it on after the view is drawn.)
  const drawWait = $derived.by(() => {
    if (!mine.drawn || seen < 0 || !movesShown()) return 0;
    const evs = view.events;
    let i = evs.length - 1;
    while (i >= 0 && (evs[i].type === 'draw' || evs[i].type === 'flower')) i--;
    const d = evs[i];
    return d && d.type === 'discard' && d.seat !== me && view.event_count - evs.length + i >= seen ? ms('discard_move') : 0;
  });
  /** The rows of the hand on a tall table (the next-step button stands at the end of the first). */
  const handRows = $derived(s.handRow > 0 && mine.tiles.length + (mine.drawn ? 1 : 0) > s.handRow ? 2 : 1);
  /** The tile under a computer's mouse (where a click discards): its figures as a chosen one's. */
  let hovered = $state<number | null>(null);
  $effect(() => {
    const i = selected ?? hovered;
    onpick?.(i === null ? null : i < mine.tiles.length ? mine.tiles[i] : mine.drawn);
  });
  /** On the player's plate: a ready hand declared, else (while playing) how far the hand is from ready, from 3 向聽. */
  const myNote = $derived.by(() => {
    const d = pos.players[me].declared;
    if (d) return { text: t(`call_${d}`), level: 0 };
    const n = view.shanten;
    if (review || dealing || revealed || view.phase === 'over' || n === undefined || n < 0 || n > 3) return null;
    return { text: n === 0 ? t('analysis_ready') : t('analysis_shanten', n), level: 0 };
  });
  const waitAt = $derived.by(() => {
    if (!waitShown || revealed) return null;
    if (view.phase === 'self' && view.current === me) {
      const i = selected ?? hovered ?? (mine.drawn ? mine.tiles.length : null);
      const x = i === null ? null : i < mine.tiles.length ? mine.tiles[i] : mine.drawn;
      const g = x ? view.ready_tiles?.[x] : null;
      return g ? groupIndices(mine.tiles, mine.drawn, parseTiles(g), i) : null;
    }
    return view.wait_tiles ? groupIndices(mine.tiles, mine.drawn, parseTiles(view.wait_tiles)) : null;
  });
  const discardable = $derived(new Set(view.my_turn ? view.legal.filter((a) => a.type === 'discard').map((a) => a.tile) : []));
  const canDiscard = (tile: string) => interactive && !dealing && discardable.has(tile);
  function discard(tile: string) {
    onact?.({ type: 'discard', tile });
  }
  const claims = $derived(interactive && !dealing && view.my_turn ? view.legal.filter((a) => a.type !== 'discard') : []);
  /** The player's turn to discard: the hand's frame pulses. */
  const myTurn = $derived(interactive && !dealing && view.my_turn && discardable.size > 0);
  /** The tile the player may claim (a discard, or a kong's added tile), while they may: it stands out. */
  // Sideways, the choices low, off the next player's melds: level with the foot of the player's melds and flowers, at
  // their right; only when those reach under where the choices would be, over them (as upright). (Not by the hand:
  // its length changes often, the choices' place hardly.)
  let claimBottom = $state<number | null>(null);
  /** The choices' height: the strip goes over them where they stand over the hand. */
  let claimsH = $state(0);
  function placeClaims() {
    const bar = tableEl?.querySelector<HTMLElement>('.claims > *');
    const line = tableEl?.querySelector<HTMLElement>('.me .me-line');
    if (!tableEl || !bar || !line) return;
    const t = tableEl.getBoundingClientRect();
    const b = bar.getBoundingClientRect(), l = line.getBoundingClientRect();
    const meldsRight = Math.max(l.left, ...[...line.querySelectorAll<HTMLElement>('.tile')].map((e) => e.getBoundingClientRect().right));
    claimBottom = t.right - 8 - b.width >= meldsRight + 8 ? t.bottom - l.bottom : null;
  }
  $effect(() => {
    if (!claims.length || !s.wide) {
      claimBottom = null;
      return;
    }
    void [claims, s.hand, s.tableW, meH, pos.players[me].melds.length, myFlowers.length];
    void tick().then(() => untrack(placeClaims));
  });
  // A call to decide on with no tile of the hand to play: a tap on the hand lifts nothing (and what was lifted goes down).
  // Else a lifted tile stays up while the others play (the player may have chosen it already: one tap plays it on
  // their turn), till the hand itself changes (a tile drawn is apart: not a change).
  const pickHand = $derived(!claims.length || view.legal.some((a) => a.type === 'discard'));
  const handKey = $derived(mine.tiles.join(','));
  $effect(() => {
    if (!pickHand) untrack(() => { if (selected !== null) selected = null; });
  });
  let lastHand = '';
  $effect(() => {
    const k = handKey;
    untrack(() => {
      if (k !== lastHand && lastHand && selected !== null && !review) selected = null;
      if (k !== lastHand) hovered = null;
      lastHand = k;
    });
  });
  const claimTile = $derived(claims.some((a) => a.type !== 'pass') && view.phase !== 'self' ? (pos.claim?.tile ?? null) : null);
  const claimSeat = $derived(claimTile && view.phase === 'react' ? (pos.claim?.seat ?? -1) : -1);

  // A computer's keys.
  function claimFor(key: string): Action | undefined {
    const of = (...types: string[]) => claims.find((a) => types.includes(a.type));
    const chis = claims.filter((a) => a.type === 'chi');
    switch (key) {
      case 'h': return of('ron', 'tsumo');
      case 'g': return of('kan', 'ankan', 'kakan');
      case 't': return of('declare');
      case 'p': return of('pon');
      case 'c': return chis[0];
      case '1': case '2': case '3': return chis[Number(key) - 1];
      case ' ': return of('pass');
    }
    return undefined;
  }
  function onkey(e: KeyboardEvent) {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const el = e.target as HTMLElement | null;
    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) return;
    // The others' next step (the manual pace): Space or Enter, wherever the focus is not on another button.
    if (onnext && (e.key === ' ' || e.key === 'Enter') && !e.repeat && (el?.tagName !== 'BUTTON' || el.classList.contains('next'))) {
      e.preventDefault();
      onnext();
      return;
    }
    if (!keys || !interactive || !view.my_turn) return;
    const n = mine.tiles.length + (mine.drawn ? 1 : 0);
    if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && discardable.size && n) {
      const d = e.key === 'ArrowLeft' ? -1 : 1;
      selected = selected === null ? (d > 0 ? 0 : n - 1) : (selected + d + n) % n;
      e.preventDefault();
    } else if ((e.key === 'Enter' || e.key === 'ArrowUp') && selected !== null) {
      const tile = selected < mine.tiles.length ? mine.tiles[selected] : mine.drawn;
      if (tile && canDiscard(tile)) {
        selected = null;
        discard(tile);
        e.preventDefault();
      }
    } else if (e.key === 'Escape') selected = null;
    else {
      const a = claimFor(e.key.toLowerCase());
      if (a) {
        e.preventDefault();
        onact?.(a);
      }
    }
  }

  // The last discard, lit while it is being decided on (or until the next one).
  const lastDiscard = $derived.by(() => {
    for (let i = view.events.length - 1; i >= 0; i--) {
      const e = view.events[i];
      if (e.type === 'discard') return e.seat;
      if (e.type === 'chi' || e.type === 'pon' || e.type === 'kan') return -1;
    }
    return -1;
  });

  // Words over a player for a call, a win, a flower: from the events that came since the last view.
  /** `kind`: the plaque's color: a call's (and a declaration's), a win's, the one who dealt in, a flower's, a draw's.
   *  `at`: where it stands: over the player's river, over their own tiles, or in the middle of the table. */
  type CalloutKind = 'call' | 'win' | 'dealin' | 'flower' | 'drawn';
  type CalloutAt = 'river' | 'own' | 'center';
  let callouts = $state<{ id: number; side: Side; text: string; kind: CalloutKind; at: CalloutAt; who: string }[]>([]);
  let seen = -1;
  let nextId = 0;

  // ---- The moments (lib/moments.ts): a call's by the caller; what ends a hand, and a declaration, over the whole
  // screen. One after another (who dealt in, then each winner); a practice game's computer players wait for them. ----
  const cur = $derived(moments[0] ?? null);
  // (Told when one comes up and when all are gone, not between two: what waits for them waits for the last.)
  const anyFx = $derived(moments.length > 0);
  $effect(() => {
    setFx(anyFx);
    return () => setFx(false);
  });
  /** The meld a call's moment is bringing (now or next): out of sight at its place till it lands. */
  const hideMeld = (seat: number) => moments.find((m) => m.kind === 'call' && m.seat === seat)?.meld ?? -1;
  /** A win's tiles: the winner's hand once it is shown (it may come a moment after the win). */
  const curShown = $derived.by((): Moment | null => {
    if (!cur || cur.kind !== 'win' || cur.type === 'flower_win') return cur;
    const hand = (revealed ?? view.hands)?.[cur.seat];
    if (!hand) return { ...cur, tiles: [], gap: 0 };
    const tiles = sortTiles(without(parseTiles(hand), cur.tile ? [cur.tile] : []));
    return { ...cur, tiles, gap: tiles.length };
  });
  function startMoments(list: Moment[]) {
    if (!list.length) return;
    // What was still up goes (it is old news); these follow one another.
    moments = list;
    jingleAt = list.find((m) => m.kind === 'sad' || m.kind === 'win' || m.kind === 'drawn')?.id ?? -1;
    if (list.some(holds)) resultTap.tapped = false;
    calloutShown(list.reduce((t, m) => t + m.ms, 0));
  }
  /** A hand's result to the player, its jingle: their own win; a hand they pay for (their deal-in, another's
   *  self-draw); neither (another's win on another's discard, a draw). */
  function jingle(): 'success' | 'failure' | 'neutral' {
    const r = view.result;
    if (!r || r.exhausted) return 'neutral';
    if (r.winners.some((w) => w.seat === me)) return 'success';
    return r.loser === me || r.self_draw ? 'failure' : 'neutral';
  }
  /** The moment the result's jingle comes with: the first over the whole screen of a hand's end. */
  let jingleAt = -1;
  // Each moment's sound: a call's, a kong's as the tile taken lands in its gap (SmallFx: at 0.82 of its drop), and a
  // tile set down as the meld lands at its place (at its end); a declaration's; a hand's end: the result's jingle as
  // its first moment over the whole screen comes. A moment let go before its sound: none. (The hands shown at a hand's
  // end sound as they are shown, below.)
  $effect(() => {
    const c = cur;
    if (!c) return;
    return untrack(() => {
      if (c.kind === 'call') {
        const p = c.parts;
        const offs = [playSound(c.type === 'chi' || c.type === 'pon' ? 'call' : 'kong', p ? p.in + 0.82 * p.drop : 0)];
        if (p && c.dst) offs.push(playSound('discard', p.in + p.drop + p.hold + p.out));
        return () => offs.forEach((f) => f());
      }
      if (c.kind === 'declare') return playSound('declare');
      if (c.id === jingleAt) return playSound(jingle());
    });
  });
  // A hand's end: one sound as the hands are shown (`revealed`), by what it is to the player: their own win; another's
  // they pay for (on their discard, or self-drawn); another's they neither gain nor lose by; a draw. (A table that
  // starts with them shown, a replay showing all: none.)
  let wasShown: boolean | null = null;
  $effect(() => {
    const shown = !!revealed;
    untrack(() => {
      if (shown && wasShown === false) {
        const r = view.result;
        const pays = r && (r.loser === me || (r.self_draw && !r.winners.some((w) => w.seat === me)));
        playSound(!r ? 'other' : r.exhausted ? 'drawn' : r.winners.some((w) => w.seat === me) ? 'win' : pays ? 'lose' : 'other');
        // Without the moments (words only): the jingle a moment after.
        if (!movesShown()) playSound(jingle(), 400);
      }
      wasShown = shown;
    });
  });
  function momentDone(id: number) {
    if (moments[0]?.id !== id) return;
    moments = moments.slice(1);
  }
  // A hand's result over the whole screen (a win, a draw; not in a review, which has its own steps) stops where the
  // light has run over the hand, its plaque and tiles still (what turns and runs behind them goes on), till a tap:
  // then it goes. A tap before that lets all of the hand's end go (the result comes at once).
  const HOLD_AT = 0.78;
  const holds = (m: Moment) => !review && (m.kind === 'win' || m.kind === 'drawn');
  $effect(() => {
    resultTap.on = !!cur && holds(cur);
    return () => { resultTap.on = false; };
  });
  /** The moment held for the tap, and what of it stopped. */
  let tapHold = $state<number | null>(null);
  let stopped: Animation[] = [];
  function holdMoment(c: Moment) {
    if (moments[0]?.id !== c.id) return;
    tapHold = c.id;
    stopped = [...document.querySelectorAll('.fx')].flatMap((el) => el.getAnimations({ subtree: true }))
      .filter((a) => a.playState === 'running' && a.effect?.getTiming().iterations !== Infinity);
    for (const a of stopped) a.pause();
  }
  function releaseHold() {
    const id = tapHold;
    const c = moments[0];
    tapHold = null;
    for (const a of stopped.splice(0)) if (a.playState === 'paused') a.play();
    resultTap.tapped = true;
    if (c && c.id === id) later(() => momentDone(c.id), c.ms * (1 - HOLD_AT));
  }
  $effect(() => {
    const c = cur;
    // (`holdFx` on the window: the screenshot scripts keep the moment up to step through it.)
    if (!c || 'holdFx' in window) return;
    return holds(c) ? later(() => holdMoment(c), c.ms * HOLD_AT) : later(() => momentDone(c.id), c.ms);
  });
  // ---- A hand's start (practice): shuffled and dealt all at once (DealFx.svelte). Meanwhile the player's hand is its
  // backs as they come, the others' backs too, nobody's flowers; then the hand turns face up (the flowers with it).
  // The computer players wait for it as for words (a tap lets it go). ----
  let dealGeo = $state<{ center: { x: number; y: number; w: number; h: number }; targets: { x: number; y: number }[] } | null>(null);
  let dealTimer: (() => void) | undefined;
  $effect(() => {
    const d = deal;
    if (!d || W === 0 || !tableEl) return;
    untrack(() => {
      if (dealsShown.last === d) return;
      dealsShown.last = d;
      if (!movesShown()) return;
      void tick().then(() => startDeal(d));
    });
  });
  function startDeal(id: string) {
    if (!tableEl) return;
    const t0 = tableEl.getBoundingClientRect();
    const rel = (r: DOMRect) => ({ x: r.left - t0.left + r.width / 2, y: r.top - t0.top + r.height / 2, w: r.width, h: r.height });
    const c = tableEl.querySelector('.center')?.getBoundingClientRect();
    if (!c) return;
    const targets = [0, 1, 2, 3].map((seat) => {
      if (seat === me) {
        const r = tableEl!.querySelector('.me')!.getBoundingClientRect();
        return { x: r.left - t0.left + Math.min(r.width / 2, 16 + 8 * s.hand), y: r.bottom - t0.top - s.hand * TILE_RATIO * 0.7 };
      }
      const el = tableEl!.querySelector(`.seat.${sideOf(seat, me)}`);
      return el ? rel(el.getBoundingClientRect()) : { x: 0, y: 0 };
    });
    dealGeo = { center: rel(c), targets };
    dealing = { id, n: [0, 0, 0, 0], flip: false };
    const total = ms('deal_shuffle') + ms('deal_pause') + ms('deal_fly') + 90 + ms('deal_flip');
    calloutShown(total);
  }
  function dealPacket(seat: number, n: number) {
    if (dealing) dealing.n[seat] += n;
  }
  /** The tiles are dealt: the player's turn face up, one after another from the left. */
  function dealDone() {
    if (!dealing) return;
    dealing.flip = true;
    void tick().then(() => {
      const tiles = [...(tableEl?.querySelectorAll<HTMLElement>('.me [data-tile]') ?? [])];
      const d = ms('deal_flip');
      tiles.forEach((el, i) => el.animate([{ transform: 'perspective(400px) rotateY(90deg)' }, { transform: 'none' }],
        { duration: d * 0.5, delay: (i / Math.max(1, tiles.length)) * d * 0.5, easing: 'ease-out', fill: 'backwards' }));
      dealTimer = later(endDeal, d);
    });
  }
  function endDeal() {
    dealTimer?.();
    dealTimer = undefined;
    dealing = null;
    dealGeo = null;
  }

  // ---- The player's discard: from where it stood in the hand to its place in the river; and the hand closing the
  // gap it left (the drawn tile to its place, the tiles between it and the gap a tile's width over) once the discard is
  // halfway there. Slow at both ends. ----
  /** The hand's tiles where they stood before the view changed (the discard is gone from it after). */
  let handRects: { tile: string; drawn: boolean; r: DOMRect }[] = [];
  $effect.pre(() => {
    void view;
    untrack(() => {
      if (!tableEl) return;
      handRects = [...tableEl.querySelectorAll<HTMLElement>('.me [data-tile]')].map((el) => ({
        tile: el.dataset.tile!, drawn: el.dataset.drawn === '1', r: (el.querySelector('.lifted') ?? el).getBoundingClientRect() }));
    });
  });
  function flyDiscard(tile: string) {
    // The drawn tile if it is the one, else the last of its kind.
    const from = handRects.find((h) => h.drawn && h.tile === tile) ?? [...handRects].reverse().find((h) => h.tile === tile);
    if (!from) return;
    const before = handRects.filter((h) => h !== from);
    void tick().then(() => {
      // The hand: each tile from where its like stood (the n-th of a kind to the n-th), where it moved.
      const left = new Map<string, DOMRect[]>();
      for (const h of before) left.set(h.tile, [...(left.get(h.tile) ?? []), h.r]);
      const hm = ms('hand_move');
      // (They wait where they stood till the discard is halfway.)
      const wait = Math.round(ms('discard_move') / 2);
      let moved = false;
      for (const el of tableEl?.querySelectorAll<HTMLElement>('.me [data-tile]') ?? []) {
        const r0 = left.get(el.dataset.tile!)?.shift();
        if (!r0) continue;
        const r1 = el.getBoundingClientRect();
        const dx = r0.left - r1.left, dy = r0.top - r1.top;
        if (Math.abs(dx) + Math.abs(dy) < 1) continue;
        moved = true;
        el.animate([{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'none' }],
          { duration: hm, delay: wait, easing: 'cubic-bezier(0.65, 0, 0.35, 1)', fill: 'backwards' });
      }
      const el = tableEl?.querySelector<HTMLElement>(`[data-river="${me}"] > :last-child`);
      if (!el) return;
      const to = el.getBoundingClientRect();
      const k = from.r.width / Math.max(1, to.width);
      const dx = from.r.left + from.r.width / 2 - (to.left + to.width / 2), dy = from.r.top + from.r.height / 2 - (to.top + to.height / 2);
      el.animate([{ transform: `translate(${dx}px, ${dy}px) scale(${k})` }, { transform: 'none' }],
        { duration: ms('discard_move'), easing: 'cubic-bezier(0.65, 0, 0.35, 1)', fill: 'backwards' });
    });
  }

  /** Another player's discard: in from the table's edge on their side (the top, the left, the right), along the line
   *  to its place in their river, fading in as it comes; slow at both ends. Its time (discard_move) is the last of the
   *  computer player's pace (lib/claims.ts BotPace). The app's HandMotion discardIn. */
  function flyIn(seat: number) {
    void tick().then(() => {
      const el = tableEl?.querySelector<HTMLElement>(`[data-river="${seat}"] > :last-child`);
      if (!el || !tableEl) return;
      const t = tableEl.getBoundingClientRect();
      const to = el.getBoundingClientRect();
      const side = sideOf(seat, me);
      // From just inside the table's edge: the tile's outer side on it.
      const dx = side === 'left' ? t.left - to.left : side === 'right' ? t.right - to.right : 0;
      const dy = side === 'top' ? t.top - to.top : 0;
      const d = ms('discard_move');
      el.animate([{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'none' }],
        { duration: d, easing: 'cubic-bezier(0.65, 0, 0.35, 1)', fill: 'backwards' });
      el.animate([{ opacity: 0 }, { opacity: 1 }], { duration: d * 0.2, fill: 'backwards' });
    });
  }

  const callParts = (): Parts => ({ in: ms('call_in'), drop: ms('call_drop'), hold: ms('call_hold'), out: ms('call_out') });
  const dealinParts = (): Parts => ({ in: ms('dealin_in'), drop: 0, hold: ms('dealin_hold'), out: ms('dealin_out') });
  const RIVER_AREA: Record<Side, string> = { top: 'rt', left: 'rl', right: 'rr', bottom: 'rb' };
  /** Where a player's small moment stands: over their river (a call's), over their tiles (who dealt in). */
  const riverOf = (seat: number) => `.river-cell.${RIVER_AREA[sideOf(seat, me)]}`;
  const seatOf = (seat: number) => (seat === me ? '.me' : `.seat.${sideOf(seat, me)}`);

  const WORDS: Partial<Record<GameEvent['type'], string>> = {
    chi: 'act_chi', pon: 'act_pon', kan: 'act_kan', ankan: 'act_ankan', kakan: 'act_kakan', tsumo: 'act_tsumo', ron: 'act_ron',
    flower: 'call_flower', flower_win: 'call_flower_win', exhausted: 'call_exhausted',
  };
  $effect(() => {
    const evs = view.events;
    const count = view.event_count;
    untrack(() => {
      // The first view shows what is there, without words for what came before it; another hand's words go.
      if (seen < 0 || count < seen) {
        seen = count;
        if (callouts.length) callouts = [];
        if (moments.length) moments = [];
        tapHold = null;
        resultTap.tapped = false;
        return;
      }
      const fresh = evs.slice(evs.length - (count - seen));
      seen = count;
      // A jump over many events (a review going to another decision) says none of them.
      if (fresh.length > 8) return;
      const anim = movesShown();
      const list: Moment[] = [];
      let flowersShown = new Set<number>();
      let dealtIn = false;
      // One at a time over a player: later words take the place of earlier ones. What is not a flower is waited for.
      // `who`: the player's name over the words (a win's, the one who dealt in), so that the words say whose they are
      // wherever they stand.
      const say = (side: Side, text: string, kind: CalloutKind, ms: number, who = '') => {
        const id = nextId++;
        // (補花 by the player's own seat, as who dealt in.)
        const at: CalloutAt = kind === 'drawn' ? 'center' : kind === 'dealin' || kind === 'flower' ? 'own' : 'river';
        callouts = [...callouts.filter((c) => c.side !== side || c.at !== at), { id, side, text, kind, at, who }];
        if (!stay || kind === 'flower') later(() => { callouts = callouts.filter((c) => c.id !== id); }, ms);
        if (kind !== 'flower') calloutShown(ms);
      };
      // A moment over the whole screen: in a review, short or small (the settings of the tool).
      const small = review;
      const fullMs = (key: TimingKey) => Math.round(ms(key) * (review ? REVIEW_END : 1));
      const base = evs.length - fresh.length;
      const lastBefore = (at: number, types: string[]) => [...evs.slice(0, at)].reverse().find((x) => types.includes(x.type));
      for (const [i, e] of fresh.entries()) {
        // A player's discard lands in their river: their words over it go.
        if (e.type === 'discard') {
          const side = sideOf(e.seat, me);
          if (callouts.some((c) => c.side === side && c.at === 'river')) callouts = callouts.filter((c) => c.side !== side || c.at !== 'river');
          // Its sound when it lands (the last of a review's step over several).
          if (i === fresh.map((x) => x.type).lastIndexOf('discard')) playSound('discard', anim ? ms('discard_move') : 0);
          if (e.seat === me && anim && e.tile) flyDiscard(e.tile);
          // Another's, the last that came, the next player's draw (and flowers) after it or nothing: a discard nobody
          // may claim comes with that draw (a review's step over several: the others' before it are in place).
          else if (e.seat !== me && anim && fresh.slice(i + 1).every((x) => x.type === 'draw' || x.type === 'flower')) flyIn(e.seat);
          continue;
        }
        const at = base + i;
        const taken = at === robbed && ['chi', 'pon', 'kan', 'ron'].includes(e.type);
        const word = e.type === 'declare' ? `call_${e.declared ?? 'tianting'}` : taken ? `rob_${e.type}` : WORDS[e.type];
        if (!word) continue;
        // One 補花 for a run of flowers.
        if (e.type === 'flower') {
          if (flowersShown.has(e.seat)) continue;
          flowersShown.add(e.seat);
        }
        const side = sideOf(e.seat, me);
        const moment = (o: Partial<Moment> & Pick<Moment, 'kind' | 'key' | 'ms'>): Moment => ({
          id: nextId++, type: e.type, seat: e.seat, side, word: t(word), who: names[e.seat], note: '', mine: e.seat === me, tiles: [],
          gap: -1, tile: null, src: null, from: null, dst: null, meld: -1, anchor: null, ...o,
        });
        /** A win's or a declaration's over the whole screen, or (a small review's) its words by the player. */
        const full = (o: Partial<Moment> & Pick<Moment, 'kind' | 'key'>): Moment => {
          if (!small) return moment({ ms: fullMs(o.key), ...o });
          const total = fullMs(o.key);
          const p = { in: ms('call_in'), drop: 0, out: ms('dealin_out'), hold: 0 };
          p.hold = Math.max(200, total - p.in - p.out);
          return moment({ ...o, kind: o.kind === 'sad' ? 'dealin' : 'small', ms: p.in + p.hold + p.out, parts: p,
            tiles: o.kind === 'sad' && o.tile ? [o.tile] : [], gap: -1, tile: null,
            anchor: o.kind === 'drawn' ? '.rivers' : o.kind === 'sad' ? seatOf(o.seat ?? e.seat) : riverOf(o.seat ?? e.seat) });
        };
        let has = false;
        if (anim) {
          if (e.type === 'chi' || e.type === 'pon' || e.type === 'kan') {
            // The discard it was called on: the last one before it. The tile called lies in the middle, as in the meld.
            const from = lastBefore(at, ['discard'])?.seat ?? -1;
            const tiles = e.type === 'chi' ? without(sortTiles(e.tiles ?? []), [e.tile!]) : Array(e.type === 'pon' ? 2 : 3).fill(e.tile);
            const meld = pos.players[e.seat].melds.length - 1;
            const parts = callParts();
            list.push(moment({ kind: 'call', key: 'call_in', ms: total(parts), parts, tiles, gap: 1, tile: e.tile,
              src: from >= 0 && from !== e.seat ? `[data-river="${from}"] > :last-child` : null, from: from >= 0 ? sideOf(from, me) : null,
              dst: `[data-meld="${e.seat}-${meld}"]`, meld, anchor: riverOf(e.seat) }));
            has = true;
          } else if (e.type === 'ankan' || e.type === 'kakan') {
            // A concealed kong (another's: its backs) or a pung made a kong: the fourth tile comes in at the end.
            const melds = pos.players[e.seat].melds;
            const meld = e.type === 'kakan' ? melds.findIndex((m) => m.type === 'kakan' && m.tile === e.tile) : melds.length - 1;
            const parts = callParts();
            list.push(moment({ kind: 'call', key: 'call_in', ms: total(parts), parts, tiles: Array(3).fill(e.tile), gap: 3, tile: e.tile,
              dst: meld >= 0 ? `[data-meld="${e.seat}-${meld}"]` : null, meld, anchor: riverOf(e.seat) }));
            has = true;
          } else if (e.type === 'tsumo') {
            list.push(full({ kind: 'win', key: 'tsumo', tile: e.tile, gap: 0 }));
            has = true;
          } else if (e.type === 'flower_win') {
            // The winner's flowers; 七搶一: the other player's one comes from where it lies.
            const mine = flowersOf(pos.players[e.seat]);
            const loser = view.result?.loser ?? -1;
            const other = loser >= 0 && loser !== e.seat ? flowersOf(pos.players[loser])[0] : undefined;
            const item = view.result?.winners.find((x) => x.seat === e.seat)?.items.find((x) => x.item === 'eight_flowers' || x.item === 'seven_rob_one');
            list.push(full({ kind: 'win', key: 'flower_win', tiles: other ? mine : mine.slice(0, -1), gap: other ? mine.length : mine.length - 1,
              tile: other ?? mine[mine.length - 1] ?? null, src: other ? `[data-flower="${other}"]` : null, from: other ? sideOf(loser, me) : null,
              note: item?.name ?? '' }));
            has = true;
          } else if (e.type === 'declare') {
            // The declared hand: the player's own; another's backs.
            const tiles = e.seat === me ? sortTiles(parseTiles(pos.hand)) : Array(concealedCount(view, e.seat)).fill(null);
            list.push(full({ kind: 'declare', key: 'declare', tiles }));
            has = true;
          } else if (e.type === 'exhausted') {
            list.push(full({ kind: 'drawn', key: 'drawn', who: '', neutral: true }));
            has = true;
          } else if (e.type === 'ron' && !dealtIn) {
            // Who dealt in first (the player's own: over the whole screen), then each winner in turn.
            dealtIn = true;
            const last = [...evs].reverse().find((x) => x.type === 'discard' || x.type === 'kakan');
            const loser = view.result?.loser ?? last?.seat ?? -1;
            const rons = fresh.filter((x) => x.type === 'ron');
            const winners = rons.map((x) => names[x.seat]).join('、');
            if (loser >= 0 && loser !== e.seat) {
              if (loser === me) list.push(full({ kind: 'sad', key: 'sad', seat: loser, side: 'bottom', word: t('call_deal_in'), who: names[loser], mine: true,
                note: `${winners} ${t('act_ron')}`, tile: e.tile }));
              else {
                const parts = dealinParts();
                list.push(moment({ kind: 'dealin', key: 'dealin_in', seat: loser, side: sideOf(loser, me), word: t('call_deal_in'), who: names[loser],
                  mine: false, ms: total(parts), parts, tiles: e.tile ? [e.tile] : [], anchor: seatOf(loser) }));
              }
            }
            for (const r of rons) {
              const rTaken = base + fresh.indexOf(r) === robbed;
              list.push({ ...full({ kind: 'win', key: 'win', tile: r.tile, gap: 0,
                src: last?.type !== 'kakan' && loser >= 0 && loser !== r.seat ? `[data-river="${loser}"] > :last-child` : null,
                from: loser >= 0 && loser !== r.seat ? sideOf(loser, me) : null,
                note: loser >= 0 && loser !== r.seat ? `${names[loser]} ${t('call_deal_in')}` : '' }),
                seat: r.seat, side: sideOf(r.seat, me), who: names[r.seat], mine: r.seat === me, word: t(rTaken ? 'rob_ron' : 'act_ron'),
                neutral: r.seat !== me && loser !== me,
                ...(small ? { anchor: riverOf(r.seat) } : {}) });
            }
            has = true;
          } else if (e.type === 'ron') has = true;
        }
        // (Its moment showed it, and sounds it: no words after it, even those that stay.)
        if (has) continue;
        if (e.type === 'flower') playSound('flower');
        else if (e.type === 'exhausted' || e.type === 'ron' || e.type === 'tsumo' || e.type === 'flower_win') {
          // (Its sound as the hands are shown.)
        } else if (e.type === 'kan' || e.type === 'ankan' || e.type === 'kakan') playSound('kong');
        else playSound(e.type === 'declare' ? 'declare' : 'call');
        const kind: CalloutKind = e.type === 'tsumo' || e.type === 'ron' || e.type === 'flower_win' ? 'win'
          : e.type === 'flower' ? 'flower' : e.type === 'exhausted' ? 'drawn' : 'call';
        say(e.type === 'exhausted' ? 'top' : side, t(word), kind, e.type === 'flower' ? flowerMs() : callMs(),
          kind === 'win' ? names[e.seat] : '');
        // Who dealt in: the discard (or the added kong's tile) the win was on. Once, however many won on it.
        if (e.type === 'ron' && !callouts.some((c) => c.kind === 'dealin')) {
          const from = view.result?.loser ?? [...evs].reverse().find((x) => x.type === 'discard' || x.type === 'kakan')?.seat ?? -1;
          if (from >= 0 && from !== e.seat) say(sideOf(from, me), t('call_deal_in'), 'dealin', callMs(), names[from]);
        }
      }
      // A hand's end: (who dealt in;) the hands shown, a pause, the result's moment (who won, the draw). A draw: a
      // pause before the hands are shown too. (Not before 天聽, 地聽.)
      if (anim) {
        const RESULT = ['ron', 'tsumo', 'flower_win', 'exhausted'];
        const at = list.findIndex((m) => (m.kind === 'win' || m.kind === 'drawn' || m.kind === 'small') && RESULT.includes(m.type));
        if (at >= 0) {
          const drawn = list[at].type === 'exhausted';
          const gap = (key: TimingKey, hide: boolean): Moment => ({
            id: nextId++, kind: 'gap', type: drawn ? 'exhausted' : 'ron', seat: me, side: 'bottom', word: '', who: '', note: '', mine: false,
            tiles: [], gap: -1, tile: null, src: null, from: null, dst: null, meld: -1, anchor: null, key, hide,
            ms: Math.round(ms(key) * (review ? REVIEW_END : 1)) });
          list.splice(at, 0, ...(drawn ? [gap('show_wait', true), gap('reveal', false)] : [gap('reveal', false)]));
        }
      }
      if (list.length) startMoments(list);
    });
  });

  /** A word's size on its plaque by its length: one Chinese character, two (or more), or other words (smaller). */
  const CJK_WORD = /^[\u3040-\u30ff\u3400-\u9fff]{1,4}$/;
  const wordSize = (text: string) => (!CJK_WORD.test(text) ? 'long' : [...text].length > 1 ? 'two' : 'one');
  const AREA: Record<Side, string> = { top: 'on-rt', left: 'on-rl', right: 'on-rr', bottom: 'on-rb' };
  /** A tap anywhere, or a key: the words go at once (and whoever waits for them goes on). The tap still does what it
   *  was for. */
  function skipCallouts(e: Event) {
    if (dealing) endDeal();
    // A result held for the tap: it goes on. Before that, a tap lets all of the hand's end go.
    if (tapHold !== null && moments[0]?.id === tapHold) {
      releaseHold();
      return;
    }
    tapHold = null;
    if (moments.some(holds)) resultTap.tapped = true;
    if (callouts.length) callouts = [];
    if (moments.length) moments = [];
    calloutsSkipped();
  }
  $effect(() => {
    window.addEventListener('pointerdown', skipCallouts, true);
    window.addEventListener('keydown', skipCallouts, true);
    return () => {
      window.removeEventListener('pointerdown', skipCallouts, true);
      window.removeEventListener('keydown', skipCallouts, true);
    };
  });

  // The others' tiles. Tall: each one's melds and flowers in OPP_LINES lines of their column, smaller when needed.
  // With everyone's tiles shown: as large as fits each one's room with the concealed tiles too: the player opposite in
  // one line along a wide table's top (the corner kept clear and the name plate off its width), the others in their
  // columns under their name plates.
  function oppW(side: Side, seat: number): number {
    const o = shownOf(seat);
    if (!s.wide) {
      if (!revealed) return Math.min(fitMelds(o.counts, o.flowers, s.sideW - 8, OPP_LINES, s.opp),
        fitShown(o.counts, o.flowers, 0, s.sideW - 8, s.topH - plateH(side) - 12, s.opp));
      return fitShown(o.counts, o.flowers, o.concealed, s.sideW - 8, s.topH - plateH(side) - 12, s.opp);
    }
    // Wide: the concealed tiles' backs, when they are not shown, take their room first.
    const backs = revealed || s.back === 0 ? 0 : concealedCount(view, seat);
    if (side === 'top') {
      const room = s.tableW - 12 - padL - padR - (plate.top.w || 150) - 14 - (backs ? backs * s.back + 10 : 0);
      return fitShownRow(o.counts, o.flowers, o.concealed, room, revealed ? s.opp - 1.5 : s.opp);
    }
    const width = s.sideW - 12;
    const backsH = backs ? runLines(backs, width, s.back) * s.back * TILE_RATIO + 4 : 0;
    const room = H - s.topH - meH - (side === 'left' ? cornerH + belowL : cornerR + belowR) - plateH(side) - 12 - backsH;
    return fitShown(o.counts, o.flowers, o.concealed, width, room, s.opp);
  }
  /** The player's own name plate (with a battle's clock over it) lies over the foot of the left column of a wide
   *  table: the column ends above it. The right one ends as high (or as the plate alone would). */
  let meCorner = $state(0);
  const cornerR = $derived(s.wide ? s.font * 1.7 + 14 : 0);
  const cornerH = $derived(s.wide ? Math.max(cornerR, meCorner + 8) : 0);
  const myFlowers = $derived(dealing && !dealing.flip ? [] : flowersOf(pos.players[me]));
  /** The player's melds and flowers, in the line over the hand (a wide table's as wide as the table; a tall one's
   *  beside the name plate): smaller rather than in a second line. */
  let meBadgeW = $state(0);
  const myMeldW = $derived.by(() => {
    const counts = pos.players[me].melds.map((m) => meldTiles(m).length);
    if (!counts.length && !myFlowers.length) return s.meld;
    if (s.wide) return fitMelds(counts, myFlowers.length, s.tableW - 28, 1, s.meld, 16);
    return fitMelds(counts, myFlowers.length, s.tableW - 16 - (meBadgeW || 90), 1, s.meld, 22);
  });
  const winds = $derived(view.seat_winds);
  const turnSeat = $derived(view.phase === 'over' ? -1 : view.current);
  const rivers: { side: Side; area: string }[] = [
    { side: 'top', area: 'rt' }, { side: 'left', area: 'rl' }, { side: 'right', area: 'rr' }, { side: 'bottom', area: 'rb' },
  ];
</script>

<svelte:window onkeydown={onkey} />
<!-- The player's tiles as they are dealt: face down, as many as have come (a tall table's in its rows). -->
{#snippet dealtBacks()}
  <div class="dealt" style="padding-top: {s.hand * TILE_RATIO * 0.2}px; max-width: {s.handRow > 0 ? s.handRow * s.hand + 2 : 9999}px">
    {#each { length: Math.min(dealing?.n[me] ?? 0, mine.tiles.length + (mine.drawn ? 1 : 0)) } as _}<Tile w={s.hand} />{/each}
  </div>
{/snippet}
<!-- A word on its plaque (`where`: a river's cell or the middle of the rivers; else the side of the seat it lies over). -->
{#snippet plaque(c: (typeof callouts)[number], where: string)}
  <div class="callout {c.kind} {c.at} {where} {wordSize(c.text)}" out:fade={{ duration: 160 }}>
    <span class="word"><span class="face">{c.text}</span></span>
    {#if c.who}<span class="who">{c.who}</span>{/if}
  </div>
{/snippet}
<div class="felt-bg" bind:clientWidth={W} bind:clientHeight={H}>
<div class="table" bind:this={tableEl} class:wide={s.wide} class:shown={!!revealed} style="max-width: {s.tableW}px; font-size: {s.font}px;
  --side-w: {s.sideW}px; --top-h: {s.topH}px; --me-h: {meH}px; --pad-l: {padL}px; --pad-r: {padR}px; --below-l: {belowL}px;
  --below-r: {belowR}px; --corner-h: {cornerH}px; --corner-r: {cornerR}px; --hand-w: {s.hand}px">
  {#if W > 0}
    {#if s.wide}
      <!-- The corners: the round, the wall and the streak; the screen's buttons. -->
      <div class="corner-info" bind:clientWidth={info.w} bind:clientHeight={info.h}>
        <div class="box-round">{t('round_wind', windName(setup.round))}{handLabel ? ' ' + handLabel : ''}</div>
        <div class="box-wall">{t('wall_left', pos.wall_left)}</div>
        {#if setup.streak > 0}<div class="box-streak ellipsis">{names[setup.dealer]} {t('streak_n', setup.streak)}</div>{/if}
      </div>
      {#if showCorner && corner}
        <div class="corner-buttons" bind:clientWidth={buttons.w} bind:clientHeight={buttons.h}>{@render corner()}</div>
      {/if}
    {/if}
    <!-- The others: the player opposite along the top, the next at the right, the previous at the left (on a tall
         table, the three along the top, each over their river). -->
    {#each others as side (side)}
      {@const seat = seatAt(side, me)}
      {@const p = pos.players[seat]}
      {@const hidden = dealing ? Math.min(concealedCount(view, seat), dealing.n[seat]) : concealedCount(view, seat)}
      {@const ow = oppW(side, seat)}
      <div class="seat {side}">
        <div class="plate" bind:clientWidth={plate[side].w} bind:clientHeight={plate[side].h}>
          <SeatBadge wind={winds[seat]} name={names[seat]} score={scores ? scores[seat] : null} dealer={setup.dealer === seat}
            streak={setup.streak} turn={turnSeat === seat} count={s.back === 0 && !revealed ? hidden : null} compact={!s.wide}
            away={away[seat]} note={notes?.[seat] ?? (p.declared ? { text: t(`call_${p.declared}`), level: 0 } : null)} />
        </div>
        <div class="seat-tiles">
          <Melds melds={p.melds} flowers={dealing ? [] : flowersOf(p)} w={ow} wrap={!(s.wide && side === 'top')} {seat} hide={hideMeld(seat)} />
          {#if revealed}
            <div class="concealed rack">{#each sortTiles(parseTiles(revealed[seat])) as x}<Tile tile={x} w={ow} />{/each}</div>
          {:else if s.back > 0}
            <div class="concealed backs">{#each { length: hidden } as _}<Tile w={s.back} />{/each}</div>
          {/if}
        </div>
      </div>
    {/each}

    <!-- The rivers (a tall table's around the box). -->
    <div class="center">
      <div class="rivers" class:wide={s.wide} style="--river: {s.river}px; --cols: {s.acrossCols}; --side-cols: {s.sideCols}; --mid: {s.mid}px">
        {#each rivers as r (r.side)}
          {@const seat = seatAt(r.side, me)}
          {@const across = r.side === 'top' || r.side === 'bottom'}
          <div class="river-cell {r.area}">
            <River {seat} discards={pos.players[seat].discards} w={s.river} cols={across ? s.acrossCols : s.sideCols}
              rows={across ? s.acrossRows : s.sideRows} last={lastDiscard === seat} claim={claimSeat === seat} />
          </div>
        {/each}
        {#each moments.length ? [] : callouts as c (c.id)}
          {#if c.at !== 'own'}{@render plaque(c, c.at === 'center' ? 'mid' : AREA[c.side])}{/if}
        {/each}
        {#if !s.wide}
          <div class="box" style="width: {s.boxW}px; min-height: {s.mid}px">
            <div class="box-round">{t('round_wind', windName(setup.round))}{handLabel ? ' ' + handLabel : ''}</div>
            <div class="box-wall">{t('wall_left', pos.wall_left)}</div>
            {#if setup.streak > 0}<div class="box-streak">{names[setup.dealer]} {t('streak_n', setup.streak)}</div>{/if}
            {#if corner && !withAside}<div class="box-extra">{@render corner()}</div>{/if}
          </div>
        {/if}
      </div>
    </div>

    <!-- The player: the name plate (wide: over the hand, at the left, their flowers over it), the melds, the hand. -->
    {#snippet myBadge()}
      <SeatBadge wind={winds[me]} name={names[me]} score={scores ? scores[me] : null} dealer={setup.dealer === me} streak={setup.streak}
        turn={turnSeat === me} compact={!s.wide} note={myNote} room={review ? '' : t('analysis_shanten', 3)} />
    {/snippet}
    {#if s.wide}
      <div class="me-corner" style="bottom: {meH + 2}px" bind:clientHeight={meCorner}>
        {#if status}<div class="status">{@render status()}</div>{/if}
        {@render myBadge()}
      </div>
      <!-- The hand to the left (a discard leaves the rest in place); over it, at its left, the melds, the first at the
           far left and each after it to its right (none moves when another comes), and the flowers. -->
      <div class="me" data-tour="hand">
        <div class="me-line" style="height: {s.meldLine}px">
          <Melds melds={pos.players[me].melds} flowers={myFlowers} w={myMeldW} wrap={false} seat={me} hide={hideMeld(me)} />
        </div>
        {#if dealing && !dealing.flip}{@render dealtBacks()}{:else}
        <MyHand tiles={mine.tiles} drawn={mine.drawn} {drawWait} pick={pickHand} ready={settings.readyHint && myTurn ? (view.ready ?? null) : null} turn={myTurn} w={s.hand} {canDiscard} ondiscard={discard}
          bind:selected oneClick={keys} {marks} {tagH} dropOutside={!review} onhover={(i) => (hovered = i)} {waitAt} />{/if}
      </div>
    {:else}
      <!-- Over the hand: the melds at the left (the first at the far left), the flowers after them; the name plate at
           the right end. -->
      <div class="me tall" data-tour="hand">
        <div class="me-top">
          <span class="me-melds"><Melds melds={pos.players[me].melds} flowers={myFlowers} w={myMeldW} seat={me} hide={hideMeld(me)} /></span>
          <span class="me-badge" bind:clientWidth={meBadgeW}>{@render myBadge()}</span>
        </div>
        {#if dealing && !dealing.flip}{@render dealtBacks()}{:else}
        <MyHand tiles={mine.tiles} drawn={mine.drawn} {drawWait} pick={pickHand} ready={settings.readyHint && myTurn ? (view.ready ?? null) : null} turn={myTurn} w={s.hand} {canDiscard} ondiscard={discard}
          bind:selected perRow={s.handRow} {marks} {tagH} dropOutside={!review} onhover={(i) => (hovered = i)} {waitAt} />{/if}
      </div>
      {#if status}<div class="status tall" style="bottom: {meH + 4}px">{@render status()}</div>{/if}
    {/if}

    <!-- Choices, and words over the players. -->
    {#if claims.length && onact}
      <div class="claims" style="bottom: {claimBottom ?? meH + 4}px" bind:clientHeight={claimsH}>
        <ClaimBar actions={claims} w={s.hand} {onact} {keys} claimed={claimTile} marks={choiceMarks} />
      </div>
    {/if}
    {#if onnext}
      <button class="next" class:tall={!s.wide} onclick={onnext} style={s.wide ? `bottom: ${meH + 4}px`
        : `bottom: ${6 + (handRows - 1) * s.hand * TILE_RATIO * (1 + ROW_GAP)}px; width: ${s.hand * (1 + DRAWN_GAP) - 4}px; height: ${s.hand * TILE_RATIO}px`}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" /></svg><span>{t('manual_next')}</span>{#if keys}<kbd>Space</kbd>{/if}
      </button>
    {/if}
    {#if strip}<div class="strip" style="bottom: {meH + 4 + (claims.length && onact && claimBottom === null ? claimsH + 6 : 0)}px">{@render strip()}</div>{/if}
    {#each moments.length ? [] : callouts as c (c.id)}
      {#if c.at === 'own'}{@render plaque(c, c.side)}{/if}
    {/each}
    {#if dealing && !dealing.flip && dealGeo}
      <DealFx center={dealGeo.center} tw={s.river} dealer={setup.dealer} targets={dealGeo.targets} onpacket={dealPacket} ondone={dealDone} />
    {/if}
    {#if cur && curShown}
      {#key cur.id}
        {#if cur.kind === 'gap'}<!-- (The pause before a win: nothing.) -->
        {:else if cur.kind === 'sad'}<SadFx m={cur} w={Math.max(40, s.hand * 1.15)} ms={cur.ms} />
        {:else if FULL.includes(cur.kind)}<CallFx m={curShown} w={Math.max(40, s.hand * 1.15)} ms={cur.ms} />
        {:else}<SmallFx m={cur} w={Math.max(24, Math.min(50, s.hand * 0.9))} root={tableEl} />{/if}
      {/key}
    {/if}
  {/if}
</div>
{#if withAside && aside}<div class="aside" style="width: {asideW}px">{@render aside()}</div>{/if}
{#if W > 0 && overlay}{@render overlay()}{/if}
</div>

<style>
  .felt-bg { position: relative; width: 100%; height: 100%; overflow: hidden; background: var(--felt); color: var(--on-felt);
    display: flex; justify-content: center; gap: 16px; }
  .aside { flex: none; height: 100%; overflow: auto; }
  .dealt { display: flex; flex-wrap: wrap; gap: 0; }
  /* Wide: the player opposite along the whole top (the screen's corner button, if any, before them), the other two
     beside the rivers. */
  .table { position: relative; width: 100%; height: 100%; overflow: hidden; color: var(--on-felt);
    display: grid; grid-template-columns: var(--side-w) 1fr var(--side-w); grid-template-rows: var(--top-h) 1fr var(--me-h);
    grid-template-areas: "top top top" "left center right" "me me me"; user-select: none; -webkit-user-select: none; }
  .table:not(.wide) { grid-template-columns: 1fr 1fr 1fr; grid-template-areas: "left top right" "center center center" "me me me"; }
  .seat { display: flex; flex-direction: column; gap: 4px; padding: 4px 6px; min-width: 0; min-height: 0; overflow: hidden; }
  .plate { flex: none; max-width: 100%; min-width: 0; display: flex; }
  .seat.top { grid-area: top; flex-direction: row; align-items: center; justify-content: center; gap: 10px; }
  .table.wide .seat.top { padding-left: calc(6px + var(--pad-l)); padding-right: calc(6px + var(--pad-r)); }
  .seat.left { grid-area: left; align-items: flex-start; justify-content: center; }
  .seat.right { grid-area: right; align-items: flex-end; justify-content: center; }
  .table.wide .seat.left { padding-top: calc(4px + var(--below-l)); padding-bottom: calc(4px + var(--corner-h)); }
  .table.wide .seat.right { padding-top: calc(4px + var(--below-r)); padding-bottom: calc(4px + var(--corner-r)); }
  /* A wide table's corners. */
  .corner-info, .corner-buttons { position: absolute; top: 4px; z-index: 2; max-width: calc(var(--side-w) - 12px); display: flex;
    flex-direction: column; gap: 4px; line-height: 1.25; }
  .corner-info { left: 8px; align-items: flex-start; padding: 4px 10px; border-radius: 12px; background: rgba(0, 0, 0, 0.18);
    font-size: 0.92em; max-width: max(calc(var(--side-w) - 12px), 12em); }
  .corner-info > div { max-width: 100%; }
  .corner-buttons { right: 8px; align-items: flex-end; text-align: right; }
  .seat.right .seat-tiles { align-items: flex-end; }
  .seat.right :global(.melds) { justify-content: flex-end; }
  .seat-tiles { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
  .seat.top .seat-tiles { flex-direction: row; align-items: flex-end; gap: 10px; }
  .concealed { display: flex; flex-wrap: wrap; gap: 0; }
  .concealed.backs { flex-wrap: nowrap; }
  .seat:not(.top) .concealed.backs { flex-wrap: wrap; max-width: 100%; }
  /* A hand shown: on a rack, to tell it from the melds. */
  .concealed.rack { padding: 3px; border-radius: 6px; background: rgba(0, 0, 0, 0.24); box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.22);
    max-width: 100%; }
  .table.wide .seat.top .concealed.rack { flex-wrap: nowrap; max-width: none; }
  /* Tall: the three along the top, as columns. */
  .table:not(.wide) .seat { justify-content: flex-start; padding: 4px 4px 0; }
  .table:not(.wide) .seat.top { flex-direction: column; align-items: center; gap: 4px; }
  .table:not(.wide) .seat.top .seat-tiles { flex-direction: column; align-items: center; }
  .table:not(.wide) .seat.top :global(.melds) { justify-content: center; }

  .center { grid-area: center; display: flex; align-items: center; justify-content: center; min-height: 0; min-width: 0; }
  .rivers { position: relative; display: grid; gap: 4px 6px;
    grid-template-columns: calc(var(--side-cols) * var(--river)) calc(var(--cols) * var(--river)) calc(var(--side-cols) * var(--river));
    grid-template-rows: auto auto auto; grid-template-areas: "rl rt rr" "rl box rr" "rl rb rr"; align-items: center; }
  .rivers.wide { grid-template-rows: auto auto; grid-template-areas: "rl rt rr" "rl rb rr"; row-gap: var(--mid); }
  .river-cell { min-height: 0; display: flex; }
  /* A river across keeps a row's room while it is empty (the words over it are centered in it). */
  .rt, .rb { min-height: calc(var(--river) * 1.45); }
  .rt { grid-area: rt; align-self: end; justify-content: center; }
  .rb { grid-area: rb; align-self: start; justify-content: center; }
  .rl { grid-area: rl; align-self: center; justify-content: flex-end; }
  .rr { grid-area: rr; align-self: center; justify-content: flex-start; }
  .box { grid-area: box; border-radius: 12px; background: rgba(0, 0, 0, 0.18); justify-self: center; align-self: center;
    display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 2px 14px; padding: 4px 10px; text-align: center;
    line-height: 1.25; }
  .box-round { font-weight: 700; font-size: 1.08em; }
  .box-wall { color: var(--on-felt-dim); }
  .box-streak { color: var(--highlight); font-size: 0.92em; font-weight: 700; }
  .box-extra { display: flex; flex-wrap: wrap; justify-content: center; gap: 6px; }

  /* The hand to the left (a discard leaves the rest in place); the melds over it at the left, the first at the far
     left and each after it to its right, so that none moves when another comes; the flowers after them. */
  .me { grid-area: me; display: flex; flex-direction: column; align-items: flex-start; justify-content: flex-end; gap: 4px;
    padding: 0 8px 6px 16px; min-width: 0; }
  .me-line { display: flex; align-items: flex-end; min-width: 0; max-width: 100%; }
  .me.tall { align-items: center; padding: 0 4px 6px; }
  .me-top { display: flex; align-items: flex-end; gap: 8px; min-width: 0; align-self: stretch; }
  .me-badge { flex: none; align-self: center; display: flex; max-width: 60%; margin-left: auto; }
  .me-melds { min-width: 0; display: flex; justify-content: flex-start; }
  .me-corner { position: absolute; left: 8px; z-index: 2; display: flex; flex-direction: column; align-items: flex-start; gap: 4px;
    max-width: calc(var(--side-w) - 12px); }

  .claims { position: absolute; right: 8px; left: 8px; display: flex; justify-content: flex-end; z-index: 3; pointer-events: none; }
  .strip { position: absolute; right: 8px; left: 8px; display: flex; flex-direction: column; align-items: center; gap: 4px; z-index: 3;
    pointer-events: none; }
  .strip > :global(*) { pointer-events: auto; }
  /* A wide table: between the side players, clear of the player's own name plate. */
  .table.wide .strip { left: calc(var(--side-w) + 4px); right: calc(var(--side-w) + 4px); }
  /* The others' next step (the manual pace): the turn's color, large enough for a thumb, always in the same place. */
  /* On a wide table in the corner over the hand's right end, under the right player's column: further in, the
     rivers of a long hand come down to this height (tried; it covered the right player's fifth row of discards).
     The right player's hand shown at the end can reach it on a low table, until the tap that it waits for. */
  .next { position: absolute; right: 8px; z-index: 3; display: inline-flex; align-items: center; justify-content: center; gap: 4px;
    min-height: 52px; padding: 0 20px 0 14px; border: none; border-radius: 26px; cursor: pointer; background: var(--highlight); color: #3a2a00;
    font-weight: 700; font-size: 1.125rem; box-shadow: 0 2px 6px rgba(0, 0, 0, 0.35); white-space: nowrap; }
  .next svg { width: 1.5em; height: 1.5em; fill: currentColor; flex: none; }
  .next.tall { right: 5px; flex-direction: column; gap: 0; padding: 0; border-radius: 12px; font-size: 0.8125rem; line-height: 1.1; }
  .next.tall svg { width: 2em; height: 2em; }
  .next:active { filter: brightness(0.92); }
  .next kbd { font: inherit; font-size: 0.7rem; font-weight: 500; padding: 0 4px; border-radius: 4px; border: 1px solid currentColor; opacity: 0.7; }
  .status { pointer-events: none; }
  .status.tall { position: absolute; left: 8px; z-index: 2; }
  /* The words over a player: one plaque a word, its corners cut (no tile has them), a lip under it and a fine line
     inside its edge, laid down a little askew: a call's gold, a win's vermilion (and larger), the one who dealt in
     dark, a draw's slate, a flower's small and jade. About twice a tile of the hand wide. Under a win's and under 放槍,
     whose they are, on a tab over the plaque's lower edge. */
  .callout { position: absolute; z-index: 4; pointer-events: none; white-space: nowrap; display: flex; flex-direction: column;
    align-items: center; font-size: clamp(2.1rem, calc(var(--hand-w) * 1.1), 5rem); animation: pop 0.32s ease-out both;
    --face: #f6b93b; --ink: #3b2200; --back: #b9780f; --line: rgba(59, 34, 0, 0.42); --cut: 0.24em; }
  .callout.two { font-size: clamp(1.7rem, calc(var(--hand-w) * 0.88), 4rem); }
  .callout.long { font-size: clamp(1.2rem, calc(var(--hand-w) * 0.6), 2.7rem); }
  .callout.win { --face: #d5382d; --ink: #fff6dc; --back: #8c1c15; --line: rgba(255, 214, 120, 0.9);
    font-size: clamp(2.4rem, calc(var(--hand-w) * 1.25), 5.7rem); }
  .callout.win.two { font-size: clamp(1.9rem, calc(var(--hand-w) * 0.98), 4.5rem); }
  .callout.win.long { font-size: clamp(1.3rem, calc(var(--hand-w) * 0.66), 3rem); }
  .callout.dealin { --face: #2e383e; --ink: #ffb3a7; --back: #12171a; --line: rgba(255, 179, 167, 0.45);
    font-size: clamp(1.6rem, calc(var(--hand-w) * 0.8), 3.7rem); }
  .callout.dealin.long { font-size: clamp(1.1rem, calc(var(--hand-w) * 0.55), 2.5rem); }
  .callout.drawn { --face: #5b7684; --ink: #fff; --back: #384b55; --line: rgba(255, 255, 255, 0.45); }
  .callout.flower { --face: #2b5b4c; --ink: #d8f3e5; --back: #17382e; --line: rgba(216, 243, 229, 0.45); --cut: 0.3em;
    font-size: clamp(1.05rem, calc(var(--hand-w) * 0.44), 2rem); }
  .word { position: relative; display: block; rotate: -2deg; padding-bottom: 0.09em;
    filter: drop-shadow(0 0.07em 0.09em rgba(0, 0, 0, 0.5)); }
  .word::before, .face { clip-path: polygon(var(--cut) 0, calc(100% - var(--cut)) 0, 100% var(--cut),
    100% calc(100% - var(--cut)), calc(100% - var(--cut)) 100%, var(--cut) 100%, 0 calc(100% - var(--cut)), 0 var(--cut)); }
  /* The lip: the same shape, a little lower. */
  .word::before { content: ''; position: absolute; inset: 0.09em 0 0; background: var(--back); }
  .face { position: relative; display: block; height: 1.5em; line-height: 1.5em; padding: 0 0.42em; background: var(--face);
    color: var(--ink); font-weight: 900; text-align: center;
    font-family: 'Noto Serif CJK TC', 'Noto Serif TC', 'Source Han Serif TC', 'Songti TC', 'PMingLiU', serif; }
  .two .face { padding: 0 0.3em 0 0.36em; letter-spacing: 0.06em; }
  .long .face { padding: 0 0.6em; }
  .flower .face { height: 1.6em; line-height: 1.6em; font-weight: 800; }
  /* The fine line inside the edge: a ring of the plaque's shape. */
  .face::after { content: ''; position: absolute; inset: 0.085em; background: var(--line); --c: calc(var(--cut) - 0.035em); --t: 0.035em;
    --k: calc(var(--c) + 0.41 * var(--t));
    clip-path: polygon(evenodd, var(--c) 0, calc(100% - var(--c)) 0, 100% var(--c), 100% calc(100% - var(--c)), calc(100% - var(--c)) 100%,
      var(--c) 100%, 0 calc(100% - var(--c)), 0 var(--c), var(--c) 0,
      var(--k) var(--t), calc(100% - var(--k)) var(--t), calc(100% - var(--t)) var(--k), calc(100% - var(--t)) calc(100% - var(--k)),
      calc(100% - var(--k)) calc(100% - var(--t)), var(--k) calc(100% - var(--t)), var(--t) calc(100% - var(--k)), var(--t) var(--k),
      var(--k) var(--t)); }
  .who { position: relative; margin-top: -0.75em; padding: 0.2em 0.9em 0.24em; border-radius: 999px; background: #18221f; color: #fff6dc;
    border: 1.5px solid var(--line); font-weight: 700; line-height: 1.1; font-size: clamp(0.8rem, 0.3em, 1rem); }
  /* Over the player's river: the cell of the rivers' grid is the room they are centered in. */
  .callout.river, .callout.center { left: 50%; top: 50%; translate: -50% -50%; }
  .callout.on-rt { grid-area: rt; }
  .callout.on-rb { grid-area: rb; }
  .callout.on-rl { grid-area: rl; }
  .callout.on-rr { grid-area: rr; }
  .callout.mid { grid-area: box; }
  .rivers.wide .callout.mid { grid-area: 1 / 2 / 3 / 3; }
  /* Over the player's own tiles: 放槍. A wide table: beside the rivers and along the top; a tall one: at the top of
     the others' columns. The player's own: over the foot of their hand. */
  .callout.own.bottom { left: 50%; bottom: 8px; translate: -50% 0; }
  .callout.own.top { left: 50%; top: 2px; translate: -50% 0; }
  .callout.own.left { left: calc(var(--side-w) / 2); top: 45%; translate: -50% -50%; }
  .callout.own.right { right: calc(var(--side-w) / 2); top: 45%; translate: 50% -50%; }
  .table:not(.wide) .callout.own.left { left: 16.67%; top: 2px; translate: -50% 0; }
  .table:not(.wide) .callout.own.right { right: 16.67%; top: 2px; translate: 50% 0; }
  /* The words come in at once and stay; they go when their time is up or at a tap (the fade of the markup). */
  @keyframes pop { 0% { opacity: 0; scale: 0.5; } 50% { opacity: 1; scale: 1.15; } 100% { opacity: 1; scale: 1; } }
  @media (prefers-reduced-motion: reduce) { .callout { animation: none; } }
</style>
