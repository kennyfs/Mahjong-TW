<!--
  The table as one seat sees it (a seat's `view`): the player at the bottom with their hand, the others around, every
  river in the middle around a box with the round, the wall and whose turn it is. Its sizes come from its own room
  (lib/layout.ts). Calls, wins and flowers show in large words over the player who made them, briefly; new discards,
  melds and the drawn tile come in moving.

  `revealed`: every seat's concealed tiles, to show (a finished hand, a replay showing all).
  `keys`: a computer's: a click discards, and the keyboard plays (← → choose a tile, Enter discards it, Esc lets go;
  H wins, G kongs, P pungs, C chows, 1 2 3 a chow's way, Space passes).
  `aside`: a panel at the right, shown when the window has room for it beside the table (a computer's).
-->
<script lang="ts">
  import { untrack, type Snippet } from 'svelte';
  import { t } from '../lib/i18n.svelte';
  import { fitMelds, OPP_LINES, tableSizes } from '../lib/layout';
  import { concealedCount, meldTiles, seatAt, sideOf, flowersOf, type Side } from '../lib/seats';
  import { parseTiles, sortTiles, windName, without } from '../lib/tiles';
  import type { Action, GameEvent, View } from '../lib/types';
  import ClaimBar from './ClaimBar.svelte';
  import Melds from './Melds.svelte';
  import MyHand from './MyHand.svelte';
  import River from './River.svelte';
  import SeatBadge from './SeatBadge.svelte';
  import Tile, { TILE_RATIO } from './Tile.svelte';

  let {
    view, me, names, scores = null, revealed = null, interactive = true, onact, handLabel = '', maxHand = 64, keys = false,
    away = [false, false, false, false], status, overlay, corner, aside, selected = $bindable(null),
  }: {
    view: View; me: number; names: string[]; scores?: number[] | null; revealed?: string[] | null; interactive?: boolean;
    onact?: (a: Action) => void; handLabel?: string; maxHand?: number; keys?: boolean; away?: boolean[];
    status?: Snippet; overlay?: Snippet; corner?: Snippet; aside?: Snippet; selected?: number | null;
  } = $props();

  const ASIDE_W = 300;
  let W = $state(0);
  let H = $state(0);
  const full = $derived(tableSizes(W, H, maxHand));
  const withAside = $derived(!!aside && full.wide && W - full.tableW >= ASIDE_W + 24);
  const s = $derived(withAside ? tableSizes(W - ASIDE_W - 16, H, maxHand) : full);
  const pos = $derived(view.position);
  const setup = $derived(pos.setup);

  // The player's tiles: the drawn one apart.
  const mine = $derived.by(() => {
    const all = revealed ? parseTiles(revealed[me]) : parseTiles(pos.hand);
    const drawn = !revealed && pos.drawn && view.phase === 'self' && view.current === me ? pos.drawn : null;
    return { tiles: sortTiles(drawn ? without(all, [drawn]) : all), drawn };
  });
  const discardable = $derived(new Set(view.my_turn ? view.legal.filter((a) => a.type === 'discard').map((a) => a.tile) : []));
  const canDiscard = (tile: string) => interactive && discardable.has(tile);
  function discard(tile: string) {
    onact?.({ type: 'discard', tile });
  }
  const claims = $derived(interactive && view.my_turn ? view.legal.filter((a) => a.type !== 'discard') : []);

  // A computer's keys.
  function claimFor(key: string): Action | undefined {
    const of = (...types: string[]) => claims.find((a) => types.includes(a.type));
    const chis = claims.filter((a) => a.type === 'chi');
    switch (key) {
      case 'h': return of('ron', 'tsumo');
      case 'g': return of('kan', 'ankan', 'kakan');
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
      let flowersShown = new Set<number>();
      for (const e of fresh) {
        const word = WORDS[e.type];
        if (!word) continue;
        // One 補花 for a run of flowers.
        if (e.type === 'flower') {
          if (flowersShown.has(e.seat)) continue;
          flowersShown.add(e.seat);
        }
        const id = nextId++;
        const big = e.type === 'tsumo' || e.type === 'ron' || e.type === 'flower_win';
        callouts = [...callouts, { id, side: e.type === 'exhausted' ? 'top' : sideOf(e.seat, me), text: t(word), big }];
        setTimeout(() => { callouts = callouts.filter((c) => c.id !== id); }, big ? 1600 : 900);
      }
    });
  });

  const others: Side[] = ['right', 'top', 'left'];
  // Tall: each of the others' melds and flowers in OPP_LINES lines of their column, their tiles smaller when needed.
  function oppW(seat: number): number {
    if (s.wide) return s.opp;
    const p = pos.players[seat];
    return fitMelds(p.melds.map((m) => meldTiles(m).length), flowersOf(p).length, s.sideW - 8, OPP_LINES, s.opp);
  }
  const winds = $derived(view.seat_winds);
  const turnSeat = $derived(view.phase === 'over' ? -1 : view.current);
  const rivers: { side: Side; area: string }[] = [
    { side: 'top', area: 'rt' }, { side: 'left', area: 'rl' }, { side: 'right', area: 'rr' }, { side: 'bottom', area: 'rb' },
  ];
