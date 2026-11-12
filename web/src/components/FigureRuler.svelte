<!--
  The width of each column of figures (lib/review.ts figureCells) over a set of lines: every line's cell of a column
  laid on the others, out of sight, and measured. FigureLine gives each cell that width, so the lines (a list's rows,
  the figures of each tile over the hand) keep every figure in its place whatever the others have.
-->
<script lang="ts">
  import type { FigureCell, FigureKey } from '../lib/review';
  let { lines, keys, widths = $bindable({}) }: { lines: FigureCell[][]; keys: readonly FigureKey[]; widths?: Partial<Record<FigureKey, number>> } = $props();
  const cols = $derived(keys.map((k) => ({ k, cells: lines.map((l) => l.find((c) => c.key === k)).filter((c) => c && (c.label || c.value)) as FigureCell[] })));
  let measured = $state<Record<string, number>>({});
  $effect(() => {
    const out: Partial<Record<FigureKey, number>> = {};
    for (const { k } of cols) out[k] = measured[k] ?? 0;
    widths = out;
  });
</script>

<div class="ruler" aria-hidden="true">
  {#each cols as { k, cells } (k)}
    <span class="col" bind:offsetWidth={measured[k]}>
      {#each cells as c}<span class="cell"><span class="lbl">{c.label}</span><span class="val">{c.value}</span></span>{/each}
    </span>
  {/each}
</div>

<style>
  .ruler { position: absolute; visibility: hidden; pointer-events: none; height: 0; overflow: hidden; white-space: nowrap; left: 0; top: 0; }
  .col { display: inline-grid; }
  .cell { grid-area: 1 / 1; display: inline-flex; gap: 0.3em; font-variant-numeric: tabular-nums; }
</style>
