<!--
  The player's concealed tiles, the drawn one set apart at the end. On the player's turn a tap lifts a tile and a
  second tap discards it; a tile dragged upwards is discarded on release. The keyboard (a computer's) moves the
  choice with the arrows and discards with Enter (`selected` is bound to the table's keys).

  `perRow`: tiles a row (an upright phone, so that they stay large enough to tap); the rows start at the same left
  edge, the drawn tile after the last one. 0: one row.
  `oneClick` (a computer's mouse): a tile rises under the pointer and one click discards it.
-->
<script lang="ts">
  import Tile, { TILE_RATIO } from './Tile.svelte';
  import { fly } from 'svelte/transition';
  import { DRAWN_GAP, LIFT, ROW_GAP } from '../lib/layout';
  import type { Tile as T } from '../lib/tiles';
  let { tiles, drawn = null, w, canDiscard = () => false, ondiscard, selected = $bindable(null), perRow = 0, oneClick = false }: {
    tiles: T[]; drawn?: T | null; w: number; canDiscard?: (t: T) => boolean; ondiscard?: (t: T) => void;
    selected?: number | null; perRow?: number; oneClick?: boolean;
  } = $props();

  // The drawn tile last, as its own index.
  const all = $derived(drawn ? [...tiles, drawn] : tiles);
  const h = $derived(w * TILE_RATIO);
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
    if (drag && e.pointerId === drag.id) drag.dy = Math.min(0, e.clientY - drag.y0);
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

<div class="hand" style="padding-top: {h * LIFT}px; row-gap: {h * ROW_GAP}px; --lift: {h * LIFT}px; {blockW ? `width: ${blockW}px` : ''}">
  {#each rows as row, r (r)}
    <div class="row" style="height: {h}px">
      {#each row as i (i + '/' + all[i] + (drawn && i === tiles.length ? '/d' : ''))}
        {@const isDrawn = !!drawn && i === tiles.length}
        <button class="slot" class:up={selected === i} class:hover={oneClick && canDiscard(all[i])}
          style="margin-left: {isDrawn && row.length > 1 ? w * DRAWN_GAP : 0}px; transform: translateY({drag?.i === i ? drag.dy : selected === i ? -h * LIFT : 0}px)"
          onpointerdown={(e) => down(e, i)} onpointermove={move} onpointerup={up} onpointercancel={() => (drag = null)}
          in:fly={{ y: isDrawn ? -h : 0, duration: isDrawn ? 220 : 0 }}
          aria-pressed={selected === i}>
          <Tile tile={all[i]} {w} selected={selected === i} />
        </button>
      {/each}
    </div>
  {/each}
</div>

<style>
  .hand { display: flex; flex-direction: column; align-items: flex-start; touch-action: none; flex: none; }
  .row { display: flex; align-items: flex-end; }
  .slot { padding: 0; margin: 0; border: none; background: none; cursor: pointer; display: block; transition: transform 0.08s;
    -webkit-tap-highlight-color: transparent; touch-action: none; }
  .slot:focus-visible { outline: 2px solid var(--highlight); outline-offset: 1px; border-radius: 4px; }
  @media (hover: hover) {
    .slot.hover:hover { transform: translateY(calc(var(--lift) * -1)) !important; }
  }
</style>
