<!--
  Over the hand at a decision (a review's, a branch's): the figures of the tile chosen in the hand, on a dark strip.
  Every discard's figures are in fixed columns (FigureRuler), so that each figure keeps its place from one tile to the
  next. `seat`: whose decision it is (the opponents' names are told from it).
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import { costOf, FIGURE_KEYS, figureCells } from '../lib/review';
  import { tileName, type Tile as T } from '../lib/tiles';
  import type { Analysis } from '../lib/types';
  import FigureLine from './FigureLine.svelte';
  import FigureRuler from './FigureRuler.svelte';

  let { analysis, seat, names, tile }: { analysis: Analysis; seat: number; names: string[]; tile: T } = $props();
  const oppNames = $derived([1, 2, 3].map((r) => names[(seat + r) % 4]));
  const lines = $derived.by(() => {
    const a = analysis;
    const best = a.best !== undefined ? a.actions[a.best] : null;
    return new Map(a.actions.flatMap((f, i) => (f.action.type === 'discard' ? [[i, figureCells(f, costOf(a, i), oppNames, best)] as const] : [])));
  });
  const index = $derived(analysis.actions.findIndex((f) => f.action.type === 'discard' && f.action.tile === tile));
  let widths = $state<Partial<Record<(typeof FIGURE_KEYS)[number], number>>>({});
</script>

{#if index >= 0}
  <div class="detail body-small">
    <FigureRuler lines={[...lines.values()]} keys={FIGURE_KEYS} bind:widths />
    <b class="what">{t('act_discard')} {tileName(tile)}</b>
    <FigureLine cells={lines.get(index) ?? []} keys={FIGURE_KEYS} {widths} />
  </div>
{/if}

<style>
  .detail { position: relative; display: flex; flex-wrap: wrap; justify-content: center; gap: 1px 12px; padding: 5px 12px; border-radius: 12px;
    background: rgba(8, 14, 11, 0.86); color: #fff; max-width: 100%; font-variant-numeric: tabular-nums; --fig-good: #b9f6c2; --fig-bad: #ffbdb5; }
  /* As wide for every tile (二萬 or 東). */
  .detail .what { min-width: 5.2em; }
  /* On the black strip the words as light as the figures (dimmed, they were hard to read there). */
  .detail :global(.lbl), .detail :global(.aft) { opacity: 1; color: #d6ebdf; }
  /* Its greens and reds the same light ones in every theme (the strip is always dark), in bold so that they read at once. */
  .detail :global(.good .val), .detail :global(.bad .val) { font-weight: 700; }
  .detail :global(.ruler .val) { font-weight: 700; }
  .detail :global(.line) { display: contents; }
</style>
