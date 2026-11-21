<!--
  分析: a position set up by hand (the player's hand and the tile drawn, or another player's discard to decide on;
  every seat's melds, flowers and discards; the dealer, the round wind, the wall), or taken from a step of a hand's
  review. A section is chosen by a tap, and the palette's tiles go into it; a tile tapped in a section leaves.

  The network's figures for each legal action follow every change at once (so the tile drawn can be changed with a
  tap to see what it does), and on request its tables of what the hand would do after each tile it may draw next and
  on each discard of the others (lib/engine.ts whatIfPosition). A step of a review keeps its hand's history while only
  the hand's own tiles are changed (the editor's source), so its figures are the review's.

  從這裡打 plays the position on (Branch.svelte, with the analysis shown at each decision): the player at their seat,
  the computer players (the settings' bot) at the others, the tiles the player cannot see (the others' concealed
  tiles, the wall) dealt at random. A step of a review is played on from its hand's position, with its history and
  the hand as it stands here; a position of its own from itself.
-->
<script lang="ts">
  import * as engine from '../lib/engine';
  import Branch from './Branch.svelte';
  import PanelSkeleton from './PanelSkeleton.svelte';
  import { t } from '../lib/i18n.svelte';
  import { nav } from '../lib/nav.svelte';
  import { editor, FLOWER_PALETTE, meldOf, PALETTE, sameSection, type Mode, type Section } from '../lib/position-editor.svelte';
  import { tileMarks } from '../lib/review';
  import { flowerLabel, flowerShort, parseChoice, taiLabel } from '../lib/rules';
  import { settings } from '../lib/settings.svelte';
  import { tileName, windName, type Tile as T } from '../lib/tiles';
  import type { Analysis, GameRecord, Meld, WhatIf } from '../lib/types';
  import { ui } from '../lib/ui.svelte';
  import DecisionPanel from './DecisionPanel.svelte';
  import Dialog from './Dialog.svelte';
  import Icon from './Icon.svelte';
  import Tile from './Tile.svelte';
  import TitleBar from './TitleBar.svelte';
  import Toggle from './Toggle.svelte';
  import WhatIfTables from './WhatIfTables.svelte';

  const ed = editor;
  const MODES: [Mode, string][] = [['self', t('analysis_my_turn')], ['claim', t('analysis_on_discard')]];
  let result = $state<Analysis | null>(null);
  let tables = $state<WhatIf | null>(null);
  let error = $state<string | null>(null);
  let running = $state(false);
  let meldTile = $state<T | null>(null);
  let showTables = $state(false);
  /** The melds, flowers and discards of the table (a tall window folds them away while they are empty). */
  let tableOpen = $state(false);
  /** A tall window: the palette can be put away to read the figures (it is, when a whole position comes from a review). */
  let paletteOpen = $state(editor.hand.length !== editor.handSize || !(editor.mode === 'self' ? editor.drawn : editor.claimTile));

  const seatName = (s: number) => [t('analysis_seat_you'), t('rel_next'), t('rel_opposite'), t('rel_prev')][s];
  const seatShort = (s: number) => [t('you'), t('rel_next'), t('rel_opposite'), t('rel_prev')][s];
  const names = [0, 1, 2, 3].map(seatShort);
  // Tiles: the palette's large enough to tap (a phone's 9 a row), the sections' a little smaller.
  // (Sideways: 34 px or more, but no wider than nine fit in the side column, as it is laid out: a phone sideways at a
  // large display size.)
  let sideW = $state(0);
  let sideH = $state(0);
  // 清空 and 隨機發牌 drop what was there: a bar over the foot of the editor (nothing moves) puts it back, for a few
  // seconds and until anything is edited.
  let undo = $state<{ text: string; back: () => void; look: string } | null>(null);
  let undoTimer: ReturnType<typeof setTimeout> | undefined;
  function act(text: string, f: () => void) {
    const back = ed.isEmpty() ? null : ed.saved();
    f();
    clearTimeout(undoTimer);
    undo = back ? { text, back, look: ed.look() } : null;
    if (back) undoTimer = setTimeout(() => (undo = null), 6000);
  }
  $effect(() => () => clearTimeout(undoTimer));
  const pw = $derived(ui.wide ? Math.min(48, Math.max(34, (ui.w * 0.42 - 24) / 9.4), sideW ? (sideW - 24) / 9.4 : 48)
    : Math.min(48, (ui.w - 24) / 9.4));
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
  // The rules: the settings', or those of the hand the position came from.
  const spec = $derived(ed.rules ?? settings.spec);
  const rulesLabel = $derived.by(() => {
    const [tai, flowers, ...rest] = spec.split(',');
    const c = parseChoice({ tai, flowers });
    const base = t('rules_chip', t(taiLabel(c.tai)), t(flowerLabel(c.flowers)));
    return rest.length ? `${base}${t('sep')}${t('rules_custom')}` : base;
  });
  const rulesShort = $derived.by(() => {
    const [tai, flowers] = spec.split(',');
    const c = parseChoice({ tai, flowers });
    return c.flowers === 'none' ? t(taiLabel(c.tai)) : t('rules_chip', t(taiLabel(c.tai)), t(flowerShort(c.flowers)));
  });
  const tableCount = $derived([0, 1, 2, 3].reduce((a, s) => a + ed.melds[s].length + ed.flowers[s].length + ed.discards[s].length, 0));
  const tableShown = $derived(ui.wide || tableOpen || tableCount > 0);

  // The figures follow the position: asked again a moment after each change, the last answer kept until the next.
  let asked = 0;
  $effect(() => {
    const ok = !missing;
    const key = ok ? JSON.stringify([spec, ed.position(), showTables]) : '';
    // A position from a hand's review, with nothing but the hand's own tiles changed, is asked of that hand's game.
    const source = ok ? ed.source : null;
    const mine = ++asked;
    if (!ok) {
      result = null;
      tables = null;
      error = null;
      running = false;
      return;
    }
    const { rules, position, withTables } = { rules: spec, position: JSON.parse(key)[1], withTables: showTables };
    running = true;
    const timer = setTimeout(async () => {
      try {
        const a = source ? await engine.analyzeGame(source.game, source.seat, true, position.hand, position.drawn)
          : await engine.analyzePosition(rules, position, 'analysis');
        if (mine !== asked) return;
        result = a;
        error = null;
        if (withTables && a.source === 'network') {
          const w = source ? await engine.whatIfGame(source.game, source.seat, position.hand, position.drawn)
            : await engine.whatIfPosition(rules, position);
          if (mine === asked) tables = w;
        } else tables = null;
      } catch (e) {
        if (mine !== asked) return;
        result = null;
        tables = null;
        error = String((e as Error).message ?? e);
      } finally {
        if (mine === asked) running = false;
      }
    }, 120);
    return () => clearTimeout(timer);
  });
  const marks = $derived(result ? tileMarks(result, -1, undefined) : null);

  /** 從這裡打: the hand played on from the position, the player at `me` (a review's step: its seat; else 0). (The
   *  branch gives the base its own seed.) */
  let playing = $state<{ game: GameRecord; me: number } | null>(null);
  function playFrom() {
    const p = ed.position();
    const src = ed.source;
    playing = src
      ? { game: { rules: src.game.rules, setup: src.game.setup, base: { game: src.game, seat: src.seat, seed: '', hand: p.hand, drawn: p.drawn }, actions: [] }, me: src.seat }
      : { game: { rules: spec, setup: p.setup, base: { position: p, seed: '' }, actions: [] }, me: 0 };
  }
  /** The seats' names at the table, from the player's. */
  const playNames = $derived(playing ? [0, 1, 2, 3].map((s) => seatShort((s - playing!.me + 4) % 4)) : []);

  function pick(tile: T) {
    if (ed.section.kind === 'melds') {
      meldTile = tile;
      return;
    }
    ed.add(tile);
  }
  function addMeld(type: Meld['type']) {
    if (meldTile && ed.section.kind === 'melds') ed.addMeld(ed.section.seat, type, meldTile);
    meldTile = null;
  }
  function remove(s: Section, i: number) {
    ed.section = s;
    ed.removeAt(s, i);
  }
  const chiOk = (x: T) => x[1] !== 'z' && x[1] !== 'f' && Number(x[0]) <= 7;
