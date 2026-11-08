<!-- Every tile face once, as symbols the tiles use (Tile.svelte): the page then holds each face's paths once. -->
<script lang="ts">
  import { FACE_H, FACE_W, TILE_ART } from '../lib/tile-art';
</script>

<svg width="0" height="0" style="position:absolute" aria-hidden="true">
  <defs>
    {#each Object.entries(TILE_ART) as [kind, layers] (kind)}
      <symbol id="tile-{kind}" viewBox="0 0 {FACE_W} {FACE_H}">
        {#each layers as [d, fill, even]}<path {d} {fill} fill-rule={even ? 'evenodd' : undefined} />{/each}
      </symbol>
    {/each}
  </defs>
</svg>
