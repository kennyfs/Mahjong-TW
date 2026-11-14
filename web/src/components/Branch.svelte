<!--
  A branch of a reviewed hand (the review's 分支), or a position of the analysis played on (從這裡打): from a decision
  the hand is played on, the player at their seat and the computer players (the settings' bot) at the others, however
  the hand was first played (a practice game, a table battle, a contest). Nothing is recorded or scored.

  The tiles are the hand's own (its wall, the others' hands): the branch says what would have come of another choice.
  With 重洗看不到的牌 what the player could not see there (the others' concealed tiles, the wall) is dealt again by the
  branch's seed (the engine's record from a base position, docs/gui-engine.md). A position of the analysis has no hand
  of its own: what the player cannot see there is always dealt by the seed.

  The analysis is there as the review shows it, before the player decides: on the hand's tiles what each discard costs
  against the best and its danger, on each choice's button (胡, 碰, 吃...) what it costs, on the name plates how likely
  each opponent is ready, and the list of every action: beside the table on a large window; else one tap away in place
  of the table, the deciding seat's tiles over it (the player may play there too). A tap on a tile chooses it and shows
  its figures over the hand (with the list beside the table, its row there is lit instead); a second tap discards it.
  A step gone back to shows the decision made there the same way, the action made there marked; another seat's step
  shows that seat's decision in the list. A step's table and its analysis come together (each a few milliseconds of
  the engine's; the player's next decision is worked out while the computer player before them takes its turn).

  The computer players always go on by themselves, at the settings' pace (the manual pace: the middle one), until the
  player decides or the hand is over. 上一步 goes back a step, as far as the branch's start, and the player may choose
  otherwise where they decided: what came after is then dropped. 下一步 goes forward again over what was played (it
  does nothing at the newest step). The seed stays for as long as the branch does: the same choices come to the same
  hand; only a new branch (or turning 重洗 on or off) deals with another seed. Back, or the end, returns to where the
  branch was taken from.
-->
<script lang="ts">
  import { untrack } from 'svelte';
  import { fullAction, isTaken } from '../lib/actions';
  import { BotPace, othersCall, preclaim, robbedEvent } from '../lib/claims';
  import * as engine from '../lib/engine';
  import { fxDone, fxOn, resultTap } from '../lib/fx.svelte';
  import { t } from '../lib/i18n.svelte';
  import { choiceMarks, pct, pts, readyLevel, tileMarks, type TileMark } from '../lib/review';
  import { flowersOf, meldTiles } from '../lib/seats';
  import { settings } from '../lib/settings.svelte';
  import { parseTiles, sortTiles, windName, without, type Tile as T } from '../lib/tiles';
  import { recordAction, type Action, type Analysis, type GameRecord, type View } from '../lib/types';
  import { ui } from '../lib/ui.svelte';
  import ClaimBar from './ClaimBar.svelte';
  import DecisionPanel from './DecisionPanel.svelte';
  import HandResult from './HandResult.svelte';
  import Icon from './Icon.svelte';
  import MyHand from './MyHand.svelte';
  import PickedFigures from './PickedFigures.svelte';
  import Table from './Table.svelte';
  import TapOn from './TapOn.svelte';
  import Tile from './Tile.svelte';
  import TitleBar from './TitleBar.svelte';

  let { game: source, me, names, onclose, unit = undefined, title = '', backLabel = '' }: {
    game: GameRecord; me: number; names: string[]; onclose: () => void;
    /** A typical payment under the hand's rules (the review's), to grade what an action costs by. */
    unit?: number;
    /** The page's title and the result's way back: a branch's (分支, 回到回顧) unless given. */
    title?: string; backLabel?: string;
  } = $props();

  const newSeed = () => Math.random().toString(36).slice(2, 10);
  let reshuffle = $state(false);
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

  // Where the list goes: beside the table on a large window, else in place of the table when asked for.
  const inlinePanel = $derived(ui.wide && ui.w >= 1180 && ui.h >= 600);
  let panelOpen = $state(false);
  const listShown = $derived(inlinePanel || panelOpen);
  const listView = $derived(panelOpen && !inlinePanel && !over);

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
  const status = $derived(error ? error : over ? t('branch_over') : view?.my_turn ? t('branch_your_turn') : atHead ? t('branch_others') : t('branch_back_note'));

  // What the analysis shows: on the table the player's own decision; in the list any seat's.
  const NO_MARKS = new Map<T, TileMark>();
  /** The index of the action made at a step gone back to, in its analysis; -1 at the decision to make now. */
  const played = $derived(look?.analysis && look.action ? look.analysis.actions.findIndex((f) => isTaken(f.action, look!.action!)) : -1);
  /** The player's own decision at this step, with the network's figures. */
  const mine = $derived(look && look.seat === me && look.analysis?.best !== undefined ? look.analysis : null);
  const marks = $derived(mine ? tileMarks(mine, played, unit) : NO_MARKS);
  const choices = $derived(mine ? choiceMarks(mine, played, unit) : null);
  const notes = $derived.by(() => {
    if (!mine?.opponents) return null;
    const out: ({ text: string; level: number } | null)[] = [null, null, null, null];
    for (const o of mine.opponents) if (o.ready[0] >= 0.1) out[o.seat] = { text: t('opp_ready', pct(o.ready[0])), level: readyLevel(o.ready[0]) };
    return out;
  });

  // The list in place of the table: the deciding seat's own tiles over it.
  const seatHand = $derived.by(() => {
    const sv = look?.seatView;
    if (!look || !sv) return null;
    const pos = sv.position;
    const all = parseTiles(pos.hand);
    const drawn = sv.phase === 'self' && pos.drawn ? pos.drawn : null;
    const p = pos.players[look.seat];
    const head = `${t('round_wind', windName(pos.setup.round))}・${t('wall_left', pos.wall_left)}`;
    return { tiles: sortTiles(drawn ? without(all, [drawn]) : all), drawn, melds: p.melds, flowers: flowersOf(p), claim: pos.claim, head };
  });
  const hw = $derived(ui.wide ? Math.min(44, (ui.w * 0.46 - 24) / 9.3) : Math.min(44, (Math.min(ui.w, 720) - 28) / 9.3));
  /** The player may play from the list: their decision, now. */
  const canPlay = $derived(!!view?.my_turn && !deciding && !over && look?.seat === me);
  const discardable = $derived(new Set(canPlay && view ? view.legal.filter((a) => a.type === 'discard').map((a) => a.tile as T) : []));
  const listChoices = $derived(canPlay && view ? view.legal.filter((a) => a.type !== 'discard') : []);

  // The tile chosen in the hand (the table's, or the list's), to see its figures.
  let selected = $state<number | null>(null);
  let picked = $state<T | null>(null);
  const pickedTile = $derived(listView ? (seatHand && selected !== null ? (selected < seatHand.tiles.length ? seatHand.tiles[selected] : seatHand.drawn) : null) : picked);
  const pickedIndex = $derived(look?.analysis && pickedTile ? look.analysis.actions.findIndex((f) => f.action.type === 'discard' && f.action.tile === pickedTile) : -1);
</script>

<svelte:window onkeydown={onkey} />

<!-- A decision's list. -->
{#snippet panel()}
  {#if look?.analysis}
    <DecisionPanel analysis={look.analysis} {played} {unit} w={inlinePanel ? 34 : Math.max(30, hw * 0.8)} {names} picked={pickedIndex}
      who={look.seat === me ? '' : names[look.seat]} />
  {:else}
    <p class="body-medium dim empty">{status}</p>
  {/if}
{/snippet}

<!-- Over the hand on the table: the figures of the tile chosen (with the list beside the table its row there is lit
     instead). -->
{#snippet strip()}
  {#if mine && pickedTile && !inlinePanel}<PickedFigures analysis={mine} seat={me} {names} tile={pickedTile} />{/if}
{/snippet}

<div class="page">
  <TitleBar title={title || t('branch_title')} onback={onclose}>
    {#if !dealt}
      <label class="shuffle body-medium">
        <input type="checkbox" bind:checked={reshuffle} />{t('branch_reshuffle')}
      </label>
    {/if}
    {#if !inlinePanel}
      <button class="btn icon text" class:on={panelOpen} aria-label={t('review_panel')} title={t('review_panel')} aria-pressed={panelOpen}
        onclick={() => (panelOpen = !panelOpen)}>
        <Icon name="assessment" />
      </button>
    {/if}
  </TitleBar>
  <div class="body">
    <div class="main">
      {#if listView}
        <div class="list-view" class:halves={ui.wide}>
          <div class="seat-hand">
            {#if seatHand && look}
              <div class="body-medium dim">{seatHand.head}・{look.seat === me ? t('analysis_your_turn') : t('analysis_turn_of', names[look.seat])}</div>
              <MyHand tiles={seatHand.tiles} drawn={seatHand.drawn} w={hw} perRow={8} bind:selected tagH={17}
                marks={look.analysis ? tileMarks(look.analysis, played, unit) : NO_MARKS} canDiscard={(x) => discardable.has(x)}
                ondiscard={(x) => act({ type: 'discard', tile: x })} turn={discardable.size > 0} pick={!listChoices.length || discardable.size > 0} />
              {#if seatHand.claim?.tile}
                <div class="claimed body-medium"><span>{names[seatHand.claim.seat]} {t('act_discard')}</span><Tile tile={seatHand.claim.tile} w={hw} mark /></div>
              {/if}
              {#if listChoices.length}
                <ClaimBar actions={listChoices} w={hw} onact={act} claimed={seatHand.claim?.tile ?? null} marks={choices} />
              {/if}
              {#if seatHand.melds.length || seatHand.flowers.length}
                <div class="melds">
                  {#each seatHand.melds as m, i (i)}<span class="meld">{#each meldTiles(m) as x}<Tile tile={x} w={hw * 0.75} />{/each}</span>{/each}
                  {#each seatHand.flowers as f (f)}<Tile tile={f} w={hw * 0.6} />{/each}
                </div>
              {/if}
            {:else}
              <p class="body-medium dim">{status}</p>
            {/if}
          </div>
          <div class="list-scroll">{@render panel()}</div>
        </div>
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
      <!-- (Not under the result: on a low window it takes the room the result needs.) -->
      {#if !sheet}<div class="controls">
        <div class="info body-medium">
          <span class="label-large">{t('branch_step', steps)}</span>
          {#if mine?.value !== undefined}<span class="now"><span class="dim">{t('review_now')}</span> <b>{pts(mine.value)}</b></span>{/if}
          <span class="dim">{status}</span>
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
    {#if inlinePanel}<div class="inline-panel">{@render panel()}</div>{/if}
  </div>
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .body { flex: 1; min-height: 0; display: flex; }
  .main { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
  .table-wrap { flex: 1; min-height: 0; position: relative; }
  .controls { padding: 6px 12px 10px; display: flex; flex-direction: column; gap: 6px; background: var(--surface-container-low, var(--surface)); }
  .info { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 12px; min-height: 2.6em; }
  .now { font-variant-numeric: tabular-nums; }
  .dim { color: var(--on-surface-variant); }
  .note { color: var(--on-surface-variant); margin-left: auto; }
  .buttons { display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; }
  .btn.icon.on { background: var(--secondary-container); color: var(--on-secondary-container); }
  .shuffle { display: inline-flex; align-items: center; gap: 6px; padding: 0 8px; cursor: pointer; white-space: nowrap; }
  .shuffle input { width: 18px; height: 18px; accent-color: var(--primary); }
  .sheet { position: absolute; inset: 0; z-index: 5; display: flex; align-items: center; justify-content: center; padding: 12px; background: rgba(0, 0, 0, 0.35); }
  .inline-panel { flex: none; width: 420px; overflow: auto; padding: 8px 12px 12px; background: var(--surface-container); }
  .empty { padding: 16px 4px; }
  .list-view { flex: 1; min-height: 0; overflow: auto; display: flex; flex-direction: column; gap: 8px; padding: 8px 12px 16px; }
  .list-view.halves { flex-direction: row; overflow: hidden; gap: 12px; }
  .seat-hand { display: flex; flex-direction: column; gap: 8px; }
  .halves .seat-hand { flex: 0 0 46%; min-width: 0; overflow: auto; }
  .halves .list-scroll { flex: 1; min-width: 0; overflow: auto; }
  .list-view:not(.halves) .list-scroll { max-width: 760px; }
  .seat-hand :global(.bar) { justify-content: flex-start; }
  .claimed { display: flex; align-items: center; gap: 8px; }
  .melds { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: flex-end; }
  .meld { display: inline-flex; }
</style>
