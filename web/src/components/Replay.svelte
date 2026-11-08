<!--
  A finished hand's review: the table as the player saw it (or everyone's tiles), step by step through the record's
  decisions (others' passes skipped), each decision open to analysis, and the result at the end.
-->
<script lang="ts">
  import { actionText, fullAction } from '../lib/actions';
  import type { AnalysisTarget } from '../lib/app.svelte';
  import * as engine from '../lib/engine';
  import { t } from '../lib/i18n.svelte';
  import { nav } from '../lib/nav.svelte';
  import { records } from '../lib/records.svelte';
  import { recordAction, type View } from '../lib/types';
  import { ui } from '../lib/ui.svelte';
  import HandResult from './HandResult.svelte';
  import DecisionAnalysis from './DecisionAnalysis.svelte';
  import Icon from './Icon.svelte';
  import Table from './Table.svelte';
  import TitleBar from './TitleBar.svelte';

  let { id, onback }: { id: string | null; onback: () => void } = $props();
  /** A short wide window (a phone held sideways): the controls in a column at the right, the table the full height. */
  const side = $derived(ui.wide && ui.h < 560);
  /** A decision's analysis over the review (Back returns to the same step). */
  let analysis = $state<AnalysisTarget | null>(null);
  function onanalyze(target: AnalysisTarget) {
    analysis = target;
    nav.guard(closeAnalysis);
  }
  function closeAnalysis() {
    analysis = null;
    nav.guard(null);
  }

  const rec = $derived(id ? records.get(id) : undefined);
  const n = $derived(rec?.game.actions.length ?? 0);
  let step = $state(0);
  let showAll = $state(false);
  let showResult = $state(true);
  let view = $state<View | null>(null);
  let final = $state<View | null>(null);
  let loaded = '';

  // A new hand starts at its deal.
  $effect(() => {
    if (rec && loaded !== rec.id) {
      loaded = rec.id;
      step = 0;
      void engine.view(rec.game, rec.me, 0, true).then((v) => (final = v));
    }
  });
  $effect(() => {
    if (!rec) return;
    const k = step, all = showAll;
    const game = { ...rec.game, actions: rec.game.actions.slice(0, k) };
    void engine.view(game, rec.me, 0, all || k === n).then((v) => { if (step === k && showAll === all) view = v; });
  });

  const names = $derived(rec?.names.map((x, i) => (i === rec.me ? t('you') : x)) ?? []);
  const isStop = (k: number) => {
    const a = rec!.game.actions[k];
    return a[1] !== 'pass' || a[0] === rec!.me;
  };
  function next() { let k = step + 1; while (k < n && !isStop(k)) k++; step = Math.min(k, n); showResult = true; }
  function prev() { let k = step - 1; while (k > 0 && !isStop(k)) k--; step = Math.max(0, k); }
  function nextMine() { let k = step + 1; while (k < n && rec!.game.actions[k][0] !== rec!.me) k++; step = Math.min(k, n); showResult = true; }
  function prevMine() { let k = step - 1; while (k > 0 && rec!.game.actions[k][0] !== rec!.me) k--; step = Math.max(0, k); }

  const deciding = $derived.by(() => {
    if (!rec || step >= n) return null;
    const { seat, action } = recordAction(rec.game.actions[step]);
    return { seat, action: fullAction(action) };
  });
  function analyze() {
    if (!rec || !deciding) return;
    onanalyze({
      game: { ...rec.game, actions: rec.game.actions.slice(0, step) }, seat: deciding.seat, rules: rec.game.rules,
      chosen: deciding.action, names,
    });
  }
  function onkey(e: KeyboardEvent) {
    if (analysis) return;
    if (e.key === 'ArrowRight') next();
    else if (e.key === 'ArrowLeft') prev();
    else if (e.key === 'ArrowDown') nextMine();
    else if (e.key === 'ArrowUp') prevMine();
    else return;
    e.preventDefault();
  }
</script>

<svelte:window onkeydown={onkey} />
{#if analysis}
  <DecisionAnalysis target={analysis} onback={closeAnalysis} />
{/if}
<div class="page" class:side={side} hidden={!!analysis}>
  {#if !side}
  <TitleBar title={t('replay_title')} {onback}>
    {#if rec}
      <button class="btn icon text" aria-label={t('favorite')} aria-pressed={!!rec.fav} onclick={() => records.setFav(rec.id, !rec.fav)}>
        <Icon name={rec.fav ? 'star' : 'starBorder'} />
      </button>
      <button class="chip" class:on={showAll} aria-pressed={showAll} onclick={() => (showAll = !showAll)}>
        <Icon name="visibility" size={18} />{ui.w > 480 ? t('replay_show_all') : ''}
      </button>
    {/if}
  </TitleBar>
  {/if}
  {#if !rec}
    <p class="body-large dim pad">{t('replay_nothing')}</p>
  {:else}
    <div class="table-wrap">
      {#if view}
        <Table {view} me={rec.me} {names} revealed={showAll || step === n ? (view.hands ?? null) : null} interactive={false}
          maxHand={ui.desk ? 64 : 56}>
          {#snippet overlay()}
            {#if step === n && final?.result && showResult}
              <div class="sheet">
                <HandResult {final} {names} me={rec.me} w={ui.wide ? Math.min(36, Math.max(24, ui.w / 30)) : Math.min(34, Math.max(24, ui.w / 13))}>
                  {#snippet actions()}
                    <button class="btn text" onclick={() => (showResult = false)}>{t('result_hands')}</button>
                    <button class="btn tonal" onclick={() => (step = 0)}>{t('replay_start')}</button>
                  {/snippet}
                </HandResult>
              </div>
            {/if}
          {/snippet}
        </Table>
      {/if}
    </div>
    <div class="controls">
      {#if side}
        <div class="side-top">
          <button class="btn icon text" aria-label={t('back')} onclick={onback}><Icon name="back" /></button>
          <button class="btn icon text" aria-label={t('favorite')} aria-pressed={!!rec.fav} onclick={() => records.setFav(rec.id, !rec.fav)}>
            <Icon name={rec.fav ? 'star' : 'starBorder'} />
          </button>
          <button class="btn icon text" class:on={showAll} aria-label={t('replay_show_all')} aria-pressed={showAll} onclick={() => (showAll = !showAll)}>
            <Icon name="visibility" />
          </button>
        </div>
      {/if}
      {#snippet analyzeButton()}
        {#if deciding}
          <button class="btn filled analyze" onclick={analyze}><Icon name="assessment" size={20} />{t('analyze_title')}</button>
        {:else if step >= n && !showResult}
          <button class="btn tonal analyze" onclick={() => (showResult = true)}>{t('replay_result')}</button>
        {/if}
      {/snippet}
      <!-- Upright: the step and the choice made, with the analysis button at their end; the steps under them (the app's
           ReplayScreen: all seven in one row left the button no room at a large font). -->
      <div class="info-row">
        <div class="info body-medium">
          <span class="label-large">{step >= n ? t('replay_end') : t('replay_step', step + 1, n)}</span>
          {#if deciding}
            <span class="decide ellipsis">{deciding.seat === rec.me ? t('replay_you_chose', actionText(deciding.action)) : t('replay_seat_chose', names[deciding.seat], actionText(deciding.action))}</span>
          {/if}
        </div>
        {#if !side}{@render analyzeButton()}{/if}
      </div>
      <div class="buttons">
        <button class="btn icon tonal" aria-label={t('replay_start')} disabled={step === 0} onclick={() => (step = 0)}><Icon name="skipPrev" /></button>
        <button class="btn icon tonal" aria-label={t('replay_prev_decision')} disabled={step === 0} onclick={prevMine}><Icon name="undo" /></button>
        <button class="btn icon tonal" aria-label={t('replay_prev')} disabled={step === 0} onclick={prev}><Icon name="prev" /></button>
        <button class="btn icon filled" aria-label={t('replay_next')} disabled={step >= n} onclick={next}><Icon name="next" /></button>
        <button class="btn icon tonal mirror" aria-label={t('replay_next_decision')} disabled={step >= n} onclick={nextMine}><Icon name="undo" /></button>
        <button class="btn icon tonal" aria-label={t('replay_end')} disabled={step >= n} onclick={() => { step = n; showResult = true; }}><Icon name="skipNext" /></button>
        {#if side}{@render analyzeButton()}{/if}
      </div>
    </div>
  {/if}
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .page.side { flex-direction: row; }
  .page[hidden] { display: none; }
  .side .controls { width: 176px; padding: 4px 8px; justify-content: flex-start; overflow: auto; }
  .side .info { flex-direction: column; gap: 2px; }
  .side .decide { white-space: normal; }
  .side .buttons { display: grid; grid-template-columns: repeat(3, 42px); gap: 6px; justify-content: center; }
  .side .analyze { grid-column: 1 / -1; justify-content: center; }
  .side-top { display: flex; justify-content: space-between; }
  .side-top .on { background: var(--secondary-container); }
  .pad { padding: 24px; }
  .chip.on { background: var(--secondary-container); color: var(--on-secondary-container); border-color: transparent; }
  .table-wrap { flex: 1; min-height: 0; position: relative; }
  .controls { flex: none; display: flex; flex-direction: column; gap: 4px; padding: 6px 10px 8px; background: var(--surface-container); }
  .info-row { display: flex; align-items: center; gap: 8px; min-height: 40px; }
  .info { flex: 1; display: flex; gap: 12px; align-items: baseline; min-width: 0; }
  .decide { color: var(--on-surface-variant); min-width: 0; }
  .buttons { display: flex; align-items: center; justify-content: space-between; gap: 4px; max-width: 480px; }
  .buttons .btn.icon { width: 42px; min-height: 42px; border-radius: 21px; }
  .mirror :global(svg) { transform: scaleX(-1); }
  .analyze { gap: 6px; }
  .sheet { position: absolute; inset: 0; z-index: 5; display: flex; align-items: center; justify-content: center; padding: 12px;
    background: rgba(0, 0, 0, 0.35); }
  .sheet :global(.result) { max-width: min(760px, 100%); }
</style>
