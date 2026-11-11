<!--
  A finished hand's review: the table as the player saw it (or everyone's tiles), step by step through the record's
  actions, with the network's analysis of each of the player's decisions and the result at the end. Only after the
  hand: never while it is played.

  The steps shown are the actions that took effect and the player's own decisions: others' passes are skipped, and so
  are others' claims that another seat's claim came before (a chow when someone else won on the tile); the engine
  says which ({"cmd":"steps"}). The button to the end goes to the last of them (the win, with who dealt in; without a
  win the last discard), then to the table at the end; the result's sheet opens from its own button, never by itself.

  The review (docs/analysis.md): the hand in short (the expected result at the first decision, what the decisions
  lost, the rest as luck), a graph of the expected result to jump from, steps between the decisions worth a look (the
  mistakes of the settings' level, and the best actions that are not the obvious ones), and at a decision what each
  action costs against the best: on the hand's tiles (what each discard costs, its danger as a band along its foot, a
  tap for a tile's line), and in a list of every action one tap away (beside the table on a large window), its rows
  numbers, bars or both (the settings). The list keeps its place from its end over a step when it is scrolled near it
  (the opponents' figures are there, to watch them change). A step can be taken to the analysis screen to try changes.
-->
<script lang="ts">
  import { actionText, fullAction, isTaken } from '../lib/actions';
  import { shared } from '../lib/app.svelte';
  import * as engine from '../lib/engine';
  import { t } from '../lib/i18n.svelte';
  import { nav } from '../lib/nav.svelte';
  import { editor } from '../lib/position-editor.svelte';
  import { records } from '../lib/records.svelte';
  import { costOf, efficiencyText, EQUAL, figureItems, gradeOf, GRADE_NAME, isKey, keysOf, levelWord, notableText, pct, pts, readyLevel,
    reasonOf, reviewOf, summarize, tagCost, tileMarks, tilesShort, type TileMark } from '../lib/review';
  import { flowersOf, meldTiles } from '../lib/seats';
  import { settings } from '../lib/settings.svelte';
  import { parseTiles, sortTiles, tileName, windName, without, type Tile as T } from '../lib/tiles';
  import { recordAction, type Analysis, type Review, type Steps, type Truth, type View } from '../lib/types';
  import { ui } from '../lib/ui.svelte';
  import DecisionPanel from './DecisionPanel.svelte';
  import HandResult from './HandResult.svelte';
  import Icon from './Icon.svelte';
  import MyHand from './MyHand.svelte';
  import Table from './Table.svelte';
  import Tile from './Tile.svelte';
  import TitleBar from './TitleBar.svelte';
  import ValueGraph from './ValueGraph.svelte';

  let { id, onback }: { id: string | null; onback: () => void } = $props();
  /** A wide window: the controls in a column at the right, the table the full height; with the list of a decision's
   *  actions under them when the window is large. */
  const side = $derived(ui.wide);
  const inlinePanel = $derived(ui.wide && ui.w >= 1180 && ui.h >= 600);
  /** A phone: the list in place of the table. */
  let panelOpen = $state(false);

  const rec = $derived(id ? records.get(id) : undefined);
  const n = $derived(rec?.game.actions.length ?? 0);
  let step = $state(0);
  let showAll = $state(false);
  let showResult = $state(false);
  let view = $state<View | null>(null);
  let final = $state<View | null>(null);
  let rev = $state<Review | null>(null);
  let revError = $state<string | null>(null);
  let steps = $state<Steps | null>(null);
  let loaded = '';

  // A new hand starts at its deal; its review comes when the network has gone through it.
  $effect(() => {
    if (rec && loaded !== rec.id) {
      loaded = rec.id;
      const r = rec;
      step = 0;
      showResult = false;
      rev = null;
      revError = null;
      steps = null;
      final = null;
      void engine.view(r.game, r.me, 0, true).then((v) => { if (loaded === r.id) final = v; });
      void engine.steps(r.game).then((s) => { if (loaded === r.id) steps = s; }, () => {});
      reviewOf(r).then(
        (x) => {
          if (loaded !== r.id) return;
          rev = x;
          if (x.first_value !== undefined && records.get(r.id)?.rev?.m !== engine.MODEL_ID) records.setReview(r.id, summarize(x));
        },
        (e) => { if (loaded === r.id) revError = String(e?.message ?? e); },
      );
    }
  });
  const cut = (k: number) => ({ ...rec!.game, actions: rec!.game.actions.slice(0, k) });
  $effect(() => {
    if (!rec) return;
    const k = step, all = showAll;
    void engine.view(cut(k), rec.me, 0, all || k === n).then((v) => { if (step === k && showAll === all) view = v; });
  });

  const names = $derived(rec?.names.map((x, i) => (i === rec.me ? t('you') : x)) ?? []);
  /** A step is shown when its action took effect or is the player's own (until the engine has said which took effect:
   *  when it is not another seat's pass). */
  const isStop = (k: number) => {
    const a = rec!.game.actions[k];
    return a[0] === rec!.me || (steps ? steps.taken[k] : a[1] !== 'pass');
  };
  const isMine = (k: number) => rec!.game.actions[k][0] === rec!.me;
  /** The steps shown, to number them. */
  const stopList = $derived.by(() => {
    const out: number[] = [];
    if (rec) for (let k = 0; k < n; k++) if (isStop(k)) out.push(k);
    return out;
  });
  const stepNumber = $derived(stopList.filter((k) => k <= step).length);
  /** The step the play ended with: the win (the first, when a discard made several winners); without a win the last
   *  action that took effect. */
  const last = $derived(steps && steps.last >= 0 ? steps.last : (stopList[stopList.length - 1] ?? 0));
  function go(k: number) { step = Math.max(0, Math.min(n, k)); showResult = false; }
  function next() { let k = step + 1; while (k < n && !isStop(k)) k++; go(k); }
  function prev() { let k = step - 1; while (k > 0 && !isStop(k)) k--; go(k); }
  function nextMine() { let k = step + 1; while (k < n && !isMine(k)) k++; go(k); }
  function prevMine() { let k = step - 1; while (k > 0 && !isMine(k)) k--; go(k); }
  // A step asked for from outside (the page's tests; a link to a step, one day).
  $effect(() => {
    const k = shared.reviewStep;
    if (k === null || !rec) return;
    shared.reviewStep = null;
    go(k);
  });
  /** To the last step; from there to the table at the end. */
  function toEnd() { go(step < last ? last : n); }
  function openResult() { step = n; showResult = true; }
  /** Whose tile a claim at step k answers: the discard (or added kong) before it. */
  function claimedFrom(k: number): number {
    for (let i = k - 1; i >= 0; i--) {
      const a = rec!.game.actions[i];
      if (a[1] === 'discard' || a[1] === 'kakan') return a[0] as number;
    }
    return -1;
  }
  /** How the hand ended, in a few words. */
  const outcome = $derived.by(() => {
    const r = final?.result;
    if (!r) return '';
    if (r.exhausted) return t('result_exhausted');
    const who = r.winners.map((w) => names[w.seat]).join('、');
    if (r.flower_win) return t('result_flower_win', who);
    if (r.self_draw) return t('result_tsumo', who);
    return t('result_ron', who) + (r.loser >= 0 ? '・' + t('result_ron_from', names[r.loser]) : '');
  });

  // The review: its decisions by step, and the ones to stop at.
  const unit = $derived(rev?.unit);
  const hasNet = $derived(rev?.first_value !== undefined);
  const keys = $derived(rev && hasNet ? keysOf(rev) : []);
  const stops = $derived(keys.filter((k) => isKey(k, settings.level)));
  const points = $derived(rev && hasNet ? rev.decisions.map((d) => ({ step: d.step, value: d.analysis.value ?? 0 })) : []);
  const mistakes = $derived(keys.filter((k) => k.grade >= (settings.level === 'beginner' ? 3 : settings.level === 'mid' ? 2 : 1)).length);
  const nextKeyStep = $derived(stops.find((k) => k.step > step)?.step ?? -1);
  const prevKeyStep = $derived([...stops].reverse().find((k) => k.step < step)?.step ?? -1);
  const mine = $derived(rev?.decisions.find((d) => d.step === step));
  const key = $derived(keys.find((k) => k.step === step));

  const deciding = $derived.by(() => {
    if (!rec || step >= n) return null;
    const { seat, action } = recordAction(rec.game.actions[step]);
    return { seat, action: fullAction(action) };
  });
  /** What the step's line adds to the action: who dealt in to a win; for a claim that was not made, whose came first. */
  const decidingNote = $derived.by(() => {
    if (!rec || !deciding) return '';
    const over = steps?.over[step] ?? -1;
    if (over >= 0) {
      const o = recordAction(rec.game.actions[over]);
      return t('replay_overridden', names[o.seat], t('act_' + o.action.type));
    }
    if (deciding.action.type === 'ron') {
      const from = claimedFrom(step);
      return from >= 0 ? t('result_ron_from', names[from]) : '';
    }
    return '';
  });

  // Another seat's decision (or one of the player's without a choice): analyzed when its list is shown.
  const listShown = $derived(inlinePanel || panelOpen);
  let other = $state<{ key: string; analysis: Analysis } | null>(null);
  const stepKey = $derived(`${rec?.id}/${step}`);
  $effect(() => {
    if (!rec || !deciding || mine || !listShown) return;
    const k = stepKey;
    if (other?.key === k) return;
    engine.analyzeGame(cut(step), deciding.seat).then((a) => { if (stepKey === k) other = { key: k, analysis: a }; }, () => {});
  });
  /** The decision shown: whose it is, its analysis, the action taken (its index) and what the others really held. */
  const focus = $derived.by((): { seat: number; analysis: Analysis; played: number; truth: Truth[] | null } | null => {
    if (!rec || !deciding) return null;
    if (mine) return { seat: rec.me, analysis: mine.analysis, played: mine.played, truth: mine.truth };
    if (other?.key !== stepKey) return null;
    return { seat: deciding.seat, analysis: other.analysis, played: other.analysis.actions.findIndex((f) => isTaken(f.action, deciding.action)), truth: null };
  });

  // On the table: the tiles' marks, the name plates' notes, the options that are not discards, the tile tapped.
  const NO_MARKS = new Map<T, TileMark>();
  const marks = $derived(!hasNet ? null : mine ? tileMarks(mine.analysis, mine.played, unit) : NO_MARKS);
  const notes = $derived.by(() => {
    if (!rec || !mine?.analysis.opponents) return null;
    const out: ({ text: string; level: number } | null)[] = [null, null, null, null];
    for (const o of mine.analysis.opponents) {
      const tr = showAll ? mine.truth.find((x) => x.seat === o.seat) : undefined;
      if (tr) {
        out[o.seat] = tr.missed ? { text: t('opp_missed'), level: 0 }
          : tr.shanten === 0 ? { text: t('opp_ready', tilesShort(tr.waits.map((x) => x.tile))), level: 3 } : { text: t('analysis_shanten', tr.shanten), level: 0 };
      } else if (o.ready[0] >= 0.1) out[o.seat] = { text: t('opp_ready', pct(o.ready[0])), level: readyLevel(o.ready[0]) };
    }
    return out;
  });
  const options = $derived.by(() => {
    if (!mine || mine.analysis.best === undefined) return [];
    const a = mine.analysis;
    return a.actions.map((f, i) => ({ f, i, cost: costOf(a, i) })).filter((x) => x.f.action.type !== 'discard');
  });
  let selected = $state<number | null>(null);
  let picked = $state<T | null>(null);
  $effect(() => { void step; selected = null; picked = null; });
  const pickedIndex = $derived(focus && picked ? focus.analysis.actions.findIndex((f) => f.action.type === 'discard' && f.action.tile === picked) : -1);
  const oppNames = $derived(focus ? [1, 2, 3].map((r) => names[(focus.seat + r) % 4]) : []);

  // The list in place of the table (a phone): the deciding seat's own tiles over it.
  const listView = $derived(panelOpen && !inlinePanel && !!deciding);
  let sv = $state<{ key: string; view: View } | null>(null);
  $effect(() => {
    if (!rec || !deciding || !listView) return;
    const k = stepKey;
    if (sv?.key === k) return;
    engine.view(cut(step), deciding.seat).then((v) => { if (stepKey === k) sv = { key: k, view: v }; }, () => {});
  });
  const seatHand = $derived.by(() => {
    if (!deciding || sv?.key !== stepKey) return null;
    const pos = sv.view.position;
    const all = parseTiles(pos.hand);
    const drawn = sv.view.phase === 'self' && pos.drawn ? pos.drawn : null;
    const p = pos.players[deciding.seat];
    const head = `${t('round_wind', windName(pos.setup.round))}・${t('wall_left', pos.wall_left)}`;
    return { tiles: sortTiles(drawn ? without(all, [drawn]) : all), drawn, melds: p.melds, flowers: flowersOf(p), claim: pos.claim, head };
  });
  const hw = $derived(ui.wide ? Math.min(44, ((ui.w - 208) * 0.46 - 24) / 9.3) : Math.min(44, (Math.min(ui.w, 720) - 28) / 9.3));

  // The list over a step: scrolled near its end (where the opponents' figures are, to watch them change from step to
  // step) it keeps its distance from the end; else it starts from its top.
  const NEAR_END = 360;
  let listEl = $state<HTMLElement | null>(null);
  let listScrollEl = $state<HTMLElement | null>(null);
  let inlineEl = $state<HTMLElement | null>(null);
  const scroller = $derived(inlinePanel ? inlineEl : ui.wide ? listScrollEl : listEl);
  /** The distance kept from the list's end, or null; scrolls right after a change are the page's own, not the hand's. */
  let fromEnd: number | null = null;
  let quietUntil = 0;
  let placed = '';
  function onListScroll(e: Event) {
    if (performance.now() < quietUntil) return;
    const el = e.currentTarget as HTMLElement;
    const d = el.scrollHeight - el.scrollTop - el.clientHeight;
    fromEnd = el.scrollTop > 0 && d <= NEAR_END ? d : null;
  }
  $effect(() => {
    // The step's list, and on a phone the seat's hand over it, are there (both come from the engine).
    const el = scroller, k = stepKey, ready = !deciding || (!!focus && (!listView || !!seatHand));
    void showAll;
    if (!el) { fromEnd = null; return; }
    quietUntil = performance.now() + 300;
    if (!ready) return;  // placed when the step's list is there
    if (fromEnd !== null) el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight - fromEnd);
    else if (placed !== k) el.scrollTop = 0;
    placed = k;
  });

  function toAnalysis() {
    if (!rec || !deciding) return;
    const seat = deciding.seat;
    void engine.view(cut(step), seat).then((v) => {
      editor.load(v.position, rec.game.rules);
      nav.go('ANALYZE');
    });
  }
  function onkey(e: KeyboardEvent) {
    if (e.key === 'ArrowRight') next();
    else if (e.key === 'ArrowLeft') prev();
    else if (e.key === 'ArrowDown') nextMine();
    else if (e.key === 'ArrowUp') prevMine();
    else if (e.key === ']' && nextKeyStep >= 0) go(nextKeyStep);
    else if (e.key === '[' && prevKeyStep >= 0) go(prevKeyStep);
    else if (e.key === 'Home') go(0);
    else if (e.key === 'End') toEnd();
    else return;
    e.preventDefault();
  }
</script>

<svelte:window onkeydown={onkey} />

{#snippet topButtons()}
  {#if rec}
    <button class="btn icon text" aria-label={t('favorite')} aria-pressed={!!rec.fav} onclick={() => records.setFav(rec.id, !rec.fav)}>
      <Icon name={rec.fav ? 'star' : 'starBorder'} />
    </button>
    <button class="btn icon text" class:on={showAll} aria-label={t('replay_show_all')} title={t('replay_show_all')} aria-pressed={showAll}
      onclick={() => (showAll = !showAll)}>
      <Icon name="visibility" />
    </button>
    {#if !inlinePanel}
      <button class="btn icon text" class:on={panelOpen} aria-label={t('review_panel')} title={t('review_panel')} aria-pressed={panelOpen}
        onclick={() => (panelOpen = !panelOpen)}>
        <Icon name="assessment" />
      </button>
    {/if}
  {/if}
{/snippet}

<!-- The hand in short, and the graph. -->
{#snippet summary()}
  <div class="sum">
    {#if rev && hasNet}
      <div class="sum-text body-small">
        <span><span class="dim">{t('review_first')}</span> {pts(rev.first_value ?? 0)}</span>
        <span><span class="dim">{t('review_decisions')}</span> <b class:bad={(rev.loss ?? 0) >= EQUAL}>{pts(-(rev.loss ?? 0))}</b></span>
        <span><span class="dim">{t('review_luck')}</span> {pts(rev.luck ?? 0)}</span>
        <span class="count" class:some={mistakes > 0}>{mistakes ? t('review_mistakes', levelWord(settings.level), mistakes) : t('review_count_none', levelWord(settings.level))}</span>
      </div>
      <ValueGraph {points} keys={stops} {step} {n} height={side ? 44 : 38} onpick={go} />
    {:else if revError}
      <div class="body-small bad">{t('review_failed', revError)}</div>
    {:else}
      <div class="body-small dim">{t('review_loading')}</div>
    {/if}
  </div>
{/snippet}

<!-- The step, the choice made there, and what the review says of it. -->
{#snippet info()}
  <div class="info body-medium">
    <div class="where">
      <span class="label-large">{step >= n ? t('replay_over') : t('replay_step', stepNumber, stopList.length)}</span>
      {#if deciding && rec}
        <span class="decide">{deciding.seat === rec.me ? t('replay_you_chose', actionText(deciding.action)) : t('replay_seat_chose', names[deciding.seat], actionText(deciding.action))}{decidingNote ? `（${decidingNote}）` : ''}</span>
      {:else if step >= n && outcome}
        <span class="decide">{outcome}</span>
      {/if}
      {#if step >= last && n > 0 && !(step >= n && showResult)}
        <button class="btn tonal to-result" onclick={openResult}>{t('replay_result')}</button>
      {/if}
    </div>
    {#if mine && key && mine.analysis.best !== undefined && mine.played >= 0}
      <div class="verdict">
        <span class="grade g{key.grade}" class:fine={key.loss < EQUAL}>{mine.played === mine.analysis.best ? t('grade_best') : key.loss < EQUAL ? t('grade_equal') : t(GRADE_NAME[key.grade])}</span>
        {#if key.loss >= EQUAL}
          <span class="bold">{t('review_loss', pts(key.loss, false))}</span>
          <span>{t('review_best_was', actionText(mine.analysis.actions[mine.analysis.best].action))}</span>
        {/if}
      </div>
      {#if key.loss >= EQUAL && reasonOf(mine.analysis, mine.played)}<div class="reason dim body-small">{reasonOf(mine.analysis, mine.played)}</div>{/if}
      {#if key.notable}
        <div class="notable body-small"><span class="star">★</span>{key.found ? t('note_found') + '：' : ''}{notableText(mine.analysis, key.notable)}</div>
      {/if}
    {/if}
  </div>
{/snippet}

{#snippet buttons()}
  <div class="buttons">
    <button class="btn icon tonal" aria-label={t('replay_start')} disabled={step === 0} onclick={() => go(0)}><Icon name="skipPrev" /></button>
    <button class="btn icon tonal key" aria-label={t('review_prev_key')} title={t('review_prev_key')} disabled={prevKeyStep < 0} onclick={() => go(prevKeyStep)}>
      <Icon name="prev" size={18} /><b>!</b>
    </button>
    <button class="btn icon tonal" aria-label={t('replay_prev_decision')} title={t('replay_prev_decision')} disabled={step === 0} onclick={prevMine}><Icon name="undo" /></button>
    <button class="btn icon tonal" aria-label={t('replay_prev')} disabled={step === 0} onclick={prev}><Icon name="prev" /></button>
    <button class="btn icon filled" aria-label={t('replay_next')} disabled={step >= n} onclick={next}><Icon name="next" /></button>
    <button class="btn icon tonal mirror" aria-label={t('replay_next_decision')} title={t('replay_next_decision')} disabled={step >= n} onclick={nextMine}><Icon name="undo" /></button>
    <button class="btn icon tonal key" aria-label={t('review_next_key')} title={t('review_next_key')} disabled={nextKeyStep < 0} onclick={() => go(nextKeyStep)}>
      <b>!</b><Icon name="next" size={18} />
    </button>
    <button class="btn icon tonal" aria-label={t('replay_last')} title={t('replay_last')} disabled={step >= n} onclick={toEnd}><Icon name="skipNext" /></button>
  </div>
{/snippet}

<!-- A decision's list, with the way to the analysis screen. -->
{#snippet panel()}
  {#if focus}
    <DecisionPanel analysis={focus.analysis} played={focus.played} {unit} mode={settings.listRows} w={inlinePanel ? 34 : Math.max(30, hw * 0.8)}
      names={names} truth={focus.truth} showTruth={showAll} picked={pickedIndex} brief={focus.seat === rec?.me && !!mine} />
    <div class="panel-foot">
      <button class="btn tonal" onclick={toAnalysis}><Icon name="assessment" size={20} />{t('review_to_analysis')}</button>
    </div>
  {:else if deciding}
    <div class="loading"><span class="spinner"></span></div>
  {/if}
{/snippet}

<!-- Over the hand on the table: the options that are not discards, and the figures of the tile tapped. -->
{#snippet strip()}
  {#if mine && options.length > 0}
    <div class="opts">
      {#each options as o (o.i)}
        <span class="opt" class:best={o.i === mine.analysis.best} class:played={o.i === mine.played}>
          {actionText(o.f.action)}
          <b class="g{o.i === mine.analysis.best ? 0 : gradeOf(o.cost, unit)}">{o.i === mine.analysis.best ? t('grade_best') : o.cost < EQUAL ? '≈' : tagCost(o.cost)}</b>
        </span>
      {/each}
    </div>
  {/if}
  {#if mine && pickedIndex >= 0}
    {@const f = mine.analysis.actions[pickedIndex]}
    <div class="detail body-small">
      <b>{t('act_discard')} {tileName(picked ?? '')}</b>
      {#each figureItems(f, costOf(mine.analysis, pickedIndex), oppNames) as [label, value] (label)}<span><span class="lbl">{label}</span> {value}</span>{/each}
      <span class="lbl">{efficiencyText(f)}</span>
    </div>
  {/if}
{/snippet}

<div class="page" class:side>
  {#if !side}
    <TitleBar title={t('replay_title')} {onback}>{@render topButtons()}</TitleBar>
  {/if}
  {#if !rec}
    <p class="body-large dim pad">{t('replay_nothing')}</p>
  {:else}
    {#if listView}
      <div class="list-view" class:halves={ui.wide} bind:this={listEl} onscroll={onListScroll}>
        <div class="seat-hand">
          {#if side}<button class="btn icon text list-back" aria-label={t('back')} onclick={onback}><Icon name="back" /></button>{/if}
          {#if seatHand && deciding}
            <div class="body-medium dim">{seatHand.head}・{t('analysis_turn_of', names[deciding.seat])}</div>
            <MyHand tiles={seatHand.tiles} drawn={seatHand.drawn} w={hw} perRow={8} bind:selected tagH={17}
              marks={focus ? tileMarks(focus.analysis, focus.played, unit) : NO_MARKS} />
            {#if seatHand.claim?.tile}
              <div class="claimed body-medium"><span>{names[seatHand.claim.seat]} {t('act_discard')}</span><Tile tile={seatHand.claim.tile} w={hw} mark /></div>
            {/if}
            {#if seatHand.melds.length || seatHand.flowers.length}
              <div class="melds">
                {#each seatHand.melds as m, i (i)}<span class="meld">{#each meldTiles(m) as x}<Tile tile={x} w={hw * 0.75} />{/each}</span>{/each}
                {#each seatHand.flowers as f (f)}<Tile tile={f} w={hw * 0.6} />{/each}
              </div>
            {/if}
          {/if}
        </div>
        <div class="list-scroll" bind:this={listScrollEl} onscroll={onListScroll}>{@render panel()}</div>
      </div>
    {:else}
      <div class="table-wrap">
        <!-- A wide window has no title bar: the way back lies over the table's corner, which the table keeps clear. -->
        {#if side}<button class="btn icon back-float" aria-label={t('back')} onclick={onback}><Icon name="back" /></button>{/if}
        {#if view}
          <Table {view} me={rec.me} {names} revealed={showAll || step === n ? (view.hands ?? null) : null} interactive={false}
            maxHand={ui.desk ? 64 : 56} {marks} {notes} {strip} bind:selected onpick={(x) => (picked = x)} clearCorner={side ? 52 : 0}>
            {#snippet overlay()}
              {#if step === n && final?.result && showResult}
                <div class="sheet">
                  <HandResult {final} {names} me={rec.me} rev={rec.rev ?? null} w={ui.wide ? Math.min(36, Math.max(24, ui.w / 30)) : Math.min(34, Math.max(24, ui.w / 13))}>
                    {#snippet actions()}
                      <button class="btn text" onclick={() => (showResult = false)}>{t('result_hands')}</button>
                      <button class="btn tonal" onclick={() => go(stops[0]?.step ?? 0)}>{stops.length ? t('review_next_key') : t('replay_start')}</button>
                    {/snippet}
                  </HandResult>
                </div>
              {/if}
            {/snippet}
          </Table>
        {/if}
      </div>
    {/if}
    <div class="controls" class:with-panel={inlinePanel}>
      {#if side}
        <div class="side-top">
          <span class="spacer"></span>
          {@render topButtons()}
        </div>
      {/if}
      {@render summary()}
      {@render info()}
      <div class="nav-row">{@render buttons()}</div>
      {#if inlinePanel}<div class="inline-panel" bind:this={inlineEl} onscroll={onListScroll}>{@render panel()}</div>{/if}
    </div>
  {/if}
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .page.side { flex-direction: row; }
  .pad { padding: 24px; }
  .btn.icon.on { background: var(--secondary-container); color: var(--on-secondary-container); }
  .table-wrap { flex: 1; min-height: 0; min-width: 0; position: relative; }
  .back-float { position: absolute; left: 6px; top: 6px; z-index: 6; width: 42px; min-height: 42px; border-radius: 21px;
    background: rgba(0, 0, 0, 0.3); color: var(--on-felt); }
  .back-float:hover { background: rgba(0, 0, 0, 0.45); }
  .list-back { align-self: flex-start; margin: -4px 0 -4px -6px; }
  .controls { flex: none; display: flex; flex-direction: column; gap: 6px; padding: 6px 10px 8px; background: var(--surface-container); min-height: 0; }
  .side .controls { width: 208px; padding: 4px 8px 6px; overflow: auto; }
  .side .controls.with-panel { width: 420px; padding: 4px 12px 12px; }
  .side-top { display: flex; align-items: center; }
  .spacer { flex: 1; }
  .sum { display: flex; align-items: center; gap: 10px; min-width: 0; }
  .side .sum { flex-direction: column; align-items: stretch; gap: 4px; }
  .sum-text { flex: none; display: grid; grid-template-columns: auto auto; gap: 0 10px; font-variant-numeric: tabular-nums; white-space: nowrap; }
  .with-panel .sum-text { display: flex; flex-wrap: wrap; gap: 2px 14px; }
  .count { font-weight: 700; color: var(--good); }
  .count.some { color: var(--bad); }
  .info { display: flex; flex-direction: column; gap: 2px; min-width: 0; min-height: 40px; justify-content: center; }
  .where { display: flex; flex-wrap: wrap; gap: 0 12px; align-items: baseline; }
  .decide { color: var(--on-surface-variant); }
  .to-result { margin-left: auto; align-self: center; min-height: 36px; padding: 0 16px; }
  .verdict { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 8px; }
  .grade { padding: 0 8px; border-radius: 8px; font-weight: 700; font-size: 0.8125rem; background: var(--found); color: #fff; }
  .grade.g1:not(.fine) { background: var(--danger-1); color: var(--on-danger); }
  .grade.g2 { background: var(--danger-2); color: var(--on-danger); }
  .grade.g3 { background: var(--danger-3); color: #fff; }
  .notable { color: var(--on-surface); font-weight: 500; }
  .star { color: var(--found); margin-right: 4px; }
  .nav-row { display: flex; align-items: center; gap: 8px; }
  .buttons { flex: 1; display: flex; align-items: center; justify-content: space-between; gap: 2px; max-width: 480px; }
  .buttons .btn.icon { width: 42px; min-height: 42px; border-radius: 21px; }
  .buttons .key { gap: 0; font-size: 1.05rem; }
  .side .buttons { display: grid; grid-template-columns: repeat(4, 42px); gap: 6px; justify-content: center; }
  .with-panel .buttons { display: flex; }
  .mirror :global(svg) { transform: scaleX(-1); }
  .inline-panel { flex: 1; min-height: 120px; overflow: auto; margin-top: 4px; padding-right: 2px; }
  .panel-foot { display: flex; justify-content: flex-end; padding: 10px 0 4px; }
  .list-view { flex: 1; min-height: 0; overflow: auto; display: flex; flex-direction: column; gap: 8px; padding: 8px 12px 16px; }
  .list-view.halves { flex-direction: row; overflow: hidden; gap: 12px; }
  .seat-hand { display: flex; flex-direction: column; gap: 8px; }
  .halves .seat-hand { flex: 0 0 46%; min-width: 0; overflow: auto; }
  .halves .list-scroll { flex: 1; min-width: 0; overflow: auto; }
  .list-view:not(.halves) .list-scroll { max-width: 760px; }
  .claimed { display: flex; align-items: center; gap: 8px; }
  .melds { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: flex-end; }
  .meld { display: inline-flex; }
  .sheet { position: absolute; inset: 0; z-index: 5; display: flex; align-items: center; justify-content: center; padding: 12px;
    background: rgba(0, 0, 0, 0.35); }
  .sheet :global(.result) { max-width: min(760px, 100%); }
  .opts { display: flex; flex-wrap: wrap; justify-content: center; gap: 6px; }
  .opt { display: inline-flex; align-items: center; gap: 6px; padding: 3px 12px; border-radius: 14px; background: rgba(0, 0, 0, 0.55); color: #fff;
    font-weight: 500; border: 2px solid transparent; }
  .opt.played { border-color: var(--highlight); }
  .opt.best { background: var(--found); }
  .opt b.g1 { color: var(--danger-1); }
  .opt b.g2 { color: var(--danger-2); }
  .opt b.g3 { color: #ff8f8f; }
  .detail { display: flex; flex-wrap: wrap; justify-content: center; gap: 1px 12px; padding: 5px 12px; border-radius: 12px; background: rgba(0, 0, 0, 0.72);
    color: #fff; max-width: 100%; font-variant-numeric: tabular-nums; }
  .detail .lbl { opacity: 0.72; }
  .loading { display: flex; justify-content: center; padding: 32px; }
  .spinner { width: 36px; height: 36px; border-radius: 50%; border: 4px solid transparent; border-top-color: var(--primary); animation: spin 0.9s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
</style>
