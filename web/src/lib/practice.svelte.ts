// Practice: the player (seat 0) against three bots, for one hand, one round (一圈) or a full game (一將). The page is
// the authority here: it holds the record, lets the bots decide one decision point at a time (so each bot's turn
// shows on its own), and shows the player only the view of seat 0. A port of the app's Practice.kt.
import * as engine from './engine';
import { t } from './i18n.svelte';
import { handRecord, newId, records } from './records.svelte';
import { PACE, settings, type Length } from './settings.svelte';
import { KEYS, load, save } from './store';
import type { Action, GameRecord, View } from './types';

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
  private running = false;
  private epoch = 0;

  get bots(): (string | null)[] {
    const b = this.meta?.bot ?? 'greedy';
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

  private persist() {
    if (this.game && this.meta) save(KEYS.practice, { game: this.game, meta: this.meta } satisfies Saved);
  }

  /** A new game under the settings' rules, length and bot; the first dealer drawn at random. `seed`: the same walls
   *  every time (the screenshot scripts). */
  async newGame(seed?: string) {
    this.epoch++;
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
   * waits the pace of the settings, so it can be followed.
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
        if (p.pending.includes(ME)) break;
        this.busy = true;
        // A bot's own turn takes the pace; deciding on a discard (nobody else may) takes a short beat.
        await sleep(p.phase === 'self' ? PACE[settings.speed] : Math.min(250, PACE[settings.speed] / 2));
        if (epoch !== this.epoch || !this.game) break;
        const seed = `${this.meta?.id}/${this.meta?.hands}/${this.game.actions.length}`;
        this.game = await engine.advance(this.game, this.bots, seed, 1);
        this.persist();
        await this.refresh();
      }
    } catch (e) {
      this.error = String((e as Error).message ?? e);
    } finally {
      this.busy = false;
      this.running = false;
    }
  }

  /** The player's decision (one of view.legal). */
  async act(action: Action) {
    if (!this.game || !this.view?.my_turn || this.running) return;
    try {
      this.game = await engine.act(this.game, ME, action);
    } catch (e) {
      this.error = String((e as Error).message ?? e);
      return;
    }
    this.persist();
    await this.refresh();
    void this.run();
  }

  private async finish() {
    if (!this.game || !this.meta) return;
    this.final = await engine.view(this.game, ME, 0, true);
    const result = this.final.result;
    if (!result) return;
    if (this.stage !== 'playing') return;
    const meta = this.meta;
    const key = `${this.game.wall.slice(0, 24)}/${this.game.actions.length}`;
    if (meta.recorded === key) {
      this.stage = 'result';
      return;
    }
    meta.recorded = key;
    meta.hands++;
    meta.totals = meta.totals.map((t, i) => t + result.points[i]);
    const rec = handRecord('practice', this.game, result, ME, this.names(), this.bots, meta.id, meta.hands);
    records.add(rec);
    this.lastRecord = rec.id;
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
    this.epoch++;
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
