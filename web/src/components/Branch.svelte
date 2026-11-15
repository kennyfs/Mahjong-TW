<!--
  A branch of a reviewed hand (the review's 分支), or a position of the analysis played on (與電腦繼續): from a decision
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

  手動繼續 (`manual`, from the analysis): no computer players; the player enters what happens at a real table, a step
  at a time, at the newest step or at one gone back to (what came after is then dropped): their own draws, the
  others' discards (摸切 or not), flowers and calls, and on their own decisions the analysis is shown as above. Under
  the table: on another's discard who called it (the seats that could, then that seat's calls, their tiles large) or
  won, and the palette for the next tile (the player's draw on a tinted ground); a phone's tap marks a tile, a second
  enters it (a computer's click enters it). The hand shows only when the player discards (its room is the table's
  otherwise); the name plate framed is the seat whose move is entered. The screens' bar is put away. The engine fits
  the tiles the player cannot see to what is entered (lib/manual.ts). Another's win is not entered: the hand just
  ends there.

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
  import { chows, enter, passes, unseen, withoutStandIn, cut, type Entry } from '../lib/manual';
  import { FLOWERS, KINDS, KINDS_SHOWN, tileName } from '../lib/tiles';
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
  import Tile from './Tile.svelte';
  import TitleBar from './TitleBar.svelte';

  let { game: source, me, names, onclose, unit = undefined, title = '', backLabel = '', manual = false }: {
    game: GameRecord; me: number; names: string[]; onclose: () => void;
    /** 手動繼續: every seat's moves entered by the player (the record's base has `manual`). */
    manual?: boolean;
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
  const root = $derived<GameRecord>(source.base ? { ...source, base: { ...source.base, seed, ...(manual ? { manual: { draws: [] } } : {}) } }
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
    const decides = v.my_turn && !v.draw_needed && (!manual || v.legal.some((a) => a.type !== 'pass'));
    // (手動繼續: every seat answers each discard, the player's answer among the others'.)
    const d = manual && decides ? { seat: me, action: myAnswer(g, k) } : k < g.actions.length ? recordAction(g.actions[k]) : decides ? { seat: me, action: null } : null;
    if (!d) return null;
    const cut = at(g, k);
    const [analysis, seatView] = await Promise.all([
      d.seat === me || listShown ? engine.analyzeGame(cut, d.seat).catch(() => null) : Promise.resolve(null),
      !listView ? Promise.resolve(null) : d.seat === me ? Promise.resolve(v) : engine.view(cut, d.seat).catch(() => null),
    ]);
    return { seat: d.seat, action: d.action ? fullAction(d.action) : null, analysis, seatView };
  }
  /** 手動繼續: what the player did from step k on, before anyone's call (null: nothing entered yet). */
  function myAnswer(g: GameRecord, k: number): Action | null {
    for (let j = k; j < g.actions.length; j++) {
      const r = recordAction(g.actions[j]);
      if (r.seat === me) return r.action;
      if (r.action.type !== 'pass') break;
    }
    return null;
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
    keptNotes = null;
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
      const claimFirst = !manual && head && v.my_turn && v.phase !== 'self' && v.phase !== 'over';
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
      if (!manual && head && v.phase !== 'over' && (!v.my_turn || v.phase !== 'self')) void run();
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
    if (manual) return actManual(a);
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

  /** 手動繼續: the player's own decision; on a tile of another's the others pass with it (a call of theirs is entered
   *  in its place, under the controls). */
  async function actManual(a: Action) {
    if (!game || !view) return;
    const answer = view.phase !== 'self';
    await entered(async (g) => {
      let out = await engine.act(g, me, a);
      if (answer) out = await enter(out, { actions: await passes(out) });
      return out;
    });
  }
  /** 手動繼續: an entry made at the step shown (what came after it dropped); a bad one says why and changes nothing. */
  let entryError = $state<string | null>(null);
  let busy = $state(false);
  async function entered(make: (g: GameRecord) => Promise<GameRecord>) {
    if (!game || busy) return;
    busy = true;
    epoch++;
    try {
      const g = await make(cut(game, cursor));
      // (An entry of draws alone is checked by the table it makes.)
      await engine.view(g, me);
      game = g;
      cursor = g.actions.length;
      entryError = null;
      ended = false;
      flowerMode = false;
      tsumogiri = false;
      claimSeat = null;
      showResult = true;
      await show();
    } catch (x) {
      entryError = String((x as Error).message ?? x);
    } finally {
      busy = false;
    }
  }
  const make = (e: Entry | ((g: GameRecord) => Promise<Entry>)) => entered(async (g) => enter(g, typeof e === 'function' ? await e(g) : e));
  /** Another seat's win (not entered: the hand ends there). */
  let ended = $state(false);
  /** The next tile entered is a flower (補花); the next discard entered was the tile just drawn (摸切). */
  let flowerMode = $state(false);
  let tsumogiri = $state(false);

  /** What is to be entered at the newest step. */
  type Ask = { kind: 'draw' } | { kind: 'turn'; seat: number; afterCall: boolean } | { kind: 'claim'; from: number; tile: string; next: number } | { kind: 'mine' } | null;
  const ask = $derived.by<Ask>(() => {
    if (!manual || !view || ended || over) return null;
    if (view.phase === 'self') {
      if (view.current === me) return view.draw_needed ? { kind: 'draw' } : { kind: 'mine' };
      const last = [...view.events].reverse().find((e) => e.seat === view!.current && e.type !== 'flower');
      return { kind: 'turn', seat: view.current, afterCall: !!last && (last.type === 'chi' || last.type === 'pon') };
    }
    if (view.phase === 'react') {
      const d = [...view.events].reverse().find((e) => e.type === 'discard');
      if (!d?.tile) return null;
      return { kind: 'claim', from: d.seat, tile: d.tile, next: (d.seat + 1) % 4 };
    }
    return view.my_turn ? { kind: 'mine' } : null;
  });
  // (The view's rules are the full spec: "flowers=0,..." for none.)
  const hasFlowers = $derived(!!view && !/(^|,)(none|flowers=0)(,|$)/.test(view.rules));
  const left = $derived(view && manual ? unseen(view, hasFlowers) : new Map<string, number>());
  /** Whose tile the palette enters: the player's draw, or another's discard (or flower). */
  const paletteSeat = $derived(ask?.kind === 'draw' ? me : ask?.kind === 'turn' ? ask.seat : ask?.kind === 'claim' ? ask.next : -1);
  /** The palette's rows: a suit a row, the honors (東南西北中發白), the flowers on a row of their own. */
  const ROWS = [0, 9, 18, 27].map((i) => KINDS_SHOWN.slice(i, i + 9));
  const paletteRows = $derived(flowerMode ? [FLOWERS] : paletteSeat === me && hasFlowers ? [...ROWS, FLOWERS] : ROWS);
  /** The palette's tile tapped once (a phone: a second tap enters it; a computer's click enters it at once). */
  let pending = $state<string | null>(null);
  /** The seat whose call on the tile on the table is being entered (its calls in the palette's place). */
  let claimSeat = $state<number | null>(null);
  $effect(() => { void ask; void flowerMode; pending = null; });
  $effect(() => { void cursor; void game; claimSeat = null; });
  function tapTile(tile: string) {
    if ((left.get(tile) ?? 0) <= 0 || busy) return;
    if (ui.desk || pending === tile) {
      pending = null;
      void tapPalette(tile);
    } else pending = tile;
  }
  function tapPalette(tile: string) {
    if (!ask || (left.get(tile) ?? 0) <= 0) return;
    const flower = tile[1] === 'f';
    if (ask.kind === 'draw') return make({ draws: [{ seat: me, tile }], actions: [] });
    if (ask.kind === 'turn') {
      if (flower) return make({ draws: [{ seat: ask.seat, tile }], actions: [] });
      return make({ draws: tsumogiri && !ask.afterCall ? [{ seat: ask.seat, tile }] : [], actions: [[ask.seat, { type: 'discard', tile }]] });
    }
    if (ask.kind === 'claim') {
      const n = ask.next;
      return make(async (g) => {
        const ps = await passes(g);
        if (n === me || flower) return { draws: [{ seat: n, tile, after: ps.length - 1 }], actions: ps };
        return { draws: tsumogiri ? [{ seat: n, tile, after: ps.length - 1 }] : [], actions: [...ps, [n, { type: 'discard', tile }]] };
      });
    }
  }
  /** Another seat's call on the tile on the table. */
  function claim(seat: number, a: Action) {
    return make(async (g) => ({ actions: [...(await passes(g, [seat])), [seat, a]] }));
  }
  /** The calls another seat could make on the tile on the table, as far as the tiles not seen allow. */
  const callsOf = (seat: number, tile: string): { label: string; action: Action; tiles: string[] }[] => {
    const n = left.get(tile) ?? 0;
    const out: { label: string; action: Action; tiles: string[] }[] = [];
    // (A hand of a single tile cannot call: it would have none to discard.)
    if (!view || 16 - 3 * view.position.players[seat].melds.length < 4) return out;
    if (ask?.kind === 'claim' && seat === ask.next)
      for (const c of chows(tile)) if (c.need.every((x) => (left.get(x) ?? 0) > 0)) out.push({ label: t('act_chi'), action: { type: 'chi', tile: c.low }, tiles: [...c.need.slice(0, 1), tile, ...c.need.slice(1)].sort() });
    if (n >= 2) out.push({ label: t('act_pon'), action: { type: 'pon' }, tiles: [tile, tile, tile] });
    if (n >= 3) out.push({ label: t('act_kan'), action: { type: 'kan' }, tiles: [tile, tile, tile, tile] });
    return out;
  };
  /** Another's concealed kong: of a kind the player has seen none of (which, the player cannot see). */
  function hiddenKong(seat: number) {
    const free = KINDS.filter((k) => (left.get(k) ?? 0) >= 4);
    if (!free.length) return;
    const tile = free[Math.floor(Math.random() * free.length)];
    return make({ actions: [[seat, { type: 'ankan', tile }]] });
  }
  const pongsOf = (seat: number) => view?.position.players[seat].melds.filter((m) => m.type === 'pon' && m.tile && (left.get(m.tile) ?? 0) > 0).map((m) => m.tile!) ?? [];
  /** The table the player sees: their draw left out while it is a stand-in. */
  const shown = $derived(view && manual ? withoutStandIn(view, me) : view);
  const manualInteractive = $derived(!!shown?.my_turn && !busy && (shown.phase === 'self' || shown.legal.some((a) => a.type !== 'pass')));
  /** 手動繼續: the hand only when the player discards (its room the table's), and the name plate framed of the seat
   *  whose move is being entered. */
  const handShown = $derived(!manual || over || (!!shown?.my_turn && shown.phase === 'self'));
  const entering = $derived(!manual || !ask ? null : ask.kind === 'draw' || ask.kind === 'mine' ? me : ask.kind === 'turn' ? ask.seat : (claimSeat ?? ask.next));
  // 手動繼續: the whole window (no bar of the screens under it).
  $effect(() => {
    if (!manual) return;
    ui.fullTable = true;
    return () => { ui.fullTable = false; };
  });
  /** The seats that could call the tile on the table, as far as the tiles not seen allow. */
  const callers = $derived(ask?.kind === 'claim' ? [1, 2, 3].map((r) => (ask.from + r) % 4).filter((o) => o !== me && callsOf(o, ask.tile).length > 0) : []);

  /** A step: the actions that show (others' passes do not; in 手動繼續 no pass). */
  const isStop = (k: number) => !!game && (manual ? game.actions[k][1] !== 'pass' : game.actions[k][0] === me || game.actions[k][1] !== 'pass');
  function go(k: number) {
    if (!game) return;
    epoch++;
    cursor = Math.max(start, Math.min(game.actions.length, k));
    showResult = true;
    ended = false;
    entryError = null;
    selected = null;
    void show();
  }
  function prev() { let k = cursor - 1; while (k > start && !isStop(k - 1)) k--; go(k); }
  function next() { if (!game) return; let k = cursor + 1; while (k < game.actions.length && !isStop(k - 1)) k++; go(k); }
  /** The player's own decisions: the step at which they decide (the newest step when none is after). */
  const isMine = (k: number) => !!game && game.actions[k][0] === me && (!manual || game.actions[k][1] !== 'pass');
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
  const status = $derived(error ? error : entryError ? t('manual_error', entryError) : ended ? t('manual_ended') : over ? t('branch_over')
    : manual ? ''
    : view?.my_turn ? t(view.legal.some((a) => a.type === 'discard') ? 'branch_your_turn' : 'branch_your_call') : atHead ? t('branch_others') : t('branch_back_note'));

  // What the analysis shows: on the table the player's own decision; in the list any seat's.
  const NO_MARKS = new Map<T, TileMark>();
  /** The index of the action made at a step gone back to, in its analysis; -1 at the decision to make now. */
  const played = $derived(look?.analysis && look.action ? look.analysis.actions.findIndex((f) => isTaken(f.action, look!.action!)) : -1);
  /** The player's own decision at this step, with the network's figures. */
  const mine = $derived(look && look.seat === me && look.analysis?.best !== undefined ? look.analysis : null);
  const marks = $derived(mine ? tileMarks(mine, played, unit) : NO_MARKS);
  const choices = $derived(mine ? choiceMarks(mine, played, unit) : null);
  // The opponents' notes stay as the player's last decision left them while the others play on from it (at the newest
  // step), till the next decision's; a step gone back to shows its own, or none.
  let keptNotes = $state.raw<ReturnType<typeof opponentNotes>>(null);
  $effect(() => { if (mine && atHead) keptNotes = opponentNotes(mine); });
  const notes = $derived(mine ? opponentNotes(mine) : atHead && !over ? keptNotes : null);

  // The list in place of the table: the deciding seat's own tiles over it; the player's own while the others play.
  // 手動繼續's palette: nine a row, the width of the column at the right or of the controls (2px apart).
  // (The column: 420px by the list on a large window; else 40% of the window, 300 to 420px.)
  const colW = $derived(inlinePanel ? 420 : Math.max(300, Math.min(420, ui.w * 0.4)));
  const pw = $derived(Math.min(side ? 48 : 60, ((side ? colW : Math.min(ui.w, 720)) - 24 - 16) / 9));
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
      <input type="checkbox" checked={reshuffle} onclick={askShuffle} /><span data-full={t('branch_reshuffle')} data-short={t('branch_reshuffle_short')}></span>
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
      <span class="label-large">{t(manual ? 'manual_step' : 'branch_step', steps)}</span>
      {#if mine?.value !== undefined}<span class="now"><span class="dim">{t('review_now')}</span> <b>{pts(mine.value)}</b></span>{/if}
      <span class="note body-small">{t(manual ? 'manual_note' : 'branch_note')}</span>
    </div>
    {#if !manual || status}<div class="status dim">{status}</div>{/if}
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
      {#if !manual}<button class="btn tonal wide-btn" onclick={newBranch}>{t('branch_new')}</button>{/if}
    </div>
  {:else}
    <div class="buttons">
      {#each ['start', 'prevMine', 'prev', 'next', 'nextMine', 'newest'] as k (k)}{@render nb(k)}{/each}
      {#if !manual}<button class="btn tonal" onclick={newBranch}>{t('branch_new')}</button>{/if}
    </div>
  {/if}
{/snippet}

<!-- 手動繼續: what to enter at the step shown (going back and entering otherwise drops what came after): on another's
     discard first who called it (the seats that could, and a win), then the palette for the next tile (the player's
     draw, or the next seat's discard), the player's draw on a tinted ground. A seat chosen: its calls in the palette's
     place, their tiles as large as the palette's. -->
{#snippet entry()}
  {#if ask && ask.kind !== 'mine'}
    <div class="entry" class:busy>
      {#if ask.kind === 'claim' && claimSeat !== null}
        {@const o = claimSeat}
        <div class="ask-row">
          <span class="ask title-small">{t('manual_call_of', names[o], tileName(ask.tile))}</span>
          <button class="btn text small" onclick={() => (claimSeat = null)}>{t('cancel')}</button>
        </div>
        <div class="calls">
          {#each callsOf(o, ask.tile) as c (c.label + c.tiles.join(''))}
            <button class="btn tonal big-call" disabled={busy} onclick={() => claim(o, c.action)}>
              <span class="title-medium">{c.label}</span>
              <span class="call-tiles">{#each c.tiles as x, i (i)}<Tile tile={x} w={pw} />{/each}</span>
            </button>
          {/each}
        </div>
      {:else}
        {#if ask.kind === 'claim'}
          <div class="call-row">
            <span class="ask title-small claim-q" title={t('manual_claim_of', names[ask.from], tileName(ask.tile))}><Tile tile={ask.tile} w={18} />{t('manual_claim_q')}</span>
            {#each callers as o (o)}
              <button class="btn tonal seat-btn" disabled={busy} onclick={() => (claimSeat = o)}>{names[o]}</button>
            {/each}
            <button class="btn tonal seat-btn" disabled={busy} onclick={() => (ended = true)}>{t('manual_win')}</button>
          </div>
        {/if}
        <div class="pick" class:draw={paletteSeat === me}>
          <div class="ask-row">
            <span class="ask title-small">
              {#if flowerMode}{t('manual_flower_of', paletteSeat === me ? t('you') : names[paletteSeat])}
              {:else if paletteSeat === me}{t('manual_your_draw')}
              {:else}{t('manual_discard_of', names[paletteSeat])}{/if}
            </span>
            {#if paletteSeat !== me}
              {#if !(ask.kind === 'turn' && ask.afterCall)}
                <label class="check body-medium" title={t('manual_tsumogiri_hint')}><input type="checkbox" bind:checked={tsumogiri} disabled={flowerMode} />{t('manual_tsumogiri')}</label>
              {/if}
              {#if hasFlowers}
                <button class="btn text small" class:on={flowerMode} aria-pressed={flowerMode} onclick={() => (flowerMode = !flowerMode)}>{t('manual_flower')}</button>
              {/if}
              {#if ask.kind === 'turn'}
                <button class="btn text small" onclick={() => hiddenKong(ask.seat)}>{t('act_ankan')}</button>
                {#each pongsOf(ask.seat) as x (x)}
                  <button class="btn text small" onclick={() => make({ actions: [[ask.seat, { type: 'kakan', tile: x }]] })}>{t('act_kakan')} <Tile tile={x} w={15} /></button>
                {/each}
                <button class="btn text small" onclick={() => (ended = true)}>{t('manual_win')}</button>
              {/if}
            {/if}
          </div>
          <div class="palette" style="--pw: {pw}px">
            {#each paletteRows as row, r (r)}
              {#each row as x, i (x)}
                {@const n = left.get(x) ?? 0}
                <button class="tile-btn" class:picked={pending === x} style:grid-column-start={i === 0 ? 1 : null} aria-label={tileName(x)}
                  disabled={n <= 0 || busy} onclick={() => tapTile(x)}>
                  <Tile tile={x} w={pw} dim={n <= 0} />
                </button>
              {/each}
            {/each}
          </div>
        </div>
      {/if}
    </div>
  {/if}
{/snippet}

<!-- 手動繼續 on an upright window: the step, the expected result and the way between steps in one line. -->
{#snippet manualBar()}
  <div class="mbar">
    <span class="label-large">{t('manual_step', steps)}</span>
    {#if mine?.value !== undefined}<b class="now" title={t('review_now')}>{pts(mine.value)}</b>{/if}
    <span class="grow"></span>
    {#each ['prevMine', 'prev', 'next', 'nextMine', 'newest'] as k (k)}{@render nb(k)}{/each}
  </div>
  {#if status}<div class="mstatus body-medium dim">{status}</div>{/if}
{/snippet}

<div class="page" class:side class:manual>
  {#if !side}
    <TitleBar title={title || t('branch_title')} onback={onclose}>{@render shuffle()}{@render listToggle()}</TitleBar>
  {/if}
  <div class="main">
    {#if listView}
      <DecisionView view={look?.seatView ?? view} heading={listHeading} analysis={look?.analysis ?? null} {played} {unit} {names}
        who={look && look.seat !== me ? names[look.seat] : ''} {canPlay} onact={act} {choices} bind:selected wide={ui.wide} {hw} />
    {:else}
      <div class="table-wrap">
        {#if view && shown}
          <Table view={shown} {me} {names} revealed={view.phase === 'over' ? (final?.hands ?? null) : null} interactive={manual ? manualInteractive : !!view.my_turn && !deciding} {robbed}
            onact={act} oneClick={ui.desk} maxHand={ui.desk ? 64 : 56} upright={manual && !side} hideHand={!handShown} turnOf={entering} {marks} {notes} {strip} bind:selected onpick={(x) => (picked = x)} choiceMarks={choices} tagRoom>
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
      <div class="controls">{#if manual}{@render manualBar()}{@render entry()}{:else}{@render info()}{@render buttons()}{/if}</div>
    {/if}
  </div>
  {#if side}
    <!-- No title bar beside the table: the way back at the column's top-left, clear of the table. -->
    <div class="column" class:with-panel={inlinePanel} class:manual-col={manual && !inlinePanel} style:--col={manual ? `${colW}px` : null}>
      <div class="side-top">
        <button class="btn icon text" aria-label={t('back')} title={t('back')} onclick={onclose}><Icon name="back" /></button>
        <span class="title-medium side-title">{title || t('branch_title')}</span>
        {@render listToggle()}
      </div>
      {@render shuffle()}
      {#if inlinePanel}
        {@render info()}
        {@render entry()}
        {@render buttons()}
        <div class="inline-panel" bind:this={inlineEl}>{@render panel()}</div>
      {:else}
        {@render info()}
        {@render entry()}
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
  .column.manual-col { width: var(--col); padding: 4px 12px 12px; }
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
  .entry { display: flex; flex-direction: column; gap: 6px; transition: opacity 0.1s; }
  .entry.busy { opacity: 0.6; }
  .ask { color: var(--primary); }
  .call-row { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 6px; }
  .seat-btn { min-height: 36px; padding: 0 10px; }
  .claim-q { display: inline-flex; align-items: center; gap: 4px; }
  .calls { display: flex; flex-wrap: wrap; gap: 8px; }
  .big-call { align-items: center; gap: 8px; padding: 6px 10px; height: auto; border-radius: 14px; }
  .call-tiles { display: inline-flex; }
  .ask-row { display: flex; flex-wrap: wrap; align-items: center; gap: 0 4px; }
  .ask-row .ask { margin-right: 4px; }
  .ask-row .small { min-height: 34px; padding: 0 8px; }
  .ask-row .on { background: var(--secondary-container); color: var(--on-secondary-container); }
  .check { display: inline-flex; align-items: center; gap: 4px; padding: 0 4px; cursor: pointer; min-height: 34px; }
  .check input { width: 18px; height: 18px; accent-color: var(--primary); }
  /* The player's own draw on a tinted ground; another's discard on the plain one. */
  .pick { display: flex; flex-direction: column; gap: 4px; border-radius: 14px; }
  .pick.draw { background: var(--secondary-container); padding: 4px 6px 8px; margin: 0 -6px; }
  .pick.draw .ask { color: var(--on-secondary-container); }
  .palette { display: grid; grid-template-columns: repeat(9, var(--pw)); gap: 2px; justify-content: center; }
  .tile-btn { padding: 0; border: none; background: none; cursor: pointer; border-radius: 6px; position: relative; -webkit-tap-highlight-color: transparent; }
  .tile-btn:disabled { cursor: default; }
  /* Tapped once: framed and tinted (a second tap enters it). */
  .tile-btn.picked { outline: 3px solid var(--highlight); outline-offset: 0; background: var(--highlight); }
  .tile-btn.picked :global(svg) { opacity: 0.85; }
  /* 手動繼續 on an upright window: the table takes what the controls leave (they scroll when they need more). */
  .page.manual:not(.side) .table-wrap { min-height: 42%; }
  .page.manual:not(.side) .controls { flex: 0 1 auto; min-height: 0; overflow: auto; padding-top: 4px; }
  .mbar { display: flex; align-items: center; gap: 4px; }
  .mbar .grow { flex: 1; }
  .mbar .now { font-variant-numeric: tabular-nums; margin-left: 6px; }
  .mbar .btn.icon { width: 36px; min-height: 36px; border-radius: 18px; }
  .mstatus { line-height: 1.3; }
</style>
