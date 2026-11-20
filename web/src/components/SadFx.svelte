<!--
  The player's own deal-in, over the whole screen (lib/moments.ts; Table.svelte starts it, and the winners' moments
  come after it): the table loses its color and darkens, dark and opaque at the top and clearer downwards, and 放槍
  sinks in with the tile dealt and whom it went to. It lasts `ms`; a tap anywhere lets it go.
-->
<script lang="ts">
  import type { Moment } from '../lib/moments';
  import Tile from './Tile.svelte';

  let { m, w, ms }: { m: Moment; w: number; ms: number } = $props();

  const tw = $derived(Math.min(w * 1.1, 64));
</script>

<div class="sad" style="--ms: {ms}ms; --tw: {tw}px" aria-hidden="true">
  <div class="dim"></div>
  <div class="shade"></div>
  <div class="stage">
    <div class="plaque">
      <span class="word"><span class="face">{m.word}</span></span>
      {#if m.note}<span class="note">{m.note}</span>{/if}
    </div>
    {#if m.tile}<div class="tile"><Tile tile={m.tile} w={tw} /></div>{/if}
  </div>
</div>

<style>
  .sad { position: fixed; inset: 0; z-index: 60; pointer-events: none; overflow: hidden; display: flex; align-items: center; justify-content: center;
    --cut: 0.24em; }
  /* The table without its color, darker; dark from the top down. */
  .dim { position: absolute; inset: 0; backdrop-filter: grayscale(0.9) brightness(0.72);
    background: rgba(8, 11, 16, 0.25); animation: dim var(--ms) ease-out both; }
  @keyframes dim { 0% { opacity: 0; } 16% { opacity: 1; } 86% { opacity: 1; } 100% { opacity: 0; } }
  .shade { position: absolute; inset: 0 0 auto; height: 100%;
    background: linear-gradient(180deg, rgba(10, 13, 19, 0.96) 0%, rgba(10, 13, 19, 0.82) 22%, rgba(10, 13, 19, 0.45) 48%, rgba(10, 13, 19, 0) 78%);
    transform-origin: top; animation: shade var(--ms) cubic-bezier(0.3, 0.1, 0.3, 1) both; }
  @keyframes shade { 0% { opacity: 0; transform: scaleY(0.2); } 30% { opacity: 1; transform: none; } 86% { opacity: 1; } 100% { opacity: 0; transform: none; } }

  /* 放槍, sinking in; the tile dealt under it. */
  .stage { position: relative; display: flex; flex-direction: column; align-items: center; gap: clamp(14px, 3vh, 28px); }
  .plaque { display: flex; flex-direction: column; align-items: center; font-size: clamp(2.6rem, 12vmin, 6rem);
    animation: sink var(--ms) cubic-bezier(0.25, 0.1, 0.25, 1) both; }
  @keyframes sink { 0%, 8% { transform: translateY(-0.5em); opacity: 0; } 30% { transform: none; opacity: 1; } 84% { transform: translateY(0.08em); opacity: 1; }
    100% { transform: translateY(0.2em); opacity: 0; } }
  .word { position: relative; display: block; padding-bottom: 0.09em; filter: drop-shadow(0 0.08em 0.12em rgba(0, 0, 0, 0.6)); }
  .word::before, .face { clip-path: polygon(var(--cut) 0, calc(100% - var(--cut)) 0, 100% var(--cut),
    100% calc(100% - var(--cut)), calc(100% - var(--cut)) 100%, var(--cut) 100%, 0 calc(100% - var(--cut)), 0 var(--cut)); }
  .word::before { content: ''; position: absolute; inset: 0.09em 0 0; background: #0b0f12; }
  .face { position: relative; display: block; height: 1.45em; line-height: 1.45em; padding: 0 0.38em; background: #2b343a; color: #ffb3a7;
    font-weight: 900; letter-spacing: 0.06em; font-family: 'Noto Serif CJK TC', 'Noto Serif TC', 'Source Han Serif TC', 'Songti TC', 'PMingLiU', serif; }
  .note { margin-top: 0.35em; padding: 0.12em 0.8em; border-radius: 999px; background: rgba(16, 20, 24, 0.92); color: #d9e2ea;
    font-size: 0.2em; font-weight: 700; }
  .tile { filter: grayscale(0.4) drop-shadow(0 6px 10px rgba(0, 0, 0, 0.6)); animation: tile var(--ms) ease-out both; }
  @keyframes tile { 0%, 18% { transform: translateY(-14px); opacity: 0; } 36% { transform: none; opacity: 1; } 86% { opacity: 1; } 100% { opacity: 0; } }
</style>
