// The analysis screen's position, set up by hand: the player's hand (and the tile just drawn, or another player's
// discard to decide on), every seat's melds, flowers and discards, the dealer, the round wind and the wall. It is
// turned into the engine's POSITION (docs/gui-engine.md), which checks it. A port of the app's PositionEditor.kt.
// A step of a hand's review can be taken here (load), to try changes on it: while only the hand's own tiles are
// changed, the figures are asked of that hand's game (source), whose history the network reads; a position of its
// own has only the plain history of its discards.
import { FLOWERS, KINDS, parseTiles, sortTiles, tilesString, type Tile } from './tiles';
import type { GameRecord, Meld, Position } from './types';

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
  /** From a hand's review: its rules (null: the settings'), and what the editor does not show of its position. */
  rules = $state<string | null>(null);
  private east = -1;
  private streak = 0;
  private afterCall = false;
  private missed = false;
  private from = $state<{ game: GameRecord; seat: number; table: string } | null>(null);

  /** What the editor holds of the table: everything but the hand's own tiles. */
  private table(): string {
    const claim = this.mode === 'claim';
    return JSON.stringify([this.mode, claim ? this.claimSeat : -1, claim ? this.claimTile : null, this.melds, this.flowers, this.discards,
      this.dealer, this.round, this.wallLeft, this.rules]);
  }

  /**
   * The step of a hand the position was taken from (the hand's record cut there, and the seat deciding), for as long
   * as nothing but the hand's own tiles differs from it: the figures are then asked of that game with this hand
   * (engine.analyzeGame), and read the game's history as the hand's review does. Null: a position of its own.
   */
  get source(): { game: GameRecord; seat: number } | null {
    const f = this.from;
    return f && f.table === this.table() ? { game: f.game, seat: f.seat } : null;
  }

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
    this.rules = null;
    this.east = -1;
    this.streak = 0;
    this.afterCall = this.missed = false;
    this.from = null;
  }

  /**
   * A position of a hand as its seat `me` saw it (a view's), under the hand's rules: the seats turned so that `me`
   * is the player. The tiles taken by melds leave the rivers (the melds show them); a discard being decided on is
   * the tile to claim. `game`: the hand's record up to this position (see source).
   */
  load(p: Position, rules: string, game?: GameRecord) {
    this.clear();
    const rel = (s: number) => (s - p.me + 4) % 4;
    const seat = (r: number) => (r + p.me) % 4;
    this.rules = rules;
    const claim = p.claim && p.claim.tile ? p.claim : null;
    this.mode = claim ? 'claim' : 'self';
    this.melds = [0, 1, 2, 3].map((r) => p.players[seat(r)].melds.map((m) => ({ type: m.type, tile: m.tile, ...(m.called ? { called: m.called } : {}) })));
    this.flowers = [0, 1, 2, 3].map((r) => parseTiles(p.players[seat(r)].flowers));
    this.discards = [0, 1, 2, 3].map((r) => {
      const list = p.players[seat(r)].discards;
      const kept = claim && seat(r) === claim.seat ? list.slice(0, -1) : list;
      return kept.filter((d) => !d.called).map((d) => d.tile);
    });
    let hand = sortTiles(parseTiles(p.hand));
    if (claim) {
      this.claimSeat = rel(claim.seat) || 3;
      this.claimTile = claim.tile;
    } else {
      // The tile drawn; after a call there is none, and the last tile stands for it.
      const drawn = p.drawn ?? hand[hand.length - 1] ?? null;
      this.afterCall = p.after_call;
      if (drawn) {
        hand = [...hand];
        hand.splice(hand.lastIndexOf(drawn), 1);
      }
      this.drawn = drawn;
    }
    this.hand = hand;
    this.dealer = rel(p.setup.dealer);
    this.east = p.setup.east >= 0 ? rel(p.setup.east) : -1;
    this.round = p.setup.round;
    this.streak = p.setup.streak;
    this.wallLeft = p.wall_left;
    this.missed = !!p.missed;
    this.section = { kind: claim ? 'claim' : 'drawn' };
    this.from = game ? { game, seat: p.me, table: this.table() } : null;
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
    const self = this.mode === 'self';
    const hand = self && this.drawn ? [...this.hand, this.drawn] : this.hand;
    const afterCall = self && this.afterCall;
    // The discard being decided on is the last of its seat's river.
    const river = (s: number) => (!self && this.claimTile && s === this.claimSeat ? [...this.discards[s], this.claimTile] : this.discards[s]);
    return {
      setup: { dealer: this.dealer, east: this.east, round: this.round, streak: this.streak },
      me: 0,
      hand: tilesString(hand),
      drawn: self && !afterCall ? this.drawn : null,
      after_call: afterCall,
      claim: self ? null : { seat: this.claimSeat, tile: this.claimTile, rob_kong: false },
      players: [0, 1, 2, 3].map((s) => ({
        melds: this.melds[s].map((m) => ({ ...m, ...(s === 0 ? {} : { from: -1 }) })),
        flowers: tilesString(this.flowers[s]),
        discards: river(s).map((tile) => ({ tile, drawn: false, called: false })),
      })),
      wall_left: this.wallLeft,
      missed: this.missed,
    };
  }
}

export const editor = new Editor();
export const PALETTE: Tile[] = KINDS;
export const FLOWER_PALETTE: Tile[] = FLOWERS;
