<!--
  A tile, standing: its face (or its back), with the edge of its back under it. `w` is its width in px; it is
  w * TILE_RATIO high. A null tile is a back (another player's tile, a concealed kong).
-->
<script lang="ts" module>
  /** Height over width: the face (3:4) and the edge under it. */
  export const TILE_RATIO = 1.45;
</script>

<script lang="ts">
  import { tileName } from '../lib/tiles';
  let { tile = null, w, selected = false, dim = false, glow = false, mark = false, label = true, cls = '' }: {
    tile?: string | null; w: number; selected?: boolean; dim?: boolean; glow?: boolean; mark?: boolean; label?: boolean; cls?: string;
  } = $props();
</script>

<svg class="tile {cls}" class:selected class:dim class:glow class:mark width={w} height={w * TILE_RATIO} viewBox="0 0 60 87"
  role={label && tile ? 'img' : undefined} aria-label={label && tile ? tileName(tile) : undefined} aria-hidden={label && tile ? undefined : 'true'}>
  <rect class="back" x="0" y="0" width="60" height="87" rx="7" />
  {#if tile}
    <rect class="face" x="0.6" y="0.6" width="58.8" height="79.4" rx="6.6" />
    <use href="#tile-{tile}" x="3" y="3.2" width="54" height="72" />
  {:else}
    <rect class="back-panel" x="5" y="5" width="50" height="72" rx="4" />
  {/if}
</svg>

<style>
  .tile { display: block; flex: none; overflow: visible; }
  .back { fill: var(--tile-back); }
  .face { fill: var(--tile-face); stroke: var(--tile-edge); stroke-width: 1.2; }
  .back-panel { fill: var(--tile-back-light); }
  .selected .face { fill: var(--tile-selected); stroke: var(--highlight); stroke-width: 3; }
  .glow .face { stroke: var(--highlight); stroke-width: 4; }
  .mark .face { fill: var(--tile-mark); }
  .dim { opacity: 0.38; }
</style>
