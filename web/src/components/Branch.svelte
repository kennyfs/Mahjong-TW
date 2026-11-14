<!--
  A branch of a reviewed hand (the review's 分支), or a position of the analysis played on (從這裡打): from a decision
  the hand is played on, the player at their seat and the computer players (the settings' bot) at the others, however
  the hand was first played (a practice game, a table battle, a contest). Nothing is recorded or scored.

  With 重洗看不到的牌 (on at first, the user's) what the player could not see there (the others' concealed tiles, the wall) is dealt again by the
  branch's seed (the engine's record from a base position, docs/gui-engine.md). A position of the analysis has no hand
  of its own: what the player cannot see there is always dealt by the seed. Turned off, the tiles are the hand's own
  (its wall, the others' hands): the branch says what would have come of another choice.

  The analysis is there as the review shows it, before the player decides: on the hand's tiles what each discard costs
  against the best and its danger, on each choice's button (胡, 碰, 吃...) what it costs, on the name plates how likely
  each opponent is ready (and, when likely, the tile it most likely waits on), and the list of every action: beside the
  table on a large window; else one tap away in place of the table, the deciding seat's tiles over it (the player may
  play there too; DecisionView), its shape in grey while the computer players play. A tap on a tile (a computer: the
  mouse over it) lifts it a little and shows its figures over the hand (with the list beside the table, its row there
  is lit instead); a second tap discards it.
  A step gone back to shows the decision made there the same way, the action made there marked; another seat's step
  shows that seat's decision in the list. A step's table and its analysis come together (each a few milliseconds of
  the engine's; the player's next decision is worked out while the computer player before them takes its turn).

  The computer players always go on by themselves, at the settings' pace (the manual pace: the middle one), until the
  player decides or the hand is over. 上一步 goes back a step, as far as the branch's start, and the player may choose
  otherwise where they decided: what came after is then dropped. 下一步 goes forward again over what was played (it
  does nothing at the newest step); 上一個決定 and 下一個決定 go to the player's own decisions. The seed stays for as
  long as the branch does: the same choices come to the same hand; only a new branch (or turning 重洗 on or off, which
  asks first when anything was played) deals with another seed. Back, or the end, returns to where the branch was
  taken from.

  The controls as the review has them: under the table on an upright window (the words there keep the room of their
  longest, so that the table does not move from step to step), in a column at the right on a wide one (the table the
  full height), with the list under them on a large one.
-->
<script lang="ts">
  import { untrack } from 'svelte';
  import { fullAction, isTaken } from '../lib/actions';
  import { BotPace, othersCall, preclaim, robbedEvent } from '../lib/claims';
  import * as engine from '../lib/engine';
  import { fxDone, fxOn, resultTap } from '../lib/fx.svelte';
  import { t } from '../lib/i18n.svelte';
  import { choiceMarks, opponentNotes, pts, tileMarks, type TileMark } from '../lib/review';
  import { topWhenAway } from '../lib/scroll';
  import { settings } from '../lib/settings.svelte';
  import type { Tile as T } from '../lib/tiles';
  import { recordAction, type Action, type Analysis, type GameRecord, type View } from '../lib/types';
  import { ui } from '../lib/ui.svelte';
  import DecisionPanel from './DecisionPanel.svelte';
  import DecisionView from './DecisionView.svelte';
  import Dialog from './Dialog.svelte';
  import HandResult from './HandResult.svelte';
  import Icon from './Icon.svelte';
  import PanelSkeleton from './PanelSkeleton.svelte';
  import PickedFigures from './PickedFigures.svelte';
  import Table from './Table.svelte';
  import TapOn from './TapOn.svelte';
  import TitleBar from './TitleBar.svelte';

  let { game: source, me, names, onclose, unit = undefined, title = '', backLabel = '' }: {
    game: GameRecord; me: number; names: string[]; onclose: () => void;
    /** A typical payment under the hand's rules (the review's), to grade what an action costs by. */
    unit?: number;
    /** The page's title and the result's way back: a branch's (分支, 回到回顧) unless given. */
    title?: string; backLabel?: string;
  } = $props();

  const newSeed = () => Math.random().toString(36).slice(2, 10);
  // (On by default, the user's: a branch is for trying another way, not for knowing what comes.)
  let reshuffle = $state(true);
  let seed = $state(newSeed());
  /** A position with no hand of its own (the analysis'): what the player cannot see is always dealt by the seed. */
  const dealt = $derived(!!source.base);
  /** The branch's start: the hand cut at the decision, or (reshuffled, or a position) its position with the unseen
   *  tiles dealt by the seed. */
  const root = $derived<GameRecord>(source.base ? { ...source, base: { ...source.base, seed } }
    : reshuffle ? { rules: source.rules, setup: source.setup, base: { game: source, seat: me, seed }, actions: [] }
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
  /** The computer players' pace (lib/claims.ts BotPace): their turns, their discards' flights, their answers to a tile. */
  const pace = new BotPace();

  // Where the controls go: under the table, or in a column at its right on a wide window; the list beside the table on
  // a large window, else in place of the table when asked for.
  const side = $derived(ui.wide);
  const inlinePanel = $derived(ui.wide && ui.w >= 1180 && ui.h >= 600);
  let panelOpen = $state(false);
  const listShown = $derived(inlinePanel || panelOpen);
  // The list beside the table back at its top when the mouse goes far from the hand (lib/scroll.ts).
  let inlineEl = $state<HTMLElement | null>(null);
  $effect(() => {
    const el = inlineEl;
    if (el) return topWhenAway(el, () => document.querySelector<HTMLElement>('.table .me .hand'));
  });
  const listView = $derived(panelOpen && !inlinePanel && !over);
  $effect(() => { void listView; selected = null; });

  /** A step's decision as the analysis shows it: whose it is, what was done there (a step gone back to; null: the
   *  decision to make now), the network's figures, and with the list in place of the table the seat's own view. */
  interface Look { seat: number; action: Action | null; analysis: Analysis | null; seatView: View | null }
  let look = $state<Look | null>(null);
  /** The decision at step k (its table `v`): the player's, always analyzed; another seat's, when the list is shown. At
   *  the newest step only the player's: the computer players go on by themselves. */
  async function lookAt(g: GameRecord, k: number, v: View): Promise<Look | null> {
    if (v.phase === 'over') return null;
    const d = k < g.actions.length ? recordAction(g.actions[k]) : v.my_turn ? { seat: me, action: null } : null;
    if (!d) return null;
    const cut = at(g, k);
    const [analysis, seatView] = await Promise.all([
      d.seat === me || listShown ? engine.analyzeGame(cut, d.seat).catch(() => null) : Promise.resolve(null),
      !listView ? Promise.resolve(null) : d.seat === me ? Promise.resolve(v) : engine.view(cut, d.seat).catch(() => null),
    ]);
    return { seat: d.seat, action: d.action ? fullAction(d.action) : null, analysis, seatView };
  }
  // The list opened or closed: the step's decision again, with what the list needs.
  $effect(() => {
    void listShown;
    void listView;
    untrack(() => void refreshLook());
  });
  async function refreshLook() {
    const g = game, v = view, k = cursor;
    if (!g || !v || deciding) return;
    const l = await lookAt(g, k, v);
    if (g === game && v === view && k === cursor && !deciding) look = l;
  }

  function restart() {
    epoch++;
    pace.reset();
    game = { ...root, actions: [...root.actions] };
    cursor = start;
    final = null;
    showResult = true;
    selected = null;
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
      const head = k === g.actions.length;
      // At the newest step the computer players decide first on a tile the player could claim (run): the player's
      // buttons and figures come after.
      const claimFirst = head && v.my_turn && v.phase !== 'self' && v.phase !== 'over';
      const [l, f] = await Promise.all([
        claimFirst ? Promise.resolve(null) : lookAt(g, k, v),
        v.phase === 'over' ? engine.view(at(g, k), me, 0, true) : Promise.resolve(null),
      ]);
      if (e !== epoch || k !== cursor || g !== game) return;
      if (claimFirst) deciding = true;
      view = v;
      look = l;
      final = f;
      error = null;
      if (head && v.phase !== 'over' && (!v.my_turn || v.phase !== 'self')) void run();
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
          const pc = await preclaim(game, me, bots, `${seed}/${game.actions.length}`);
          // (No answer to wait for: the player's own turn, shown with its figures already.)
          if (e !== epoch || !game || !pc) break;
          if (!pc.robbed) {
            const g = pc.game;
            const v = await engine.view(g, me);
            const l = await lookAt(g, g.actions.length, v);
            if (e !== epoch) break;
            game = g;
            cursor = g.actions.length;
            view = v;
            look = l;
            break;
          }
          // The discard stays a while first (rob_show, from when it came: BotPace), its words done.
          await pace.settle();
          await pace.rob();
          if (e !== epoch || !game) break;
          const before = view?.event_count ?? 0;
          const v = await engine.view(pc.game, me);
          if (e !== epoch) break;
          robbed = robbedEvent(v.events, before, pc.robbed.seat);
          game = pc.game;
          cursor = game.actions.length;
          view = v;
          look = null;
          deciding = false;
          if (v.phase === 'over') final = await engine.view(game, me, 0, true);
          continue;
        }
        // A computer player's turn at its pace, its discard flying in to the river in the last of it, over when the
        // tile lands; their answers to a tile: a call after the tile's stay, none at once (lib/claims.ts othersCall).
        // Each counts once a call's moment or words are over. (BotPace.)
        const claim = p.phase !== 'self';
        const cur = game;
        await pace.settle();
        if (e !== epoch || !game) break;
        const before = view?.event_count ?? 0;
        const from = performance.now();
        const g = await engine.advance(cur, bots, `${seed}/${cur.actions.length}`, 1);
        if (e !== epoch) break;
        const v = await engine.view(g, me);
        if (e !== epoch) break;
        // The player's own turn next: its figures are worked out while the turn before it is paced.
        const next = v.my_turn && v.phase === 'self' ? lookAt(g, g.actions.length, v) : Promise.resolve(null);
        let fly = 0;
        if (claim) {
          if (othersCall(v, before, me)) await pace.call();
        } else fly = await pace.turn(cur, p.current, g, from);
        const l = await next;
        if (e !== epoch) break;
        // A tile the player could claim: no buttons until the computer players have answered (above).
        if (v.my_turn && v.phase !== 'self' && v.phase !== 'over') deciding = true;
        game = g;
        pace.came(g, fly);
        cursor = g.actions.length;
        view = v;
        look = l;
        if (v.phase === 'over') final = await engine.view(g, me, 0, true);
        if (fly > 0) await pace.land();
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
      // The player's discard flies to the river: the computer player after them discards once it has landed.
      pace.came(g, pace.flight());
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
    selected = null;
    void show();
  }
  function prev() { let k = cursor - 1; while (k > start && !isStop(k - 1)) k--; go(k); }
  function next() { if (!game) return; let k = cursor + 1; while (k < game.actions.length && !isStop(k - 1)) k++; go(k); }
  /** The player's own decisions: the step at which they decide (the newest step when none is after). */
  const isMine = (k: number) => !!game && game.actions[k][0] === me;
  function prevMine() { let k = cursor - 1; while (k > start && !isMine(k)) k--; go(k); }
  function nextMine() { if (!game) return; let k = cursor + 1; while (k < game.actions.length && !isMine(k)) k++; go(k); }
  function onkey(e: KeyboardEvent) {
    if (confirmShuffle) return;
    if (e.key === 'ArrowLeft') prev();
    else if (e.key === 'ArrowRight') next();
    else if (e.key === 'ArrowUp') prevMine();
    else if (e.key === 'ArrowDown') nextMine();
    else return;
    e.preventDefault();
  }
  /** Another seed (the effect above starts again). */
  function newBranch() { seed = newSeed(); }
  /** 重洗看不到的牌 turned on or off starts the branch again: asked first when anything was played. */
  let confirmShuffle = $state(false);
  function askShuffle(e: MouseEvent) {
    if (game && game.actions.length > start) {
      e.preventDefault();
      confirmShuffle = true;
    } else reshuffle = !reshuffle;
  }
  const sheet = $derived(view?.phase === 'over' && !!final?.result && showResult && !fxOn.on && !held);
  const atHead = $derived(!!game && cursor === game.actions.length);
  const steps = $derived(game ? game.actions.slice(start, cursor).filter((_, i) => isStop(start + i)).length : 0);
  const status = $derived(error ? error : over ? t('branch_over') : view?.my_turn ? t(view.legal.some((a) => a.type === 'discard') ? 'branch_your_turn' : 'branch_your_call') : atHead ? t('branch_others') : t('branch_back_note'));

  // What the analysis shows: on the table the player's own decision; in the list any seat's.
  const NO_MARKS = new Map<T, TileMark>();
  /** The index of the action made at a step gone back to, in its analysis; -1 at the decision to make now. */
  const played = $derived(look?.analysis && look.action ? look.analysis.actions.findIndex((f) => isTaken(f.action, look!.action!)) : -1);
  /** The player's own decision at this step, with the network's figures. */
  const mine = $derived(look && look.seat === me && look.analysis?.best !== undefined ? look.analysis : null);
  const marks = $derived(mine ? tileMarks(mine, played, unit) : NO_MARKS);
  const choices = $derived(mine ? choiceMarks(mine, played, unit) : null);
  const notes = $derived(opponentNotes(mine));

  // The list in place of the table: the deciding seat's own tiles over it; the player's own while the others play.
  const hw = $derived(ui.wide ? Math.min(44, ((ui.w - 208) * 0.46 - 24) / 9.3) : Math.min(44, (Math.min(ui.w, 720) - 28) / 9.3));
  /** The player may play from the list: their decision, now. */
  const canPlay = $derived(!!view?.my_turn && !deciding && !over && look?.seat === me);
  // (No decision to show: the status under it says what goes on, once.)
  const listHeading = $derived(look ? (look.seat === me ? t('analysis_your_turn') : t('analysis_turn_of', names[look.seat])) : '');

  // The tile chosen on the table (or under the mouse), to see its figures; with the list beside the table its row.
  let selected = $state<number | null>(null);
  let picked = $state<T | null>(null);
  const pickedIndex = $derived(look?.analysis && picked ? look.analysis.actions.findIndex((f) => f.action.type === 'discard' && f.action.tile === picked) : -1);
</script>

<svelte:window onkeydown={onkey} />

<!-- A decision's list beside the table: its shape in grey while there is none (the computer players playing). -->
{#snippet panel()}
  {#if look?.analysis}
    <DecisionPanel analysis={look.analysis} {played} {unit} w={34} {names} picked={pickedIndex} who={look.seat === me ? '' : names[look.seat]} />
  {:else}
    <PanelSkeleton w={34} />
  {/if}
{/snippet}

<!-- Over the hand on the table: the figures of the tile chosen (with the list beside the table its row there is lit
     instead). -->
{#snippet strip()}
  {#if mine && picked && !inlinePanel}<PickedFigures analysis={mine} seat={me} {names} tile={picked} />{/if}
{/snippet}

{#snippet shuffle()}
  {#if !dealt}
    <label class="shuffle body-medium">
      <input type="checkbox" checked={reshuffle} onclick={askShuffle} />{t('branch_reshuffle')}
    </label>
  {/if}
{/snippet}

{#snippet listToggle()}
  {#if !inlinePanel}
    <button class="btn icon text" class:on={panelOpen} aria-label={t('review_panel')} title={t('review_panel')} aria-pressed={panelOpen}
      onclick={() => (panelOpen = !panelOpen)}>
      <Icon name="assessment" />
    </button>
  {/if}
{/snippet}

<!-- The step, the expected result at the player's decision, and what to do: the words keep the room of their longest. -->
{#snippet info()}
  <div class="info body-medium">
    <div class="line1">
      <span class="label-large">{t('branch_step', steps)}</span>
      {#if mine?.value !== undefined}<span class="now"><span class="dim">{t('review_now')}</span> <b>{pts(mine.value)}</b></span>{/if}
      <span class="note body-small">{t('branch_note')}</span>
    </div>
    <div class="status dim">{status}</div>
  </div>
{/snippet}

{#snippet nb(kind: string)}
  {#if kind === 'start'}
    <button class="btn icon tonal" aria-label={t('branch_to_start')} title={t('branch_to_start')} disabled={cursor <= start} onclick={() => go(start)}><Icon name="skipPrev" /></button>
  {:else if kind === 'prevMine'}
    <button class="btn icon tonal" aria-label={t('replay_prev_decision')} title={t('replay_prev_decision')} disabled={cursor <= start} onclick={prevMine}><Icon name="undo" /></button>
  {:else if kind === 'prev'}
    <button class="btn icon tonal" aria-label={t('replay_prev')} title={t('replay_prev')} disabled={cursor <= start} onclick={prev}><Icon name="prev" /></button>
  {:else if kind === 'next'}
    <button class="btn icon {atHead ? 'tonal' : 'filled'}" aria-label={t('replay_next')} title={t('replay_next')} disabled={atHead} onclick={next}><Icon name="next" /></button>
  {:else if kind === 'nextMine'}
    <button class="btn icon tonal mirror" aria-label={t('replay_next_decision')} title={t('replay_next_decision')} disabled={atHead} onclick={nextMine}><Icon name="undo" /></button>
  {:else}
    <button class="btn icon tonal" aria-label={t('branch_to_newest')} title={t('branch_to_newest')} disabled={atHead} onclick={() => game && go(game.actions.length)}><Icon name="skipNext" /></button>
  {/if}
{/snippet}

{#snippet buttons()}
  {#if side && !inlinePanel}
    <div class="buttons two">
      <div class="brow">{@render nb('start')}{@render nb('prevMine')}{@render nb('nextMine')}{@render nb('newest')}</div>
      <div class="brow">{@render nb('prev')}{@render nb('next')}</div>
      <button class="btn tonal wide-btn" onclick={newBranch}>{t('branch_new')}</button>
    </div>
  {:else}
    <div class="buttons">
      {#each ['start', 'prevMine', 'prev', 'next', 'nextMine', 'newest'] as k (k)}{@render nb(k)}{/each}
      <button class="btn tonal" onclick={newBranch}>{t('branch_new')}</button>
    </div>
  {/if}
{/snippet}

<div class="page" class:side>
  {#if !side}
    <TitleBar title={title || t('branch_title')} onback={onclose}>{@render shuffle()}{@render listToggle()}</TitleBar>
  {/if}
  <div class="main">
    {#if listView}
      <DecisionView view={look?.seatView ?? view} heading={listHeading} analysis={look?.analysis ?? null} {played} {unit} {names}
        who={look && look.seat !== me ? names[look.seat] : ''} {canPlay} onact={act} {choices} bind:selected wide={ui.wide} {hw} />
    {:else}
      <div class="table-wrap">
        {#if view}
          <Table {view} {me} {names} revealed={view.phase === 'over' ? (final?.hands ?? null) : null} interactive={!!view.my_turn && !deciding} {robbed}
            onact={act} maxHand={ui.desk ? 64 : 56} {marks} {notes} {strip} bind:selected onpick={(x) => (picked = x)} choiceMarks={choices} tagRoom>
            {#snippet overlay()}
              {#if over && (held || fxOn.on)}<TapOn onnext={() => (held = false)} />{/if}
              {#if sheet && final}
                <div class="sheet">
                  <HandResult {final} {names} {me} rev={null} next={false} w={ui.wide ? Math.min(36, Math.max(24, ui.w / 30)) : Math.min(34, Math.max(24, ui.w / 13))}>
                    {#snippet actions()}
                      <button class="btn text" onclick={() => (showResult = false)}>{t('result_hands')}</button>
                      <button class="btn tonal" onclick={restart}>{t('branch_again')}</button>
                      <button class="btn filled" onclick={onclose}>{backLabel || t('branch_back')}</button>
                    {/snippet}
                  </HandResult>
                </div>
              {/if}
            {/snippet}
          </Table>
        {/if}
      </div>
    {/if}
    <!-- (Under the table, not under the result: on a low window it takes the room the result needs.) -->
    {#if !side && !sheet}
      <div class="controls">{@render info()}{@render buttons()}</div>
    {/if}
  </div>
  {#if side}
    <!-- No title bar beside the table: the way back at the column's top-left, clear of the table. -->
    <div class="column" class:with-panel={inlinePanel}>
      <div class="side-top">
        <button class="btn icon text" aria-label={t('back')} title={t('back')} onclick={onclose}><Icon name="back" /></button>
        <span class="title-medium side-title">{title || t('branch_title')}</span>
        {@render listToggle()}
      </div>
      {@render shuffle()}
      {#if inlinePanel}
        {@render info()}
        {@render buttons()}
        <div class="inline-panel" bind:this={inlineEl}>{@render panel()}</div>
      {:else}
        {@render info()}
        <div class="foot">{@render buttons()}</div>
      {/if}
    </div>
  {/if}
</div>

{#if confirmShuffle}
  <Dialog onclose={() => (confirmShuffle = false)}>
    <p class="body-large" style="margin:0">{t('branch_reshuffle_confirm')}</p>
    {#snippet actions()}
      <button class="btn text" onclick={() => (confirmShuffle = false)}>{t('cancel')}</button>
      <button class="btn text" onclick={() => { confirmShuffle = false; reshuffle = !reshuffle; }}>{t('branch_restart')}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .page.side { flex-direction: row; }
  .main { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
  .table-wrap { flex: 1; min-height: 0; position: relative; }
  .controls { padding: 6px 12px 10px; display: flex; flex-direction: column; gap: 6px; background: var(--surface-container-low, var(--surface)); }
  .column { flex: none; width: 208px; display: flex; flex-direction: column; gap: 6px; padding: 4px 8px 8px; overflow: auto;
    background: var(--surface-container); }
  .column.with-panel { width: 420px; padding: 4px 12px 12px; }
  .side-top { display: flex; align-items: center; gap: 2px; }
  .side-title { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .foot { margin-top: auto; padding-top: 6px; }
  .info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
  /* One line: the note gives way first (no wrap, so the controls keep their height). */
  .line1 { display: flex; align-items: baseline; gap: 0 12px; white-space: nowrap; overflow: hidden; }
  .column .line1 { flex-wrap: wrap; white-space: normal; }
  .now { font-variant-numeric: tabular-nums; }
  .dim { color: var(--on-surface-variant); }
  .note { color: var(--on-surface-variant); margin-left: auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
  .column .note { margin-left: 0; flex-basis: 100%; }
  /* The room of the longest words: two lines (in the narrow column the buttons stand at its foot whatever the words). */
  .status { min-height: 2lh; }
  .buttons { display: flex; gap: 6px; justify-content: center; align-items: center; flex-wrap: wrap; }
  .buttons .btn.icon { width: 42px; min-height: 42px; border-radius: 21px; }
  .buttons.two { flex-direction: column; align-items: stretch; gap: 6px; }
  .brow { display: flex; justify-content: space-between; gap: 4px; }
  .brow:nth-child(2) { justify-content: center; gap: 12px; }
  .wide-btn { width: 100%; }
  .mirror :global(svg) { transform: scaleX(-1); }
  .btn.icon.on { background: var(--secondary-container); color: var(--on-secondary-container); }
  .shuffle { display: inline-flex; align-items: center; gap: 6px; padding: 0 8px; cursor: pointer; white-space: nowrap; min-height: 40px; }
  .shuffle input { width: 18px; height: 18px; accent-color: var(--primary); }
  .sheet { position: absolute; inset: 0; z-index: 5; display: flex; align-items: center; justify-content: center; padding: 12px; background: rgba(0, 0, 0, 0.35); }
  .inline-panel { flex: 1; min-height: 120px; overflow: auto; margin-top: 4px; }
</style>
