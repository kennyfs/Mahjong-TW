// The analysis screen's position, set up by hand: the player's hand (and the tile just drawn, or another player's
// discard to decide on), every seat's melds, flowers and discards, the dealer, the round wind and the wall. It is
// turned into the engine's POSITION (docs/gui-engine.md), which checks it. A port of the app's PositionEditor.kt.
import { FLOWERS, KINDS, sortTiles, tilesString, type Tile } from './tiles';
import type { Meld, Position } from './types';

export type Mode = 'self' | 'claim';
export type Section =
  | { kind: 'hand' }
  | { kind: 'drawn' }
  | { kind: 'claim' }
  | { kind: 'melds'; seat: number }
  | { kind: 'flowers'; seat: number }
  | { kind: 'discards'; seat: number };

export const sameSection = (a: Section, b: Section) =>
  a.kind === b.kind && (!('seat' in a) || ('seat' in b && a.seat === b.seat));

/** A meld's tiles (a chow by its lowest tile). */
export function meldOf(m: Meld): Tile[] {
  if (!m.tile) return [];
  if (m.type === 'chi') return [0, 1, 2].map((d) => `${Number(m.tile![0]) + d}${m.tile![1]}`);
  return Array(m.type === 'pon' ? 3 : 4).fill(m.tile);
}

class Editor {
  hand = $state<Tile[]>([]);
  drawn = $state<Tile | null>(null);
  mode = $state<Mode>('self');
  claimSeat = $state(3);
  claimTile = $state<Tile | null>(null);
  melds = $state<Meld[][]>([[], [], [], []]);
  flowers = $state<Tile[][]>([[], [], [], []]);
  discards = $state<Tile[][]>([[], [], [], []]);
  dealer = $state(0);
  round = $state(0);
  wallLeft = $state(40);
  section = $state<Section>({ kind: 'hand' });

  /** How many concealed tiles the hand needs, the drawn tile apart: 16 less 3 a meld. */
  get handSize(): number { return 16 - 3 * this.melds[0].length; }

  /** Copies of a tile seen anywhere. */
  count(t: Tile): number {
    let n = this.hand.filter((x) => x === t).length + (this.drawn === t ? 1 : 0) + (this.claimTile === t ? 1 : 0);
    for (let s = 0; s < 4; s++) {
      for (const m of this.melds[s]) n += meldOf(m).filter((x) => x === t).length;
      n += this.discards[s].filter((x) => x === t).length + this.flowers[s].filter((x) => x === t).length;
    }
    return n;
  }
  left(t: Tile): number { return (t[1] === 'f' ? 1 : 4) - this.count(t); }

  /** A tile from the palette into the section being edited. False: there is no room for it. */
  add(t: Tile): boolean {
    const s = this.section;
    if (this.left(t) <= 0) return false;
    if (t[1] === 'f' && s.kind !== 'flowers') return false;
    if (t[1] !== 'f' && s.kind === 'flowers') return false;
    switch (s.kind) {
      case 'hand':
        if (this.hand.length >= this.handSize) return false;
        this.hand = sortTiles([...this.hand, t]);
        return true;
      case 'drawn': this.drawn = t; return true;
      case 'claim': this.claimTile = t; return true;
      case 'flowers': this.flowers[s.seat] = sortTiles([...this.flowers[s.seat], t]); return true;
      case 'discards':
        if (this.discards[s.seat].length >= 30) return false;
        this.discards[s.seat] = [...this.discards[s.seat], t];
        return true;
      case 'melds': return false;
    }
  }

  /** A meld for a seat: its kind and tile (a chow's lowest). */
  addMeld(seat: number, type: Meld['type'], tile: Tile): boolean {
    if (this.melds[seat].length >= 5) return false;
    const m: Meld = { type, tile };
    const need = meldOf(m);
    for (const t of new Set(need)) if (this.left(t) < need.filter((x) => x === t).length) return false;
    this.melds[seat] = [...this.melds[seat], m];
    if (seat === 0) this.hand = this.hand.slice(0, this.handSize);
    return true;
  }

  removeAt(s: Section, i: number) {
    switch (s.kind) {
      case 'hand': this.hand = this.hand.filter((_, k) => k !== i); break;
      case 'drawn': this.drawn = null; break;
      case 'claim': this.claimTile = null; break;
      case 'melds': this.melds[s.seat] = this.melds[s.seat].filter((_, k) => k !== i); break;
      case 'flowers': this.flowers[s.seat] = this.flowers[s.seat].filter((_, k) => k !== i); break;
      case 'discards': this.discards[s.seat] = this.discards[s.seat].filter((_, k) => k !== i); break;
    }
  }

  clear() {
    this.hand = [];
    this.drawn = null;
    this.claimTile = null;
    this.melds = [[], [], [], []];
    this.flowers = [[], [], [], []];
    this.discards = [[], [], [], []];
    this.section = { kind: 'hand' };
  }

  /** A random hand of 16 and a drawn tile, nothing else seen. */
  random() {
    this.clear();
    const wall = KINDS.flatMap((t) => [t, t, t, t]);
    for (let i = wall.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [wall[i], wall[j]] = [wall[j], wall[i]];
    }
    this.hand = sortTiles(wall.slice(0, 16));
    if (this.mode === 'self') this.drawn = wall[16];
    else this.claimTile = wall[16];
  }

  /** What is missing before the engine can be asked, or null. */
  missing(): { need: number; have: number } | null {
    if (this.hand.length !== this.handSize) return { need: this.handSize, have: this.hand.length };
    return null;
  }

  position(): Position {
    const hand = this.mode === 'self' && this.drawn ? [...this.hand, this.drawn] : this.hand;
    return {
      setup: { dealer: this.dealer, east: -1, round: this.round, streak: 0 },
      me: 0,
      hand: tilesString(hand),
      drawn: this.mode === 'self' ? this.drawn : null,
      after_call: false,
      claim: this.mode === 'claim' ? { seat: this.claimSeat, tile: this.claimTile, rob_kong: false } : null,
      players: [0, 1, 2, 3].map((s) => ({
        melds: this.melds[s].map((m) => ({ ...m, ...(s === 0 ? {} : { from: -1 }) })),
        flowers: tilesString(this.flowers[s]),
        discards: this.discards[s].map((tile) => ({ tile, drawn: false, called: false })),
      })),
      wall_left: this.wallLeft,
    };
  }
}

export const editor = new Editor();
export const PALETTE: Tile[] = KINDS;
export const FLOWER_PALETTE: Tile[] = FLOWERS;
