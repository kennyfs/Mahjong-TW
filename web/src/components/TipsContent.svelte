<!--
  Table tips (打牌要訣): what self-play says about attack and defense, as tables a player can carry to the table: the
  river's marks (摸切, the first discard after a call, claimed tiles), whether an opponent is ready by its discards and
  melds, when the network falls back, and which tiles deal in least (lib/explain.ts, docs/explain.md). Shown on its own
  page (Tips.svelte, from the settings) and over a review (from a decision's explanation). The app's TipsContent is the same.
-->
<script lang="ts">
  import { percent, steps, TIPS_FOLD, TIPS_HALF, TIPS_SAFETY, TIPS_THREAT } from '../lib/explain';
  import { t } from '../lib/i18n.svelte';
  import type { Discard } from '../lib/types';
  import River from './River.svelte';

  const SAMPLE: Discard[] = [
    { tile: '1z', drawn: false, called: false }, { tile: '9m', drawn: true, called: false },
    { tile: '2p', drawn: false, called: true }, { tile: '7s', drawn: false, called: false, after_call: true },
    { tile: '4z', drawn: true, called: false }, { tile: '8p', drawn: true, called: false },
  ];
  const pc = (p: number) => (p < 0.0005 ? '0%' : p < 0.0095 ? `${(p * 100).toFixed(1)}%` : `${Math.round(p * 100)}%`);
  // A cell's shade by its chance (0 to 1): the higher, the redder.
  const shade = (p: number, top: number) => `--a: ${Math.round(Math.min(1, p / top) * 48)}%`;
  // A row's label as a heading: "Ready", not the sentence's "ready".
  const cap = (x: string) => x.charAt(0).toUpperCase() + x.slice(1);
  const RANKS = ['tips_row_honor', 'explain_rank_19', 'explain_rank_28', 'explain_rank_37', 'explain_rank_456'];
</script>

<div class="tips">
  <section>
    <h3 class="title-medium">{t('tips_river_title')}</h3>
    <div class="sample"><River discards={SAMPLE} w={34} cols={6} /></div>
    <p class="body-medium">{t('tips_river_text')}</p>
  </section>

  <section>
    <h3 class="title-medium">{t('tips_ready_title')}</h3>
    <p class="body-medium">{t('tips_ready_text')}</p>
    <table class="body-small">
      <thead>
        <tr><th rowspan="2">{t('tips_discards')}</th><th colspan="5" class="group-head">{t('tips_melds_head')}</th></tr>
        <tr>{#each [0, 1, 2, 3, 4] as m (m)}<th>{m}</th>{/each}</tr>
      </thead>
      <tbody>
        {#each ['3–5', '6–8', '9–11', '12+'] as label, i (label)}
          <tr><th>{label}</th>{#each TIPS_THREAT[i] as p, m (m)}{#if p === null}<td class="none">–</td>{:else}<td style={shade(p, 0.9)}>{pc(p)}</td>{/if}{/each}</tr>
        {/each}
      </tbody>
    </table>
  </section>

  <section>
    <h3 class="title-medium">{t('tips_fold_title')}</h3>
    <p class="body-medium">{t('tips_fold_text')}</p>
    <table class="body-small">
      <thead><tr><th>{t('tips_wall_head')}</th>{#each ['30+', '20–29', '10–19', '<10'] as w (w)}<th>{w}</th>{/each}</tr></thead>
      <tbody>
        {#each [steps(0), steps(1), steps(2), t('tips_away_more')].map(cap) as label, i (i)}
          <tr><th>{label}</th>{#each TIPS_FOLD[i] as p, j (j)}<td style={shade(p, 0.7)}>{pc(p)}</td>{/each}</tr>
        {/each}
      </tbody>
    </table>
  </section>

  <section>
    <h3 class="title-medium">{t('tips_safe_title')}</h3>
    <p class="body-medium">{t('tips_safe_text')}</p>
    <table class="body-small">
      <thead><tr><th></th><th>{t('tips_col_genbutsu')}</th><th>{t('tips_col_suji')}</th><th>{t('tips_col_seen', 2)}</th>
        <th>{t('tips_col_seen', 1)}</th><th>{t('tips_col_unseen')}</th></tr></thead>
      <tbody>
        {#each RANKS as r, i (r)}
          <tr><th>{t(r)}</th>{#each TIPS_SAFETY[i] as p, j (j)}{#if p === null}<td class="none">–</td>{:else}<td style={shade(p, 0.14)}>{pc(p)}</td>{/if}{/each}</tr>
        {/each}
      </tbody>
    </table>
    <p class="body-small dim">{t('tips_safe_note')}</p>
    <p class="body-small dim">{t('tips_half_note', percent(TIPS_HALF))}</p>
  </section>

  <p class="body-small dim">{t('tips_source')}</p>
  <p class="body-small dim">{t('tips_more')}</p>
</div>

<style>
  .tips { display: flex; flex-direction: column; gap: 20px; }
  section { display: flex; flex-direction: column; gap: 8px; }
  h3, p { margin: 0; line-height: 1.55; }
  .sample { padding: 8px; border-radius: 12px; background: var(--felt); align-self: flex-start; }
  table { border-collapse: separate; border-spacing: 2px; width: 100%; max-width: 520px; table-layout: fixed; }
  th { font-weight: 500; color: var(--on-surface-variant); text-align: center; padding: 3px 2px; line-height: 1.25; }
  th.group-head { padding-bottom: 1px; }
  tbody th { text-align: left; white-space: nowrap; }
  thead tr:first-child th:first-child, tbody th { width: 22%; }
  td { text-align: center; padding: 6px 2px; border-radius: 6px; font-variant-numeric: tabular-nums;
    background: color-mix(in srgb, var(--error) var(--a, 0%), var(--surface-container-high));
    border: 1px solid color-mix(in srgb, var(--outline-variant, var(--outline)) 35%, transparent); }
  td.none { background: var(--surface-container-high); color: var(--outline); }
</style>
