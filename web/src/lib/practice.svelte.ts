// Practice: the player (seat 0) against three bots, for one hand, one round (一圈) or a full game (一將). The page is
// the authority here: it holds the record, lets the bots decide one decision point at a time (so each bot's turn
// shows on its own), and shows the player only the view of seat 0. A port of the app's Practice.kt.
//
// The computer players' pace is the settings': a pause before each of their turns, or `manual`: nothing of theirs
// happens until the player says so (`step`). One step is one thing that shows on the table, as the table settles it: a
// discard (with the draw before it), a call, a declaration, a win. What the three answer to a discard is settled
// together, by the rules' precedence, before any of it shows (a chow that a pung or a win comes before never shows),
// so what the next step will be is worked out ahead and held. Only what shows waits: when all three let a discard
// go, the game goes on by itself (a wait there would say that one of them could have called). The player's own
// call is answered at once, also when another's call comes before it (`notice` says so).
import * as engine from './engine';
import { t } from './i18n.svelte';
import { handRecord, newId, records } from './records.svelte';
import { keepSummary } from './review';
import { calloutsDone } from './callouts';
import { preclaim, robbedEvent, robPause } from './claims';
import { fxDone } from './fx.svelte';
import { PACE, settings, type Length } from './settings.svelte';
import { KEYS, load, save } from './store';
import type { Action, GameEvent, GameRecord, View } from './types';

export const ME = 0;

interface Meta {
  id: string;
  length: Length;
  rules: string;
  bot: string;
  startDealer: number;
  startRound: number;
  /** Hands finished in this game. */
  hands: number;
  totals: number[];
  over: boolean;
  /** Walls from the id (a test's game). */
  seeded?: boolean;
  /** The hand already recorded (its wall's start and length of its record), so a reload does not record it again. */
  recorded?: string;
}

interface Saved { game: GameRecord; meta: Meta }

export type Stage = 'playing' | 'result' | 'over';

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
/** What shows on the table as a player's doing (a draw and a flower are not waited for). */
const SHOWN = new Set<GameEvent['type']>(['discard', 'chi', 'pon', 'kan', 'ankan', 'kakan', 'declare', 'tsumo', 'ron', 'flower_win']);
/** A notice's time on the table, ms. */
const NOTICE_MS = 4500;

class Practice {
  game = $state<GameRecord | null>(null);
  meta = $state<Meta | null>(null);
  /** What the player sees (seat 0's view, every event from the start). */
  view = $state<View | null>(null);
  /** The finished hand, everything shown (the hands and others' concealed kongs), on its result. */
  final = $state<View | null>(null);
  stage = $state<Stage>('playing');
  /** The record of the hand just finished (its review and favorite). */
  lastRecord = $state<string | null>(null);
  busy = $state(false);
  error = $state<string | null>(null);
  /** The manual pace: the computer players' next step is ready and waits for `step`. */
  waiting = $state(false);
  /** A few words on the table for a moment: the player's call did not stand, or was taken before they were asked. */
  notice = $state<string | null>(null);
  /** The event (an index into the view's events) that was a claim taken from the player (lib/claims.ts): its moment
   *  says 搶碰, 搶胡. */
  robbed = $state<number | null>(null);
  private running = false;
  private epoch = 0;
  /** The step held (the game after it, and the player's view of it), for the game of this many actions. */
  private ahead: { from: number; game: GameRecord; view: View; robbed?: number; say?: string } | null = null;
  /** The call the player just made on another's tile, until what came of it is known. */
  private claimed: Action['type'] | null = null;
  private noticeTimer: ReturnType<typeof setTimeout> | undefined;

  get bots(): (string | null)[] {
    const b = this.meta?.bot ?? settings.bot;
    return [null, b, b, b];
  }

  /** The players' names as shown (kept so in the records). */
  names(): string[] { return [t('you'), t('cpu_n', 1), t('cpu_n', 2), t('cpu_n', 3)]; }

  /** The game in play from the last visit, or a new one. */
  async resume() {
    const s = load<Saved | null>(KEYS.practice, null);
    if (s?.game && s.meta && !s.meta.over && s.meta.rules === settings.spec) {
      this.game = s.game;
      this.meta = s.meta;
      this.stage = 'playing';
      await this.refresh();
      if (this.view?.phase === 'over') await this.finish();
      else void this.run();
    } else await this.newGame();
  }

  /** Another game or hand: nothing held from the last. */
  private reset() {
    this.epoch++;
    this.ahead = null;
    this.waiting = false;
    this.claimed = null;
    this.robbed = null;
    this.say(null);
  }

  /** The words once the moment that shows the same is over (they would be under its veil). */
  private sayAfterFx(text: string) {
    const e = this.epoch;
    void fxDone().then(() => { if (e === this.epoch) this.say(text); });
  }

  private say(text: string | null) {
    clearTimeout(this.noticeTimer);
    this.notice = text;
    if (text) this.noticeTimer = setTimeout(() => (this.notice = null), NOTICE_MS);
  }

