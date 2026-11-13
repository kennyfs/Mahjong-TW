<!--
  The player's concealed tiles, the drawn one set apart at the end. On the player's turn a tap lifts a tile and a
  second tap discards it; a tile dragged upwards is discarded on release. The keyboard (a computer's) moves the
  choice with the arrows and discards with Enter (`selected` is bound to the table's keys).

  `perRow`: tiles a row (an upright phone, so that they stay large enough to tap); the rows start at the same left
  edge, the drawn tile after the last one. 0: one row.
  `oneClick` (a computer's mouse): a tile rises under the pointer and one click discards it.
  `marks` (a review): by tile, a tag over it (what it costs against the best), whether it was the one played, and its
  danger as a band along its foot; `tagH` px are kept over each row for the tags. A tile chosen there (to see its
  figures) is not lifted: its tag would run into the row above; its face and frame say it is chosen.
  `turn`: the player's turn to discard: a frame of the turn's color pulses around the tiles, and a tile that may not
  be discarded then (after declaring a ready hand: all but those that keep it ready, later all but the one drawn) is
  darker.

  A tile's touch target is its own place, whether the tile is lifted or not, and while it is lifted the room over it
  too: the second tap lands where the first did (a thumb at the tile's foot) or on the tile where it now is. (A
  target that rose with the tile left its foot dead: "sometimes it will not discard"; and under a mouse the tile
  fell back from under the pointer there.)
-->
<script lang="ts">
  import Tile, { TILE_RATIO } from './Tile.svelte';
  import { fly } from 'svelte/transition';
  import { DRAWN_GAP, LIFT, ROW_GAP } from '../lib/layout';
  import type { TileMark } from '../lib/review';
  import type { Tile as T } from '../lib/tiles';
  let { tiles, drawn = null, w, canDiscard = () => false, ondiscard, selected = $bindable(null), perRow = 0, oneClick = false,
    marks = null, tagH = 0, turn = false, drawWait = 0 }: {
    tiles: T[]; drawn?: T | null; w: number; canDiscard?: (t: T) => boolean; ondiscard?: (t: T) => void;
    selected?: number | null; perRow?: number; oneClick?: boolean; marks?: Map<T, TileMark> | null; tagH?: number;
    turn?: boolean;
    /** How long the drawn tile waits before it comes in (ms): another's discard still flying to its river. */
    drawWait?: number;
  } = $props();

  // The drawn tile last, as its own index.
  const all = $derived(drawn ? [...tiles, drawn] : tiles);
  const h = $derived(w * TILE_RATIO);
  /** How far a chosen tile rises. */
  const lift = $derived(marks ? 0 : h * LIFT);
  const rows = $derived.by(() => {
    const n = tiles.length;
    const size = perRow > 0 ? perRow : Math.max(1, n);
    const out: number[][] = [];
    for (let i = 0; i < n; i += size) out.push(Array.from({ length: Math.min(size, n - i) }, (_, k) => i + k));
    if (!out.length) out.push([]);
    // A seventeenth tile not drawn (the dealer's at the deal) ends the second row, as a drawn tile would: two rows.
    if (perRow > 0 && !drawn && out.length > 1 && out[out.length - 1].length === 1) out[out.length - 2].push(...out.pop()!);
    if (drawn) out[out.length - 1].push(n);
    return out;
  });
  // Wrapped: as wide as a full row and the drawn tile, so the rows keep their place whether a tile was drawn or not.
  const blockW = $derived(perRow > 0 ? (perRow + DRAWN_GAP + 1) * w : 0);
  let drag = $state<{ i: number; y0: number; dy: number; id: number } | null>(null);

  function tap(i: number) {
    const t = all[i];
    if (oneClick && canDiscard(t)) {
      selected = null;
      ondiscard?.(t);
      return;
    }
    if (!canDiscard(t)) {
      selected = selected === i ? null : i;
      return;
    }
    if (selected === i) {
      selected = null;
      ondiscard?.(t);
    } else selected = i;
  }

  function down(e: PointerEvent, i: number) {
    if (e.button !== 0) return;
    drag = { i, y0: e.clientY, dy: 0, id: e.pointerId };
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }
  function move(e: PointerEvent) {
    // Only a tile that may be discarded follows the finger up (a review's tiles are only tapped, to see their figures).
    if (drag && e.pointerId === drag.id && canDiscard(all[drag.i])) drag.dy = Math.min(0, e.clientY - drag.y0);
  }
  function up(e: PointerEvent) {
    if (!drag || e.pointerId !== drag.id) return;
    const d = drag;
    drag = null;
    if (d.dy < -h * 0.45) {
      const t = all[d.i];
      if (canDiscard(t)) {
        selected = null;
        ondiscard?.(t);
      }
      return;
    }
    if (Math.abs(d.dy) < h * 0.15) tap(d.i);
  }
</script>

<div class="hand" class:turn style="padding-top: {Math.max(h * LIFT, tagH)}px; row-gap: {Math.max(h * ROW_GAP, tagH)}px; --lift: {lift}px;
  --top: {Math.max(h * LIFT, tagH)}px; {blockW ? `width: ${blockW}px` : ''}">
  {#each rows as row, r (r)}
    <div class="row" style="height: {h}px">
      {#each row as i (i + '/' + all[i] + (drawn && i === tiles.length ? '/d' : ''))}
        {@const isDrawn = !!drawn && i === tiles.length}
        <button class="slot" class:up={selected === i} class:hover={oneClick && canDiscard(all[i])} class:held={turn && !canDiscard(all[i])}
          style="margin-left: {isDrawn && row.length > 1 ? w * DRAWN_GAP : 0}px"
          onpointerdown={(e) => down(e, i)} onpointermove={move} onpointerup={up} onpointercancel={() => (drag = null)}
          in:fly={{ y: isDrawn ? -h : 0, duration: isDrawn ? 220 : 0, delay: isDrawn ? drawWait : 0 }}
          aria-pressed={selected === i} data-tile={all[i]} data-drawn={isDrawn ? '1' : '0'}>
          <span class="lifted" style="transform: translateY({drag?.i === i ? drag.dy : selected === i ? -lift : 0}px)">
            {#if marks?.get(all[i])?.tag}
              {@const m = marks.get(all[i])!}
              <span class="tag {m.tone}" class:played={m.played} style="font-size: {Math.min(13, Math.max(10, w * 0.27))}px; height: {tagH - 2}px">{m.tag}</span>
            {/if}
            <Tile tile={all[i]} {w} selected={selected === i} glow={!!marks?.get(all[i])?.played} danger={marks?.get(all[i])?.danger ?? 0} />
          </span>
        </button>
      {/each}
    </div>
  {/each}
</div>

<style>
  .hand { display: flex; flex-direction: column; align-items: flex-start; touch-action: none; flex: none; position: relative; }
  /* The player's turn: the frame around the tiles (not the room over them for a lifted tile), pulsing. */
  .hand.turn::after { content: ''; position: absolute; left: -5px; right: -5px; top: calc(var(--top) - 5px); bottom: -5px;
    border: 3px solid var(--highlight); border-radius: 10px; pointer-events: none; animation: turn 1.1s ease-in-out infinite alternate; }
  @keyframes turn {
    from { opacity: 0.45; box-shadow: 0 0 2px 0 var(--highlight); }
    to { opacity: 1; box-shadow: 0 0 16px 3px var(--highlight); }
  }
  @media (prefers-reduced-motion: reduce) {
    .hand.turn::after { animation: none; box-shadow: 0 0 10px 2px var(--highlight); }
  }
  .row { display: flex; align-items: flex-end; }
  .slot { padding: 0; margin: 0; border: none; background: none; cursor: pointer; display: block;
    -webkit-tap-highlight-color: transparent; touch-action: none; position: relative; }
  .slot.up::before, .slot.hover:hover::before { content: ''; position: absolute; left: 0; right: 0; bottom: 100%; height: var(--lift); }
  .lifted { display: block; position: relative; transition: transform 0.08s; }
  /* On the player's turn, a tile that may not be discarded (a declared ready hand keeps its tiles): darker. */
  .slot.held .lifted { filter: brightness(0.58) saturate(0.8); }
  .tag { position: absolute; left: 50%; bottom: 100%; transform: translateX(-50%); margin-bottom: 1px; padding: 0 3px; border-radius: 5px;
    display: flex; align-items: center; font-weight: 700; line-height: 1; white-space: nowrap; pointer-events: none;
    background: rgba(20, 32, 26, 0.76); color: #fff; font-variant-numeric: tabular-nums; }
  .tag.best { background: var(--found); color: #fff; }
  .tag.g1 { background: var(--danger-1); color: var(--on-danger); }
  .tag.g2 { background: var(--danger-2); color: var(--on-danger); }
  .tag.g3 { background: var(--danger-3); color: #fff; }
  .tag.played { outline: 2px solid var(--highlight); outline-offset: 0; }
  .slot:focus-visible { outline: none; }
  .slot:focus-visible .lifted { outline: 2px solid var(--highlight); outline-offset: 1px; border-radius: 4px; }
  @media (hover: hover) {
    .slot.hover:hover .lifted { transform: translateY(calc(var(--lift) * -1)) !important; }
  }
</style>
