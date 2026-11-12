<!--
  One action's figures in fixed columns (FigureRuler's widths): the label at a cell's left, the figure at its right,
  green or red where it is notably better or worse than the best action's. A cell the action has not keeps its room;
  a column no line has takes none.
-->
<script lang="ts">
  import type { FigureCell, FigureKey } from '../lib/review';
  let { cells, keys, widths }: { cells: FigureCell[]; keys: readonly FigureKey[]; widths: Partial<Record<FigureKey, number>> } = $props();
  const shown = $derived(keys.filter((k) => (widths[k] ?? 0) > 0).map((k) => ({ k, c: cells.find((c) => c.key === k) })));
</script>

<span class="line">
  {#each shown as { k, c } (k)}
    <span class="cell" class:good={c?.tone === 1} class:bad={c?.tone === -1} style="width: {Math.ceil(widths[k] ?? 0)}px">
      <span class="lbl">{c?.label ?? ''}</span><span class="rt"><span class="val">{c?.value ?? ''}</span>{#if c?.after}<span class="aft">{c.after}</span>{/if}</span>
    </span>
  {/each}
</span>

<style>
  .line { display: flex; flex-wrap: wrap; column-gap: 12px; row-gap: 1px; }
  .cell { display: inline-flex; justify-content: space-between; gap: 0.3em; white-space: nowrap; font-variant-numeric: tabular-nums; flex: none; }
  .lbl, .aft { opacity: 0.72; }
  .cell.good .val { color: var(--fig-good, var(--good)); }
  .cell.bad .val { color: var(--fig-bad, var(--bad)); }
</style>
