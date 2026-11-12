<!--
  A branch of a reviewed hand (the review's 分支): from one of the player's decisions the hand is played on, the player
  at their seat and the computer players (the settings' bot) at the others, however the hand was first played (a
  practice game, a table battle, a contest). Nothing is recorded or scored.

  The tiles are the hand's own (its wall, the others' hands): the branch says what would have come of another choice.
  With 重洗看不到的牌 what the player could not see there (the others' concealed tiles, the wall) is dealt again by the
  branch's seed (the engine's record from a base position, docs/gui-engine.md).

  The computer players always go on by themselves, at the settings' pace (the manual pace: the middle one), until the
  player decides or the hand is over. 上一步 goes back a step, as far as the branch's start, and the player may choose
  otherwise where they decided: what came after is then dropped. 下一步 goes forward again over what was played (it
  does nothing at the newest step). The seed stays for as long as the branch does: the same choices come to the same
  hand; only a new branch (or turning 重洗 on or off) deals with another seed. Back, or the end, returns to the review
  at the step the branch was taken from.
-->
<script lang="ts">
  import { fxDone, fxOn, resultTap } from '../lib/fx.svelte';
  import TapOn from './TapOn.svelte';
  import { untrack } from 'svelte';
  import { calloutsDone } from '../lib/callouts';
  import { justCalled, othersCall, preclaim, robbedEvent, robPause } from '../lib/claims';
  import * as engine from '../lib/engine';
  import { t } from '../lib/i18n.svelte';
  import { settings } from '../lib/settings.svelte';
  import { ms } from '../lib/timing';
  import type { Action, GameRecord, View } from '../lib/types';
  import { ui } from '../lib/ui.svelte';
  import HandResult from './HandResult.svelte';
  import Icon from './Icon.svelte';
  import Table from './Table.svelte';
  import TitleBar from './TitleBar.svelte';

  let { game: source, me, names, onclose }: { game: GameRecord; me: number; names: string[]; onclose: () => void } = $props();

  const newSeed = () => Math.random().toString(36).slice(2, 10);
  let reshuffle = $state(false);
  let seed = $state(newSeed());
  /** The branch's start: the hand cut at the decision, or (reshuffled) its position with the unseen tiles dealt again. */
  const root = $derived<GameRecord>(reshuffle
    ? { rules: source.rules, setup: source.setup, base: { game: source, seat: me, seed }, actions: [] }
    : source);
  const start = $derived(root.actions.length);
  let game = $state<GameRecord | null>(null);
  /** The actions shown (of `game`): less than all after 上一步. */
  let cursor = $state(0);
  let view = $state<View | null>(null);
  let final = $state<View | null>(null);
  let error = $state<string | null>(null);
  let showResult = $state(true);
  /** The computer players deciding first on a tile the player could claim (lib/claims.ts): no buttons meanwhile. */
  let deciding = $state(false);
  /** The end: the table as it ended till the player's tap (TapOn.svelte), then the result. */
  let held = $state(false);
  const over = $derived(view?.phase === 'over');
  $effect(() => {
    if (!over) {
      held = false;
      return;
    }
    held = true;
    // The result's moment was tapped on (Table.svelte): the result at once; else (words alone) till a tap.
    let alive = true;
    void fxDone().then(() => { if (alive && resultTap.tapped) held = false; });
    return () => { alive = false; };
  });
  /** A claim taken from the player: its event (Table's `robbed`). */
  let robbed = $state<number | null>(null);
  /** Changes when the branch starts again or the player goes back: a run of the computer players' turns stops. */
  let epoch = 0;

  const at = (g: GameRecord, k: number): GameRecord => ({ ...g, actions: g.actions.slice(0, k) });
  const bots = $derived([0, 1, 2, 3].map((s) => (s === me ? null : settings.bot)));
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

  function restart() {
    epoch++;
    game = { ...root, actions: [...root.actions] };
    cursor = start;
    final = null;
    showResult = true;
    void show();
  }
  // A new start (the first, or 重洗 turned on or off): from the decision again.
  // (untracked: what restart reads, the game and the step shown, are not the effect's to follow.)
  $effect(() => {
    void root;
    untrack(restart);
  });

  async function show() {
    const g = game;
    if (!g) return;
    const k = cursor, e = epoch;
    try {
      const v = await engine.view(at(g, k), me, 0, false);
      if (e !== epoch || k !== cursor || g !== game) return;
      view = v;
      error = null;
      if (v.phase === 'over') final = await engine.view(at(g, k), me, 0, true);
      else final = null;
      // At the newest step the computer players go on, and decide first on a tile the player could claim.
      if (k === g.actions.length && v.phase !== 'over' && (!v.my_turn || v.phase !== 'self')) void run();
    } catch (x) {
      error = String((x as Error).message ?? x);
    }
  }

  /** The computer players, a decision point at a time, until the player decides or the hand is over. */
  // (A loop of another epoch, waiting out a pace when the branch started again, ends at its next look: it holds back
  // neither this one nor its state.)
  let running = false;
  let loop = 0;
  let loopEpoch = -1;
  async function run() {
    if (running && loopEpoch === epoch) return;
    const my = ++loop;
    running = true;
    const e = epoch;
    loopEpoch = e;
    try {
      while (game && e === epoch && cursor === game.actions.length) {
        const p = await engine.pending(game);
        if (p.phase === 'over') break;
        if (p.pending.includes(me)) {
          deciding = true;
          const shown = performance.now();
          const pc = await preclaim(game, me, bots, `${seed}/${game.actions.length}`);
          if (e !== epoch || !game || !pc) break;
          if (!pc.robbed) {
            game = pc.game;
            cursor = game.actions.length;
            view = await engine.view(game, me);
            break;
          }
          await calloutsDone();
          const left = Math.max(0, robPause() - (performance.now() - shown));
          await sleep(left);
          if (e !== epoch || !game) break;
          const before = view?.event_count ?? 0;
          const v = await engine.view(pc.game, me);
          if (e !== epoch) break;
          robbed = robbedEvent(v.events, before, pc.robbed.seat);
          game = pc.game;
          cursor = game.actions.length;
          view = v;
          deciding = false;
          if (v.phase === 'over') final = await engine.view(game, me, 0, true);
          continue;
        }
        // A computer player's turn at its pace; their answers to a tile: a call after the tile's stay, none at once
        // (lib/claims.ts othersCall).
        // (The pace counts once a call's moment or words are over.)
        const claim = p.phase !== 'self';
        await calloutsDone();
        if (e !== epoch || !game) break;
        if (!claim) {
          const key = justCalled(game, p.current) ? 'bot_after_call' : 'bot_turn';
          const pace = ms(key);
          await sleep(pace);
          if (e !== epoch || !game) break;
        }
        const before = view?.event_count ?? 0;
        const from = performance.now();
        const g = await engine.advance(game, bots, `${seed}/${game.actions.length}`, 1);
        if (e !== epoch) break;
        const v = await engine.view(g, me);
        if (e !== epoch) break;
        if (claim && othersCall(v, before, me)) {
          const left = Math.max(0, ms('bot_claim') - (performance.now() - from));
          await sleep(left);
          if (e !== epoch) break;
        }
        game = g;
        cursor = g.actions.length;
        view = v;
        if (v.phase === 'over') final = await engine.view(g, me, 0, true);
      }
    } catch (x) {
      error = String((x as Error).message ?? x);
    } finally {
      if (loop === my) {
        running = false;
        deciding = false;
      }
    }
  }

  /** The player's decision at the step shown: what came after it is dropped. */
  async function act(a: Action) {
    if (!game || !view?.my_turn) return;
    epoch++;
    const e = epoch;
    try {
      const before = view.event_count;
      const g = await engine.act(at(game, cursor), me, a);
      if (e !== epoch) return;
      // The others decided first: a claim of the player's that another's came before is shown as taken (搶).
      if (a.type !== 'pass' && view.phase !== 'self') {
        const v = await engine.view(g, me);
        const fresh = v.events.slice(before);
        const over = fresh.some((x) => x.seat === me) ? undefined : fresh.find((x) => x.seat !== me && ['chi', 'pon', 'kan', 'ron'].includes(x.type));
        if (over) robbed = v.events.indexOf(over);
      }
      game = g;
      cursor = g.actions.length;
      showResult = true;
      await show();
    } catch (x) {
      error = String((x as Error).message ?? x);
    }
  }

  /** A step: the actions that show (others' passes do not). */
  const isStop = (k: number) => !!game && (game.actions[k][0] === me || game.actions[k][1] !== 'pass');
  function go(k: number) {
    if (!game) return;
    epoch++;
    cursor = Math.max(start, Math.min(game.actions.length, k));
    showResult = true;
    void show();
  }
  function prev() { let k = cursor - 1; while (k > start && !isStop(k - 1)) k--; go(k); }
  function next() { if (!game) return; let k = cursor + 1; while (k < game.actions.length && !isStop(k - 1)) k++; go(k); }
  function onkey(e: KeyboardEvent) {
    if (e.key === 'ArrowLeft') prev();
    else if (e.key === 'ArrowRight') next();
    else return;
    e.preventDefault();
  }
  /** Another seed (the effect above starts again). */
  function newBranch() { seed = newSeed(); }
  const sheet = $derived(view?.phase === 'over' && !!final?.result && showResult && !fxOn.on && !held);
  const atHead = $derived(!!game && cursor === game.actions.length);
  const steps = $derived(game ? game.actions.slice(start, cursor).filter((_, i) => isStop(start + i)).length : 0);
