<!-- One player's discards, in order, `cols` a row: the tiles taken by a meld faded, the last discard lit. More than
     `rows` rows' worth (a long hand) and the tiles get smaller to stay in the river's room. `claim`: the player may
     claim the last discard: it stands out, larger and pulsing. `seat`: whose (`data-river`, where a call's moment
     takes the tile from, CallFx.svelte). -->
<script lang="ts">
  import type { Discard } from '../lib/types';
  import Tile from './Tile.svelte';
  import { fly } from 'svelte/transition';
  let { discards, w, cols, rows = 0, last = false, claim = false, from = 'bottom', seat = -1 }: {
    discards: Discard[]; w: number; cols: number; rows?: number; last?: boolean; claim?: boolean;
    from?: 'bottom' | 'top' | 'left' | 'right'; seat?: number;
  } = $props();
  const fit = $derived(rows > 0 && discards.length > cols * rows ? Math.ceil(discards.length / rows) : cols);
  const tw = $derived((w * cols) / fit);
  const dx = $derived(from === 'left' ? -tw : from === 'right' ? tw : 0);
  const dy = $derived(from === 'top' ? -tw : from === 'bottom' ? tw : 0);
</script>

<div class="river" data-river={seat} style="width: {cols * w}px; grid-template-columns: repeat({fit}, {tw}px)">
  {#each discards as d, i (i)}
    {@const end = i === discards.length - 1}
    <span class="slot" class:claim={claim && end && !d.called} in:fly={{ x: dx * 1.5, y: dy * 1.5, duration: 220 }}>
      <Tile tile={d.tile} w={tw} dim={d.called} glow={last && end && !d.called} />
    </span>
  {/each}
</div>

<style>
  .river { display: grid; align-content: start; }
  .slot { display: block; position: relative; }
  .slot.claim { z-index: 3; animation: claim 0.9s ease-in-out infinite alternate; }
  @keyframes claim {
    from { transform: scale(1.25); filter: drop-shadow(0 0 2px var(--highlight)); }
    to { transform: scale(1.4); filter: drop-shadow(0 0 9px var(--highlight)); }
  }
  @media (prefers-reduced-motion: reduce) {
    .slot.claim { animation: none; transform: scale(1.35); filter: drop-shadow(0 0 6px var(--highlight)); }
  }
</style>
