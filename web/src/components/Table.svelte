<!--
  The table as one seat sees it (a seat's `view`): the player at the bottom with their hand, the others around, every
  river in the middle. A wide table has the round, the wall and the dealer's streak in its top-left corner and the
  screen's `corner` (its buttons) in its top-right one, nothing between the rivers; a tall one has them in a box
  between the rivers (its screen's buttons are in its own bar). Its sizes come from its own room (lib/layout.ts).
  Calls, wins and flowers show in large words over the player who made them, briefly (and 放槍 over the one who dealt
  in); new discards, melds and the drawn tile come in moving.

  The player's turn to discard: the hand in a pulsing frame of the turn's color, and with no tile drawn (the dealer's
  first discard, after a call) its last tile set apart as a drawn one is. A discard the player may claim stands out
  in its river (larger, pulsing), and the claim buttons show their tiles large, the claimed one lit.
  A wide table's own flowers lie over the hand, above the player's name plate (in the hand's row they took its room).

  `revealed`: every seat's concealed tiles, to show (a finished hand, a replay showing all). The others' then lie on
  a rack (a lighter strip) after their melds, their tiles as large as their room allows: the player opposite in one
  line along the top of a wide table; on a tall one the others' strip grows for them (the rivers give the room).
  `keys`: a computer's: a click discards, and the keyboard plays (← → choose a tile, Enter discards it, Esc lets go;
  H wins, G kongs, P pungs, C chows, 1 2 3 a chow's way, Space passes).
  `aside`: a panel at the right (`asideW` px wide), shown when the window has room for it beside the table (a computer's);
  the `corner` is not shown then (the panel has its buttons).
  A review's: `marks` (tags and danger on the hand's tiles, lib/review.ts), `notes` (a few words on each name plate),
  `strip` (a line over the hand: the options that are not discards, a tile's figures), `onpick` (the tile tapped).
-->
<script lang="ts">
  import { untrack, type Snippet } from 'svelte';
  import { t } from '../lib/i18n.svelte';
  import {
    DRAWN_GAP, fitMelds, fitShown, fitShownRow, OPP_LINES, runLines, SHOWN_RIVER, shownHeight, tableSizes, tallTopRoom, type TableSizes,
  } from '../lib/layout';
  import { concealedCount, meldTiles, seatAt, sideOf, flowersOf, type Side } from '../lib/seats';
  import type { TileMark } from '../lib/review';
  import { parseTiles, sortTiles, windName, without, type Tile as T } from '../lib/tiles';
  import type { Action, GameEvent, View } from '../lib/types';
  import ClaimBar from './ClaimBar.svelte';
  import Melds from './Melds.svelte';
  import MyHand from './MyHand.svelte';
  import River from './River.svelte';
  import SeatBadge from './SeatBadge.svelte';
  import Tile, { TILE_RATIO } from './Tile.svelte';

  let {
    view, me, names, scores = null, revealed = null, interactive = true, onact, handLabel = '', maxHand = 64, keys = false,
    away = [false, false, false, false], status, overlay, corner, aside, selected = $bindable(null), asideW = 300,
    marks = null, notes = null, strip, onpick,
  }: {
    view: View; me: number; names: string[]; scores?: number[] | null; revealed?: string[] | null; interactive?: boolean;
    onact?: (a: Action) => void; handLabel?: string; maxHand?: number; keys?: boolean; away?: boolean[];
    status?: Snippet; overlay?: Snippet; corner?: Snippet; aside?: Snippet; selected?: number | null; asideW?: number;
    marks?: Map<T, TileMark> | null; notes?: ({ text: string; level: number } | null)[] | null; strip?: Snippet;
    onpick?: (tile: T | null) => void;
  } = $props();

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
  const tagH = $derived(marks ? TAG_H : 0);
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

  // The player's tiles: the drawn one apart. With none drawn on the player's turn to discard (the dealer's first
  // discard, after a call), the last one apart as a drawn one is: the hand says whose turn it is.
  const mine = $derived.by(() => {
    const all = revealed ? parseTiles(revealed[me]) : parseTiles(pos.hand);
    const drawn = !revealed && pos.drawn && view.phase === 'self' && view.current === me ? pos.drawn : null;
    const tiles = sortTiles(drawn ? without(all, [drawn]) : all);
    const apart = !revealed && !drawn && view.phase === 'self' && view.current === me && tiles.length % 3 === 2 ? tiles.pop()! : null;
    return { tiles, drawn: drawn ?? apart, apart: !!apart };
  });
  $effect(() => {
    const i = selected;
    onpick?.(i === null ? null : i < mine.tiles.length ? mine.tiles[i] : mine.drawn);
  });
  const discardable = $derived(new Set(view.my_turn ? view.legal.filter((a) => a.type === 'discard').map((a) => a.tile) : []));
  const canDiscard = (tile: string) => interactive && discardable.has(tile);
  function discard(tile: string) {
    onact?.({ type: 'discard', tile });
  }
  const claims = $derived(interactive && view.my_turn ? view.legal.filter((a) => a.type !== 'discard') : []);
  /** The player's turn to discard: the hand's frame pulses. */
  const myTurn = $derived(interactive && view.my_turn && discardable.size > 0);
  /** The tile the player may claim (a discard, or a kong's added tile), while they may: it stands out. */
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
    if (!keys || !interactive || !view.my_turn || e.ctrlKey || e.metaKey || e.altKey) return;
    const el = e.target as HTMLElement | null;
    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) return;
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
  let callouts = $state<{ id: number; side: Side; text: string; big: boolean }[]>([]);
  let seen = -1;
  let nextId = 0;
  const WORDS: Partial<Record<GameEvent['type'], string>> = {
    chi: 'act_chi', pon: 'act_pon', kan: 'act_kan', ankan: 'act_ankan', kakan: 'act_kakan', tsumo: 'act_tsumo', ron: 'act_ron',
    flower: 'call_flower', flower_win: 'call_flower_win', exhausted: 'call_exhausted',
  };
  $effect(() => {
    const evs = view.events;
    const count = view.event_count;
    untrack(() => {
      // The first view shows what is there, without words for what came before it.
      if (seen < 0 || count < seen) {
        seen = count;
        return;
      }
      const fresh = evs.slice(evs.length - (count - seen));
      seen = count;
      // A jump over many events (a review going to another decision) says none of them.
      if (fresh.length > 8) return;
      let flowersShown = new Set<number>();
      let dealtIn = false;
      const say = (side: Side, text: string, big: boolean) => {
        const id = nextId++;
        callouts = [...callouts, { id, side, text, big }];
        setTimeout(() => { callouts = callouts.filter((c) => c.id !== id); }, big ? 2000 : 900);
      };
      for (const e of fresh) {
        const word = e.type === 'declare' ? `call_${e.declared ?? 'tianting'}` : WORDS[e.type];
        if (!word) continue;
        // One 補花 for a run of flowers.
        if (e.type === 'flower') {
          if (flowersShown.has(e.seat)) continue;
          flowersShown.add(e.seat);
        }
        const big = e.type === 'tsumo' || e.type === 'ron' || e.type === 'flower_win';
        say(e.type === 'exhausted' ? 'top' : sideOf(e.seat, me), t(word), big);
        // Who dealt in: the discard (or the added kong's tile) the win was on. Once, however many won on it.
        if (e.type === 'ron' && !dealtIn) {
          dealtIn = true;
          const from = view.result?.loser ?? [...evs].reverse().find((x) => x.type === 'discard' || x.type === 'kakan')?.seat ?? -1;
          if (from >= 0 && from !== e.seat) say(sideOf(from, me), t('call_deal_in'), false);
        }
      }
    });
  });

  // The others' tiles. Tall: each one's melds and flowers in OPP_LINES lines of their column, smaller when needed.
  // With everyone's tiles shown: as large as fits each one's room with the concealed tiles too: the player opposite in
  // one line along a wide table's top (the corner kept clear and the name plate off its width), the others in their
  // columns under their name plates.
  function oppW(side: Side, seat: number): number {
    const o = shownOf(seat);
    if (!s.wide) {
      if (!revealed) return fitMelds(o.counts, o.flowers, s.sideW - 8, OPP_LINES, s.opp);
      return fitShown(o.counts, o.flowers, o.concealed, s.sideW - 8, s.topH - plateH(side) - 12, s.opp);
    }
    // Wide: the concealed tiles' backs, when they are not shown, take their room first.
    const backs = revealed ? 0 : concealedCount(view, seat);
    if (side === 'top') {
      const room = s.tableW - 12 - padL - padR - (plate.top.w || 150) - 14 - (backs ? backs * s.back + 10 : 0);
      return fitShownRow(o.counts, o.flowers, o.concealed, room, revealed ? s.opp - 1.5 : s.opp);
    }
    const width = s.sideW - 12;
    const backsH = backs ? runLines(backs, width, s.back) * s.back * TILE_RATIO + 4 : 0;
    const room = H - s.topH - meH - (side === 'left' ? cornerH + belowL : cornerR + belowR) - plateH(side) - 12 - backsH;
    return fitShown(o.counts, o.flowers, o.concealed, width, room, s.opp);
  }
  /** The player's own name plate (with their flowers over it, and a battle's clock) lies over the foot of the left
   *  column of a wide table: the column ends above it. The right one ends as high (or as the plate alone would). */
  let meCorner = $state(0);
  const cornerR = $derived(s.wide ? s.font * 1.7 + 14 : 0);
  const cornerH = $derived(s.wide ? Math.max(cornerR, meCorner + 8) : 0);
  const myFlowers = $derived(flowersOf(pos.players[me]));
  /** The player's melds: a wide table's in the hand's row, smaller when they would take the hand's room (kongs); a
   *  tall one's in the line over the hand beside the name plate, smaller rather than in a second line. */
  let meBadgeW = $state(0);
  const myMeldW = $derived.by(() => {
    const counts = pos.players[me].melds.map((m) => meldTiles(m).length);
    if (!counts.length) return s.meld;
    if (s.wide) return fitShownRow(counts, 0, 0, s.tableW - 28 - (mine.tiles.length + 1 + DRAWN_GAP) * s.hand, s.meld);
    return fitMelds(counts, myFlowers.length, s.tableW - 16 - (meBadgeW || 90), 1, s.meld, 22);
  });
  const winds = $derived(view.seat_winds);
  const turnSeat = $derived(view.phase === 'over' ? -1 : view.current);
  const rivers: { side: Side; area: string }[] = [
    { side: 'top', area: 'rt' }, { side: 'left', area: 'rl' }, { side: 'right', area: 'rr' }, { side: 'bottom', area: 'rb' },
  ];
</script>

<svelte:window onkeydown={onkey} />
<div class="felt-bg" bind:clientWidth={W} bind:clientHeight={H}>
<div class="table" class:wide={s.wide} class:shown={!!revealed} style="max-width: {s.tableW}px; font-size: {s.font}px;
  --side-w: {s.sideW}px; --top-h: {s.topH}px; --me-h: {meH}px; --pad-l: {padL}px; --pad-r: {padR}px; --below-l: {belowL}px;
  --below-r: {belowR}px; --corner-h: {cornerH}px; --corner-r: {cornerR}px">
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
      {@const hidden = concealedCount(view, seat)}
      {@const ow = oppW(side, seat)}
      <div class="seat {side}">
        <div class="plate" bind:clientWidth={plate[side].w} bind:clientHeight={plate[side].h}>
          <SeatBadge wind={winds[seat]} name={names[seat]} score={scores ? scores[seat] : null} dealer={setup.dealer === seat}
            streak={setup.streak} turn={turnSeat === seat} count={s.back === 0 && !revealed ? hidden : null} compact={!s.wide}
            away={away[seat]} note={notes?.[seat] ?? (p.declared ? { text: t(`call_${p.declared}`), level: 0 } : null)} />
        </div>
        <div class="seat-tiles">
          <Melds melds={p.melds} flowers={flowersOf(p)} w={ow} wrap={!(s.wide && side === 'top')} />
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
            <River discards={pos.players[seat].discards} w={s.river} cols={across ? s.acrossCols : s.sideCols}
              rows={across ? s.acrossRows : s.sideRows} last={lastDiscard === seat} claim={claimSeat === seat} from={r.side} />
          </div>
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
        turn={turnSeat === me} compact={!s.wide} note={pos.players[me].declared ? { text: t(`call_${pos.players[me].declared}`), level: 0 } : null} />
    {/snippet}
    {#if s.wide}
      <div class="me-corner" style="bottom: {meH + 2}px" bind:clientHeight={meCorner}>
        {#if status}<div class="status">{@render status()}</div>{/if}
        {#if myFlowers.length}<div class="me-flowers"><Melds melds={[]} flowers={myFlowers} w={s.opp} /></div>{/if}
        {@render myBadge()}
      </div>
      <div class="me" data-tour="hand">
        <Melds melds={pos.players[me].melds} w={myMeldW} wrap={false} />
        <MyHand tiles={mine.tiles} drawn={mine.drawn} apart={mine.apart} turn={myTurn} w={s.hand} {canDiscard} ondiscard={discard}
          bind:selected oneClick={keys} {marks} {tagH} />
      </div>
    {:else}
      <div class="me tall" data-tour="hand">
        <div class="me-top">
          <span class="me-badge" bind:clientWidth={meBadgeW}>{@render myBadge()}</span>
          <Melds melds={pos.players[me].melds} flowers={myFlowers} w={myMeldW} />
        </div>
        <MyHand tiles={mine.tiles} drawn={mine.drawn} apart={mine.apart} turn={myTurn} w={s.hand} {canDiscard} ondiscard={discard}
          bind:selected perRow={s.handRow} {marks} {tagH} />
      </div>
      {#if status}<div class="status tall" style="bottom: {meH + 4}px">{@render status()}</div>{/if}
    {/if}

    <!-- Choices, and words over the players. -->
    {#if claims.length && onact}
      <div class="claims" style="bottom: {meH + 4}px"><ClaimBar actions={claims} w={s.hand} {onact} {keys} claimed={claimTile} /></div>
    {/if}
    {#if strip}<div class="strip" style="bottom: {meH + 4}px">{@render strip()}</div>{/if}
    {#each callouts as c (c.id)}
      <div class="callout {c.side}" class:big={c.big}>{c.text}</div>
    {/each}
  {/if}
</div>
{#if withAside && aside}<div class="aside" style="width: {asideW}px">{@render aside()}</div>{/if}
{#if W > 0 && overlay}{@render overlay()}{/if}
</div>

<style>
  .felt-bg { position: relative; width: 100%; height: 100%; overflow: hidden; background: var(--felt); color: var(--on-felt);
    display: flex; justify-content: center; gap: 16px; }
  .aside { flex: none; height: 100%; overflow: auto; }
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
  .concealed.rack { padding: 3px; border-radius: 6px; background: rgba(255, 255, 255, 0.17); max-width: 100%; }
  .table.wide .seat.top .concealed.rack { flex-wrap: nowrap; max-width: none; }
  /* Tall: the three along the top, as columns. */
  .table:not(.wide) .seat { justify-content: flex-start; padding: 4px 4px 0; }
  .table:not(.wide) .seat.top { flex-direction: column; align-items: center; gap: 4px; }
  .table:not(.wide) .seat.top .seat-tiles { flex-direction: column; align-items: center; }
  .table:not(.wide) .seat.top :global(.melds) { justify-content: center; }

  .center { grid-area: center; display: flex; align-items: center; justify-content: center; min-height: 0; min-width: 0; }
  .rivers { display: grid; gap: 4px 6px;
    grid-template-columns: calc(var(--side-cols) * var(--river)) calc(var(--cols) * var(--river)) calc(var(--side-cols) * var(--river));
    grid-template-rows: auto auto auto; grid-template-areas: "rl rt rr" "rl box rr" "rl rb rr"; align-items: center; }
  .rivers.wide { grid-template-rows: auto auto; grid-template-areas: "rl rt rr" "rl rb rr"; row-gap: var(--mid); }
  .river-cell { min-height: 0; display: flex; }
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

  .me { grid-area: me; display: flex; align-items: flex-end; justify-content: center; gap: 12px; padding: 0 8px 6px; min-width: 0; }
  .me.tall { flex-direction: column; align-items: center; justify-content: flex-end; gap: 4px; padding: 0 4px 6px; }
  .me-top { display: flex; align-items: flex-end; gap: 8px; min-width: 0; align-self: stretch; }
  .me-badge { flex: none; align-self: center; display: flex; max-width: 60%; }
  .me-corner { position: absolute; left: 8px; z-index: 2; display: flex; flex-direction: column; align-items: flex-start; gap: 4px;
    max-width: calc(var(--side-w) - 12px); }
  .me-flowers { padding-left: 4px; }

  .claims { position: absolute; right: 8px; left: 8px; display: flex; justify-content: flex-end; z-index: 3; pointer-events: none; }
  .strip { position: absolute; right: 8px; left: 8px; display: flex; flex-direction: column; align-items: center; gap: 4px; z-index: 3;
    pointer-events: none; }
  .strip > :global(*) { pointer-events: auto; }
  .status { pointer-events: none; }
  .status.tall { position: absolute; left: 8px; z-index: 2; }
  .callout { position: absolute; z-index: 4; pointer-events: none; font-weight: 900; font-size: 2.2rem; color: #fff;
    text-shadow: 0 0 6px #000, 0 2px 0 #b71c1c, 0 0 14px rgba(255, 193, 7, 0.8); animation: pop 0.9s ease-out both; white-space: nowrap; }
  .callout.big { font-size: 3.4rem; animation-duration: 1.6s; }
  .callout.bottom { left: 50%; bottom: calc(var(--me-h) + 10px); transform: translateX(-50%); }
  .callout.top { left: 50%; top: calc(var(--top-h) + 6px); transform: translateX(-50%); }
  .callout.left { left: calc(var(--side-w) * 0.5); top: 42%; }
  .callout.right { right: calc(var(--side-w) * 0.5); top: 42%; }
  .table:not(.wide) .callout.left { left: 4%; top: calc(var(--top-h) * 0.5); }
  .table:not(.wide) .callout.right { right: 4%; top: calc(var(--top-h) * 0.5); }
  .table:not(.wide) .callout.top { top: calc(var(--top-h) * 0.5); }
  @keyframes pop { 0% { opacity: 0; scale: 0.5; } 15% { opacity: 1; scale: 1.15; } 30% { scale: 1; } 80% { opacity: 1; } 100% { opacity: 0; } }
</style>
