<!--
  An analysis by tile efficiency alone (the network's is DecisionPanel's; this is what is left when it does not
  load): each legal action with its shanten, the tiles that help, and when ready the waits with copies left and their
  tai, the best ones and the one chosen marked.
-->
<script lang="ts">
  import { actionText, actionTiles, bestOf, isTaken, rank } from '../lib/actions';
  import { t } from '../lib/i18n.svelte';
  import type { Action, Analysis } from '../lib/types';
  import Tile from './Tile.svelte';
  let { analysis, chosen = null, w = 30 }: { analysis: Analysis; chosen?: Action | null; w?: number } = $props();

  const list = $derived(analysis.actions.map((f, i) => ({ f, i })).sort((a, b) => {
    const x = rank(a.f), y = rank(b.f);
    return x[0] - y[0] || x[1] - y[1] || a.i - b.i;
  }));
  const best = $derived(bestOf(analysis.actions));
</script>

<div class="list">
  {#each list as { f, i } (i)}
    {@const isChosen = !!chosen && isTaken(f.action, chosen)}
    <div class="row" class:best={best.has(i)} class:chosen={isChosen}>
      <div class="act">
        <span class="label-large">{actionText(f.action)}</span>
        {#if actionTiles(f.action).length}
          <span class="tiles">{#each actionTiles(f.action) as x}<Tile tile={x} w={w * 0.8} />{/each}</span>
        {/if}
        <span class="marks">
          {#if best.has(i)}<span class="mark best">{t('analysis_best')}</span>{/if}
          {#if isChosen}<span class="mark chosen">{t('analysis_chosen')}</span>{/if}
        </span>
      </div>
      <div class="figs body-medium">
        {#if f.shanten < 0}
          <span class="fig win">{t('analysis_win')}{f.win_tai >= 0 ? t('sep') + t('analysis_win_tai', f.win_tai) : ''}</span>
        {:else if f.shanten === 0}
          <span class="fig ready">{t('analysis_ready')}</span>
          <span class="fig">{t('analysis_ukeire', f.ukeire)}</span>
        {:else}
          <span class="fig">{t('analysis_shanten', f.shanten)}</span>
          <span class="fig">{t('analysis_ukeire', f.ukeire)}</span>
        {/if}
      </div>
      {#if f.waits.length}
        <div class="waits">
          <span class="body-small dim">{t('analysis_waits')}</span>
          {#each f.waits as wt (wt.tile)}
            <span class="wait">
              <Tile tile={wt.tile} w={w * 0.75} />
              <span class="body-small">{t('analysis_wait_left', wt.left)}<br />{t('analysis_wait_tai', wt.ron_tai, wt.tsumo_tai)}</span>
            </span>
          {/each}
        </div>
      {/if}
    </div>
  {/each}
  {#if !analysis.actions.length}<p class="body-large dim">{t('analysis_no_actions')}</p>{/if}
  <p class="source body-small dim">{t('analysis_source_efficiency')}</p>
</div>

<style>
  .list { display: flex; flex-direction: column; gap: 6px; }
  .row { display: flex; flex-direction: column; gap: 4px; padding: 8px 12px; border-radius: 12px; background: var(--surface-container); }
  .row.best { background: var(--secondary-container); color: var(--on-secondary-container); }
  .row.chosen { outline: 2px solid var(--primary); }
  .act { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .tiles { display: inline-flex; gap: 1px; }
  .marks { display: inline-flex; gap: 6px; margin-left: auto; }
  .mark { padding: 1px 8px; border-radius: 8px; font-size: 0.75rem; font-weight: 700; }
  .mark.best { background: var(--primary); color: var(--on-primary); }
  .mark.chosen { background: var(--tertiary-container); color: var(--on-tertiary-container); }
  .figs { display: flex; flex-wrap: wrap; gap: 4px 14px; }
  .fig.win { color: var(--bad, #c62828); font-weight: 700; }
  .fig.ready { font-weight: 700; }
  .waits { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; }
  .wait { display: inline-flex; align-items: center; gap: 4px; line-height: 1.2; }
  .source { margin: 8px 4px 0; line-height: 1.5; }
</style>
