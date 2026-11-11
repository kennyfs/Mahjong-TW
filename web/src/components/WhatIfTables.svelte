<!--
  What the hand would do next, by the network: after each tile it may draw (the best action and the hand's value then;
  the tiles it would keep lit, the ones it would discard at once dim), and on each discard of the others that it would
  do better to claim than to leave.
-->
<script lang="ts">
  import { actionText } from '../lib/actions';
  import { t } from '../lib/i18n.svelte';
  import { EQUAL, pts } from '../lib/review';
  import type { WhatIf, WhatIfClaim } from '../lib/types';
  import Tile from './Tile.svelte';

  let { data, w = 30, seatName }: { data: WhatIf; w?: number; seatName: (seat: number) => string } = $props();

  // A draw is kept when the answer is anything but discarding that tile again.
  const draws = $derived(data.draws.map((d) => ({ ...d, kept: !(d.action.type === 'discard' && d.action.tile === d.tile) })));
  const kept = $derived(draws.filter((d) => d.kept));
  const keptTiles = $derived(kept.reduce((a, d) => a + d.left, 0));
  const top = $derived(Math.max(...draws.map((d) => d.value), -Infinity));

  const passOf = (c: WhatIfClaim) => c.options.find((o) => o.action.type === 'pass');
  const claims = $derived(data.claims.map((c) => {
    const best = c.options[c.best];
    return { ...c, act: best.action, gain: (best.q ?? 0) - (passOf(c)?.q ?? best.q ?? 0), take: best.action.type !== 'pass' };
  }));
  const seats = $derived([...new Set(claims.map((c) => c.seat))]);
  const taken = $derived(claims.filter((c) => c.take));
  const left = $derived(new Set(claims.filter((c) => !c.take).map((c) => c.tile)).size);
  const word = (type: string) => t('act_' + type);
</script>

<div class="tables">
  <div class="head">
    <span class="title-small">{t('whatif_draws')}</span>
    <span class="body-small dim">{t('whatif_keep', kept.length, keptTiles)}</span>
  </div>
  <p class="hint body-small dim">{t('whatif_draws_hint')}</p>
  <div class="cells">
    {#each draws as d (d.tile)}
      <div class="cell" class:off={!d.kept} class:top={d.kept && d.value >= top - EQUAL}>
        <div class="pair">
          <Tile tile={d.tile} {w} />
          {#if d.kept}
            <span class="arrow">→</span>
            {#if d.action.type === 'discard' && d.action.tile}<Tile tile={d.action.tile} w={w * 0.8} />{:else}<span class="word">{word(d.action.type)}</span>{/if}
          {/if}
        </div>
        <div class="under body-small">
          {#if d.kept}<b>{pts(d.value)}</b>{:else}<span>{t('whatif_tsumogiri')}</span>{/if}
          <span class="dim">{t('whatif_left', d.left)}</span>
        </div>
      </div>
    {/each}
  </div>

  <div class="head second">
    <span class="title-small">{t('whatif_claims')}</span>
  </div>
  <p class="hint body-small dim">{t('whatif_claims_hint')}</p>
  {#each seats as seat (seat)}
    {#if taken.some((c) => c.seat === seat)}
      <div class="who label-large">{seatName(seat)}</div>
      <div class="cells">
        {#each taken.filter((c) => c.seat === seat) as c (c.tile)}
          <div class="cell">
            <div class="pair"><Tile tile={c.tile} {w} /><span class="arrow">→</span><span class="word">{c.act.type === 'chi' ? actionText(c.act) : word(c.act.type)}</span></div>
            <div class="under body-small"><b>{pts(c.gain)}</b><span class="dim">{t('whatif_left', c.left)}</span></div>
          </div>
        {/each}
      </div>
    {/if}
  {/each}
  {#if !taken.length}<p class="body-medium dim">{t('whatif_no_claims')}</p>{/if}
  {#if left > 0}<p class="body-small dim">{t('whatif_pass_better', left)}</p>{/if}
</div>

<style>
  .tables { display: flex; flex-direction: column; gap: 6px; }
  .head { display: flex; align-items: baseline; gap: 12px; color: var(--primary); }
  .head.second { margin-top: 10px; }
  .hint { margin: 0; line-height: 1.5; }
  .cells { display: flex; flex-wrap: wrap; gap: 6px; }
  .cell { display: flex; flex-direction: column; gap: 2px; padding: 5px 7px; border-radius: 10px; background: var(--surface-container); }
  .cell.top { background: var(--secondary-container); color: var(--on-secondary-container); box-shadow: inset 0 0 0 2px var(--found); }
  .cell.off { opacity: 0.5; }
  .pair { display: flex; align-items: center; gap: 3px; }
  .arrow { color: var(--on-surface-variant); }
  .word { font-weight: 700; padding: 0 2px; white-space: nowrap; }
  .under { display: flex; justify-content: space-between; gap: 8px; font-variant-numeric: tabular-nums; white-space: nowrap; }
  .who { margin-top: 4px; }
  p { margin: 0; }
</style>