  private persist() {
    if (this.game && this.meta) save(KEYS.practice, { game: this.game, meta: this.meta } satisfies Saved);
  }

  /** A new game under the settings' rules, length and bot; the first dealer drawn at random. `seed`: the same walls
   *  every time (the screenshot scripts). */
  async newGame(seed?: string) {
    this.reset();
    const rules = settings.spec;
    const startDealer = seed ? 0 : Math.floor(Math.random() * 4);
    this.meta = {
      id: seed ?? newId(), length: settings.length, rules, bot: settings.bot, startDealer, startRound: 0, hands: 0,
      totals: [0, 0, 0, 0], over: false, seeded: !!seed,
    };
    this.final = null;
    this.lastRecord = null;
    this.stage = 'playing';
    try {
      this.game = await engine.newHand(rules, { dealer: startDealer, east: -1, round: 0, streak: 0 }, seed ? `${seed}/0` : undefined);
    } catch (e) {
      this.error = String((e as Error).message ?? e);
      return;
    }
    this.persist();
    await this.refresh();
    void this.run();
  }

  private async refresh() {
    if (!this.game) return;
    this.view = await engine.view(this.game, ME);
  }

  /**
   * The bots decide, a decision point at a time, until the player must decide or the hand is over. Each bot's turn
   * waits the pace of the settings, and the words of a call (lib/callouts.ts), so it can be followed; at the manual
   * pace, what shows waits for `step` (see the top).
   */
  private async run() {
    if (this.running) return;
    this.running = true;
    const epoch = this.epoch;
    try {
      while (this.game && epoch === this.epoch) {
        const p = await engine.pending(this.game);
        if (p.phase === 'over') {
          await this.finish();
          break;
        }
        const seed = `${this.meta?.id}/${this.meta?.hands}/${this.game.actions.length}`;
        if (p.pending.includes(ME)) {
          this.claimed = null;
          // Another's tile the player could claim: the computer players decide on it first (lib/claims.ts), the
          // player's buttons held back meanwhile.
          this.busy = true;
          const shown = performance.now();
          const pc = await preclaim(this.game, ME, this.bots, seed);
          if (epoch !== this.epoch || !this.game || !pc) break;
          if (!pc.robbed) {
            this.game = pc.game;
            this.persist();
            await this.refresh();
            break;
          }
          // A claim came before all of the player's: made at once, as the computer players' step.
          const from = this.game.actions.length;
          const before = this.view?.event_count ?? 0;
          const view = await engine.view(pc.game, ME);
          if (epoch !== this.epoch || !this.game || this.game.actions.length !== from) break;
          const r = pc.robbed;
          const robbed = robbedEvent(view.events, before, r.seat);
          const say = t('claim_robbed', this.names()[r.seat], t('act_' + r.type), r.options.map((o) => t('act_' + o)).join('、'));
          if (settings.speed === 'manual') {
            this.ahead = { from, game: pc.game, view, robbed, say };
            this.waiting = true;
            break;
          }
          this.busy = true;
          // The discard stays a while first (lib/claims.ts robPause, counted from when it showed), its words done.
          await calloutsDone();
          await sleep(Math.max(0, robPause() - (performance.now() - shown)));
          if (epoch !== this.epoch || !this.game || this.game.actions.length !== from) break;
          this.robbed = robbed;
          this.game = pc.game;
          this.view = view;
          this.sayAfterFx(say);
          this.persist();
          continue;
        }
        if (settings.speed === 'manual') {
          const from = this.game.actions.length;
          const before = this.view?.event_count ?? 0;
          const game = await engine.advance(this.game, this.bots, seed, 1);
          const view = await engine.view(game, ME);
          if (epoch !== this.epoch || !this.game || this.game.actions.length !== from) break;
          const fresh = view.events.slice(Math.max(0, view.events.length - (view.event_count - before))).filter((e) => SHOWN.has(e.type));
          const mine = fresh.some((e) => e.seat === ME);
          // Another's call came before the player's own: said at once, as the answer to the player's tap.
          const over = this.claimed && !mine ? fresh.find((e) => e.seat !== ME && e.type !== 'discard') : undefined;
          if (fresh.length && !mine && !over) {
            this.ahead = { from, game, view };
            this.waiting = true;
            break;
          }
          if (over) this.sayAfterFx(t('claim_overridden', t('act_' + this.claimed), this.names()[over.seat], t('act_' + over.type)));
          this.claimed = null;
          this.game = game;
          this.view = view;
          this.persist();
          continue;
        }
        this.busy = true;
        // A bot's own turn takes the pace; deciding on a discard (nobody else may) takes a short beat.
        const pace = PACE[settings.speed];
        await sleep(p.phase === 'self' ? pace : Math.min(250, pace / 2));
        // A call's words (碰, 槓, 天聽) stay their time before the game goes on; a tap anywhere lets them go.
        await calloutsDone();
        if (epoch !== this.epoch || !this.game) break;
        const before = this.view?.event_count ?? 0;
        this.game = await engine.advance(this.game, this.bots, seed, 1);
        this.persist();
        await this.refresh();
        if (this.claimed && this.view) {
          const v = this.view;
          const fresh = v.events.slice(Math.max(0, v.events.length - (v.event_count - before))).filter((e) => SHOWN.has(e.type));
          const over = fresh.some((e) => e.seat === ME) ? undefined : fresh.find((e) => e.seat !== ME && e.type !== 'discard');
          if (over) this.sayAfterFx(t('claim_overridden', t('act_' + this.claimed), this.names()[over.seat], t('act_' + over.type)));
          this.claimed = null;
        }
      }
    } catch (e) {
      this.error = String((e as Error).message ?? e);
    } finally {
      this.busy = false;
      this.running = false;
    }
  }

