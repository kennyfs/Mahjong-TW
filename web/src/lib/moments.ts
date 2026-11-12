// The moments of the table (Table.svelte starts them from the events, one after another): a call's (吃, 碰, 槓), a
// deal-in's and a win's, a declaration's (天聽, 地聽), an exhaustive draw's. Calls and another's deal-in are small, by
// the player (SmallFx.svelte); what ends a hand, and a declaration, takes the whole screen (CallFx.svelte; the
// player's own deal-in SadFx.svelte). Their times: lib/timing.ts.

import type { Tile as T } from './tiles';
import type { TimingKey } from './timing';

export type Side = 'bottom' | 'right' | 'top' | 'left';

/** A small one's parts, ms: it comes in (sliding from the left, slowing), the tile called drops into it, it stays,
 *  it goes (its meld flies to its place on the table). */
export interface Parts { in: number; drop: number; hold: number; out: number }

export interface Moment {
  id: number;
  /** call: a chow, a pung, a kong; dealin: who dealt in (another); small: a win's words by the winner (a review's end,
   *  small); sad: the player's own deal-in; win: a win (on a discard, self-drawn, by flowers); declare: 天聽, 地聽;
   *  drawn: an exhaustive draw; gap: nothing shown, a pause at a hand's end (`hide`: before the hands are shown, a
   *  draw's; else after, before the result's moment). */
  kind: 'call' | 'dealin' | 'small' | 'sad' | 'win' | 'declare' | 'drawn' | 'gap';
  /** The event's type (chi, pon, kan, ankan, kakan, ron, tsumo, flower_win, declare, exhausted). */
  type: string;
  seat: number;
  side: Side;
  word: string;
  /** Whose (the name), and a line under it (who dealt in, to whom). */
  who: string;
  note: string;
  /** The player's own. */
  mine: boolean;
  /** The tiles around the gap, where the gap is (-1: none), and the tile that comes into it (null: a back). */
  tiles: (T | null)[];
  gap: number;
  tile: T | null;
  /** Where the tile comes from: an element (a river's last tile, another's flower), else from a side (null: above). */
  src: string | null;
  from: Side | null;
  /** The meld it brings (its element; kept out of sight there till it lands), -1 none. */
  dst: string | null;
  meld: number;
  /** Where a small one stands: an element it is centered over (the caller's river, the seat that dealt in). */
  anchor: string | null;
  /** Its whole time, ms; a small one's parts. */
  ms: number;
  parts?: Parts;
  /** Its time's name (lib/timing.ts). */
  key: TimingKey;
  /** A gap's: the hands stay hidden through it. */
  hide?: boolean;
  /** A result the player neither gains nor loses by (another's win on another's discard, a draw): its neutral look. */
  neutral?: boolean;
}

export const FULL: Moment['kind'][] = ['sad', 'win', 'declare', 'drawn'];
export const total = (p: Parts) => p.in + p.drop + p.hold + p.out;
