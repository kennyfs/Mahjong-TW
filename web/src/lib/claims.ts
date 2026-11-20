// Before the player is asked about another's tile (a chow, a pung, a kong, a win on a discard or on an added kong):
// the computer players that must decide on the same tile decide first. When one of their claims comes before every
// claim the player could make (a pung before a chow, a win before either), it is made at once and the player is not
// asked: the table shows it as taken from them (搶碰, 搶胡: Table.svelte's `robbed`). Otherwise their decisions stay
// in the record and the player is asked as before (and whatever they choose stands against them).
//
// Practice and a review's branch use it; a table battle's room decides claims its own way (server/).

import * as engine from './engine';
import { calloutsDone } from './callouts';
import { now, wait } from './clock';
import { movesShown } from './fx.svelte';
import { awaitSoundSynced } from './sound';
import { ms } from './timing';
import type { Action, GameRecord, View } from './types';

export interface Preclaim {
  /** The game with the computer players' decisions on the tile (and, when taken from the player, the player's pass). */
  game: GameRecord;
  /** The claim that came first and the player's options it beat; null when the player is asked. */
  robbed: { seat: number; type: Action['type']; options: Action['type'][] } | null;
}

/** How long the tile a claim is taken on stays on the table before the claim's moment, ms, by the pace (the manual
 *  pace: until the player's tap; a branch, which has none, takes the middle one): the player sees what was discarded
 *  before it is taken. */
export const robPause = () => ms('rob_show');

/** Whether a step of the game (its view, `before` events before it) is a computer player's call on another's tile (a
 *  chow, a pung, a kong, a win): that one shows after the tile's stay (`bot_claim`); a step with none shows at once,
 *  as there would be no pause where nobody could call, so that a pause never tells the player that someone could. */
export function othersCall(view: View, before: number, me: number): boolean {
  const fresh = view.events.slice(Math.max(0, view.events.length - (view.event_count - before)));
  return fresh.some((e) => e.seat !== me && ['chi', 'pon', 'kan', 'ron'].includes(e.type));
}

/** Whether `seat`, to play now, came to it by a chow or a pung (since the last discard): its discard then takes the
 *  pace that follows a call (bot_after_call), counted from the end of the call's moment (or words). */
export function justCalled(game: GameRecord, seat: number): boolean {
  for (let k = game.actions.length - 1; k >= 0; k--) {
    const [s, type] = game.actions[k];
    if (type === 'discard' || type === 'kakan') return false;
    if ((type === 'chi' || type === 'pon') && s === seat) return true;
  }
  return false;
}

/** null: nothing to decide first (the player's own turn, or the player is not to decide). `decide`: a computer
 *  player's answer from elsewhere (a scene of the tuning tool keeps to its record), else its bot's. */
export async function preclaim(game: GameRecord, me: number, bots: (string | null)[], seed: string,
  decide?: (g: GameRecord, seat: number) => Action | null): Promise<Preclaim | null> {
  const p = await engine.pending(game);
  if (p.phase === 'self' || p.phase === 'over' || !p.pending.includes(me)) return null;
  // Those still to decide (a branch from the player's decision may have the others' answers already in its record:
  // they are weighed the same).
  const others = p.pending.filter((s) => s !== me && bots[s]);
  // Each decides on the game as it stands (none sees another's answer), then the answers go in.
  const answers = await Promise.all(others.map((s) => decide?.(game, s) ?? engine.bot(game, s, bots[s]!, `${seed}/${s}`)));
  let g = game;
  for (let i = 0; i < others.length; i++) g = await engine.act(g, others[i], answers[i]);
  // Nobody else claims (now or before): the player is asked.
  let since = game.actions.length - 1;
  while (since >= 0 && game.actions[since][1] !== 'discard' && game.actions[since][1] !== 'kakan') since--;
  const before = game.actions.slice(since + 1).some((x) => x[1] !== 'pass');
  if (answers.every((a) => a.type === 'pass') && !before) return { game: g, robbed: null };
  // Would any of the player's claims stand?
  const v = await engine.view(g, me);
  const options = v.legal.filter((a) => a.type !== 'pass');
  for (const o of options) {
    const tried = await engine.act(g, me, o);
    const st = await engine.steps(tried);
    if (st.taken[tried.actions.length - 1]) return { game: g, robbed: null };
  }
  const done = await engine.act(g, me, { type: 'pass' });
  const st = await engine.steps(done);
  const at = done.actions.findIndex((x, i) => i > since && st.taken[i] && x[1] !== 'pass');
  const won = at >= 0 ? done.actions[at] : null;
  return { game: done, robbed: won ? { seat: won[0] as number, type: won[1] as Action['type'], options: [...new Set(options.map((o) => o.type))] } : null };
}

