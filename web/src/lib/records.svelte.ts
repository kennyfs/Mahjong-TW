// Every finished hand, kept as its record (rules, setup, wall, decisions) with who played which seat, so it can be
// replayed and each decision analyzed; battles as groups of their hands. In localStorage, newest last. A port of the
// app's Records.kt.
import { KEYS, load, save } from './store';
import type { GameRecord, Result } from './types';

export type RecordKind = 'practice' | 'table' | 'contest';

/** A hand's review by the network, in short (lib/review.ts), kept with the record for the lists and the statistics. */
export interface ReviewSummary {
  /** The network that reviewed it (engine.MODEL_ID): another one reviews it again. */
  m: string;
  /** The expected result at the first decision, the points lost by decisions, and the rest of the result: luck. */
  first: number;
  loss: number;
  luck: number;
  /** A typical payment under the hand's rules, which the grades are scaled by. */
  unit: number;
  /** Decisions that had a choice; how many were questionable, mistakes, blunders. */
  n: number;
  grades: number[];
  /** The points lost by kind of decision (review.ts KINDS_OF_LOSS: tile, danger, call, win). */
  kinds: number[];
}

export interface HandRecord {
  id: string;
  /** When the hand ended (ISO 8601). */
  time: string;
  kind: RecordKind;
  game: GameRecord;
  /** The player's seat. */
  me: number;
  names: string[];
  /** Per seat: the bot's spec, or null for a person. */
  bots: (string | null)[];
  points: number[];
  winners: number[];
  loser: number;
  selfDraw: boolean;
  exhausted: boolean;
  /** The first winner's tai. */
  tai: number;
  /** The practice game or the battle it belongs to, and its number there (from 1). */
  group?: string;
  index?: number;
  fav?: boolean;
  rev?: ReviewSummary;
}

export interface BattleGroup {
  id: string;
  time: string;
  kind: 'table' | 'contest';
  via: 'online' | 'nearby';
  /** The rules' full spec. */
  rules: string;
  names: string[];
  /** The player's place in `names`. */
  me: number;
  totals: number[];
  hands: number;
  left?: boolean;
  fav?: boolean;
}

/** Records kept at most: the oldest that are not favorites go first. */
const MAX = 3000;

class Records {
  hands = $state<HandRecord[]>(load<HandRecord[]>(KEYS.records, []));
  battles = $state<BattleGroup[]>(load<BattleGroup[]>(KEYS.battles, []));

  add(r: HandRecord) {
    if (this.hands.some((h) => h.id === r.id)) return;
    let list = [...this.hands, r];
    if (list.length > MAX) {
      const drop = list.length - MAX;
      let dropped = 0;
      list = list.filter((h) => h.fav || dropped++ >= drop);
    }
    this.hands = list;
    this.persist();
  }

  addBattle(b: BattleGroup) {
    this.battles = [...this.battles.filter((x) => x.id !== b.id), b];
    save(KEYS.battles, this.battles);
  }

  setFav(id: string, fav: boolean) {
    this.hands = this.hands.map((h) => (h.id === id ? { ...h, fav } : h));
    this.persist();
  }

  setReview(id: string, rev: ReviewSummary) {
    this.hands = this.hands.map((h) => (h.id === id ? { ...h, rev } : h));
    this.persist();
  }

  setBattleFav(id: string, fav: boolean) {
    this.battles = this.battles.map((b) => (b.id === id ? { ...b, fav } : b));
    save(KEYS.battles, this.battles);
  }

  /** Everything but the favorites (and the battles holding one). */
  clear() {
    this.hands = this.hands.filter((h) => h.fav);
    const keep = new Set(this.hands.map((h) => h.group));
    this.battles = this.battles.filter((b) => b.fav || keep.has(b.id));
    this.persist();
    save(KEYS.battles, this.battles);
  }

  /** Every record, as a file to hand on (to find a bug in a hand played): the app's form (Records.kt). */
  exportText(): string {
    return JSON.stringify({ app: 'mahjong-tw', version: 'web', time: new Date().toISOString(), hands: this.hands, battles: this.battles });
  }

  get(id: string): HandRecord | undefined {
    return this.hands.find((h) => h.id === id);
  }

  ofGroup(group: string): HandRecord[] {
    return this.hands.filter((h) => h.group === group).sort((a, b) => (a.index ?? 0) - (b.index ?? 0));
  }

  private persist() {
    if (!save(KEYS.records, this.hands)) {
      // Out of room: drop the oldest half that are not favorites and try again.
      const plain = this.hands.filter((h) => !h.fav);
      const drop = new Set(plain.slice(0, Math.ceil(plain.length / 2)).map((h) => h.id));
      this.hands = this.hands.filter((h) => !drop.has(h.id));
      save(KEYS.records, this.hands);
    }
  }
}

export const records = new Records();

export function newId(): string {
  const a = new Uint8Array(9);
  crypto.getRandomValues(a);
  return Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** A record of a finished hand. */
export function handRecord(
  kind: RecordKind, game: GameRecord, result: Result, me: number, names: string[], bots: (string | null)[],
  group?: string, index?: number,
): HandRecord {
  return {
    id: newId(), time: new Date().toISOString(), kind, game, me, names, bots,
    points: result.points, winners: result.winners.map((w) => w.seat), loser: result.loser, selfDraw: result.self_draw,
    exhausted: result.exhausted, tai: result.winners[0]?.tai ?? 0, group, index,
  };
}
