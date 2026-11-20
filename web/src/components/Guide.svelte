<!--
  How to play (規則說明): pages for someone new to Taiwanese mahjong. First the rules to explain (a tai preset and a
  flower preset, the settings' at the start); then the goal, the tiles and flowers, a turn and the calls, the river's
  marks (摸切, taken tiles, the first discard after a call), being ready, tai and payments, a quiz on waits, and on to
  practice or the tour. Where the flowers and the tai come up, the
  same choice is there again, so a change shows at once. The values (base, dealer, every tai) come from the engine
  ({"cmd":"rules"}). Opened from the settings, the welcome card and the tour's end, under the screen it was opened
  from (#/<screen>/guide); Back leaves it. The app's ui/GuideScreen.kt is the same.
-->
<script lang="ts">
  import { t } from '../lib/i18n.svelte';
  import { nav } from '../lib/nav.svelte';
  import { practice } from '../lib/practice.svelte';
  import { FLOWER_PARAMS, FLOWER_PRESETS, flowerLabel, info, TAI_PRESETS, taiLabel } from '../lib/rules';
  import { settings } from '../lib/settings.svelte';
  import { parseTiles, taiName as itemName, tileName } from '../lib/tiles';
  import { tour } from '../lib/tour.svelte';
  import type { Discard, RulesInfo } from '../lib/types';
  import { ui } from '../lib/ui.svelte';
  import Linked from './Linked.svelte';
  import River from './River.svelte';
  import Tile from './Tile.svelte';
  import TitleBar from './TitleBar.svelte';
  import Toggle from './Toggle.svelte';

  let { onback, onglossary = () => nav.open('glossary', nav.screen, true) }: { onback: () => void; onglossary?: () => void } = $props();

  const PAGES = 9;
  let page = $state(0);
  let scroller: HTMLElement | undefined = $state();
  function go(p: number) {
    page = Math.max(0, Math.min(PAGES - 1, p));
    scroller?.scrollTo({ top: 0 });
  }

  // ---- The rules explained: the settings' presets at first ----
  let tai = $state(settings.rules.tai);
  let flowers = $state(settings.rules.flowers);
  let rules = $state<RulesInfo | null>(null);
  $effect(() => {
    const spec = `${tai},${flowers}`;
    void info(spec).then((i) => { if (`${tai},${flowers}` === spec) rules = i; });
  });
  const v = $derived(rules?.values ?? {});
  const fmt = (x: number) => (Number.isInteger(x) ? String(x) : x.toFixed(1));
  const n = (k: string) => fmt(v[k] ?? 0);
  const taiName = $derived(t(taiLabel(tai)));
  const rulesName = $derived(t('rules_chip', taiName, t(flowerLabel(flowers))));
  const taiOptions: [string, string][] = TAI_PRESETS.map((p) => [p, t(taiLabel(p))]);
  const flowerOptions: [string, string][] = FLOWER_PRESETS.map((p) => [p, t(flowerLabel(p))]);

  // ---- Tiles shown ----
  const w = $derived(ui.desk ? 44 : ui.w < 400 ? 40 : 44);
  const small = $derived(ui.desk ? 36 : ui.w < 400 ? 26 : 30);
  const GOAL: [string, string][] = [['234m', 'guide_chow'], ['678p', 'guide_chow'], ['345s', 'guide_chow'], ['111z', 'guide_pung'],
    ['777z', 'guide_pung'], ['99p', 'guide_pair']];
  const SUITS: [string, string][] = [['123456789m', 'guide_row_m'], ['123456789p', 'guide_row_p'], ['123456789s', 'guide_row_s'],
    ['1234765z', 'guide_row_z']];
  const FLOWER_TILES = '12345678f';
  // A call: the tiles in hand, and the one taken.
  /** The river's marks, one tile each (River.svelte draws them as at the table). */
  const MARKS: [Discard, string][] = [
    [{ tile: '9m', drawn: true, called: false }, 'guide_river_drawn'], [{ tile: '2p', drawn: false, called: true }, 'guide_river_called'],
    [{ tile: '4z', drawn: true, called: true }, 'guide_river_drawn_called'], [{ tile: '7s', drawn: false, called: false, after_call: true }, 'guide_river_after'],
  ];
  const CALLS: [string, string, string][] = [['guide_chi', '35m', '4m'], ['guide_pon', '77z', '7z'], ['guide_kan', '111p', '1p']];
  const WAITS: [string, string, string][] = [['guide_wait_ryanmen', '34m', '25m'], ['guide_wait_penchan', '12s', '3s'],
    ['guide_wait_kanchan', '46p', '5p'], ['guide_wait_tanki', '7z', '7z'], ['guide_wait_shanpon', '11z99p', '1z9p']];

  // ---- Tai and payments, under the rules explained ----
  const NOT_LISTED = ['dealer', 'streak'];
  const taiItems = $derived((rules?.items ?? []).filter((i) => (v[i.param] ?? 0) > 0 && !NOT_LISTED.includes(i.param) &&
    (flowers !== 'none' || !FLOWER_PARAMS.includes(i.param))));
  let helped = $state<string | null>(null);
  const example = $derived.by(() => {
    const each = (v.base ?? 0) + (v.zimo ?? 0);
    return t('guide_tai_example', n('zimo'), fmt(each), fmt(each + (v.dealer ?? 0)), fmt(3 * each + (v.dealer ?? 0)));
  });

  // ---- The quiz: hands that are ready, and their waits (from the engine's analysis of each) ----
  const QUIZ: { hand: string; suit: string; waits: string[]; why: string }[] = [
    { hand: '234567m333789p678s8s', suit: 's', waits: ['5s', '8s'], why: 'guide_try_why1' },
    { hand: '2345m678p333456s111z', suit: 'm', waits: ['2m', '5m'], why: 'guide_try_why2' },
    { hand: '34567m11678p333456s', suit: 'm', waits: ['2m', '5m', '8m'], why: 'guide_try_why3' },
  ];
  let q = $state(0);
  let picked = $state<string[]>([]);
  let checked = $state(false);
  const quiz = $derived(QUIZ[q]);
  const candidates = $derived([1, 2, 3, 4, 5, 6, 7, 8, 9].map((k) => `${k}${quiz.suit}`));
  const right = $derived(checked && quiz.waits.length === picked.length && quiz.waits.every((x) => picked.includes(x)));
  function pick(x: string) {
    if (checked) return;
    picked = picked.includes(x) ? picked.filter((y) => y !== x) : [...picked, x];
  }
  function nextQuestion() {
    q = (q + 1) % QUIZ.length;
    picked = [];
    checked = false;
  }
  const mark = (x: string) => !checked ? '' : quiz.waits.includes(x) ? (picked.includes(x) ? 'ok' : 'missed') : picked.includes(x) ? 'bad' : '';

  // ---- The end ----
  const same = $derived(tai === settings.rules.tai && flowers === settings.rules.flowers && !Object.keys(settings.rules.overrides).length);
  function play(apply: boolean) {
    if (apply) {
      settings.rules = { tai, flowers, overrides: {} };
      settings.persist();
      void practice.settingsChanged();
    }
    nav.go('PLAY');
  }
</script>

{#snippet tiles(s: string, size: number, glow = false)}
  {#each parseTiles(s) as x, i (i)}<Tile tile={x} w={size} {glow} />{/each}
{/snippet}

{#snippet taiSwitch()}
  <div class="switch">
    <span class="body-medium dim">{t('guide_switch_tai')}</span>
    <Toggle options={taiOptions} selected={tai} onselect={(x) => (tai = x)} height={40} />
  </div>
{/snippet}

{#snippet flowerSwitch()}
  <div class="switch">
    <span class="body-medium dim">{t('guide_switch_flowers')}</span>
    <Toggle options={flowerOptions} selected={flowers} onselect={(x) => (flowers = x)} height={40} />
  </div>
{/snippet}

<div class="page">
  <TitleBar title={t('guide_title')} {onback}>
    <button class="btn text" onclick={onglossary}>{t('glossary_title')}</button>
    <span class="body-medium dim">{t('guide_page', page + 1, PAGES)}</span>
  </TitleBar>
  <div class="scroll" bind:this={scroller}><div class="content">
    {#if page === 0}
      <h2 class="headline-small">{t('guide_rules_title')}</h2>
      <p class="body-large"><Linked text={t('guide_rules_text')} /></p>
      <div class="section-label title-small">{t('rules_tai')}</div>
      <Toggle options={taiOptions} selected={tai} onselect={(x) => (tai = x)} />
      <p class="body-medium dim"><Linked text={t(`tai_${tai}_desc`)} /></p>
      <div class="section-label title-small">{t('rules_flowers')}</div>
      <Toggle options={flowerOptions} selected={flowers} onselect={(x) => (flowers = x)} />
      <p class="body-medium dim"><Linked text={t(`flowers_${flowers}_desc`)} /></p>
      <p class="body-medium note"><Linked text={t('guide_rules_note')} /></p>
    {:else if page === 1}
      <h2 class="headline-small">{t('guide_goal_title')}</h2>
      <p class="body-large"><Linked text={t('guide_goal_text')} /></p>
      <p class="body-large"><Linked text={t('guide_goal_sets')} /></p>
      <p class="body-medium dim"><Linked text={t('guide_goal_example')} /></p>
      <div class="felt groups">
        {#each GOAL as [s, label] (s)}
          <div class="group"><div class="row">{@render tiles(s, small)}</div><span class="label-medium">{t(label)}</span></div>
        {/each}
      </div>
      <p class="body-medium"><Linked text={t('guide_goal_kong')} /></p>
    {:else if page === 2}
      <h2 class="headline-small">{t('guide_tiles_title')}</h2>
      <p class="body-large"><Linked text={t('guide_tiles_text')} /></p>
      <div class="felt suits">
        {#each SUITS as [s, label] (s)}
          <div class="suit"><span class="label-large name">{t(label)}</span><div class="row">{@render tiles(s, small)}</div></div>
        {/each}
        {#if flowers !== 'none'}
          <div class="suit"><span class="label-large name">{t('guide_row_f')}</span><div class="row">{@render tiles(FLOWER_TILES, small)}</div></div>
        {/if}
      </div>
      {@render flowerSwitch()}
      {#if flowers === 'none'}
        <p class="body-large"><Linked text={t('guide_flowers_none')} /></p>
      {:else}
        <p class="body-large"><Linked text={t('guide_flowers_has')} /></p>
        <p class="body-large"><Linked text={flowers === 'see' ? t('guide_flowers_see', n('flower_tai')) : t('guide_flowers_seat', n('flower_tai'), n('flower_set'))} /></p>
        {#if (v.eight_flowers ?? 0) > 0}<p class="body-medium"><Linked text={t('guide_flower_wins', n('eight_flowers'))} /></p>{/if}
      {/if}
    {:else if page === 3}
      <h2 class="headline-small">{t('guide_turn_title')}</h2>
      <p class="body-large"><Linked text={t('guide_turn_text')} /></p>
      <p class="body-large"><Linked text={t('guide_turn_claims')} /></p>
      <div class="calls">
        {#each CALLS as [label, hand, taken] (label)}
          <div class="call">
            <div class="row example">{@render tiles(hand, small)}<span class="plus">+</span>{@render tiles(taken, small, true)}</div>
            <p class="body-medium"><Linked text={t(label)} /></p>
          </div>
        {/each}
        <div class="call"><p class="body-medium"><Linked text={t('guide_hu')} /></p></div>
      </div>
      <p class="body-medium"><Linked text={t('guide_turn_show')} /></p>
      <p class="body-medium"><Linked text={t('guide_turn_priority')} /></p>
      <p class="body-medium"><Linked text={(v.multi_ron ?? 0) > 0 ? t('guide_multi_ron_on', taiName) : t('guide_multi_ron_off', taiName)} /></p>
      <p class="body-medium"><Linked text={t('guide_turn_end', v.keep ?? 16)} /></p>
    {:else if page === 4}
      <h2 class="headline-small">{t('guide_river_title')}</h2>
      <p class="body-large"><Linked text={t('guide_river_text')} /></p>
      <div class="marks">
        {#each MARKS as [d, label] (label)}
          <div class="mark-row"><div class="felt one"><River discards={[d]} w={small} cols={1} /></div><p class="body-medium"><Linked text={t(label)} /></p></div>
        {/each}
      </div>
      <p class="body-medium dim"><Linked text={t('guide_river_tips')} /></p>
    {:else if page === 5}
      <h2 class="headline-small">{t('guide_ready_title')}</h2>
      <p class="body-large"><Linked text={t('guide_ready_text')} /></p>
      <div class="waits">
        {#each WAITS as [label, hand, waits] (label)}
          <div class="wait">
            <div class="row">{@render tiles(hand, small)}</div>
            <span class="body-medium dim">{t('analysis_waits')}</span>
            <div class="row">{@render tiles(waits, small, true)}</div>
            <span class="body-medium wname">{t(label)}</span>
          </div>
        {/each}
      </div>
      <p class="body-medium"><Linked text={(v.duting ?? 0) > 0 ? t('guide_duting_on', taiName, n('duting')) : t('guide_duting_off', taiName)} /></p>
      <p class="body-medium"><Linked text={t('guide_missed')} /></p>
    {:else if page === 6}
      <h2 class="headline-small">{t('guide_tai_title')}</h2>
      {@render taiSwitch()}
      <p class="body-large"><Linked text={t('guide_tai_text', n('base'))} /></p>
      <p class="body-large"><Linked text={t('guide_tai_who')} /></p>
      <p class="body-large"><Linked text={t('guide_tai_dealer', n('dealer'), n('streak'), fmt((v.dealer ?? 0) + (v.streak ?? 0)))} /></p>
      <p class="body-medium dim"><Linked text={t('guide_tai_items')} /></p>
      <div class="items">
        {#each taiItems as it (it.param)}
          <button class="item" class:open={helped === it.param} onclick={() => (helped = helped === it.param ? null : it.param)}>
            <span class="body-large">{itemName(it.param, it.name)}</span><span class="title-small">{t('tai_unit', n(it.param))}</span>
          </button>
          {#if helped === it.param}<p class="body-medium help"><Linked text={t('help_' + it.param)} /></p>{/if}
        {/each}
      </div>
      <p class="body-medium"><Linked text={example} /></p>
      {#if (v.menqing_zimo ?? 0) > 0}
        <p class="body-medium"><Linked text={t('guide_tai_menqing', n('menqing'), n('zimo'), n('menqing_zimo'), fmt((v.menqing ?? 0) + (v.zimo ?? 0) + (v.menqing_zimo ?? 0)))} /></p>
      {/if}
      <p class="body-medium"><Linked text={(v.min_tai ?? 0) > 0 ? t('guide_tai_min', n('min_tai')) : t('guide_tai_min_none')} /></p>
      <p class="body-medium"><Linked text={(v.draw_streak ?? 0) > 0 ? t('guide_tai_draw_on') : t('guide_tai_draw_off')} /></p>
      <p class="body-medium"><Linked text={t('guide_tai_next')} /></p>
    {:else if page === 7}
      <h2 class="headline-small">{t('guide_try_title')}</h2>
      <p class="body-large"><Linked text={t('guide_try_text')} /></p>
      <span class="label-large dim">{t('guide_try_q', q + 1, QUIZ.length)}</span>
      <div class="felt quiz-hand"><div class="row wrap">{@render tiles(quiz.hand, small)}</div></div>
      <div class="picks" style:--cols={ui.w < 560 ? 5 : 9}>
        {#each candidates as x (x)}
          <button class="pick {mark(x)}" class:on={picked.includes(x)} aria-pressed={picked.includes(x)} aria-label={tileName(x)} onclick={() => pick(x)}>
            <Tile tile={x} {w} dim={checked && !quiz.waits.includes(x) && !picked.includes(x)} />
          </button>
        {/each}
      </div>
      {#if checked}
        <p class="title-medium" class:good={right} class:bad={!right}>
          {right ? t('guide_try_right') : t('guide_try_wrong', quiz.waits.map(tileName).join(t('sep_list')))}</p>
        <p class="body-large"><Linked text={t(quiz.why)} /></p>
        <p class="body-small dim">{t('guide_try_legend')}</p>
        <div class="buttons">
          <button class="btn tonal" onclick={nextQuestion}>{q < QUIZ.length - 1 ? t('guide_try_next') : t('guide_try_again')}</button>
        </div>
      {:else}
        <div class="buttons"><button class="btn filled" disabled={!picked.length} onclick={() => (checked = true)}>{t('guide_try_check')}</button></div>
      {/if}
    {:else}
      <h2 class="headline-small">{t('guide_end_title')}</h2>
      <p class="body-large"><Linked text={t('guide_end_text')} /></p>
      <div class="buttons column">
        {#if !same}<button class="btn filled big" onclick={() => play(true)}>{t('guide_end_play_rules', rulesName)}</button>{/if}
        <button class={same ? 'btn filled big' : 'btn tonal big'} onclick={() => play(false)}>{t('guide_end_play')}</button>
        <button class="btn outlined big" onclick={() => { nav.go('PLAY'); tour.start(); }}>{t('guide_end_tour')}</button>
      </div>
    {/if}
    {#if page > 0 && page < PAGES - 1}<p class="body-small dim now">{t('guide_rules_now', rulesName)}</p>{/if}
  </div></div>
  <div class="bar">
    <button class="btn text" disabled={page === 0} onclick={() => go(page - 1)}>{t('guide_prev')}</button>
    <span class="dots" aria-hidden="true">{#each Array(PAGES) as _, i (i)}<span class="dot" class:on={i === page}></span>{/each}</span>
    {#if page < PAGES - 1}
      <button class="btn filled" onclick={() => go(page + 1)}>{t('guide_next')}</button>
    {:else}
      <button class="btn filled" onclick={onback}>{t('guide_done')}</button>
    {/if}
  </div>
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .scroll { flex: 1; min-height: 0; overflow: auto; }
  .content { max-width: 760px; margin: 0 auto; padding: 4px 16px 16px; display: flex; flex-direction: column; gap: 10px; }
  h2 { margin: 4px 0 0; }
  p { margin: 0; line-height: 1.6; }
  .note { color: var(--primary); }
  .now { margin-top: 6px; }
  .section-label { color: var(--primary); margin-top: 4px; }
  .felt { background: var(--felt); color: var(--on-felt); border-radius: 14px; padding: 10px 12px; }
  .row { display: flex; align-items: flex-end; gap: 1px; }
  .row.wrap { flex-wrap: wrap; gap: 2px; }
  .marks { display: flex; flex-direction: column; gap: 8px; }
  .mark-row { display: flex; align-items: center; gap: 12px; }
  .mark-row p { margin: 0; flex: 1; }
  .felt.one { padding: 6px; flex: none; }
  .groups { display: flex; flex-wrap: wrap; gap: 10px 14px; align-self: flex-start; }
  .group { display: flex; flex-direction: column; align-items: center; gap: 4px; }
  .suits { display: flex; flex-direction: column; gap: 6px; align-self: flex-start; max-width: 100%; overflow-x: auto; }
  .suit { display: flex; align-items: center; gap: 10px; }
  .name { flex: none; min-width: 3em; white-space: nowrap; color: var(--on-felt); }
  .switch { display: flex; flex-direction: column; gap: 6px; padding: 8px 12px; border-radius: 14px; background: var(--surface-container); }
  .calls { display: flex; flex-direction: column; gap: 8px; }
  .call { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
  .example { flex: none; }
  .plus { padding: 0 4px; align-self: center; font-weight: 700; }
  .waits { display: flex; flex-direction: column; gap: 8px; }
  .wait { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .wname { color: var(--on-surface-variant); }
  .items { display: flex; flex-direction: column; border-radius: 14px; background: var(--surface-container); overflow: hidden; }
  .item { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 10px 14px; min-height: 48px; border: none;
    background: none; color: inherit; cursor: pointer; text-align: left; border-bottom: 1px solid var(--surface-container-highest); }
  .item.open { background: var(--secondary-container); }
  .help { padding: 8px 14px 12px; color: var(--on-surface-variant); white-space: pre-line; }
  .quiz-hand { align-self: flex-start; max-width: 100%; }
  /* The nine candidates in rows of five on a phone (big enough to tap), in one row elsewhere. */
  .picks { display: grid; grid-template-columns: repeat(var(--cols), max-content); gap: 6px; }
  .pick { border: 3px solid transparent; border-radius: 10px; padding: 2px; background: none; cursor: pointer; }
  .pick.on { border-color: var(--primary); background: var(--secondary-container); }
  .pick.ok { border-color: var(--good); }
  .pick.bad { border-color: var(--bad); }
  .pick.missed { border-color: var(--good); border-style: dashed; }
  .good { color: var(--good); }
  .bad { color: var(--bad); }
  .buttons { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .buttons.column { flex-direction: column; align-items: stretch; max-width: 420px; }
  .bar { flex: none; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 12px;
    border-top: 1px solid var(--surface-container-highest); }
  .dots { display: flex; gap: 6px; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--surface-container-highest); }
  .dot.on { background: var(--primary); }
</style>
