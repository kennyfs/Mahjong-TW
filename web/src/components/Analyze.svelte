<!--
  分析: a position set up by hand (the player's hand and the tile drawn, or another player's discard to decide on;
  every seat's melds, flowers and discards; the dealer, the round wind, the wall), then the engine's figures for each
  legal action. A section is chosen by a tap, and the palette's tiles go into it; a tile tapped in a section leaves.
-->
<script lang="ts">
  import * as engine from '../lib/engine';
  import { t } from '../lib/i18n.svelte';
  import { editor, FLOWER_PALETTE, meldOf, PALETTE, sameSection, type Mode, type Section } from '../lib/position-editor.svelte';
  import { settings } from '../lib/settings.svelte';
  import { tileName, windName, type Tile as T } from '../lib/tiles';
  import type { Analysis, Meld } from '../lib/types';
  import { ui } from '../lib/ui.svelte';
  import AnalysisList from './AnalysisList.svelte';
  import Icon from './Icon.svelte';
  import Tile from './Tile.svelte';
  import TitleBar from './TitleBar.svelte';
  import Toggle from './Toggle.svelte';

  const ed = editor;
  const MODES: [Mode, string][] = [['self', t('analysis_my_turn')], ['claim', t('analysis_on_discard')]];
  let result = $state<Analysis | null>(null);
  let error = $state<string | null>(null);
  let running = $state(false);
  let meldTile = $state<T | null>(null);
  let resultsEl = $state<HTMLElement | null>(null);

  const seatName = (s: number) => [t('analysis_seat_you'), t('rel_next'), t('rel_opposite'), t('rel_prev')][s];
  const seatShort = (s: number) => [t('you'), t('rel_next'), t('rel_opposite'), t('rel_prev')][s];
  // Tiles: the palette's large enough to tap (a phone's 9 a row), the sections' a little smaller.
  const pw = $derived(ui.wide ? Math.min(48, Math.max(34, (ui.w * 0.42 - 24) / 9.4)) : Math.min(48, (ui.w - 24) / 9.4));
  const sw = $derived(Math.max(26, pw * 0.8));
  const isOn = (s: Section) => sameSection(ed.section, s);
  const palette = $derived(ed.section.kind === 'flowers' ? FLOWER_PALETTE : PALETTE);
  const missing = $derived.by(() => {
    const m = ed.missing();
    if (m) return t('analysis_need_tiles', m.need, m.have);
    if (ed.mode === 'self' && !ed.drawn) return t('analysis_drawn');
    if (ed.mode === 'claim' && !ed.claimTile) return t('analysis_claim_tile');
    return null;
  });

  function pick(tile: T) {
    if (ed.section.kind === 'melds') {
      meldTile = tile;
      return;
    }
    ed.add(tile);
    result = null;
  }
  function addMeld(type: Meld['type']) {
    if (meldTile && ed.section.kind === 'melds') ed.addMeld(ed.section.seat, type, meldTile);
    meldTile = null;
    result = null;
  }
  function remove(s: Section, i: number) {
    ed.section = s;
    ed.removeAt(s, i);
    result = null;
  }
  async function run() {
    if (missing) return;
    running = true;
    error = null;
    try {
      result = await engine.analyzePosition(settings.spec, ed.position(), 'analysis');
      setTimeout(() => resultsEl?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 50);
    } catch (e) {
      result = null;
      error = String((e as Error).message ?? e);
    } finally {
      running = false;
    }
  }
  const chiOk = (x: T) => x[1] !== 'z' && x[1] !== 'f' && Number(x[0]) <= 7;
</script>

{#snippet tiles(list: T[], s: Section, w: number)}
  <div class="tiles">
    {#each list as x, i (i + x)}
      <button class="tile-btn" aria-label={tileName(x)} onclick={() => remove(s, i)}><Tile tile={x} {w} /></button>
    {/each}
  </div>
{/snippet}

{#snippet section(s: Section, title: string, body: import('svelte').Snippet)}
  <div class="section" class:on={isOn(s)} role="button" tabindex="0" onclick={() => (ed.section = s)}
    onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') ed.section = s; }}>
    <div class="sec-title label-large">{title}{#if isOn(s)}<span class="editing">{t('analysis_editing')}</span>{/if}</div>
    {@render body()}
  </div>
{/snippet}

<div class="page">
  <TitleBar title={t('analyze_title')}>
    <button class="btn text" onclick={() => { ed.random(); result = null; }}>{t('analysis_random')}</button>
    <button class="btn text" onclick={() => { ed.clear(); result = null; }}>{t('analysis_clear')}</button>
  </TitleBar>
  <div class="body" class:wide={ui.wide}>
    <div class="edit">
      <div class="setup">
        <div class="field">
          <span class="label-large">{t('analysis_situation')}</span>
          <Toggle options={MODES} selected={ed.mode}
            onselect={(v) => { ed.mode = v; result = null; ed.section = { kind: v === 'self' ? 'drawn' : 'claim' }; }} />
        </div>
        <div class="row-fields">
          <label class="field small"><span class="label-large">{t('analysis_dealer')}</span>
            <select bind:value={ed.dealer}>{#each [0, 1, 2, 3] as s}<option value={s}>{seatShort(s)}</option>{/each}</select></label>
          <label class="field small"><span class="label-large">{t('analysis_round')}</span>
            <select bind:value={ed.round}>{#each [0, 1, 2, 3] as w}<option value={w}>{windName(w)}</option>{/each}</select></label>
          <label class="field small"><span class="label-large">{t('analysis_wall_left')}</span>
            <input type="number" min="0" max="120" bind:value={ed.wallLeft} /></label>
          {#if ed.mode === 'claim'}
            <label class="field small"><span class="label-large">{t('analysis_claim_from')}</span>
              <select bind:value={ed.claimSeat}>{#each [1, 2, 3] as s}<option value={s}>{seatShort(s)}</option>{/each}</select></label>
          {/if}
        </div>
      </div>

      {#snippet handBody()}
        {@render tiles(ed.hand, { kind: 'hand' }, sw)}
        <div class="body-small dim">{t('analysis_need_tiles', ed.handSize, ed.hand.length)}</div>
      {/snippet}
      {@render section({ kind: 'hand' }, t('analysis_hand'), handBody)}

      {#if ed.mode === 'self'}
        {#snippet drawnBody()}{@render tiles(ed.drawn ? [ed.drawn] : [], { kind: 'drawn' }, sw)}{/snippet}
        {@render section({ kind: 'drawn' }, t('analysis_drawn'), drawnBody)}
      {:else}
        {#snippet claimBody()}{@render tiles(ed.claimTile ? [ed.claimTile] : [], { kind: 'claim' }, sw)}{/snippet}
        {@render section({ kind: 'claim' }, t('analysis_claim_tile'), claimBody)}
      {/if}

      {#each [0, 1, 2, 3] as seat (seat)}
        <div class="seat-title title-small">{seatName(seat)}{ed.dealer === seat ? '・' + t('dealer_mark') : ''}</div>
        {#snippet meldsBody()}
          <div class="tiles">
            {#each ed.melds[seat] as m, i (i)}
              <button class="meld" onclick={(e) => { e.stopPropagation(); remove({ kind: 'melds', seat }, i); }}>
                {#each meldOf(m) as x, k}<Tile tile={m.type === 'ankan' && (k === 0 || k === 3) ? null : x} w={sw * 0.85} />{/each}
              </button>
            {/each}
          </div>
        {/snippet}
        {@render section({ kind: 'melds', seat }, t('analysis_melds'), meldsBody)}
        {#snippet flowersBody()}{@render tiles(ed.flowers[seat], { kind: 'flowers', seat }, sw * 0.85)}{/snippet}
        {#if settings.rules.flowers !== 'none' || ed.flowers[seat].length}
          {@render section({ kind: 'flowers', seat }, t('analysis_flowers'), flowersBody)}
        {/if}
        {#snippet discardsBody()}{@render tiles(ed.discards[seat], { kind: 'discards', seat }, sw * 0.85)}{/snippet}
        {@render section({ kind: 'discards', seat }, t('analysis_discards'), discardsBody)}
      {/each}

      {#if !ui.wide}
        {#if error}<p class="bad body-medium pad">{t('analysis_error', error)}</p>{/if}
        {#if result}<div class="results" bind:this={resultsEl}><AnalysisList analysis={result} w={sw} /></div>{/if}
      {/if}
    </div>

    <div class="side">
      {#if !ui.wide}
        <!-- A tall window: the button over the palette, always in view (the results come under the editor). -->
        <div class="run">
          {#if missing}<span class="body-medium dim">{missing}</span>{/if}
          <button class="btn filled" disabled={!!missing || running} onclick={run}>{t('analysis_run')}</button>
        </div>
      {/if}
      <div class="palette">
        <div class="body-small dim hint">{t('analysis_palette_hint')}</div>
        <div class="grid" style="grid-template-columns: repeat(9, {pw}px)">
          {#each palette as x (x)}
            <button class="tile-btn" aria-label={tileName(x)} disabled={ed.left(x) <= 0} onclick={() => pick(x)}>
              <Tile tile={x} w={pw} dim={ed.left(x) <= 0} />
            </button>
          {/each}
        </div>
      </div>
      {#if ui.wide}
        <div class="run">
          {#if missing}<span class="body-medium dim">{missing}</span>{/if}
          <button class="btn filled" disabled={!!missing || running} onclick={run}>{t('analysis_run')}</button>
        </div>
        {#if error}<p class="bad body-medium pad">{t('analysis_error', error)}</p>{/if}
        {#if result}<div class="results" bind:this={resultsEl}><AnalysisList analysis={result} w={sw} /></div>{/if}
      {/if}
    </div>
  </div>
</div>

{#if meldTile}
  <div class="scrim" role="presentation" onclick={() => (meldTile = null)}></div>
  <div class="meld-pick" role="dialog" aria-label={t('analysis_meld_pick')}>
    <div class="title-medium">{t('analysis_meld_pick')}</div>
    {#if chiOk(meldTile)}<button class="btn tonal" onclick={() => addMeld('chi')}>{t('analysis_chi_from', tileName(meldTile))}</button>{/if}
    <button class="btn tonal" onclick={() => addMeld('pon')}>{t('analysis_pon_of', tileName(meldTile))}</button>
    <button class="btn tonal" onclick={() => addMeld('kan')}>{t('analysis_kan_of', tileName(meldTile))}</button>
    <button class="btn tonal" onclick={() => addMeld('ankan')}>{t('analysis_ankan_of', tileName(meldTile))}</button>
    <button class="btn text" onclick={() => (meldTile = null)}>{t('cancel')}</button>
  </div>
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .body { flex: 1; min-height: 0; display: flex; flex-direction: column; }
  .body.wide { flex-direction: row; }
  .edit { flex: 1; min-height: 0; overflow: auto; padding: 0 12px 16px; }
  .side { flex: none; display: flex; flex-direction: column; background: var(--surface-container); }
  .body.wide .side { width: min(46%, 520px); overflow: auto; padding-bottom: 16px; }
  .setup { display: flex; flex-direction: column; gap: 8px; padding: 4px 0 8px; }
  .row-fields { display: flex; flex-wrap: wrap; gap: 8px 16px; }
  .field { display: flex; flex-direction: column; gap: 4px; }
  .field.small select, .field.small input { min-height: 40px; min-width: 88px; padding: 0 8px; border-radius: 8px; border: 1px solid var(--outline);
    background: var(--surface); color: var(--on-surface); font: inherit; }
  .seat-title { margin: 12px 0 4px; color: var(--primary); }
  .section { display: flex; flex-direction: column; gap: 4px; padding: 6px 8px; margin: 4px 0; border-radius: 12px; border: 2px solid transparent;
    background: var(--surface-container-low, var(--surface-container)); cursor: pointer; min-height: 44px; }
  .section.on { border-color: var(--primary); background: var(--secondary-container); }
  .sec-title { display: flex; gap: 8px; align-items: baseline; }
  .editing { font-size: 0.75rem; color: var(--primary); }
  .tiles { display: flex; flex-wrap: wrap; gap: 2px 0; min-height: 10px; }
  .tile-btn { padding: 0; border: none; background: none; cursor: pointer; }
  .tile-btn:disabled { cursor: default; }
  .meld { display: inline-flex; padding: 0; margin-right: 8px; border: none; background: none; cursor: pointer; }
  .palette { padding: 8px 12px; display: flex; flex-direction: column; gap: 6px; align-items: center; }
  .hint { align-self: flex-start; }
  .grid { display: grid; gap: 4px 0; }
  .run { display: flex; align-items: center; justify-content: flex-end; gap: 12px; padding: 8px 12px; flex-wrap: wrap; }
  .results { padding: 0 12px; }
  .pad { padding: 0 12px; }
  .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.35); z-index: 30; }
  .meld-pick { position: fixed; z-index: 31; left: 50%; top: 50%; transform: translate(-50%, -50%); display: flex; flex-direction: column; gap: 8px;
    padding: 20px; border-radius: 20px; background: var(--surface-container-high); min-width: 240px; }
</style>