  /** The manual pace: the computer players' next step, now. */
  step() {
    const a = this.ahead;
    if (!this.waiting || !a || !this.game || this.running || a.from !== this.game.actions.length) return;
    this.ahead = null;
    this.waiting = false;
    if (a.robbed !== undefined) this.robbed = a.robbed;
    this.game = a.game;
    this.view = a.view;
    if (a.say) this.sayAfterFx(a.say);
    this.persist();
    void this.run();
  }

  /** The pace changed: a step held for the manual pace goes on by itself at another. */
  paceChanged() {
    if (settings.speed !== 'manual' && this.waiting) {
      this.ahead = null;
      this.waiting = false;
      void this.run();
    }
  }

  /** The player's decision (one of view.legal). */
  async act(action: Action) {
    if (!this.game || !this.view?.my_turn || this.running) return;
    this.say(null);
    // A call on another's tile may yet give way to a call that comes before it.
    this.claimed = this.view.phase !== 'self' && action.type !== 'pass' ? action.type : null;
    const before = this.view.event_count;
    try {
      this.game = await engine.act(this.game, ME, action);
      // The others decided first (lib/claims.ts): the claim is settled now. Another's that came first is the moment's
      // 搶 (the player's buttons had a claim that would have stood, but not the one chosen).
      const v = await engine.view(this.game, ME);
      if (this.claimed) {
        const fresh = v.events.slice(before).filter((e) => SHOWN.has(e.type));
        const over = fresh.some((e) => e.seat === ME) ? undefined : fresh.find((e) => e.seat !== ME && e.type !== 'discard');
        if (over) {
          this.robbed = v.events.indexOf(over);
          this.sayAfterFx(t('claim_overridden', t('act_' + this.claimed), this.names()[over.seat], t('act_' + over.type)));
          this.claimed = null;
        }
      }
      this.view = v;
    } catch (e) {
      this.error = String((e as Error).message ?? e);
      return;
    }
    this.persist();
    void this.run();
  }

  private async finish() {
    if (!this.game || !this.meta) return;
    this.final = await engine.view(this.game, ME, 0, true);
    const result = this.final.result;
    if (!result) return;
    if (this.stage !== 'playing') return;
    const meta = this.meta;
    const key = `${(this.game.wall ?? '').slice(0, 24)}/${this.game.actions.length}`;
    if (meta.recorded === key) {
      // After a reload: the hand's record (this game's, by its number) for the review and favorite on the result, and
      // the summary if the reload cut it short.
      const rec = records.ofGroup(meta.id).find((r) => r.index === meta.hands);
      this.lastRecord = rec ? rec.id : null;
      if (rec) void keepSummary(rec);
      this.stage = 'result';
      return;
    }
    meta.recorded = key;
    meta.hands++;
    meta.totals = meta.totals.map((t, i) => t + result.points[i]);
    const rec = handRecord('practice', this.game, result, ME, this.names(), this.bots, meta.id, meta.hands);
    records.add(rec);
    this.lastRecord = rec.id;
    void keepSummary(rec);
    // Is the game over? One hand: yes. A round: when the next hand would be in the next round wind. A full game:
    // when the engine says so (after the north round).
    if (meta.length === 'hand') meta.over = true;
    this.meta = { ...meta };
    this.stage = 'result';
    this.persist();
  }

  /** The next hand of the game, or its end. */
  async nextHand() {
    if (!this.game || !this.meta || this.stage !== 'result') return;
    if (this.meta.over) {
      this.stage = 'over';
      this.persist();
      return;
    }
    const n = await engine.nextHand(this.game, this.meta.startDealer, this.meta.seeded ? `${this.meta.id}/${this.meta.hands}` : undefined);
    const roundOver = n.game.setup.round !== this.game.setup.round;
    if (n.gameOver || (this.meta.length === 'round' && roundOver)) {
      this.meta = { ...this.meta, over: true };
      this.stage = 'over';
      this.persist();
      return;
    }
    this.reset();
    this.game = n.game;
    this.final = null;
    this.stage = 'playing';
    this.persist();
    await this.refresh();
    void this.run();
  }

  /** The settings changed (rules, length, bot): a new game when they differ from this game's. */
  async settingsChanged() {
    if (!this.meta || this.meta.rules !== settings.spec || this.meta.length !== settings.length || this.meta.bot !== settings.bot)
      await this.newGame();
  }
}

export const practice = new Practice();
