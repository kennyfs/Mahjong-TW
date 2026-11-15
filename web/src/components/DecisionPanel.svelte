<!--
  A decision's analysis by the network: every legal action from the best down, with what it costs against the best,
  and what the network thinks of the three opponents. Each row shows, as bars, where the action differs from the best
  (what it wins, what it pays for dealing in, what it pays to others' self-draws; hatched where it is worse than the
  best, filled where it is better), with the few figures that matter most (the settings' choice of numbers or both is
  gone: the user's, 2026-11-13). Without a network (it failed to load) the list is tile efficiency's.

  `played`: the index of the action taken (a review), or -1: its row comes first, the others from the best down. The
  best and the one taken are both set off (a filled row, a tag each: 最佳, and `who` took it: 你 or the seat's name);
  one row with both tags when they are the same. A row that loses a grade's worth against the best is tinted in the
  grade's color (yellow, orange, red), the one taken more strongly. A row's figure at the right is always its expected result, with what
  it loses against the best before it (smaller, in brackets) when that is more than a tie.
  `picked`: the one whose tile is chosen in the hand.
  `truth`: what the opponents really held, shown on request. `brief`: without the verdict on the action taken and the
  note on a best action that is not the obvious one (the review says them itself).
-->
<script lang="ts">
  import { tick } from 'svelte';
  import { actionText, actionTiles } from '../lib/actions';
  import { t } from '../lib/i18n.svelte';
  import { keepInBand } from '../lib/scroll';
  import { costOf, dangerTiles, efficiencyText, EQUAL, figureCells, gradeOf, GRADE_NAME, notableOf, notableText, pct, pts, readyLevel, reasonOf,
    tilesShort } from '../lib/review';
  import type { Analysis, Truth } from '../lib/types';
  import type { FigureKey } from '../lib/review';
  import AnalysisList from './AnalysisList.svelte';
  import FigureLine from './FigureLine.svelte';
  import FigureRuler from './FigureRuler.svelte';
  import Dialog from './Dialog.svelte';
  import Icon from './Icon.svelte';
  import Tile from './Tile.svelte';

  let { analysis, played = -1, unit = undefined, w = 32, names = null, truth = null, showTruth = false, picked = -1,
    brief = false, who = '' }: {
    analysis: Analysis; played?: number; unit?: number; w?: number; names?: string[] | null;
    truth?: Truth[] | null; showTruth?: boolean; picked?: number; brief?: boolean; who?: string;
  } = $props();

  /** The row of the tile chosen in the hand comes into the middle of the list's height (lib/scroll.ts). */
  let panelEl = $state<HTMLElement | null>(null);
  $effect(() => {
    if (picked < 0 || !panelEl) return;
    void tick().then(() => {
      const row = panelEl?.querySelector<HTMLElement>('.row.picked');
      if (row) keepInBand(row);
    });
  });
  /** The explanation of a row's figures, open. */
  let help = $state(false);
  const net = $derived(analysis.source === 'network' && analysis.best !== undefined);
  const best = $derived(analysis.best ?? 0);
  const rows = $derived(analysis.actions.map((f, i) => ({ f, i, cost: net ? costOf(analysis, i) : 0 }))
    .sort((a, b) => Number(b.i === played) - Number(a.i === played) || a.cost - b.cost || a.i - b.i));
  const notable = $derived(net ? notableOf(analysis, unit) : null);
  const playedCost = $derived(net && played >= 0 ? costOf(analysis, played) : 0);
  const grade = $derived(gradeOf(playedCost, unit));
  // The figures of each row in fixed columns (FigureRuler): the bars' rows a few of them.
  const figKeys = ['win', 'deal', 'danger', 'eff'] as const;
  const figLines = $derived(net ? rows.map(({ f }) => figureCells(f, null, oppNames, analysis.actions[best])) : []);
  let figWidths = $state<Partial<Record<FigureKey, number>>>({});
  const oppNames = $derived((analysis.opponents ?? []).map((o, r) => names?.[o.seat] ?? [t('rel_next'), t('rel_opposite'), t('rel_prev')][r]));

  // The bars: an action's three parts against the best's, drawn to one scale from a line two thirds along.
  const ZERO = 66;
  const diffs = $derived(rows.map(({ f }) => {
    const b = analysis.actions[best].parts, p = f.parts;
    return b && p ? [b[0] - p[0], b[1] - p[1], b[2] - p[2]] : [0, 0, 0];
  }));
  const perPoint = $derived.by(() => {
    let worse = 0.3, better = 0.15;
    for (const d of diffs) {
      worse = Math.max(worse, d.reduce((a, x) => a + Math.max(0, x), 0));
      better = Math.max(better, d.reduce((a, x) => a + Math.max(0, -x), 0));
    }
    return Math.min(ZERO / worse, (100 - ZERO) / better);
  });
  const PART = ['win', 'deal', 'tsumo'];
  function segments(d: number[]) {
    const out: { cls: string; left: number; width: number }[] = [];
    let l = ZERO, r = ZERO;
    d.forEach((x, k) => {
      const wd = Math.abs(x) * perPoint;
      if (wd < 0.4) return;
      if (x > 0) { l -= wd; out.push({ cls: PART[k] + ' worse', left: l, width: wd }); }
      else { out.push({ cls: PART[k], left: r, width: wd }); r += wd; }
    });
    return out;
  }
</script>

{#if !net}
  <AnalysisList {analysis} chosen={played >= 0 ? analysis.actions[played]?.action ?? null : null} {w} />
{:else}
  <div class="panel" bind:this={panelEl}>
    <div class="body-small"><FigureRuler lines={figLines} keys={figKeys} bind:widths={figWidths} /></div>
    {#if played >= 0 && !brief}
      <div class="verdict g{grade}">
        <span class="badge-g">{played === best ? t('grade_best') : playedCost < EQUAL ? t('grade_equal') : t(GRADE_NAME[grade])}</span>
        {#if playedCost >= EQUAL}<span class="bold">{t('review_loss', pts(playedCost, false))}</span>{/if}
        {#if played !== best && playedCost >= EQUAL}<span>{t('review_best_was', actionText(analysis.actions[best].action))}</span>{/if}
        {#if reasonOf(analysis, played)}<span class="dim">{reasonOf(analysis, played)}</span>{/if}
      </div>
    {/if}
    {#if notable && !brief}
      <div class="notable"><span class="star">★</span>{notableText(analysis, notable)}</div>
    {/if}
    <div class="legend body-small">
      <span>{t('bars_legend')}</span>
      <span class="key"><i class="win"></i>{t('bars_win')}</span>
      <span class="key"><i class="deal"></i>{t('bars_deal')}</span>
      <span class="key"><i class="tsumo"></i>{t('bars_tsumo')}</span>
      <span class="sides"><span class="key"><i class="worse"></i>{t('bars_worse')}</span><span class="key"><i class="better"></i>{t('bars_better')}</span></span>
      <!-- What the figure at each row's right is: said here, not on every row. -->
      <button class="btn icon text info" aria-label={t('list_help')} title={t('list_help')} aria-haspopup="dialog" onclick={() => (help = true)}>
        <Icon name="info" size={20} />
      </button>
    </div>
    {#each rows as { f, i, cost }, k (i)}
      {@const g = gradeOf(cost, unit)}
      <div class="row" class:g1={g === 1} class:g2={g === 2} class:g3={g === 3} class:strong={i === best || (i === played && g === 0)}
        class:played={i === played} class:picked={i === picked}>
        <div class="act">
          {#if actionTiles(f.action).length}
            <span class="tiles">{#each actionTiles(f.action) as x}<Tile tile={x} w={w * 0.82} />{/each}</span>
          {/if}
          <span class="label-large">{actionTiles(f.action).length ? t('act_' + f.action.type) : actionText(f.action)}</span>
          <span class="marks">
            {#if i === best}<span class="mark best">{t('grade_best')}</span>{/if}
            {#if i === played}<span class="mark you">{who || t('review_you')}</span>{/if}
          </span>
          <span class="cost">
            {#if i !== best && cost >= EQUAL}<span class="less g{g}">({pts(-cost)})</span>{/if}{pts(f.q ?? 0)}
          </span>
        </div>
        <div class="bar" aria-hidden="true">
          <span class="zero" style="left: {ZERO}%"></span>
          {#each segments(diffs[k]) as sg}<span class="seg {sg.cls}" style="left: {sg.left}%; width: {sg.width}%"></span>{/each}
        </div>
        <div class="figs body-small"><FigureLine cells={figLines[k]} keys={figKeys} widths={figWidths} /></div>
        {#if f.waits.length && f.shanten === 0}
          <div class="waits body-small">
            <span class="dim">{t('analysis_waits')}</span>
            {#each f.waits as wt (wt.tile)}<span class="wait"><Tile tile={wt.tile} w={w * 0.6} />{t('analysis_wait_left', wt.left)}</span>{/each}
          </div>
        {/if}
      </div>
    {/each}

    {#if analysis.opponents?.length}
      <div class="section-header">{t('opp_title')}</div>
      {#each analysis.opponents as o, r (o.seat)}
        {@const tr = showTruth ? truth?.find((x) => x.seat === o.seat) : undefined}
        <div class="opp">
          <div class="opp-head">
            <span class="label-large">{oppNames[r]}</span>
            <span class="ready l{readyLevel(o.ready[0])}">{t('opp_ready', pct(o.ready[0]))}</span>
            <span class="body-small dim">{t('opp_one_away', pct(o.ready[1]))}</span>
            {#if tr}
              <span class="truth body-small">{t('opp_truth')}：{tr.missed ? t('opp_missed') : tr.shanten === 0 ? t('opp_ready', tilesShort(tr.waits.map((x) => x.tile))) : t('analysis_shanten', tr.shanten)}</span>
            {/if}
          </div>
          <div class="opp-danger body-small">
            {#each dangerTiles(o) as d (d.tile)}
              <span class="wait"><Tile tile={d.tile} w={w * 0.6} danger={d.p >= 0.15 ? 3 : d.p >= 0.05 ? 2 : 1} />{pct(d.p)}</span>
            {:else}
              <span class="dim">{t('opp_no_danger')}</span>
            {/each}
          </div>
        </div>
      {/each}
    {/if}
    <p class="source body-small dim">{t('analysis_source_network')}</p>
  </div>
  {#if help}
    <Dialog title={t('list_help')} onclose={() => (help = false)}>
      <p class="body-large help">{t('list_help_text')}</p>
      {#snippet actions()}<button class="btn text" onclick={() => (help = false)}>{t('close')}</button>{/snippet}
    </Dialog>
  {/if}
{/if}

<style>
  .panel { display: flex; flex-direction: column; gap: 6px; position: relative; }
  .verdict { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 10px; padding: 8px 12px; border-radius: 12px; background: var(--surface-container-high); }
  .badge-g { padding: 1px 8px; border-radius: 8px; font-weight: 700; font-size: 0.8125rem; background: var(--found); color: #fff; }
  .g1 .badge-g { background: var(--danger-1); color: var(--on-danger); }
  .g2 .badge-g { background: var(--danger-2); color: var(--on-danger); }
  .g3 .badge-g { background: var(--danger-3); color: #fff; }
  .notable { display: flex; gap: 6px; padding: 6px 12px; border-radius: 12px; background: var(--tertiary-container); color: var(--on-tertiary-container); font-weight: 500; }
  .star { color: var(--found); }
  .legend { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; padding: 0 4px; color: var(--on-surface-variant); }
  .info { margin-left: auto; width: 32px; min-height: 32px; border-radius: 16px; color: var(--on-surface-variant); }
  .help { margin: 0; line-height: 1.6; }
  .key { display: inline-flex; align-items: center; gap: 4px; }
  .key i { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
  .win { background: var(--found); }
  .deal { background: var(--danger-3); }
  .tsumo { background: var(--danger-2); }
  .row { display: flex; flex-direction: column; gap: 4px; padding: 7px 12px; border-radius: 12px; background: var(--surface-container); }
  .row.strong { background: var(--secondary-container); color: var(--on-secondary-container); }
  /* What an action loses against the best, by its grade: yellow (questionable), orange (a mistake), red (a blunder);
     stronger on the one taken, which is not filled as the best is then. */
  .row.g1 { background: color-mix(in srgb, var(--danger-1) 18%, var(--surface-container)); }
  .row.g2 { background: color-mix(in srgb, var(--danger-2) 18%, var(--surface-container)); }
  .row.g3 { background: color-mix(in srgb, var(--danger-3) 15%, var(--surface-container)); }
  .row.played.g1 { background: color-mix(in srgb, var(--danger-1) 45%, var(--surface-container)); }
  .row.played.g2 { background: color-mix(in srgb, var(--danger-2) 42%, var(--surface-container)); }
  .row.played.g3 { background: color-mix(in srgb, var(--danger-3) 34%, var(--surface-container)); }
  .row.g1 .less, .row.g2 .less, .row.g3 .less { color: color-mix(in srgb, var(--danger-3) 45%, var(--on-surface)); font-weight: 700; }
  .row.picked { box-shadow: inset 0 0 0 2px var(--highlight); }
  .act { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .tiles { display: inline-flex; gap: 1px; }
  .marks { display: inline-flex; gap: 6px; }
  .mark { padding: 1px 8px; border-radius: 8px; font-size: 0.75rem; font-weight: 700; }
  .mark.best { background: var(--found); color: #fff; }
  .mark.you { background: var(--primary); color: var(--on-primary); }
  .cost { margin-left: auto; font-weight: 700; font-variant-numeric: tabular-nums; }
  .less { font-size: 0.8125rem; margin-right: 6px; }
  .less.g1 { color: color-mix(in srgb, var(--danger-1) 60%, var(--on-surface)); }
  .less.g2 { color: color-mix(in srgb, var(--danger-2) 75%, var(--on-surface)); }
  .less.g3 { color: var(--danger-3); }
  .figs { display: flex; flex-wrap: wrap; gap: 2px 12px; font-variant-numeric: tabular-nums; }
  .row.strong .dim { color: inherit; opacity: 0.75; }
  .bar { position: relative; height: 12px; border-radius: 6px; background: color-mix(in srgb, var(--on-surface) 8%, transparent); overflow: hidden; }
  .zero { position: absolute; top: 0; bottom: 0; width: 2px; margin-left: -1px; background: var(--on-surface); opacity: 0.55; z-index: 1; }
  .seg { position: absolute; top: 0; bottom: 0; }
  /* Left of the line the action is worse than the best there: hatched, so that a long green bar is not read as a
     good chance of winning. Right of it (better there): filled. */
  .seg.worse, .key i.worse { background-image: repeating-linear-gradient(135deg, transparent 0 3px, rgba(255, 255, 255, 0.6) 3px 5.5px); }
  .key i.worse, .key i.better { background-color: var(--on-surface-variant); }
  .sides { display: inline-flex; gap: 10px; }
  .waits, .opp-danger { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; }
  .wait { display: inline-flex; align-items: center; gap: 3px; font-variant-numeric: tabular-nums; }
  .opp { display: flex; flex-direction: column; gap: 4px; padding: 7px 12px; border-radius: 12px; background: var(--surface-container); }
  .opp-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 10px; }
  .ready { padding: 0 8px; border-radius: 8px; font-weight: 700; font-size: 0.8125rem; background: var(--surface-container-highest); }
  .ready.l1 { background: var(--danger-1); color: var(--on-danger); }
  .ready.l2 { background: var(--danger-2); color: var(--on-danger); }
  .ready.l3 { background: var(--danger-3); color: #fff; }
  .truth { margin-left: auto; font-weight: 700; }
  .source { margin: 6px 4px 0; line-height: 1.5; }
  .panel :global(.section-header) { padding: 10px 4px 2px; }
</style>
