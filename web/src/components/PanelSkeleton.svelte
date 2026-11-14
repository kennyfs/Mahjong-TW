<!--
  A decision's list before it is there (the computer players playing, the engine working): its shape in grey, as a
  page shows while it loads, so that the place neither stands empty nor jumps when the list comes. `w`: the list's
  tile width.
-->
<script lang="ts">
  let { rows = 5, w = 32 }: { rows?: number; w?: number } = $props();
</script>

<div class="skel" aria-hidden="true">
  <span class="line head"></span>
  {#each Array.from({ length: rows }, (_, i) => i) as i (i)}
    <div class="row">
      <span class="tile" style="width: {w * 0.75}px; height: {w}px"></span>
      <div class="bars">
        <span class="line" style="width: {92 - i * 9}%"></span>
        <span class="line thin"></span>
      </div>
    </div>
  {/each}
</div>

<style>
  .skel { display: flex; flex-direction: column; gap: 8px; }
  .row { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-radius: 12px; background: var(--surface-container-high); }
  .bars { flex: 1; display: flex; flex-direction: column; gap: 8px; }
  .tile, .line { display: block; border-radius: 5px; background: var(--surface-container-highest); animation: pulse 1.6s ease-in-out infinite; }
  .line { height: 10px; }
  .line.thin { width: 55%; height: 8px; }
  .line.head { width: 45%; height: 12px; margin: 4px 0; }
  @keyframes pulse { 0%, 100% { opacity: 0.5; } 50% { opacity: 1; } }
  @media (prefers-reduced-motion: reduce) { .tile, .line { animation: none; } }
</style>
