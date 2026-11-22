// Every finished hand, kept as its record (rules, setup, wall, decisions) with who played which seat, so it can be
// replayed and each decision analyzed; battles as groups of their hands. In localStorage, newest last. A port of the
// app's Records.kt.
import { KEYS, load, save, saveOrFull } from './store';
import type { GameRecord, Result } from './types';

export type RecordKind = 'practice' | 'table' | 'contest';

/** A hand's review by the network, in short (lib/review.ts), kept with the record for the lists and the statistics. */
export interface ReviewSummary {
  /** What reviewed it (engine.REVIEW_ID: the network and the review): another one reviews it again. */
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
  /** Played with the analysis shown (practice's 練習時顯示分析): kept to review, out of the statistics and the game's
   *  totals. */
  hints?: boolean;
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
export const MAX = 3000;

/** What an import did: the hands added, the hands already here, the oldest not kept (past MAX, or past the browser's
 *  room: `room`). */
export interface Imported { added: number; had: number; dropped: number; room: boolean }

/** A records file's hands and battles: one exported by the app or the page (exportText, Records.kt's), else null. */
export function parseRecords(text: string): { hands: HandRecord[]; battles: BattleGroup[] } | null {
  let o: { app?: unknown; hands?: unknown; battles?: unknown };
  try {
    o = JSON.parse(text);
  } catch {
    return null;
  }
  if (!o || typeof o !== 'object' || o.app !== 'mahjong-tw' || !Array.isArray(o.hands)) return null;
  const isHand = (h: any) => !!h && typeof h.id === 'string' && typeof h.time === 'string' && !!h.game && typeof h.game === 'object'
    && typeof h.game.rules === 'string' && Array.isArray(h.game.actions)
    && typeof h.me === 'number' && Array.isArray(h.points) && Array.isArray(h.names)
    && Array.isArray(h.winners) && Array.isArray(h.bots);
  const isBattle = (b: any) => !!b && typeof b.id === 'string' && typeof b.time === 'string' && Array.isArray(b.names)
    && Array.isArray(b.totals) && typeof b.me === 'number';
  return { hands: o.hands.filter(isHand), battles: Array.isArray(o.battles) ? o.battles.filter(isBattle) : [] };
}

/** A record's time, for the order; one that cannot be read as the oldest. */
const when = (time: string) => {
  const x = Date.parse(time);
  return Number.isNaN(x) ? 0 : x;
};

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

  /**
   * A file's records (parseRecords) added to these, as the app's Records.merge: a hand or a battle already here (its
   * id) stays as it is, the file's copy skipped; the rest go in by when they ended; past MAX the oldest that are not
   * favorites go, as when playing (and more if the browser has no room for them all).
   */
  import(file: { hands: HandRecord[]; battles: BattleGroup[] }): Imported {
    const ids = new Set(this.hands.map((h) => h.id));
    const fresh = file.hands.filter((h) => !ids.has(h.id) && !!ids.add(h.id));
    const battleIds = new Set(this.battles.map((b) => b.id));
    const freshBattles = file.battles.filter((b) => !battleIds.has(b.id) && !!battleIds.add(b.id));
    const had = file.hands.length - fresh.length;
    if (!fresh.length && !freshBattles.length) return { added: 0, had, dropped: 0, room: false };
    // (Array.prototype.sort is stable: the same time keeps the order it had.)
    let list = [...this.hands, ...fresh].sort((a, b) => when(a.time) - when(b.time));
    const all = list.length;
    if (all > MAX) {
      const drop = all - MAX;
      let dropped = 0;
      list = list.filter((h) => h.fav || dropped++ >= drop);
    }
    const kept = list.length;
    this.hands = list;
    // Flush synchronously so storage-full eviction happens now and `room`/`dropped` reflect what actually fit.
    this.unsaved = true;
    this.flush();
    this.battles = [...this.battles, ...freshBattles].sort((a, b) => when(a.time) - when(b.time));
    save(KEYS.battles, this.battles);
    return { added: fresh.length, had, dropped: all - this.hands.length, room: this.hands.length < kept };
  }

  /** Every record, as a file to hand on (to find a bug in a hand played, or to another browser, phone or version):
   *  the app's form (Records.kt). */
  exportText(): string {
    return JSON.stringify({ app: 'mahjong-tw', version: 'web', time: new Date().toISOString(), hands: this.hands, battles: this.battles });
  }

  get(id: string): HandRecord | undefined {
    return this.hands.find((h) => h.id === id);
  }

  ofGroup(group: string): HandRecord[] {
    return this.hands.filter((h) => h.group === group).sort((a, b) => (a.index ?? 0) - (b.index ?? 0));
  }

  /** Records changed and not yet written. */
  private unsaved = false;

  /** Writes the records soon, when the page is idle: thousands of hands take a while to write, and a hand ends as its
   *  animation over the whole screen begins. At once when the page is hidden or closed. */
  private persist() {
    if (this.unsaved) return;
    this.unsaved = true;
    if (typeof window === 'undefined') return this.flush();
    if (!this.listening) {
      this.listening = true;
      window.addEventListener('pagehide', () => this.flush());
      document.addEventListener('visibilitychange', () => { if (document.hidden) this.flush(); });
    }
    if (typeof requestIdleCallback === 'function') requestIdleCallback(() => this.flush(), { timeout: 3000 });
    else setTimeout(() => this.flush(), 1000);
  }

  private listening = false;

  private flush() {
    if (!this.unsaved) return;
    this.unsaved = false;
    // Out of room: drop the oldest half that are not favorites and try again until it fits. (Storage that cannot be
    // written at all, a private window's say, drops nothing.)
    while (saveOrFull(KEYS.records, this.hands) === 'full') {
      const plain = this.hands.filter((h) => !h.fav);
      if (!plain.length) break;
      const drop = new Set(plain.slice(0, Math.ceil(plain.length / 2)).map((h) => h.id));
      this.hands = this.hands.filter((h) => !drop.has(h.id));
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