</script>

<svelte:window onkeydown={onkey} />
<div class="felt-bg" bind:clientWidth={W} bind:clientHeight={H}>
<div class="table" class:wide={s.wide} style="max-width: {s.tableW}px; font-size: {s.font}px;
  --side-w: {s.sideW}px; --top-h: {s.topH}px; --me-h: {s.meH}px">
  {#if W > 0}
    <!-- The others: the player opposite on top, the next at the right, the previous at the left (on a tall table, the
         three along the top, each over their river). -->
    {#each others as side (side)}
      {@const seat = seatAt(side, me)}
      {@const p = pos.players[seat]}
      {@const hidden = concealedCount(view, seat)}
      <div class="seat {side}">
        <SeatBadge wind={winds[seat]} name={names[seat]} score={scores ? scores[seat] : null} dealer={setup.dealer === seat}
          streak={setup.streak} turn={turnSeat === seat} count={s.back === 0 && !revealed ? hidden : null} compact={!s.wide}
          away={away[seat]} />
        <div class="seat-tiles">
          <Melds melds={p.melds} flowers={flowersOf(p)} w={oppW(seat)} />
          {#if revealed}
            <div class="concealed">{#each sortTiles(parseTiles(revealed[seat])) as x}<Tile tile={x} w={s.opp} />{/each}</div>
          {:else if s.back > 0}
            <div class="concealed backs">{#each { length: hidden } as _}<Tile w={s.back} />{/each}</div>
          {/if}
        </div>
      </div>
    {/each}

    <!-- The rivers around the box. -->
    <div class="center">
      <div class="rivers" style="--river: {s.river}px; --cols: {s.acrossCols}; --side-cols: {s.sideCols}">
        {#each rivers as r (r.side)}
          {@const seat = seatAt(r.side, me)}
          {@const across = r.side === 'top' || r.side === 'bottom'}
          <div class="river-cell {r.area}">
            <River discards={pos.players[seat].discards} w={s.river} cols={across ? s.acrossCols : s.sideCols}
              rows={across ? s.acrossRows : s.sideRows} last={lastDiscard === seat} from={r.side} />
          </div>
        {/each}
        <div class="box" style="width: {s.boxW}px; min-height: {s.boxH}px">
          <div class="box-round">{t('round_wind', windName(setup.round))}{handLabel ? ' ' + handLabel : ''}</div>
          <div class="box-wall">{t('wall_left', pos.wall_left)}</div>
          {#if setup.streak > 0}<div class="box-streak">{names[setup.dealer]} {t('streak_n', setup.streak)}</div>{/if}
          {#if corner && !withAside}<div class="box-extra">{@render corner()}</div>{/if}
        </div>
      </div>
    </div>

    <!-- The player: the name plate (wide: over the hand, at the left), the melds and flowers, the hand. -->
    {#snippet myBadge()}
      <SeatBadge wind={winds[me]} name={names[me]} score={scores ? scores[me] : null} dealer={setup.dealer === me} streak={setup.streak}
        turn={turnSeat === me} compact={!s.wide} />
    {/snippet}
    {#if s.wide}
      <div class="me-corner" style="bottom: {s.meH + 2}px">
        {#if status}<div class="status">{@render status()}</div>{/if}
        {@render myBadge()}
      </div>
      <div class="me">
        <Melds melds={pos.players[me].melds} flowers={flowersOf(pos.players[me])} w={s.meld} wrap={false} />
        <MyHand tiles={mine.tiles} drawn={mine.drawn} w={s.hand} {canDiscard} ondiscard={discard} bind:selected oneClick={keys} />
      </div>
    {:else}
      <div class="me tall">
        <div class="me-top">
          {@render myBadge()}
          <Melds melds={pos.players[me].melds} flowers={flowersOf(pos.players[me])} w={s.meld} />
        </div>
        <MyHand tiles={mine.tiles} drawn={mine.drawn} w={s.hand} {canDiscard} ondiscard={discard} bind:selected perRow={s.handRow} />
      </div>
      {#if status}<div class="status tall" style="bottom: {s.meH + 4}px">{@render status()}</div>{/if}
    {/if}

    <!-- Choices, and words over the players. -->
    {#if claims.length && onact}
      <div class="claims" style="bottom: {s.meH + 4}px"><ClaimBar actions={claims} w={s.hand} {onact} {keys} /></div>
    {/if}
    {#each callouts as c (c.id)}
      <div class="callout {c.side}" class:big={c.big}>{c.text}</div>
    {/each}
  {/if}
</div>
{#if withAside && aside}<div class="aside" style="width: {ASIDE_W}px">{@render aside()}</div>{/if}
{#if W > 0 && overlay}{@render overlay()}{/if}
</div>

<style>
  .felt-bg { position: relative; width: 100%; height: 100%; overflow: hidden; background: var(--felt); color: var(--on-felt);
    display: flex; justify-content: center; gap: 16px; }
  .aside { flex: none; height: 100%; overflow: auto; }
  .table { position: relative; width: 100%; height: 100%; overflow: hidden; color: var(--on-felt);
    display: grid; grid-template-columns: var(--side-w) 1fr var(--side-w); grid-template-rows: var(--top-h) 1fr var(--me-h);
    grid-template-areas: "left top right" "left center right" "me me me"; user-select: none; -webkit-user-select: none; }
  .table:not(.wide) { grid-template-columns: 1fr 1fr 1fr; grid-template-areas: "left top right" "center center center" "me me me"; }
  .seat { display: flex; flex-direction: column; gap: 4px; padding: 4px 6px; min-width: 0; min-height: 0; overflow: hidden; }
  .seat.top { grid-area: top; flex-direction: row; align-items: center; justify-content: center; gap: 10px; }
  .seat.top > :global(.badge-row) { flex: none; }
  .seat.left { grid-area: left; align-items: flex-start; justify-content: center; }
  .seat.right { grid-area: right; align-items: flex-end; justify-content: center; }
  .seat.right .seat-tiles { align-items: flex-end; }
  .seat.right :global(.melds) { justify-content: flex-end; }
  .seat-tiles { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
  .seat.top .seat-tiles { flex-direction: row; align-items: flex-end; gap: 10px; }
  .concealed { display: flex; flex-wrap: wrap; gap: 0; }
  .concealed.backs { flex-wrap: nowrap; }
  .seat:not(.top) .concealed.backs { flex-wrap: wrap; max-width: 100%; }
  /* Tall: the three along the top, as columns. */
  .table:not(.wide) .seat { justify-content: flex-start; padding: 4px 4px 0; }
  .table:not(.wide) .seat.top { flex-direction: column; align-items: center; gap: 4px; }
  .table:not(.wide) .seat.top .seat-tiles { flex-direction: column; align-items: center; }
  .table:not(.wide) .seat.top :global(.melds) { justify-content: center; }
  .table:not(.wide) .seat > :global(.badge-row) { flex: none; }

  .center { grid-area: center; display: flex; align-items: center; justify-content: center; min-height: 0; min-width: 0; }
  .rivers { display: grid; gap: 4px 6px;
    grid-template-columns: calc(var(--side-cols) * var(--river)) calc(var(--cols) * var(--river)) calc(var(--side-cols) * var(--river));
    grid-template-rows: auto auto auto; grid-template-areas: "rl rt rr" "rl box rr" "rl rb rr"; align-items: center; }
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
  .me-top > :global(.badge-row) { flex: none; align-self: center; }
  .me-corner { position: absolute; left: 8px; z-index: 2; display: flex; flex-direction: column; align-items: flex-start; gap: 4px;
    max-width: calc(var(--side-w) - 12px); }

  .claims { position: absolute; right: 8px; left: 8px; display: flex; justify-content: flex-end; z-index: 3; pointer-events: none; }
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
