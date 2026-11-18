<!-- One player's discards, in order, `cols` a row: the tiles taken by a meld faded, the last discard lit, the tiles that
     came straight from the draw (摸切) greyer, and the first discard after the player's own call marked by a bar at its
     left (how many discards since a call is something the table shows, as the network sees it). More than
     `rows` rows' worth (a long hand) and the tiles get smaller to stay in the river's room. `claim`: the player may
     claim the last discard: it stands out, larger and pulsing. `seat`: whose (`data-river`, where a call's moment
     takes the tile from, CallFx.svelte; where the table moves a new discard: Table.svelte flyDiscard, flyIn). -->
<script lang="ts">
  import type { Discard } from '../lib/types';
  import Tile from './Tile.svelte';
  let { discards, w, cols, rows = 0, last = false, claim = false, seat = -1 }: {
    discards: Discard[]; w: number; cols: number; rows?: number; last?: boolean; claim?: boolean; seat?: number;
  } = $props();
  const fit = $derived(rows > 0 && discards.length > cols * rows ? Math.ceil(discards.length / rows) : cols);
  const tw = $derived((w * cols) / fit);
</script>

<div class="river" data-river={seat} style="width: {cols * w}px; grid-template-columns: repeat({fit}, {tw}px)">
  {#each discards as d, i (i)}
    {@const end = i === discards.length - 1}
    <span class="slot" class:claim={claim && end && !d.called} class:after={d.after_call}>
      <Tile tile={d.tile} w={tw} dim={d.called} glow={last && end && !d.called} tsumogiri={d.drawn} />
    </span>
  {/each}
</div>

<style>
  .river { display: grid; align-content: start; }
  .slot { display: block; position: relative; }
  .slot.after::before { content: ''; position: absolute; z-index: 1; left: -1px; top: 12%; height: 62%; width: max(2px, 8%); border-radius: 2px;
    background: var(--mark); }
  .slot.claim { z-index: 3; animation: claim 0.9s ease-in-out infinite alternate; }
  @keyframes claim {
    from { transform: scale(1.25); filter: drop-shadow(0 0 2px var(--highlight)); }
    to { transform: scale(1.4); filter: drop-shadow(0 0 9px var(--highlight)); }
  }
  @media (prefers-reduced-motion: reduce) {
    .slot.claim { animation: none; transform: scale(1.35); filter: drop-shadow(0 0 6px var(--highlight)); }
  }
</style>
