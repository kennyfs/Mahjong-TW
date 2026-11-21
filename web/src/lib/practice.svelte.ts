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
import { calloutsSkipped, dealArm, dealReady, dealReset } from './callouts';
import { later, now } from './clock';
import { BotPace, forcedDiscardAction, othersCall, preclaim, robbedEvent } from './claims';
import { fxDone, movesShown } from './fx.svelte';
import { settings, type Length } from './settings.svelte';
import { ms } from './timing';
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
  /** The hand in play had the analysis shown (settings.practiceHints) at some point: it counts for nothing. */
  hinted?: boolean;
}

interface Saved { game: GameRecord; meta: Meta }

export type Stage = 'playing' | 'result' | 'over';

/** What shows on the table as a player's doing (a draw and a flower are not waited for). */
const SHOWN = new Set<GameEvent['type']>(['discard', 'chi', 'pon', 'kan', 'ankan', 'kakan', 'declare', 'tsumo', 'ron', 'flower_win']);

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
  /** The hand just begun (its id), for the table to deal (Table.svelte `deal`); null for a hand taken up again. */
  deal = $state<string | null>(null);
  /** A few words on the table for a moment: the player's call did not stand, or was taken before they were asked. */
  notice = $state<string | null>(null);
  /** The event (an index into the view's events) that was a claim taken from the player (lib/claims.ts): its moment
   *  says 搶碰, 搶胡. */
  robbed = $state<number | null>(null);
  private running = false;
  private acting = false;
  private epoch = 0;
  /** The loop running (`run`): its number and its game's epoch. One of a game gone (another dealt while it waited
   *  out a pace or a moment) ends at its next look; it holds back neither another game's loop nor its state. */
  private loop = 0;
  private loopEpoch = -1;
  /** This game's loop is running. */
  private get looping() { return this.running && this.loopEpoch === this.epoch; }
  /** The step held (the game after it, and the player's view of it), for the game of this many actions. */
  private ahead: { from: number; game: GameRecord; view: View; robbed?: number; say?: string } | null = null;
  /** The call the player just made on another's tile, until what came of it is known. */
  private claimed: Action['type'] | null = null;
  private noticeTimer: (() => void) | undefined;
  private forcedTimer: (() => void) | undefined;
  /** The computer players' pace (lib/claims.ts BotPace): their turns, their discards' flights, their answers to a tile. */
  private pace = new BotPace();

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
      this.reset();
      this.game = s.game;
      this.meta = s.meta;
      this.stage = 'playing';
      const epoch = this.epoch;
      try {
        await this.refresh();
      } catch {
        if (epoch === this.epoch) await this.newGame();
        return;
      }
      if (epoch !== this.epoch) return;
      if (this.view?.phase === 'over') await this.finish();
      else void this.run();
    } else await this.newGame();
  }

  /** Another game or hand: nothing held from the last. */
  private reset() {
    this.epoch++;
    this.acting = false;
    this.forcedTimer?.();
    this.forcedTimer = undefined;
    // (What the last game's loop waits for goes at once: it sees the game is gone.)
    calloutsSkipped();
    this.pace.reset();
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
    this.noticeTimer?.();
    this.notice = text;
    if (text) {
      const t = ms('notice');
      this.noticeTimer = later(() => (this.notice = null), t);
    }
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
    // (No table of the last game's view meanwhile: the new one's first view is its first, without words.)
    this.view = null;
    const epoch = this.epoch;
    try {
      const game = await engine.newHand(rules, { dealer: startDealer, east: -1, round: 0, streak: 0 }, seed ? `${seed}/0` : undefined);
      // (Another game came meanwhile: this one is no more.)
      if (epoch !== this.epoch) return;
      this.game = game;
      this.persist();
      await this.refresh();
      if (epoch !== this.epoch) return;
      this.dealt(`${this.meta.id}/0`);
    } catch (e) {
      if (epoch === this.epoch) this.error = t('error_engine', String((e as Error).message ?? e));
      return;
    }
    void this.run();
  }

  /** A hand begun: the table deals it (DealFx), and the computer players wait until the player's hand has turned face
   *  up there (dealArm): however long that is, also when the table is not on the screen yet (a new game from the
   *  settings: dealt when the player comes back to it). */
  private dealt(id: string) {
    this.deal = id;
    if (movesShown()) dealArm(id);
    else dealReset();
  }

  private async refresh() {
    const g = this.game;
    if (!g) return;
    const v = await engine.view(g, ME);
    // (Not the view of a game gone meanwhile.)
    if (this.game === g) this.view = v;
  }

  /**
   * The bots decide, a decision point at a time, until the player must decide or the hand is over. Each bot's turn
   * waits the pace of the settings, and the words of a call (lib/callouts.ts), so it can be followed; at the manual
   * pace, what shows waits for `step` (see the top).
   */
  private async run() {
    if (this.looping) return;
    const my = ++this.loop;
    this.running = true;
    const epoch = this.epoch;
    this.loopEpoch = epoch;
    try {
      while (this.game && epoch === this.epoch) {
        const p = await engine.pending(this.game);
        if (p.phase === 'over') {
          if (epoch !== this.epoch) break;
          await this.finish();
          break;
        }
        const seed = `${this.meta?.id}/${this.meta?.hands}/${this.game.actions.length}`;
        if (p.pending.includes(ME)) {
          this.claimed = null;
          if (p.phase === 'self') {
            this.forcedDiscard(epoch);
            break;
          }
          // Another's tile the player could claim: the computer players decide on it first (lib/claims.ts), the
          // player's buttons held back meanwhile.
          this.busy = true;
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
          const say = t('claim_robbed', this.names()[r.seat], t('act_' + r.type), r.options.map((o) => t('act_' + o)).join(t('sep_list')));
          if (settings.speed === 'manual') {
            this.ahead = { from, game: pc.game, view, robbed, say };
            this.waiting = true;
            break;
          }
          this.busy = true;
          // The discard stays a while first (rob_show, from when it came: BotPace), its words done.
          await this.pace.settle();
          await this.pace.rob();
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
        // A bot's own turn takes its pace (bot_turn; after its own chow or pung bot_after_call), its discard flying in
        // to the river in the last of it, and is over when the tile lands. Deciding on a discard (nobody else may): a
        // call shows after the tile's stay (bot_claim), no call at once (lib/claims.ts othersCall). Each counts once a
        // call's moment or words (碰, 槓, 天聽) are over (a tap anywhere lets them go). (BotPace.)
        const claim = p.phase !== 'self';
        const game = this.game;
        await dealReady();
        if (epoch !== this.epoch || !this.game) break;
        await this.pace.settle();
        if (epoch !== this.epoch || !this.game) break;
        const before = this.view?.event_count ?? 0;
        const from = now();
        const next = await engine.advance(game, this.bots, seed, 1);
        let view: View | null = null;
        let fly = 0;
        if (claim) {
          view = await engine.view(next, ME);
          if (epoch !== this.epoch) break;
          if (othersCall(view, before, ME)) await this.pace.call();
        } else fly = await this.pace.turn(game, p.current, next, from);
        // (Another game came meanwhile: this one's step is no more.)
        if (epoch !== this.epoch) break;
        this.game = next;
        this.pace.came(next, fly);
        this.persist();
        if (view) this.view = view;
        else await this.refresh();
        if (this.claimed && this.view) {
          const v = this.view;
          const fresh = v.events.slice(Math.max(0, v.events.length - (v.event_count - before))).filter((e) => SHOWN.has(e.type));
          const over = fresh.some((e) => e.seat === ME) ? undefined : fresh.find((e) => e.seat !== ME && e.type !== 'discard');
          if (over) this.sayAfterFx(t('claim_overridden', t('act_' + this.claimed), this.names()[over.seat], t('act_' + over.type)));
          this.claimed = null;
        }
        if (fly > 0) {
          await this.pace.land();
          if (epoch !== this.epoch) break;
        }
      }
    } catch (e) {
      this.error = t('error_engine', String((e as Error).message ?? e));
    } finally {
      if (this.loop === my) {
        this.busy = false;
        this.running = false;
      }
    }
  }

  /**
   * A declared ready hand (天聽, 地聽, 報聽) whose only choice is the tile it drew: discarded by itself at a computer
   * player's pace (bot_turn), as there is nothing to decide. A win or a concealed kong it may make waits for the player.
   */
  private forcedDiscard(epoch: number) {
    const v = this.view;
    const g = this.game;
    if (!v || !g) return;
    const a = forcedDiscardAction(v, ME);
    if (!a) return;
    const n = g.actions.length;
    this.forcedTimer?.();
    this.forcedTimer = undefined;
    void dealReady().then(() => {
      if (epoch !== this.epoch) return;
      this.forcedTimer = later(() => {
        this.forcedTimer = undefined;
        if (epoch === this.epoch && this.game?.actions.length === n && this.view?.my_turn) void this.act(a);
      }, ms('bot_turn'));
    });
  }

  /** The manual pace: the computer players' next step, now. */
  step() {
    const a = this.ahead;
    if (!this.waiting || !a || !this.game || this.looping || a.from !== this.game.actions.length) return;
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
    const g = this.game;
    const v0 = this.view;
    if (!g || !v0?.my_turn || this.looping || this.acting) return;
    const epoch = this.epoch;
    this.acting = true;
    this.say(null);
    // A call on another's tile may yet give way to a call that comes before it.
    this.claimed = v0.phase !== 'self' && action.type !== 'pass' ? action.type : null;
    const before = v0.event_count;
    try {
      const next = await engine.act(g, ME, action);
      if (epoch !== this.epoch || this.game !== g) return;
      // The others decided first (lib/claims.ts): the claim is settled now. Another's that came first is the moment's
      // 搶 (the player's buttons had a claim that would have stood, but not the one chosen).
      const v = await engine.view(next, ME);
      if (epoch !== this.epoch || this.game !== g) return;
      if (this.claimed) {
        const fresh = v.events.slice(Math.max(0, v.events.length - (v.event_count - before))).filter((e) => SHOWN.has(e.type));
        const over = fresh.some((e) => e.seat === ME) ? undefined : fresh.find((e) => e.seat !== ME && e.type !== 'discard');
        if (over) {
          this.robbed = v.events.indexOf(over);
          this.sayAfterFx(t('claim_overridden', t('act_' + this.claimed), this.names()[over.seat], t('act_' + over.type)));
        }
        this.claimed = null;
      }
      this.game = next;
      // The player's discard flies to the river: the computer player after them discards once it has landed (BotPace).
      this.pace.came(next, this.pace.flight());
      this.view = v;
    } catch (e) {
      if (epoch === this.epoch) {
        this.claimed = null;
        this.error = t('error_engine', String((e as Error).message ?? e));
      }
      return;
    } finally {
      if (epoch === this.epoch) this.acting = false;
    }
    this.persist();
    void this.run();
  }

  private async finish() {
    const g = this.game;
    const meta = this.meta;
    if (!g || !meta) return;
    const epoch = this.epoch;
    const finalView = await engine.view(g, ME, 0, true);
    if (epoch !== this.epoch || this.game !== g || this.stage !== 'playing') return;
    this.final = finalView;
    const result = finalView.result;
    if (!result) return;
    const key = `${(g.wall ?? '').slice(0, 24)}/${g.actions.length}`;
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
    // A hand played with the analysis shown counts for nothing: not in the totals, nor the records' statistics.
    if (!meta.hinted) meta.totals = meta.totals.map((t, i) => t + result.points[i]);
    const rec = handRecord('practice', g, result, ME, this.names(), this.bots, meta.id, meta.hands);
    if (meta.hinted) rec.hints = true;
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
    const g = this.game;
    const m = this.meta;
    if (!g || !m || this.stage !== 'result' || this.acting) return;
    if (m.over) {
      this.stage = 'over';
      this.persist();
      return;
    }
    const epoch = this.epoch;
    this.acting = true;
    let n: { game: GameRecord; gameOver: boolean };
    try {
      n = await engine.nextHand(g, m.startDealer, m.seeded ? `${m.id}/${m.hands}` : undefined);
    } catch (e) {
      if (epoch === this.epoch) this.error = t('error_engine', String((e as Error).message ?? e));
      return;
    } finally {
      if (epoch === this.epoch) this.acting = false;
    }
    if (epoch !== this.epoch || this.game !== g || this.stage !== 'result') return;
    const roundOver = n.game.setup.round !== g.setup.round;
    if (n.gameOver || (m.length === 'round' && roundOver)) {
      this.meta = { ...m, over: true };
      this.stage = 'over';
      this.persist();
      return;
    }
    this.reset();
    const nextEpoch = this.epoch;
    this.game = n.game;
    this.meta = { ...m, hinted: false };
    this.final = null;
    this.stage = 'playing';
    this.persist();
    try {
      await this.refresh();
    } catch (e) {
      if (nextEpoch === this.epoch) this.error = t('error_engine', String((e as Error).message ?? e));
      return;
    }
    if (nextEpoch !== this.epoch) return;
    this.dealt(`${this.meta.id}/${this.meta.hands}`);
    void this.run();
  }

  /** The analysis is shown on the hand in play (settings.practiceHints): it counts for nothing (finish). */
  markHinted() {
    if (!this.meta || this.meta.hinted || this.stage !== 'playing') return;
    this.meta = { ...this.meta, hinted: true };
    this.persist();
  }

  /** The settings changed (rules, length, bot): a new game when they differ from this game's. */
  async settingsChanged() {
    if (!this.meta || this.meta.rules !== settings.spec || this.meta.length !== settings.length || this.meta.bot !== settings.bot)
      await this.newGame();
  }
}

export const practice = new Practice();
