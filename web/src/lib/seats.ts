// Seats as the player sees them: the player at the bottom, the next player (下家) at the right, the one opposite at
// the top, the previous player (上家) at the left.
import type { Meld, PlayerPos, View } from './types';
import { parseTiles, without, type Tile } from './tiles';

export type Side = 'bottom' | 'right' | 'top' | 'left';
export const SIDES: Side[] = ['bottom', 'right', 'top', 'left'];

export const sideOf = (seat: number, me: number): Side => SIDES[(seat - me + 4) % 4];
export const seatAt = (side: Side, me: number) => (me + SIDES.indexOf(side)) % 4;

/** How many concealed tiles a seat holds now. */
export function concealedCount(view: View, seat: number): number {
  const p = view.position.players[seat];
  const n = 16 - 3 * p.melds.length;
  return n + (view.phase === 'self' && view.current === seat ? 1 : 0);
}

/** A meld's tiles in the order to show them: a chow with the tile taken in the middle, as on a real table (it says
 *  which tile was claimed); a concealed kong of another player as four backs (nulls). */
export function meldTiles(m: Meld): (Tile | null)[] {
  if (!m.tile) return [null, null, null, null];
  const ts = m.tiles ?? [];
  if (m.type === 'ankan') return [null, ts[1] ?? m.tile, ts[2] ?? m.tile, null];
  if (m.type === 'chi' && m.called && ts.length === 3) {
    const rest = without(ts, [m.called]);
    if (rest.length === 2) return [rest[0], m.called, rest[1]];
  }
  if (ts.length) return ts;
  // A meld set up by hand (the analysis' position) has its kind and tile only (matches Tiles.kt).
  if (m.type === 'chi') {
    const n = Number(m.tile[0]);
    return [0, 1, 2].map((d) => `${n + d}${m.tile![1]}`);
  }
  return Array(m.type === 'pon' ? 3 : 4).fill(m.tile);
}

export const flowersOf = (p: PlayerPos) => parseTiles(p.flowers);