</script>

{#snippet tiles(list: T[], s: Section, w: number, marked = false)}
  <div class="tiles">
    {#each list as x, i (i + x)}
      {@const m = marked ? marks?.get(x) : undefined}
      <button class="tile-btn" aria-label={tileName(x)} onclick={() => remove(s, i)}>
        {#if m}<span class="tag {m.tone}">{m.tag}</span>
        {:else if marked && running && !result && !missing}<span class="tag skel" style="width: {Math.round(w * 0.7)}px" aria-hidden="true"></span>{/if}
        <Tile tile={x} {w} danger={m?.danger ?? 0} />
      </button>
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

{#snippet results()}
  <div class="status">
    {#if missing}<span class="body-medium dim">{missing}</span>
    {:else if error}<span class="body-medium bad">{t('analysis_error', error)}</span>
    {/if}
  </div>
  {#if !missing && !error && running && !result}<PanelSkeleton w={sw} rows={4} />{/if}
  {#if result}
    <div class="results" class:stale={running}>
      <div class="play-row">
        <button class="btn filled" disabled={running} title={t('analysis_play_hint')} onclick={playFrom}>{t('analysis_play_from')}</button>
      </div>
      <DecisionPanel analysis={result} w={sw} {names} />
      {#if result.source === 'network'}
        <div class="whatif">
          {#if !showTables}
            <button class="btn tonal" onclick={() => (showTables = true)}>{t('whatif_show')}</button>
          {:else if tables}
            <WhatIfTables data={tables} w={Math.max(26, sw * 0.82)} seatName={seatShort} />
          {:else}
            <span class="body-medium dim">{t('review_loading')}</span>
          {/if}
        </div>
      {/if}
    </div>
  {/if}
{/snippet}

{#if playing}
  <Branch game={playing.game} me={playing.me} names={playNames} onclose={() => (playing = null)} title={t('analysis_play_from')}
    backLabel={t('analysis_play_back')} />
{:else}
<div class="page">
  <TitleBar title={t('analyze_title')}>
    <button class="chip" class:on={!!ed.rules} title={ed.rules ? t('analysis_rules_of_hand') + t('colon') + t('analysis_rules_reset') : t('settings_rules')}
      onclick={() => { if (ed.rules) ed.rules = null; else nav.open('rules', 'SETTINGS'); }}><span data-full={rulesLabel} data-short={rulesShort}></span></button>
    <button class="btn text" aria-label={t('analysis_random')} onclick={() => act(t('analysis_dealt'), () => ed.random())}><span data-full={t('analysis_random')} data-short={t('analysis_random_short')}></span></button>
    <button class="btn text" onclick={() => act(t('analysis_cleared'), () => ed.clear())}>{t('analysis_clear')}</button>
  </TitleBar>
  <div class="body" class:wide={ui.wide}>
    <div class="edit">
      <div class="setup">
        <div class="field">
          <span class="label-large">{t('analysis_situation')}</span>
          <Toggle options={MODES} selected={ed.mode}
            onselect={(v) => { ed.mode = v; ed.section = { kind: v === 'self' ? 'drawn' : 'claim' }; }} />
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
        {@render tiles(ed.hand, { kind: 'hand' }, sw, ed.mode === 'self')}
        {#if ed.hand.length !== ed.handSize}<div class="body-small dim">{t('analysis_need_tiles', ed.handSize, ed.hand.length)}</div>{/if}
      {/snippet}
      {@render section({ kind: 'hand' }, t('analysis_hand'), handBody)}

      {#if ed.mode === 'self'}
        {#snippet drawnBody()}{@render tiles(ed.drawn ? [ed.drawn] : [], { kind: 'drawn' }, sw, true)}{/snippet}
        {@render section({ kind: 'drawn' }, t('analysis_drawn'), drawnBody)}
      {:else}
        {#snippet claimBody()}{@render tiles(ed.claimTile ? [ed.claimTile] : [], { kind: 'claim' }, sw)}{/snippet}
        {@render section({ kind: 'claim' }, t('analysis_claim_tile'), claimBody)}
      {/if}

      {#if !ui.wide}{@render results()}{/if}

      {#if !tableShown}
        <button class="btn text fold" onclick={() => (tableOpen = true)}>{t('analysis_melds')}{t('sep')}{t('analysis_flowers')}{t('sep')}{t('analysis_discards')}</button>
      {:else}
        {#each [0, 1, 2, 3] as seat (seat)}
          <div class="seat-title title-small">{seatName(seat)}{ed.dealer === seat ? t('sep') + t('dealer_mark') : ''}</div>
          {#snippet meldsBody()}
            <div class="tiles">
              {#each ed.melds[seat] as m, i (i)}
                <button class="meld" onclick={(e) => { e.stopPropagation(); remove({ kind: 'melds', seat }, i); }}>
                  {#each m.tile ? meldOf(m) : [null, null, null, null] as x, k}<Tile tile={m.type === 'ankan' && (k === 0 || k === 3) ? null : x} w={sw * 0.85} />{/each}
                </button>
              {/each}
            </div>
          {/snippet}
          {@render section({ kind: 'melds', seat }, t('analysis_melds'), meldsBody)}
          {#snippet flowersBody()}{@render tiles(ed.flowers[seat], { kind: 'flowers', seat }, sw * 0.85)}{/snippet}
          {#if !spec.includes(',none') || ed.flowers[seat].length}
            {@render section({ kind: 'flowers', seat }, t('analysis_flowers'), flowersBody)}
          {/if}
          {#snippet discardsBody()}{@render tiles(ed.discards[seat], { kind: 'discards', seat }, sw * 0.85)}{/snippet}
          {@render section({ kind: 'discards', seat }, t('analysis_discards'), discardsBody)}
        {/each}
      {/if}
    </div>

    <div class="side" bind:clientWidth={sideW} bind:clientHeight={sideH}>
      <div class="palette">
        <div class="hint">
          {#if ui.wide || paletteOpen}<span class="body-small dim">{t('analysis_palette_hint')}</span>{/if}
          {#if !ui.wide}
            <button class="btn text fold-palette" aria-expanded={paletteOpen} onclick={() => (paletteOpen = !paletteOpen)}>
              {paletteOpen ? t('analysis_palette_hide') : t('analysis_palette_show')}<span class="turn" class:down={paletteOpen}><Icon name="dropDown" /></span>
            </button>
          {/if}
        </div>
        {#if ui.wide || paletteOpen}
          <div class="grid" style="grid-template-columns: repeat(9, {pw}px)">
            {#each palette as x (x)}
              <button class="tile-btn" aria-label={tileName(x)} disabled={ed.left(x) <= 0} onclick={() => pick(x)}>
                <Tile tile={x} w={pw} dim={ed.left(x) <= 0} />
              </button>
            {/each}
          </div>
        {/if}
      </div>
      {#if ui.wide}<div class="side-results">{@render results()}</div>{/if}
    </div>
    {#if undo && ed.look() === undo.look}
      {@const u = undo}
      <div class="snack body-medium" role="status" style={ui.wide ? `right: ${sideW + 12}px; bottom: 12px` : `right: 12px; bottom: ${sideH + 8}px`}>
        <span>{u.text}</span>
        <button class="btn text" onclick={() => { u.back(); undo = null; }}>{t('analysis_undo')}</button>
      </div>
    {/if}
  </div>
</div>

{#if meldTile}
  <Dialog title={t('analysis_meld_pick')} onclose={() => (meldTile = null)}>
    <div class="meld-pick">
      {#if chiOk(meldTile)}<button class="btn tonal" onclick={() => addMeld('chi')}>{t('analysis_chi_from', tileName(meldTile))}</button>{/if}
      <button class="btn tonal" onclick={() => addMeld('pon')}>{t('analysis_pon_of', tileName(meldTile))}</button>
      <button class="btn tonal" onclick={() => addMeld('kan')}>{t('analysis_kan_of', tileName(meldTile))}</button>
      <button class="btn tonal" onclick={() => addMeld('ankan')}>{t('analysis_ankan_of', tileName(meldTile))}</button>
    </div>
    {#snippet actions()}<button class="btn text" onclick={() => (meldTile = null)}>{t('cancel')}</button>{/snippet}
  </Dialog>
{/if}
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .body { flex: 1; min-height: 0; display: flex; flex-direction: column; position: relative; }
  .snack { position: absolute; left: 12px; z-index: 2; display: flex; align-items: center; justify-content: space-between; gap: 8px;
    max-width: 480px; padding: 4px 4px 4px 16px; border-radius: 8px; background: var(--inverse-surface); color: var(--inverse-on-surface);
    box-shadow: 0 3px 10px rgba(0, 0, 0, 0.3); }
  .snack .btn { color: var(--inverse-primary); }
  .body.wide { flex-direction: row; }
  .edit { flex: 1; min-height: 0; overflow: auto; padding: 0 12px 16px; }
  .side { flex: none; display: flex; flex-direction: column; background: var(--surface-container); }
  .body.wide .side { width: min(48%, 560px); overflow: auto; padding-bottom: 16px; }
  .chip.on { background: var(--secondary-container); color: var(--on-secondary-container); border-color: transparent; }
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
  .tile-btn { padding: 0; border: none; background: none; cursor: pointer; position: relative; }
  .tile-btn:disabled { cursor: default; }
  .tag { position: absolute; left: 50%; bottom: 100%; transform: translateX(-50%); padding: 0 3px; border-radius: 5px; font-size: 10.5px;
    font-weight: 700; line-height: 14px; white-space: nowrap; pointer-events: none; background: rgba(0, 0, 0, 0.6); color: #fff;
    font-variant-numeric: tabular-nums; }
  .tag.best { background: var(--found-fill); }
  .tag.skel { height: 14px; background: rgba(128, 128, 128, 0.4); animation: pulse 1.6s ease-in-out infinite; }
  @keyframes pulse { 0%, 100% { opacity: 0.5; } 50% { opacity: 1; } }
  @media (prefers-reduced-motion: reduce) { .tag.skel { animation: none; } .results { transition: none; } }
  .section:has(.tag) .tiles { padding-top: 15px; row-gap: 17px; }
  .meld { display: inline-flex; padding: 0; margin-right: 8px; border: none; background: none; cursor: pointer; }
  .fold { align-self: flex-start; margin: 2px 0; }
  .palette { padding: 8px 12px; display: flex; flex-direction: column; gap: 6px; align-items: center; }
  .hint { align-self: stretch; display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 24px; }
  .fold-palette { margin-left: auto; min-height: 44px; gap: 0; }
  .turn { display: inline-flex; transform: rotate(180deg); }
  .turn.down { transform: none; }
  .grid { display: grid; gap: 4px 0; }
  .status { display: flex; align-items: center; min-height: 28px; padding: 4px 0; }
  .side-results { padding: 0 12px; }
  .results { display: flex; flex-direction: column; gap: 12px; transition: opacity 0.1s; }
  .results.stale { opacity: 0.7; }
  .play-row { display: flex; justify-content: flex-end; }
  .whatif { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
  .meld-pick { display: flex; flex-direction: column; gap: 8px; min-width: 240px; }
</style>
