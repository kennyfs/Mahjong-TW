<!-- One player's discards, in order, `cols` a row: the tiles taken by a meld faded, the last discard lit. More than
     `rows` rows' worth (a long hand) and the tiles get smaller to stay in the river's room. -->
<script lang="ts">
  import type { Discard } from '../lib/types';
  import Tile from './Tile.svelte';
  import { fly } from 'svelte/transition';
  let { discards, w, cols, rows = 0, last = false, from = 'bottom' }: {
    discards: Discard[]; w: number; cols: number; rows?: number; last?: boolean; from?: 'bottom' | 'top' | 'left' | 'right';
  } = $props();
  const fit = $derived(rows > 0 && discards.length > cols * rows ? Math.ceil(discards.length / rows) : cols);
  const tw = $derived((w * cols) / fit);
  const dx = $derived(from === 'left' ? -tw : from === 'right' ? tw : 0);
  const dy = $derived(from === 'top' ? -tw : from === 'bottom' ? tw : 0);
</script>

<div class="river" style="width: {cols * w}px; grid-template-columns: repeat({fit}, {tw}px)">
  {#each discards as d, i (i)}
    <span class="slot" in:fly={{ x: dx * 1.5, y: dy * 1.5, duration: 220 }}>
      <Tile tile={d.tile} w={tw} dim={d.called} glow={last && i === discards.length - 1 && !d.called} />
    </span>
  {/each}
</div>

<style>
  .river { display: grid; align-content: start; }
  .slot { display: block; }
</style>