/** The event (an index into a view's events) the claim made in `robbed` became: the first call or win of its seat
 *  after `from` events. */
export function robbedEvent(events: { seat: number; type: string }[], from: number, seat: number): number {
  for (let i = from; i < events.length; i++) {
    const e = events[i];
    if (e.seat === seat && ['chi', 'pon', 'kan', 'ron'].includes(e.type)) return i;
  }
  return -1;
}

/** The type of the last action of `game` (a step of the computer players: what it did). */
export function lastType(game: GameRecord): string | undefined {
  const a = game.actions[game.actions.length - 1];
  return a ? String(a[1]) : undefined;
}

/**
 * The computer players' pace as the table shows it (practice.svelte.ts, Branch.svelte; the app's Claims.kt BotPace). A
 * computer player's own turn takes its pace (bot_turn; after its own chow or pung bot_after_call), counted from when it
 * came, once the words or moment of what came before are over. A discard of theirs flies in to their river in the last
 * discard_move of it (when the moves show: Table.svelte flyIn), never before the tile before it has landed, and the turn
 * is over when it lands (with a pace of 900 and a flight of 500: still for 400, then 500 in flight). What they answer
 * to a tile, a call (bot_claim) or a claim taken from the player (rob_show), shows that long after the tile came, its
 * flight included, or after the words or moment over it.
 */
export class BotPace {
  /** When the last discard (or added kong) came, or the words or moment over it ended; when the last discard lands. */
  private shownAt = 0;
  private landAt = 0;

  /** A discard's flight to its river, ms: discard_move when the moves show; else none (it is there at once). */
  flight(): number {
    return movesShown() ? ms('discard_move') : 0;
  }

  /** Another game: nothing of the last one's tiles to wait for. */
  reset() {
    this.shownAt = 0;
    this.landAt = 0;
  }

  /** Returns once the words and moments the table shows are over, and their sounds heard (awaitSoundSynced). (The
   *  table has the view by then: the engine's answers come after its effects have run.) */
  async settle() {
    const words = await calloutsDone();
    const t = now();
    await awaitSoundSynced();
    if (words || now() - t > 15) this.shownAt = now();
  }

  /** What `next` added came now: a discard (in flight for `fly`) or an added kong is a tile the others may answer. */
  came(next: GameRecord, fly: number) {
    const type = lastType(next);
    if (type !== 'discard' && type !== 'kakan') return;
    this.shownAt = now();
    this.landAt = this.shownAt + (type === 'discard' ? fly : 0);
  }

  /** A computer player's own turn (`seat`, in `game`; `next`: what it does), come at `from`: waits its pace, less the
   *  flight of its discard. Resolves to that flight (0: none, or not a discard). */
  async turn(game: GameRecord, seat: number, next: GameRecord, from: number): Promise<number> {
    const fly = lastType(next) === 'discard' ? this.flight() : 0;
    const pace = ms(justCalled(game, seat) ? 'bot_after_call' : 'bot_turn');
    await wait(Math.max(0, from + pace - fly - now(), this.landAt - now()));
    return fly;
  }

  /** A computer player's call on the tile: after its stay (bot_claim). */
  call() {
    return wait(Math.max(0, this.shownAt + ms('bot_claim') - now(), this.landAt - now()));
  }

  /** A claim taken from the player: after the tile's stay (rob_show; robPause). */
  rob() {
    return wait(Math.max(0, this.shownAt + robPause() - now(), this.landAt - now()));
  }

  /** The tile just discarded lands, and is heard landing: a computer player's turn is over then. */
  async land() {
    await wait(Math.max(0, this.landAt - now()));
    await awaitSoundSynced();
  }
}
