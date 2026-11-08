<!--
  One decision's analysis, from a finished hand (its review): the engine's figures for each legal action of the
  deciding seat, the one taken marked, with the table as that seat saw it. A large window shows both side by side; a
  phone shows the seat's hand beside the list when sideways and over it when upright, and the whole table at a tap
  (so that no tile gets small). Only after the hand: never while it is played.
-->
<script lang="ts">
  import type { AnalysisTarget } from '../lib/app.svelte';
  import * as engine from '../lib/engine';
  import { t } from '../lib/i18n.svelte';
  import { flowersOf, meldTiles } from '../lib/seats';
  import { parseTiles, sortTiles, tileName, windName, without } from '../lib/tiles';
  import type { Analysis, View } from '../lib/types';
  import { NAV_RAIL, ui } from '../lib/ui.svelte';
  import AnalysisList from './AnalysisList.svelte';
  import Icon from './Icon.svelte';
  import Table from './Table.svelte';
  import Tile from './Tile.svelte';
  import TitleBar from './TitleBar.svelte';

  let { target, onback }: { target: AnalysisTarget | null; onback: () => void } = $props();
  let view = $state<View | null>(null);
  let analysis = $state<Analysis | null>(null);
  let error = $state<string | null>(null);
  let showTable = $state(false);
  $effect(() => {
    const g = target?.game;
    const seat = target?.seat ?? 0;
    view = null;
    analysis = null;
    error = null;
    if (!g) return;
    engine.view(g, seat).then((v) => (view = v), (e) => (error = String(e.message ?? e)));
    engine.analyzeGame(g, seat).then((a) => (analysis = a), (e) => (error = String(e.message ?? e)));
  });
  const names = $derived(target?.names ?? [t('you'), t('cpu_n', 1), t('cpu_n', 2), t('cpu_n', 3)]);
  const seat = $derived(target?.seat ?? 0);
  const sideBySide = $derived(ui.wide && ui.w >= 1000);
  // A phone sideways: the hand in one column, the list in the other (as the app's DecisionAnalysisScreen).
  const sideways = $derived(ui.wide && !sideBySide);

  // The deciding seat's tiles: the drawn one apart, or the discard decided on.
  const mine = $derived.by(() => {
    if (!view) return null;
    const pos = view.position;
    const all = parseTiles(pos.hand);
    const drawn = view.phase === 'self' && pos.drawn ? pos.drawn : null;
    const p = pos.players[seat];
    return { tiles: sortTiles(drawn ? without(all, [drawn]) : all), drawn, melds: p.melds, flowers: flowersOf(p) };
  });
  const situation = $derived.by(() => {
    if (!view) return '';
    const pos = view.position;
    const head = `${t('round_wind', windName(pos.setup.round))}・${t('wall_left', pos.wall_left)}`;
    if (pos.claim && pos.claim.tile) return `${head}・${names[pos.claim.seat]} ${t('act_discard')} ${tileName(pos.claim.tile)}`;
    if (mine?.drawn) return `${head}・${t('analysis_drawn')} ${tileName(mine.drawn)}`;
    return head;
  });
  const hw = $derived(sideways ? Math.min(44, ((ui.w - NAV_RAIL) / 2 - 24) / 9.4) : Math.min(44, (Math.min(ui.w, 720) - 32) / 9.4));
</script>

{#snippet summary()}
  {#if mine && view}
    <div class="summary">
      <div class="body-medium dim">{situation}</div>
      <div class="hand">
        {#each mine.tiles as x, i (i)}<Tile tile={x} w={hw} />{/each}
        {#if mine.drawn}<span class="gap"></span><Tile tile={mine.drawn} w={hw} glow />{/if}
        {#if view.position.claim?.tile}<span class="gap"></span><Tile tile={view.position.claim.tile} w={hw} mark />{/if}
      </div>
      {#if mine.melds.length || mine.flowers.length}
        <div class="melds">
          {#each mine.melds as m, i (i)}<span class="meld">{#each meldTiles(m) as x}<Tile tile={x} w={hw * 0.75} />{/each}</span>{/each}
          {#each mine.flowers as f (f)}<Tile tile={f} w={hw * 0.6} />{/each}
        </div>
      {/if}
    </div>
  {/if}
{/snippet}

<div class="page">
  <TitleBar title={target ? t('analysis_title_decision', names[seat]) : t('analyze_title')} {onback}>
    {#if !sideBySide}
      <button class="chip" class:on={showTable} aria-pressed={showTable} onclick={() => (showTable = !showTable)}>
        <Icon name={showTable ? 'assessment' : 'tile'} size={18} />{showTable ? t('analysis_results') : t('records_table')}
      </button>
    {/if}
  </TitleBar>
  {#if error}<p class="body-large bad pad">{t('analysis_error', error)}</p>{/if}
  {#if sideBySide}
    <div class="body wide">
      <div class="table-wrap">
        {#if view && target}<Table {view} me={seat} {names} interactive={false} maxHand={ui.desk ? 60 : 52} />{/if}
      </div>
      <div class="results">
        {#if analysis}
          <div class="section-header">{t('analysis_results')}</div>
          <div class="list"><AnalysisList {analysis} chosen={target?.chosen ?? null} w={34} /></div>
        {:else if !error}<div class="loading"><span class="spinner"></span></div>{/if}
      </div>
    </div>
  {:else if showTable}
    <div class="table-wrap">
      {#if view && target}<Table {view} me={seat} {names} interactive={false} />{/if}
    </div>
  {:else if sideways}
    <div class="body">
      <div class="scroll half">{@render summary()}</div>
      <div class="scroll half">
        {#if analysis}
          <div class="section-header">{t('analysis_results')}</div>
          <div class="list"><AnalysisList {analysis} chosen={target?.chosen ?? null} w={Math.max(30, hw * 0.8)} /></div>
        {:else if !error}<div class="loading"><span class="spinner"></span></div>{/if}
      </div>
    </div>
  {:else}
    <div class="scroll"><div class="readable">
      {@render summary()}
      {#if analysis}
        <div class="section-header">{t('analysis_results')}</div>
        <div class="list"><AnalysisList {analysis} chosen={target?.chosen ?? null} w={Math.max(30, hw * 0.8)} /></div>
      {:else if !error}<div class="loading"><span class="spinner"></span></div>{/if}
    </div></div>
  {/if}
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .pad { padding: 8px 16px; }
  .chip.on { background: var(--secondary-container); color: var(--on-secondary-container); border-color: transparent; }
  .body { flex: 1; min-height: 0; display: flex; }
  .table-wrap { flex: 1; min-height: 0; min-width: 0; position: relative; }
  .results { flex: 0 0 min(460px, 40%); min-height: 0; overflow: auto; padding-bottom: 16px; }
  .scroll { flex: 1; overflow: auto; padding-bottom: 16px; }
  .half { flex: 1 1 0; min-width: 0; }
  .readable { max-width: 760px; margin: 0 auto; }
  .summary { display: flex; flex-direction: column; gap: 8px; padding: 8px 16px; }
  .hand { display: flex; flex-wrap: wrap; row-gap: 6px; align-items: flex-end; }
  .gap { width: 10px; }
  .melds { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: flex-end; }
  .meld { display: inline-flex; }
  .list { padding: 0 12px; }
  .loading { display: flex; justify-content: center; padding: 32px; }
  .spinner { width: 36px; height: 36px; border-radius: 50%; border: 4px solid transparent; border-top-color: var(--primary); animation: spin 0.9s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
</style>
