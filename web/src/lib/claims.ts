// Before the player is asked about another's tile (a chow, a pung, a kong, a win on a discard or on an added kong):
// the computer players that must decide on the same tile decide first. When one of their claims comes before every
// claim the player could make (a pung before a chow, a win before either), it is made at once and the player is not
// asked: the table shows it as taken from them (搶碰, 搶胡: Table.svelte's `robbed`). Otherwise their decisions stay
// in the record and the player is asked as before (and whatever they choose stands against them).
//
// Practice and a review's branch use it; a table battle's room decides claims its own way (server/).

import * as engine from './engine';
import { settings, type Speed } from './settings.svelte';
import type { Action, GameRecord } from './types';

export interface Preclaim {
  /** The game with the computer players' decisions on the tile (and, when taken from the player, the player's pass). */
  game: GameRecord;
  /** The claim that came first and the player's options it beat; null when the player is asked. */
  robbed: { seat: number; type: Action['type']; options: Action['type'][] } | null;
}

/** How long the tile a claim is taken on stays on the table before the claim's moment, ms, by the pace (the manual
 *  pace: until the player's tap; a branch, which has none, takes the middle one): the player sees what was discarded
 *  before it is taken. */
const ROB_SHOW: Record<Speed, number> = { slow: 2400, normal: 1600, fast: 900, manual: 1600 };
export const robPause = () => ROB_SHOW[settings.speed] ?? 1600;

/** null: nothing to decide first (the player's own turn, or no computer player but the player's to decide). */
export async function preclaim(game: GameRecord, me: number, bots: (string | null)[], seed: string): Promise<Preclaim | null> {
  const p = await engine.pending(game);
  if (p.phase === 'self' || p.phase === 'over' || !p.pending.includes(me)) return null;
  const others = p.pending.filter((s) => s !== me && bots[s]);
  if (!others.length) return null;
  // Each decides on the game as it stands (none sees another's answer), then the answers go in.
  const answers = await Promise.all(others.map((s) => engine.bot(game, s, bots[s]!, `${seed}/${s}`)));
  let g = game;
  for (let i = 0; i < others.length; i++) g = await engine.act(g, others[i], answers[i]);
  if (answers.every((a) => a.type === 'pass')) return { game: g, robbed: null };
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
  const at = done.actions.findIndex((x, i) => i >= game.actions.length && st.taken[i] && x[1] !== 'pass');
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
