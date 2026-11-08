<!-- A player's melds, each a group of tiles, and their flowers after them (each on its own, so that a line breaks
     between them). The gaps are MELD_GAPS' (lib/layout.ts counts lines with them). -->
<script lang="ts">
  import type { Meld } from '../lib/types';
  import { meldTiles } from '../lib/seats';
  import { FLOWER_SCALE, meldGaps } from '../lib/layout';
  import Tile from './Tile.svelte';
  import { scale } from 'svelte/transition';
  let { melds, flowers = [], w, wrap = true }: { melds: Meld[]; flowers?: string[]; w: number; wrap?: boolean } = $props();
  const g = $derived(meldGaps(w));
</script>

<div class="melds" class:wrap style="gap: {g.row}px {g.col}px">
  {#each melds as m, i (i + m.type)}
    <div class="meld" in:scale={{ start: 1.4, duration: 260 }}>
      {#each meldTiles(m) as t}<Tile tile={t} {w} />{/each}
    </div>
  {/each}
  {#each flowers as f (f)}<span class="flower" in:scale={{ start: 1.4, duration: 260 }}><Tile tile={f} w={w * FLOWER_SCALE} /></span>{/each}
</div>

<style>
  .melds { display: flex; align-items: flex-end; }
  .melds.wrap { flex-wrap: wrap; }
  .meld { display: flex; gap: 0; }
  .flower { display: block; }
</style>
