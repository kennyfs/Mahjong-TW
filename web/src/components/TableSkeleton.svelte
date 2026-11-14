<!--
  A table before there is one (the engine still downloading, a battle's first view on its way): the felt with the
  shapes of the players' plates, the middle and the hand in a lighter tone, pulsing, as a page shows while it loads.
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
</script>

<div class="skel-table" role="status" aria-label={t('loading')}>
  <div class="top"><span class="plate"></span><span class="backs">{#each Array(8) as _, i (i)}<span class="back"></span>{/each}</span></div>
  <div class="left"><span class="plate"></span></div>
  <div class="middle"><span class="box"></span></div>
  <div class="right"><span class="plate"></span></div>
  <div class="me">
    <span class="plate"></span>
    <div class="hand">{#each Array(16) as _, i (i)}<span class="tile"></span>{/each}</div>
  </div>
</div>

<style>
  .skel-table { position: absolute; inset: 0; overflow: hidden; background: var(--felt); display: grid;
    grid-template-columns: 1fr 2fr 1fr; grid-template-rows: auto 1fr auto; grid-template-areas: "top top top" "left middle right" "me me me";
    gap: 12px; padding: 12px; }
  .top { grid-area: top; display: flex; flex-direction: column; align-items: center; gap: 8px; }
  .left { grid-area: left; display: flex; align-items: center; }
  .right { grid-area: right; display: flex; align-items: center; justify-content: flex-end; }
  .middle { grid-area: middle; display: flex; align-items: center; justify-content: center; }
  .me { grid-area: me; display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
  .plate, .back, .box, .tile { display: block; background: var(--on-felt); opacity: 0.14; border-radius: 6px; animation: pulse 1.6s ease-in-out infinite; }
  .plate { width: 112px; height: 24px; border-radius: 12px; }
  .backs { display: flex; gap: 3px; }
  .back { width: 18px; height: 26px; border-radius: 3px; }
  .box { width: min(150px, 60%); aspect-ratio: 1; border-radius: 12px; }
  .hand { display: flex; flex-wrap: wrap; gap: 3px; max-width: 100%; }
  .tile { width: clamp(30px, calc((100vw - 96px) / 16), 46px); aspect-ratio: 60 / 87; }
  /* Upright (as the table): the three others' plates in a line at the top. */
  @media (max-width: 599px) {
    .skel-table { grid-template-areas: "left top right" "middle middle middle" "me me me"; }
    .left, .right { align-items: flex-start; }
    .top .plate, .left .plate, .right .plate { width: min(112px, 100%); }
  }
  @media (max-width: 599px) { .hand { max-width: calc(8 * (min(46px, (100vw - 48px) / 8) + 3px)); }
    .tile { width: min(46px, calc((100vw - 48px) / 8 - 3px)); } }
  @keyframes pulse { 0%, 100% { opacity: 0.1; } 50% { opacity: 0.2; } }
  @media (prefers-reduced-motion: reduce) { .plate, .back, .box, .tile { animation: none; } }
</style>
