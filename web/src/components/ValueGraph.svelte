<!--
  A reviewed hand at a glance: the expected result (the best action's value) at each of the player's decisions, along
  the hand's steps; the decisions worth a look as dots (a mistake by its grade, a good find as a star); where the
  review is now as a line. A tap goes to the nearest decision.
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import type { Key } from '../lib/review';

  let { points, keys, step, n, height = 44, onpick }: {
    /** The value at each decision's step. */
    points: { step: number; value: number }[];
    /** The decisions to mark (those the review stops at). */
    keys: Key[];
    step: number; n: number; height?: number; onpick: (step: number) => void;
  } = $props();

  let W = $state(0);
  const PAD = 6;
  const range = $derived.by(() => {
    let lo = 0, hi = 0;
    for (const p of points) { lo = Math.min(lo, p.value); hi = Math.max(hi, p.value); }
    const pad = Math.max(0.3, (hi - lo) * 0.12);
    return { lo: lo - pad, hi: hi + pad };
  });
  const x = (s: number) => PAD + (Math.max(1, W) - 2 * PAD) * (n > 0 ? s / n : 0);
  const y = (v: number) => 3 + (height - 6) * (1 - (v - range.lo) / (range.hi - range.lo));
  const line = $derived(points.map((p, i) => `${i ? 'L' : 'M'}${x(p.step).toFixed(1)} ${y(p.value).toFixed(1)}`).join(''));
  const valueAt = (s: number) => points.find((p) => p.step === s)?.value ?? 0;

  function tap(e: MouseEvent) {
    if (!points.length) return;
    const px = e.clientX - (e.currentTarget as HTMLElement).getBoundingClientRect().left;
    // A marked decision near the tap wins over a plain one nearer.
    let best = points[0].step, d = Infinity;
    for (const p of points) {
      const marked = keys.some((k) => k.step === p.step);
      const dist = Math.abs(x(p.step) - px) - (marked ? 10 : 0);
      if (dist < d) { d = dist; best = p.step; }
    }
    onpick(best);
  }
</script>

<div class="graph" bind:clientWidth={W} style="height: {height}px" role="button" tabindex="-1" aria-label={t('review_graph')} title={t('review_graph')}
  onclick={tap} onkeydown={() => {}}>
  {#if W > 0 && points.length}
    <svg width={W} {height}>
      <line class="zero" x1={PAD} x2={W - PAD} y1={y(0)} y2={y(0)} />
      <path class="value" d={line} />
      <line class="now" x1={x(Math.min(step, n))} x2={x(Math.min(step, n))} y1="0" y2={height} />
      {#each keys as k (k.step)}
        {#if k.found}
          <text class="found" x={x(k.step)} y={y(valueAt(k.step)) + 4.5} text-anchor="middle">★</text>
        {:else}
          <circle class="dot g{k.grade}" class:idea={!k.grade} cx={x(k.step)} cy={y(valueAt(k.step))} r={k.grade >= 3 ? 5.5 : 4.5} />
        {/if}
      {/each}
    </svg>
  {/if}
</div>

<style>
  .graph { width: 100%; min-width: 0; border-radius: 8px; background: color-mix(in srgb, var(--on-surface) 6%, transparent); cursor: pointer; overflow: hidden; }
  svg { display: block; }
  .zero { stroke: var(--outline); stroke-width: 1; stroke-dasharray: 3 3; }
  .value { fill: none; stroke: var(--primary); stroke-width: 2; stroke-linejoin: round; }
  .now { stroke: var(--on-surface); stroke-width: 2; opacity: 0.6; }
  .dot { stroke: var(--surface); stroke-width: 1.5; }
  .dot.g1 { fill: var(--danger-1); }
  .dot.g2 { fill: var(--danger-2); }
  .dot.g3 { fill: var(--danger-3); }
  .dot.idea { fill: var(--found); }
  .found { fill: var(--found); font-size: 15px; font-weight: 700; paint-order: stroke; stroke: var(--surface); stroke-width: 2px; }
</style>