</script>

<svelte:window onkeydown={onkey} />

<div class="page">
  <TitleBar title={t('branch_title')} onback={onclose}>
    <label class="shuffle body-medium">
      <input type="checkbox" bind:checked={reshuffle} />{t('branch_reshuffle')}
    </label>
  </TitleBar>
  <div class="table-wrap">
    {#if view}
      <Table {view} {me} {names} revealed={view.phase === 'over' ? (final?.hands ?? null) : null} interactive={!!view.my_turn && !deciding} {robbed}
        onact={act} maxHand={ui.desk ? 64 : 56}>
        {#snippet overlay()}
          {#if over && (held || fxOn.on)}<TapOn onnext={() => (held = false)} />{/if}
          {#if sheet && final}
            <div class="sheet">
              <HandResult {final} {names} {me} rev={null} next={false} w={ui.wide ? Math.min(36, Math.max(24, ui.w / 30)) : Math.min(34, Math.max(24, ui.w / 13))}>
                {#snippet actions()}
                  <button class="btn text" onclick={() => (showResult = false)}>{t('result_hands')}</button>
                  <button class="btn tonal" onclick={restart}>{t('branch_again')}</button>
                  <button class="btn filled" onclick={onclose}>{t('branch_back')}</button>
                {/snippet}
              </HandResult>
            </div>
          {/if}
        {/snippet}
      </Table>
    {/if}
  </div>
  <!-- (Not under the result: on a low window it takes the room the result needs.) -->
  {#if !sheet}<div class="controls">
    <div class="info body-medium">
      <span class="label-large">{t('branch_step', steps)}</span>
      <span class="dim">{error ? error : view?.phase === 'over' ? t('branch_over') : view?.my_turn ? t('branch_your_turn') : atHead ? t('branch_others') : t('branch_back_note')}</span>
      <span class="note body-small">{t('branch_note')}</span>
    </div>
    <div class="buttons">
      <button class="btn icon tonal" aria-label={t('branch_to_start')} title={t('branch_to_start')} disabled={cursor <= start} onclick={() => go(start)}><Icon name="skipPrev" /></button>
      <button class="btn icon tonal" aria-label={t('replay_prev')} title={t('replay_prev')} disabled={cursor <= start} onclick={prev}><Icon name="prev" /></button>
      <button class="btn icon {atHead ? 'tonal' : 'filled'}" aria-label={t('replay_next')} title={t('replay_next')} disabled={atHead} onclick={next}><Icon name="next" /></button>
      <button class="btn icon tonal" aria-label={t('branch_to_newest')} title={t('branch_to_newest')} disabled={atHead} onclick={() => game && go(game.actions.length)}><Icon name="skipNext" /></button>
      <button class="btn tonal" onclick={newBranch}>{t('branch_new')}</button>
    </div>
  </div>{/if}
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .table-wrap { flex: 1; min-height: 0; position: relative; }
  .controls { padding: 6px 12px 10px; display: flex; flex-direction: column; gap: 6px; background: var(--surface-container-low, var(--surface)); }
  .info { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 12px; min-height: 2.6em; }
  .note { color: var(--on-surface-variant); margin-left: auto; }
  .buttons { display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; }
  .shuffle { display: inline-flex; align-items: center; gap: 6px; padding: 0 8px; cursor: pointer; white-space: nowrap; }
  .shuffle input { width: 18px; height: 18px; accent-color: var(--primary); }
  .sheet { position: absolute; inset: 0; z-index: 5; display: flex; align-items: center; justify-content: center; padding: 12px; background: rgba(0, 0, 0, 0.35); }
</style>
