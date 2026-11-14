<!--
  Practice: the table against three bots; a bar over it with the hand, the rules and the totals; the hand's result over
  the table when it ends, and the game's totals when the game does. At the manual pace (the settings) the computer
  players' steps wait for the player's button (lib/practice.svelte.ts), and so does the table at a hand's end. The
  review (and each decision's analysis) is a button on the result.

  練習時顯示分析 (the settings', as 聽牌提示: the bar over a phone's table has no room left): the player's decisions
  with the analysis shown as a branch shows it (Branch.svelte): what each discard costs and its danger on the hand's
  tiles, what each choice costs on its button, how likely each opponent is ready on the name plates (and the tile it
  most likely waits on), the tiles a ready hand waits with (blue), a tile's figures over the hand when it is chosen
  (or under a computer's mouse), and, where the window has room for the panel beside the table (a computer's), the
  list of every action there (詳細分析: a computer's extra; the table says enough to play by). A hand that had it on at any point
  counts for nothing (not in the game's totals, nor in the records' statistics; its record is marked) and keeps it on
  till its end: turned off meanwhile it would only have been a peek at how to play one turn.
-->
<script lang="ts">
  import { fxDone, fxOn, resultTap } from '../lib/fx.svelte';
  import { onMount, untrack } from 'svelte';
  import * as engine from '../lib/engine';
  import { t } from '../lib/i18n.svelte';
  import { nav } from '../lib/nav.svelte';
  import { ME, practice } from '../lib/practice.svelte';
  import { records } from '../lib/records.svelte';
  import { choiceMarks, opponentNotes, tileMarks, type TileMark } from '../lib/review';
  import { flowerLabel, flowerShort, taiLabel } from '../lib/rules';
  import { shorten } from '../lib/shorten';
  import { settings } from '../lib/settings.svelte';
  import { windName, type Tile as T } from '../lib/tiles';
  import type { Action, Analysis, GameRecord } from '../lib/types';
  import { signed, ui } from '../lib/ui.svelte';
  import Dialog from './Dialog.svelte';
  import HandResult from './HandResult.svelte';
  import Icon from './Icon.svelte';
  import DecisionPanel from './DecisionPanel.svelte';
  import PanelSkeleton from './PanelSkeleton.svelte';
  import PickedFigures from './PickedFigures.svelte';
  import Table from './Table.svelte';
  import TableSkeleton from './TableSkeleton.svelte';
  import TapOn from './TapOn.svelte';

  let { onreview }: { onreview: (id: string) => void } = $props();

  const names = $derived([t('you'), t('cpu_n', 1), t('cpu_n', 2), t('cpu_n', 3)]);
  let confirmNew = $state(false);
  let hideResult = $state(false);
  onMount(() => {
    if (!practice.game) void practice.resume();
  });
  const meta = $derived(practice.meta);
  const view = $derived(practice.view);
  const final = $derived(practice.final);
  const stage = $derived(practice.stage);
  const handLabel = $derived(meta ? t('hand_n', meta.hands + (stage === 'playing' ? 1 : 0)) : '');
  const rulesChip = $derived(t('rules_chip', t(taiLabel(settings.rules.tai)), t(flowerLabel(settings.rules.flowers))));
  const rulesShort = $derived(t('rules_chip', t(taiLabel(settings.rules.tai)), t(flowerShort(settings.rules.flowers))));
  const handNo = $derived(meta ? String(meta.hands + (stage === 'playing' ? 1 : 0)) : '');
  const lastFav = $derived(practice.lastRecord ? !!records.get(practice.lastRecord)?.fav : false);
  $effect(() => { if (stage === 'playing') hideResult = false; });
  /** A hand's end: the result's moment waits for the player's tap (Table.svelte), then the result; without one (words
   *  alone) the table as it ended waits for it (TapOn.svelte). Not for a result come back to. */
  let held = $state(false);
  let inPlay = false;
  $effect(() => {
    if (stage === 'playing') {
      inPlay = true;
      held = false;
      return;
    }
    if (stage !== 'result' || !inPlay) return;
    inPlay = false;
    held = true;
    // The result's moment was tapped on (Table.svelte): the result at once; else (words alone) till a tap.
    let alive = true;
    void fxDone().then(() => { if (alive && resultTap.tapped) held = false; });
    return () => { alive = false; };
  });

  function act(a: Action) { void practice.act(a); }
  /** The manual pace: the others' next step waits for the player. */
  const manual = $derived(settings.speed === 'manual');
  const onnext = $derived(stage === 'playing' && practice.waiting ? () => practice.step()
    : null);
  const totals = $derived(meta?.totals ?? [0, 0, 0, 0]);
  const shown = $derived(stage !== 'playing' && final ? final : view);

  // 練習時顯示分析: the player's decision analyzed when it comes (a few milliseconds of the engine's), shown once there;
  // on till the end of a hand that had it on.
  const hintsOn = $derived(settings.practiceHints || (stage === 'playing' && !!meta?.hinted));
  $effect(() => {
    if (hintsOn && stage === 'playing' && view) untrack(() => practice.markHinted());
  });
  let hint = $state.raw<{ game: GameRecord; analysis: Analysis } | null>(null);
  $effect(() => {
    const v = view, g = practice.game;
    if (!hintsOn || !v || !g || stage !== 'playing' || !v.my_turn || v.phase === 'over') return;
    if (untrack(() => hint)?.game === g) return;
    void engine.analyzeGame(g, ME).then((a) => { if (practice.game === g) hint = { game: g, analysis: a }; }, () => {});
  });
  const mine = $derived(hintsOn && stage === 'playing' && view?.my_turn && hint?.game === practice.game && hint.analysis.best !== undefined
    ? hint.analysis : null);
  const NO_MARKS = new Map<T, TileMark>();
  const marks = $derived(hintsOn && stage === 'playing' ? (mine ? tileMarks(mine, -1, undefined) : NO_MARKS) : null);
  const choices = $derived(mine ? choiceMarks(mine, -1, undefined) : null);
  // The opponents' notes and the player's expected result stay as the player's last decision left them while the
  // others play (a short while: a little out of date at most), till the next decision's; a new hand starts without.
  let keptNotes = $state.raw<ReturnType<typeof opponentNotes>>(null);
  let keptValue = $state<number | null>(null);
  $effect(() => { if (mine) { keptNotes = opponentNotes(mine); keptValue = mine.value ?? null; } });
  $effect(() => { void practice.deal; keptNotes = null; keptValue = null; });
  const notes = $derived(hintsOn && stage === 'playing' ? keptNotes : null);
  const myValue = $derived(hintsOn && stage === 'playing' ? keptValue : null);
  let picked = $state<T | null>(null);
  // The list beside the table, where the window has room for the panel (wider with the hints on).
  let asideShown = $state(false);
  /** The hand's deal going on (Table): the list in grey till its tiles are turned up. */
  let inDeal = $state(false);
  const pickedIndex = $derived(mine && picked ? mine.actions.findIndex((f) => f.action.type === 'discard' && f.action.tile === picked) : -1);
  const lastHinted = $derived(practice.lastRecord ? !!records.get(practice.lastRecord)?.hints : false);
</script>

<!-- Over the hand: the figures of the tile chosen. -->
{#snippet strip()}
  {#if mine && picked && !asideShown}<PickedFigures analysis={mine} seat={ME} {names} tile={picked} />{/if}
{/snippet}

<!-- The panel's list with the hints on: its shape in grey while the computer players play, and through the deal. -->
{#snippet hintList()}
  <div class="p-list">
    {#if mine && !inDeal}
      <DecisionPanel analysis={mine} w={32} {names} picked={pickedIndex} />
    {:else}
      <PanelSkeleton w={32} rows={4} />
    {/if}
  </div>
{/snippet}

{#snippet notice()}<span class="notice">{practice.notice}</span>{/snippet}
<div class="play">
  {#if !ui.wide}
  <header class="bar" data-tour="top" {@attach shorten}>
    <!-- The round's label, then the rules', in a few characters where the bar has no room for them (東-3). -->
    {#if view}<div class="where title-medium ellipsis" data-full="{t('round_wind', windName(view.position.setup.round))} {handLabel}"
      data-short={t('round_short', windName(view.position.setup.round), handNo)}></div>{/if}
    <button class="chip" aria-label={rulesChip} onclick={() => nav.open('rules')}><span data-full={rulesChip} data-short={rulesShort}></span><Icon name="dropDown" size={18} /></button>
    <div class="spacer"></div>
    <button class="btn text" onclick={() => (confirmNew = true)}>{t('practice_new')}</button>
  </header>
  {/if}
  <div class="table-wrap">
    {#if shown}
      <!-- (Another game, a scene of the tuning tool: a table of its own, nothing of the last one's moments.) -->
      {#key meta?.id}
      <Table view={shown} me={ME} {names} scores={totals} revealed={stage !== 'playing' ? final?.hands ?? null : null}
        interactive={stage === 'playing' && !practice.busy} onact={act} handLabel={ui.wide ? handLabel : ''} handShort={ui.wide ? handNo : ''} maxHand={ui.desk ? 72 : 64}
        keys={ui.desk} {onnext} stay={manual} status={practice.notice ? notice : undefined} robbed={practice.robbed}
        deal={stage === 'playing' ? practice.deal : null} {marks} {notes} {strip} onpick={(x) => (picked = x)} choiceMarks={choices}
        tagRoom={hintsOn && stage === 'playing'} asideW={hintsOn ? 400 : 300} value={myValue} bind:asideShown bind:inDeal>
        {#snippet corner()}
          {#if ui.wide}
            <span class="corner-buttons" data-tour="top">
              <button class="mini" aria-label={rulesChip} onclick={() => nav.open('rules')}><span data-full={rulesChip} data-short={rulesShort}></span></button>
              <button class="mini" onclick={() => (confirmNew = true)}>{t('practice_new')}</button>
            </span>
          {/if}
        {/snippet}
        {#snippet aside()}
          <div class="panel">
            <div class="p-head">
              <div class="title-large">{t('round_wind', windName(shown.position.setup.round))} {handLabel}</div>
              <div class="body-medium dim">{t('wall_left', shown.position.wall_left)}</div>
            </div>
            <div class="p-card">
              <div class="label-large dim">{t('scores_title')}</div>
              {#each [0, 1, 2, 3] as seat}
                <div class="p-score" class:me={seat === ME}>
                  <span class="p-wind" class:dealer={shown.position.setup.dealer === seat}>{windName(shown.seat_winds[seat])}</span>
                  <span class="p-name">{names[seat]}</span>
                  {#if shown.position.setup.dealer === seat}<span class="p-deal">{t('dealer_mark')}{shown.position.setup.streak > 0 ? t('streak_n', shown.position.setup.streak) : ''}</span>{/if}
                  <span class="p-pts" class:good={totals[seat] > 0} class:bad={totals[seat] < 0}>{signed(totals[seat])}</span>
                </div>
              {/each}
            </div>
            <div class="p-buttons" data-tour="top">
              <button class="chip on-felt" onclick={() => nav.open('rules')}>{rulesChip}<Icon name="dropDown" size={18} /></button>
              <button class="btn tonal" onclick={() => (confirmNew = true)}>{t('practice_new')}</button>
            </div>
            {#if hintsOn}
              {@render hintList()}
            {:else}
              <div class="p-card keys">
                <div class="label-large dim">{t('keys_title')}</div>
                <div>{t('key_choose')}</div>
                <div>{t('key_discard')}</div>
                <div>{t('key_claims')}</div>
                <div class="dim">{t('key_chi_ways')}</div>
              </div>
            {/if}
          </div>
        {/snippet}
        {#snippet overlay()}
          {#if stage === 'result' && (held || fxOn.on)}
            <TapOn onnext={() => (held = false)} />
          {:else if stage === 'result' && final?.result && !hideResult}
            <div class="sheet">
              <HandResult {final} {names} me={ME} rev={practice.lastRecord ? records.get(practice.lastRecord)?.rev ?? null : null} w={ui.wide ? Math.min(40, Math.max(26, ui.w / 27)) : Math.min(36, Math.max(26, ui.w / 13))}>
                {#snippet actions()}
                  {#if lastHinted}<span class="body-small hint-note">{t('result_hints_note')}</span>{/if}
                  <button class="btn icon" aria-label={t('favorite')} onclick={() => practice.lastRecord && records.setFav(practice.lastRecord, !lastFav)}>
                    <Icon name={lastFav ? 'star' : 'starBorder'} />
                  </button>
                  <button class="btn text" onclick={() => (hideResult = true)}>{t('result_hands')}</button>
                  <button class="btn tonal" onclick={() => practice.lastRecord && onreview(practice.lastRecord)}>{t('review_hand')}</button>
                  <button class="btn filled" onclick={() => practice.nextHand()}>{meta?.over ? t('see_totals') : t('next_hand')}</button>
                {/snippet}
              </HandResult>
            </div>
          {:else if stage === 'result' && hideResult}
            <div class="peek"><button class="btn filled" onclick={() => (hideResult = false)}>{t('back')}</button></div>
          {:else if stage === 'over' && meta}
            <div class="sheet">
              <div class="totals">
                <div class="headline-small">{t('game_over')}</div>
                <div class="body-medium dim">{t('hands_played', meta.hands)}</div>
                <div class="tot-grid">
                  {#each [0, 1, 2, 3] as seat}
                    <div class="tot" class:me={seat === ME}>
                      <span>{names[seat]}</span>
                      <span class="pts" class:good={totals[seat] > 0} class:bad={totals[seat] < 0}>{signed(totals[seat])}</span>
                    </div>
                  {/each}
                </div>
                <div class="actions">
                  <button class="btn filled" onclick={() => practice.newGame()}>{t('new_game')}</button>
                </div>
              </div>
            </div>
          {/if}
        {/snippet}
      </Table>
      {/key}
    {:else}
      <TableSkeleton />
    {/if}
  </div>
</div>

{#if confirmNew}
  <Dialog onclose={() => (confirmNew = false)}>
    <p class="body-large" style="margin:0">{t('practice_new_confirm')}</p>
    {#snippet actions()}
      <button class="btn text" onclick={() => (confirmNew = false)}>{t('cancel')}</button>
      <button class="btn text" onclick={() => { confirmNew = false; void practice.newGame(); }}>{t('ok')}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .play { height: 100%; display: flex; flex-direction: column; }
  .bar { flex: none; display: flex; align-items: center; gap: 8px; padding: 4px 8px; min-height: 48px; }
  .where { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
  .bar .chip { flex: none; white-space: nowrap; }
  .mini { border: 1px solid var(--on-felt-dim); background: transparent; color: var(--on-felt); border-radius: 12px; padding: 1px 8px;
    font-size: 0.75rem; cursor: pointer; }
  .spacer { flex: 1; }
  .hint-note { color: var(--on-surface-variant); margin-right: auto; }
  /* On the felt: the list on a card of the page's own colors (its rows are made for them; on the felt its words were
     white on light rows). */
  .p-list { margin-top: 4px; padding: 8px; border-radius: 16px; background: var(--surface); color: var(--on-surface); }
  /* The corner's buttons together (the tour lights them as one), one over the other in the table's top-right corner. */
  .corner-buttons { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
  .notice { display: inline-block; padding: 4px 12px; border-radius: 12px; background: rgba(0, 0, 0, 0.72); color: #fff; font-weight: 500;
    font-size: 0.9375rem; }
  .table-wrap { flex: 1; min-height: 0; position: relative; }
  .sheet { position: absolute; inset: 0; z-index: 5; display: flex; align-items: center; justify-content: center; padding: 12px;
    background: rgba(0, 0, 0, 0.35); }
  .sheet :global(.result) { max-width: min(760px, 100%); }
  .peek { position: absolute; right: 12px; top: 12px; z-index: 5; }
  .totals { display: flex; flex-direction: column; gap: 12px; padding: 20px; border-radius: 20px; background: var(--surface-container-high);
    color: var(--on-surface); min-width: min(420px, 100%); }
  .tot-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
  .tot { display: flex; flex-direction: column; align-items: center; padding: 8px; border-radius: 12px; background: var(--surface-container); }
  .tot.me { outline: 2px solid var(--primary); }
  .pts { font-size: 1.375rem; font-weight: 700; }
  .actions { display: flex; justify-content: flex-end; gap: 8px; }
  .panel { display: flex; flex-direction: column; gap: 14px; padding: 16px 16px 16px 0; color: var(--on-felt); }
  .p-head { display: flex; flex-direction: column; gap: 2px; }
  .p-card { display: flex; flex-direction: column; gap: 6px; padding: 12px 14px; border-radius: 16px; background: rgba(0, 0, 0, 0.18); }
  .p-card .dim, .p-head .dim { color: var(--on-felt-dim); }
  .p-score { display: flex; align-items: center; gap: 8px; font-size: 1rem; }
  .p-score.me .p-name { font-weight: 700; }
  .p-wind { width: 1.7em; height: 1.7em; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center;
    background: var(--on-felt); color: var(--felt); font-weight: 700; flex: none; }
  .p-wind.dealer { background: var(--highlight); color: #3a2a00; }
  .p-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .p-deal { color: var(--highlight); font-weight: 700; flex: none; }
  .p-pts { margin-left: auto; font-weight: 700; flex: none; }
  .p-pts.good { color: var(--felt-good); }
  .p-pts.bad { color: var(--felt-bad); }
  .p-buttons { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .chip.on-felt { color: var(--on-felt); border-color: var(--on-felt-dim); background: transparent; }
  .keys { font-size: 0.9rem; line-height: 1.5; }
</style>
